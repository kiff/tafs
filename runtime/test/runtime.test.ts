import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import type Anthropic from "@anthropic-ai/sdk";
import {
  agentParams,
  credentialParams,
  deploymentParams,
  environmentParams,
  FILE_TOOLS,
  KIFF_MCP_URL,
  MEMORY_INSTRUCTIONS,
  memoryStoreParams,
  KIFF_SKILL_NAME,
  MODEL,
} from "../src/config.js";
import { KIFF_SKILL } from "../src/kiff-skill.js";
import { JOBS, type Job, jobById, systemPrompt } from "../src/jobs.js";
import { agentChanged, ensureKiffSkill, ensureMemory, environmentChanged, setup, startTaf } from "../src/runtime.js";

type Agent = Anthropic.Beta.Agents.BetaManagedAgentsAgent;
type Environment = Anthropic.Beta.Environments.BetaEnvironment;

const refunds = jobById("refunds");

/** KIFF's skill as setup finds it already uploaded, pinned to its version. */
const SKILL = { type: "custom" as const, skill_id: "skill_kiff", version: "skillver_1" };

/** An agent as the API returns it for a job: defaults filled in, effort as an object. */
function echoed(job: Job, version = 1, change: (a: Record<string, unknown>) => void = () => {}): Agent {
  const p = agentParams(job);
  const a: Record<string, unknown> = {
    id: `agent_${job.id}`,
    type: "agent",
    version,
    archived_at: null,
    metadata: { tafs_job: job.id },
    name: p.name,
    description: p.description,
    system: p.system,
    model: { id: MODEL, effort: { type: "high" }, speed: "standard" },
    mcp_servers: [{ type: "url", name: "kiff", url: KIFF_MCP_URL }],
    tools: [
      {
        type: "mcp_toolset",
        mcp_server_name: "kiff",
        default_config: { enabled: true, permission_policy: { type: "always_allow" } },
        configs: [],
      },
      {
        // The API lists every built-in tool, each with its own setting.
        type: "agent_toolset_20260401",
        default_config: { enabled: false, permission_policy: { type: "always_allow" } },
        configs: ["bash", "edit", "glob", "grep", "read", "web_fetch", "web_search", "write"].map((name) => ({
          name,
          type: name,
          enabled: (FILE_TOOLS as readonly string[]).includes(name),
          permission_policy: { type: "always_allow" },
        })),
      },
    ],
    skills: [SKILL],
    multiagent: null,
  };
  change(a);
  return a as unknown as Agent;
}

function env(networking: Record<string, unknown> = {}): Environment {
  return {
    id: "env_tafs",
    name: "tafs",
    archived_at: null,
    config: {
      type: "cloud",
      networking: { type: "limited", allow_package_managers: false, allow_mcp_servers: true, allowed_hosts: [], ...networking },
      packages: {},
    },
  } as unknown as Environment;
}

describe("jobs", () => {
  it("has the five jobs, each with a valid default schedule", () => {
    expect(JOBS.map((j) => j.id)).toEqual(["refunds", "billpay", "access", "deploys", "chargebacks"]);
    for (const j of JOBS) expect(j.cron.split(" ")).toHaveLength(5);
  });

  it("tells every taf how to work under a Card", () => {
    for (const j of JOBS) {
      const prompt = systemPrompt(j);
      expect(prompt).toContain("kiff_pending");
      expect(prompt).toContain("kiff_operation_id");
      expect(prompt).toContain("never repeat the call");
      expect(prompt).toContain("kiff skill");
      expect(prompt).toContain("don't wait for it and don't retry it during this run");
      expect(prompt).toContain(j.instructions);
    }
  });

  it("refuses an unknown job", () => {
    expect(() => jobById("payroll")).toThrow(/no job "payroll"/);
  });
});

describe("agent", () => {
  it("acts only through KIFF, lets the Card decide, and has file tools for its memory", () => {
    const p = agentParams(refunds);
    expect(p.model).toEqual({ id: MODEL, effort: "high" });
    expect(p.mcp_servers).toEqual([{ type: "url", name: "kiff", url: KIFF_MCP_URL }]);
    expect(p.tools?.[0]).toEqual(
      { type: "mcp_toolset", mcp_server_name: "kiff", default_config: { enabled: true, permission_policy: { type: "always_allow" } } },
    );
    expect(p.tools?.[1]).toEqual({
      type: "agent_toolset_20260401",
      default_config: { enabled: false },
      configs: ["read", "write", "edit", "glob", "grep"].map((name) => ({ name, enabled: true, permission_policy: { type: "always_allow" } })),
    });
    expect(p.tools).toHaveLength(2);
    expect(p.metadata).toEqual({ tafs_job: "refunds" });
  });

  it("matches the API's echo of the same configuration", () => {
    expect(agentChanged(echoed(refunds), refunds, SKILL)).toBe(false);
    expect(agentChanged(echoed(refunds, 1, (a) => { (a.mcp_servers as Array<{ url: string }>)[0]!.url = `${KIFF_MCP_URL}/`; }), refunds, SKILL)).toBe(false);
  });

  it("changes when the prompt, summary or model changes", () => {
    expect(agentChanged(echoed(refunds, 1, (a) => { a.system = "old"; }), refunds, SKILL)).toBe(true);
    expect(agentChanged(echoed(refunds, 1, (a) => { a.description = "old"; }), refunds, SKILL)).toBe(true);
    expect(agentChanged(echoed(refunds, 1, (a) => { a.model = { id: "claude-opus-5", effort: { type: "high" } }; }), refunds, SKILL)).toBe(true);
  });

  it("changes when only the effort changes", () => {
    expect(agentChanged(echoed(refunds, 1, (a) => { a.model = { id: MODEL, effort: { type: "medium" } }; }), refunds, SKILL)).toBe(true);
    expect(agentChanged(echoed(refunds, 1, (a) => { a.model = { id: MODEL }; }), refunds, SKILL)).toBe(true);
  });

  it("changes when only the MCP server URL changes", () => {
    expect(agentChanged(echoed(refunds, 1, (a) => { a.mcp_servers = [{ type: "url", name: "kiff", url: "https://mcp.example.com/mcp" }]; }), refunds, SKILL)).toBe(true);
    expect(agentChanged(echoed(refunds, 1, (a) => { a.mcp_servers = []; a.tools = []; }), refunds, SKILL)).toBe(true);
  });

  it("changes when only the tool permissions change", () => {
    const policy = (type: string) => (a: Record<string, unknown>) => {
      (a.tools as Array<{ default_config: { permission_policy: { type: string } } }>)[0]!.default_config.permission_policy = { type };
    };
    expect(agentChanged(echoed(refunds, 1, policy("always_ask")), refunds, SKILL)).toBe(true);
    expect(agentChanged(echoed(refunds, 1, policy("auto")), refunds, SKILL)).toBe(true);
    expect(agentChanged(echoed(refunds, 1, (a) => {
      (a.tools as Array<{ configs: unknown[] }>)[0]!.configs = [{ name: "refund", enabled: false, permission_policy: null }];
    }), refunds, SKILL)).toBe(true);
  });

  it("changes when a shell or web tool is turned on", () => {
    for (const name of ["bash", "web_fetch", "web_search"]) {
      expect(agentChanged(echoed(refunds, 1, (a) => {
        const set = (a.tools as Array<{ configs: Array<{ name: string; enabled: boolean }> }>)[1]!;
        set.configs.find((c) => c.name === name)!.enabled = true;
      }), refunds, SKILL)).toBe(true);
    }
    expect(agentChanged(echoed(refunds, 1, (a) => { (a.tools as unknown[]).pop(); }), refunds, SKILL)).toBe(true);
  });

  it("changes when another toolset or a skill is added", () => {
    expect(agentChanged(echoed(refunds, 1, (a) => { (a.tools as unknown[]).push({ type: "web_toolset" }); }), refunds, SKILL)).toBe(true);
    expect(agentChanged(echoed(refunds, 1, (a) => { a.skills = [SKILL, { type: "anthropic", skill_id: "xlsx" }]; }), refunds, SKILL)).toBe(true);
  });

  it("changes when KIFF's skill is missing or pinned to another version", () => {
    expect(agentChanged(echoed(refunds, 1, (a) => { a.skills = []; }), refunds, SKILL)).toBe(true);
    expect(agentChanged(echoed(refunds, 1, (a) => { a.skills = [{ ...SKILL, version: "skillver_0" }]; }), refunds, SKILL)).toBe(true);
    expect(agentChanged(echoed(refunds), refunds, { ...SKILL, skill_id: "skill_other" })).toBe(true);
  });
});

describe("environment", () => {
  it("installs nothing and reaches only the MCP servers", () => {
    expect(environmentParams().config).toEqual({
      type: "cloud",
      networking: { type: "limited", allow_package_managers: false, allow_mcp_servers: true },
    });
  });

  it("is checked by its configuration, not its name", () => {
    expect(environmentChanged(env())).toBe(false);
    expect(environmentChanged(env({ allow_package_managers: true }))).toBe(true);
    expect(environmentChanged(env({ allow_mcp_servers: false }))).toBe(true);
    expect(environmentChanged(env({ allowed_hosts: ["evil.example.com"] }))).toBe(true);
    expect(environmentChanged({ ...env(), config: { type: "cloud", networking: { type: "unrestricted" }, packages: {} } } as unknown as Environment)).toBe(true);
  });
});

describe("memory", () => {
  it("names the store for the taf and describes it for the model", () => {
    const p = memoryStoreParams("Rae's Refunds!", "Refunds", { tafs_tenant: "t_acme" });
    expect(p.name).toBe("tafs-rae-s-refunds");
    expect(p.description).toContain("Rae's Refunds!");
    expect(p.metadata).toEqual({ tafs_tenant: "t_acme", tafs_taf: "Rae's Refunds!", tafs_kind: "memory" });
    expect(memoryStoreParams("¡¡", "Refunds").name).toBe("tafs-taf");
  });

  it("tells the taf notes never widen its Card, and never to keep secrets", () => {
    expect(MEMORY_INSTRUCTIONS).toMatch(/never widen your Card/);
    expect(MEMORY_INSTRUCTIONS).toMatch(/Never write secrets/);
    expect(MEMORY_INSTRUCTIONS.length).toBeLessThanOrEqual(4096);
  });
});

describe("credential", () => {
  it("binds the KIFF key to the gateway URL", () => {
    expect(credentialParams(" kiff_key \n").auth).toEqual({ type: "static_bearer", mcp_server_url: KIFF_MCP_URL, token: "kiff_key" });
  });

  it("refuses an empty key", () => {
    expect(() => credentialParams("  ")).toThrow(/KIFF agent key/);
  });
});

describe("deployment", () => {
  const base = { name: "Rae", job: refunds, agentID: "agent_1", environmentID: "env_1", vaultID: "vlt_1" };

  it("runs the job on its schedule with the taf's vault and a budget", () => {
    const d = deploymentParams(base);
    expect(d.agent).toBe("agent_1");
    expect(d.vault_ids).toEqual(["vlt_1"]);
    expect(d.schedule).toEqual({ type: "cron", expression: refunds.cron, timezone: "UTC" });
    expect(d.budget).toEqual({ type: "limit", max_list_cost: { amount: "200", currency: "USD" } });
    expect(d.initial_events).toEqual([{ type: "user.message", content: [{ type: "text", text: refunds.kickoff }] }]);
    expect(d.metadata).toEqual({ tafs_job: "refunds", tafs_taf: "Rae" });
  });

  it("mounts the taf's memory into every run and records it", () => {
    expect(deploymentParams(base).resources).toBeUndefined();
    const d = deploymentParams({ ...base, memoryStoreID: "memstore_1" });
    expect(d.resources).toEqual([
      { type: "memory_store", memory_store_id: "memstore_1", access: "read_write", instructions: MEMORY_INSTRUCTIONS },
    ]);
    expect(d.metadata).toEqual({ tafs_job: "refunds", tafs_taf: "Rae", tafs_memory: "memstore_1" });
  });

  it("takes the taf's own schedule and budget", () => {
    const d = deploymentParams({ ...base, cron: "0 8 * * *", timezone: "Europe/Paris", budgetCents: 50 });
    expect(d.schedule).toEqual({ type: "cron", expression: "0 8 * * *", timezone: "Europe/Paris" });
    expect(d.budget?.max_list_cost.amount).toBe("50");
  });

  it("refuses a bad schedule or budget", () => {
    expect(() => deploymentParams({ ...base, cron: "hourly" })).toThrow(/cron/);
    expect(() => deploymentParams({ ...base, budgetCents: 0 })).toThrow(/budget/);
    expect(() => deploymentParams({ ...base, budgetCents: 1.5 })).toThrow(/budget/);
  });
});

interface FakeDeployment {
  id: string;
  name: string;
  archived_at: null;
  metadata: Record<string, string>;
  agent: { type: "agent"; id: string; version: number };
  resources?: Array<Record<string, unknown>>;
}

/** Every deployment the fake was asked to create, across clients. */
const created: unknown[] = [];

function fakeClient(opts: {
  agents?: Agent[];
  envs?: Environment[];
  deployments?: FakeDeployment[];
  run?: { session_id: string | null; error?: { type: string; message: string } | null };
  /** Custom skills already uploaded; by default, KIFF's skill at the pinned commit. */
  skills?: Array<{ id: string; display_name: string; created_at: string; latest_version_id: string }>;
}) {
  const calls: string[] = [];
  const updates: Array<{ id: string; body: unknown }> = [];
  let n = 0;
  const skills = opts.skills ?? [{ id: SKILL.skill_id, display_name: KIFF_SKILL_NAME, created_at: "2026-10-01T00:00:00Z", latest_version_id: SKILL.version }];
  const client = {
    skills: {
      list: async function* () { yield* skills; },
      create: async (p: { files: Array<{ name: string; text(): Promise<string> }>; display_name: string }) => {
        calls.push(`skill.create ${p.display_name}`);
        updates.push({ id: "skill", body: { names: p.files.map((f) => f.name), text: await p.files[0]!.text() } });
        return { id: "skill_new", display_name: p.display_name, created_at: "2026-10-15T00:00:00Z", latest_version_id: "skillver_new" };
      },
    },
    beta: {
      environments: {
        list: async function* () { yield* opts.envs ?? []; },
        create: async () => { calls.push("env.create"); return { id: "env_new" }; },
        update: async (id: string, body: unknown) => { calls.push(`env.update ${id}`); updates.push({ id, body }); return { id }; },
      },
      agents: {
        list: async function* () { yield* opts.agents ?? []; },
        create: async (p: { metadata: Record<string, string>; skills?: unknown }) => {
          calls.push(`agent.create ${p.metadata.tafs_job}`);
          updates.push({ id: `agent.create ${p.metadata.tafs_job}`, body: p });
          return { id: `agent_${++n}`, version: 1 };
        },
        update: async (id: string, body: { version: number }) => {
          calls.push(`agent.update ${id}`);
          updates.push({ id, body });
          return { id, version: body.version + 1 };
        },
      },
      deployments: {
        list: async function* () { yield* opts.deployments ?? []; },
        update: async (id: string, body: unknown) => { calls.push(`deployment.update ${id}`); updates.push({ id, body }); return { id }; },
        create: async (p: { name: string }) => { calls.push("deployment.create"); created.push(p); return { id: "depl_new", name: p.name }; },
        run: async () => { calls.push("deployment.run"); return opts.run ?? { session_id: "sesn_1", error: null }; },
      },
      vaults: {
        create: async () => { calls.push("vault.create"); return { id: "vlt_1" }; },
        credentials: { create: async () => { calls.push("credential.create"); return { id: "cred_1" }; } },
      },
      memoryStores: {
        create: async (p: unknown) => { calls.push("memory.create"); updates.push({ id: "memory", body: p }); return { id: "memstore_1" }; },
        memories: {
          create: async (id: string, p: unknown) => { calls.push(`note.create ${id}`); updates.push({ id, body: p }); return { id: "mem_1" }; },
        },
      },
    },
  } as unknown as Anthropic;
  return { client, calls, updates };
}

const allEchoed = (change: (job: Job) => ((a: Record<string, unknown>) => void) | undefined = () => undefined) =>
  JOBS.map((j) => echoed(j, 1, change(j)));

const taf = (agentVersion: number, metadata: Record<string, string> = { tafs_memory: "memstore_rae" }): FakeDeployment => ({
  id: "depl_rae",
  name: "Rae",
  archived_at: null,
  metadata: { tafs_taf: "Rae", tafs_job: "refunds", ...metadata },
  agent: { type: "agent", id: "agent_refunds", version: agentVersion },
});

describe("KIFF's skill", () => {
  it("is the vendored file, pinned to one commit", () => {
    const onDisk = readFileSync(new URL("../skills/kiff/SKILL.md", import.meta.url), "utf8");
    expect(KIFF_SKILL.content).toBe(onDisk);
    expect(KIFF_SKILL.commit).toMatch(/^[0-9a-f]{40}$/);
    expect(KIFF_SKILL.content).toMatch(/^---\nname: kiff\n/);
    expect(KIFF_SKILL_NAME).toBe(`kiff-plugins@${KIFF_SKILL.commit.slice(0, 12)}`);
  });

  it("is uploaded when missing, as kiff/SKILL.md, and every job agent gets it", async () => {
    const { client, calls, updates } = fakeClient({ skills: [] });
    await setup(client);
    expect(calls.slice(0, 3)).toEqual(["env.create", `skill.create ${KIFF_SKILL_NAME}`, "agent.create refunds"]);
    const upload = updates.find((u) => u.id === "skill")!.body as { names: string[]; text: string };
    expect(upload.names).toEqual(["kiff/SKILL.md"]);
    expect(upload.text).toBe(KIFF_SKILL.content);
    expect(calls.filter((c) => c.startsWith("skill.create"))).toHaveLength(1);
    for (const j of JOBS) {
      const made = updates.find((u) => u.id === `agent.create ${j.id}`)!.body as { skills: unknown };
      expect(made.skills).toEqual([{ type: "custom", skill_id: "skill_new", version: "skillver_new" }]);
    }
  });

  it("is found again by its pinned name, the oldest when two setups raced", async () => {
    const old = { id: "skill_old", display_name: KIFF_SKILL_NAME, created_at: "2026-10-01T00:00:00Z", latest_version_id: "skillver_a" };
    const dup = { id: "skill_dup", display_name: KIFF_SKILL_NAME, created_at: "2026-10-02T00:00:00Z", latest_version_id: "skillver_b" };
    const other = { id: "skill_prev", display_name: "kiff-plugins@000000000000", created_at: "2026-09-01T00:00:00Z", latest_version_id: "skillver_c" };
    const { client, calls } = fakeClient({ skills: [dup, other, old] });
    expect(await ensureKiffSkill(client)).toEqual({ type: "custom", skill_id: "skill_old", version: "skillver_a" });
    expect(calls).toEqual([]);
  });

  it("is attached to the agent with its version, so a new pin updates every agent", () => {
    expect(agentParams(refunds, SKILL).skills).toEqual([SKILL]);
    expect(agentParams(refunds).skills).toEqual([]);
  });
});

describe("setup", () => {
  it("creates what is missing", async () => {
    const { client, calls } = fakeClient({});
    const s = await setup(client);
    expect(s.environmentID).toBe("env_new");
    expect(Object.keys(s.agents)).toEqual(JOBS.map((j) => j.id));
    expect(calls).toEqual(["env.create", ...JOBS.map((j) => `agent.create ${j.id}`)]);
  });

  it("changes nothing when everything matches", async () => {
    const { client, calls } = fakeClient({ agents: allEchoed(), envs: [env()], deployments: [taf(1)] });
    const s = await setup(client);
    expect(s.environmentID).toBe("env_tafs");
    expect(s.agents.refunds).toBe("agent_refunds");
    expect(calls).toEqual([]);
  });

  it("updates only the agent that drifted, with its whole configuration", async () => {
    const agents = allEchoed((j) => (j.id === "refunds" ? (a) => { a.mcp_servers = [{ type: "url", name: "kiff", url: "https://mcp.example.com/mcp" }]; } : undefined));
    const { client, calls, updates } = fakeClient({ agents, envs: [env()] });
    await setup(client);
    expect(calls).toEqual(["agent.update agent_refunds"]);
    const body = updates[0]!.body as Record<string, unknown>;
    expect(body.version).toBe(1);
    expect(body.mcp_servers).toEqual(agentParams(refunds).mcp_servers);
    expect(body.tools).toEqual(agentParams(refunds).tools);
    expect(body.model).toEqual(agentParams(refunds).model);
    expect(body.skills).toEqual([SKILL]);
  });

  it("restores an environment whose networking drifted", async () => {
    const { client, calls, updates } = fakeClient({ agents: allEchoed(), envs: [env({ allow_package_managers: true })] });
    const s = await setup(client);
    expect(s.environmentID).toBe("env_tafs");
    expect(calls).toEqual(["env.update env_tafs"]);
    expect(updates[0]!.body).toEqual({ config: environmentParams().config });
  });

  it("moves an existing taf from agent v1 to v2, sending only the agent", async () => {
    const agents = allEchoed((j) => (j.id === "refunds" ? (a) => { a.system = "old"; } : undefined));
    const { client, calls, updates } = fakeClient({ agents, envs: [env()], deployments: [taf(1)] });
    await setup(client);
    expect(calls).toEqual(["agent.update agent_refunds", "deployment.update depl_rae"]);
    expect(updates[1]).toEqual({ id: "depl_rae", body: { agent: { type: "agent", id: "agent_refunds", version: 2 } } });
  });

  it("moves a taf left behind on an older version even when the agent is current", async () => {
    const agents = allEchoed().map((a) => (a.id === "agent_refunds" ? ({ ...a, version: 3 } as Agent) : a));
    const { client, calls, updates } = fakeClient({ agents, envs: [env()], deployments: [taf(2)] });
    await setup(client);
    expect(calls).toEqual(["deployment.update depl_rae"]);
    expect(updates[0]!.body).toEqual({ agent: { type: "agent", id: "agent_refunds", version: 3 } });
  });

  it("gives a taf started before memory its memory store, attached to its runs", async () => {
    const { client, calls, updates } = fakeClient({ agents: allEchoed(), envs: [env()], deployments: [taf(1, { tafs_tenant: "t_acme" })] });
    await setup(client);
    expect(calls).toEqual(["memory.create", "deployment.update depl_rae"]);
    expect(updates[0]!.body).toEqual(memoryStoreParams("Rae", "Refunds", { tafs_tenant: "t_acme" }));
    expect(updates[1]).toEqual({
      id: "depl_rae",
      body: {
        metadata: { tafs_memory: "memstore_1" },
        resources: [{ type: "memory_store", memory_store_id: "memstore_1", access: "read_write", instructions: MEMORY_INSTRUCTIONS }],
      },
    });
  });

  it("leaves deployments it did not make alone", async () => {
    const agents = allEchoed((j) => (j.id === "refunds" ? (a) => { a.system = "old"; } : undefined));
    const other = { ...taf(1), id: "depl_other", metadata: {} };
    const { client, calls } = fakeClient({ agents, envs: [env()], deployments: [other] });
    await setup(client);
    expect(calls).toEqual(["agent.update agent_refunds"]);
  });
});

describe("ensureMemory", () => {
  it("returns the store a taf already has, without calls", async () => {
    const { client, calls } = fakeClient({});
    expect(await ensureMemory(client, taf(1) as never)).toBe("memstore_rae");
    expect(calls).toEqual([]);
  });

  it("keeps memory stores already attached, and doesn't replace resources of other kinds", async () => {
    const kept = { type: "memory_store", memory_store_id: "memstore_shared", access: "read_only", instructions: "shared" };
    const one = fakeClient({});
    await ensureMemory(one.client, { ...taf(1, {}), resources: [kept] } as never);
    expect((one.updates[1]!.body as { resources: unknown[] }).resources).toEqual([
      kept,
      { type: "memory_store", memory_store_id: "memstore_1", access: "read_write", instructions: MEMORY_INSTRUCTIONS },
    ]);

    const two = fakeClient({});
    await ensureMemory(two.client, { ...taf(1, {}), resources: [{ type: "file", file_id: "file_1" }] } as never);
    expect(two.updates[1]!.body).toEqual({ metadata: { tafs_memory: "memstore_1" } });
  });
});

describe("startTaf", () => {
  const s = { environmentID: "env_tafs", agents: { refunds: "agent_refunds" } };

  it("returns the first run's session", async () => {
    const { client, calls } = fakeClient({});
    const r = await startTaf(client, s, { name: "Rae", job: "refunds", kiffKey: "k" });
    expect(r.sessionID).toBe("sesn_1");
    expect(calls).toEqual(["vault.create", "credential.create", "memory.create", "deployment.create", "deployment.run"]);
  });

  it("gives the taf a memory store, mounted into its runs, with the owner's notes as its first note", async () => {
    const { client, calls, updates } = fakeClient({});
    await startTaf(client, s, { name: "Rae", job: "refunds", kiffKey: "k", notes: "  Offer store credit first.  ", metadata: { tafs_tenant: "t_acme" } });
    expect(calls).toContain("note.create memstore_1");
    expect(updates.find((u) => u.id === "memory")!.body).toEqual(memoryStoreParams("Rae", "Refunds", { tafs_tenant: "t_acme" }));
    expect(updates.find((u) => u.id === "memstore_1")!.body).toEqual({ path: "/from-the-owner.md", content: "Offer store credit first." });
    expect(created[created.length - 1]).toMatchObject({
      resources: [{ type: "memory_store", memory_store_id: "memstore_1", access: "read_write" }],
      metadata: { tafs_memory: "memstore_1" },
    });
  });

  it("writes no note when the owner said nothing", async () => {
    const { client, calls } = fakeClient({});
    await startTaf(client, s, { name: "Rae", job: "refunds", kiffKey: "k", notes: "   " });
    expect(calls.some((c) => c.startsWith("note.create"))).toBe(false);
  });

  it("fails, naming the deployment, when the first run did not start", async () => {
    const { client } = fakeClient({ run: { session_id: null, error: { type: "session_rate_limited", message: "too many sessions" } } });
    const start = startTaf(client, s, { name: "Rae", job: "refunds", kiffKey: "k" });
    await expect(start).rejects.toThrow(/deployment depl_new/);
    await expect(start).rejects.toThrow(/session_rate_limited: too many sessions/);
    await expect(start).rejects.toThrow(/tafs run --name "Rae"/);
  });
});

/**
 * tafs says KIFF decides every action a taf takes (#31). On tafs's side that
 * rests on one thing: a taf has no way to reach a company's tools except its
 * KIFF gateway. These fail if any job agent, deployment or sandbox gets another.
 */
describe("tool surface", () => {
  it("every job agent has KIFF's gateway and its file tools, and nothing else", () => {
    for (const job of JOBS) {
      const p = agentParams(job, SKILL);
      expect(p.mcp_servers, job.id).toEqual([{ type: "url", name: "kiff", url: KIFF_MCP_URL }]);
      expect(p.tools?.map((t) => t.type), job.id).toEqual(["mcp_toolset", "agent_toolset_20260401"]);
      expect((p.tools?.[0] as { mcp_server_name: string }).mcp_server_name, job.id).toBe("kiff");
      const builtIn = p.tools?.[1] as { default_config: { enabled: boolean }; configs: Array<{ name: string; enabled: boolean }> };
      expect(builtIn.default_config.enabled, `${job.id}: built-in tools are off unless named`).toBe(false);
      expect(builtIn.configs.filter((c) => c.enabled).map((c) => c.name).sort(), job.id).toEqual([...FILE_TOOLS].sort());
      for (const name of ["bash", "web_fetch", "web_search"]) expect(builtIn.configs.map((c) => c.name), `${job.id}: ${name}`).not.toContain(name);
      expect(p.skills, `${job.id}: KIFF's skill only`).toEqual([SKILL]);
    }
  });

  it("a taf's deployment adds no tools of its own", () => {
    const d = deploymentParams({ name: "Rae", job: refunds, agentID: "agent_1", environmentID: "env_1", vaultID: "vlt_1", memoryStoreID: "memstore_1" });
    expect(Object.keys(d)).not.toContain("tools");
    expect(Object.keys(d)).not.toContain("mcp_servers");
    expect((d.resources ?? []).map((r) => r.type)).toEqual(["memory_store"]);
  });

  it("the sandbox reaches MCP servers only: no hosts, no package managers", () => {
    const net = (environmentParams().config as { networking: Record<string, unknown> }).networking;
    expect(net).toEqual({ type: "limited", allow_package_managers: false, allow_mcp_servers: true });
    expect(net.allowed_hosts ?? []).toEqual([]);
  });
});
