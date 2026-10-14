import { describe, expect, it } from "vitest";
import type Anthropic from "@anthropic-ai/sdk";
import { agentParams, credentialParams, deploymentParams, environmentParams, KIFF_MCP_URL, MODEL } from "../src/config.js";
import { JOBS, type Job, jobById, systemPrompt } from "../src/jobs.js";
import { agentChanged, environmentChanged, setup, startTaf } from "../src/runtime.js";

type Agent = Anthropic.Beta.Agents.BetaManagedAgentsAgent;
type Environment = Anthropic.Beta.Environments.BetaEnvironment;

const refunds = jobById("refunds");

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
    ],
    skills: [],
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
      expect(prompt).toContain(j.instructions);
    }
  });

  it("refuses an unknown job", () => {
    expect(() => jobById("payroll")).toThrow(/no job "payroll"/);
  });
});

describe("agent", () => {
  it("reaches tools only through KIFF and lets the Card decide", () => {
    const p = agentParams(refunds);
    expect(p.model).toEqual({ id: MODEL, effort: "high" });
    expect(p.mcp_servers).toEqual([{ type: "url", name: "kiff", url: KIFF_MCP_URL }]);
    expect(p.tools).toEqual([
      { type: "mcp_toolset", mcp_server_name: "kiff", default_config: { enabled: true, permission_policy: { type: "always_allow" } } },
    ]);
    expect(p.metadata).toEqual({ tafs_job: "refunds" });
  });

  it("matches the API's echo of the same configuration", () => {
    expect(agentChanged(echoed(refunds), refunds)).toBe(false);
    expect(agentChanged(echoed(refunds, 1, (a) => { (a.mcp_servers as Array<{ url: string }>)[0]!.url = `${KIFF_MCP_URL}/`; }), refunds)).toBe(false);
  });

  it("changes when the prompt, summary or model changes", () => {
    expect(agentChanged(echoed(refunds, 1, (a) => { a.system = "old"; }), refunds)).toBe(true);
    expect(agentChanged(echoed(refunds, 1, (a) => { a.description = "old"; }), refunds)).toBe(true);
    expect(agentChanged(echoed(refunds, 1, (a) => { a.model = { id: "claude-opus-5", effort: { type: "high" } }; }), refunds)).toBe(true);
  });

  it("changes when only the effort changes", () => {
    expect(agentChanged(echoed(refunds, 1, (a) => { a.model = { id: MODEL, effort: { type: "medium" } }; }), refunds)).toBe(true);
    expect(agentChanged(echoed(refunds, 1, (a) => { a.model = { id: MODEL }; }), refunds)).toBe(true);
  });

  it("changes when only the MCP server URL changes", () => {
    expect(agentChanged(echoed(refunds, 1, (a) => { a.mcp_servers = [{ type: "url", name: "kiff", url: "https://mcp.example.com/mcp" }]; }), refunds)).toBe(true);
    expect(agentChanged(echoed(refunds, 1, (a) => { a.mcp_servers = []; a.tools = []; }), refunds)).toBe(true);
  });

  it("changes when only the tool permissions change", () => {
    const policy = (type: string) => (a: Record<string, unknown>) => {
      (a.tools as Array<{ default_config: { permission_policy: { type: string } } }>)[0]!.default_config.permission_policy = { type };
    };
    expect(agentChanged(echoed(refunds, 1, policy("always_ask")), refunds)).toBe(true);
    expect(agentChanged(echoed(refunds, 1, policy("auto")), refunds)).toBe(true);
    expect(agentChanged(echoed(refunds, 1, (a) => {
      (a.tools as Array<{ configs: unknown[] }>)[0]!.configs = [{ name: "refund", enabled: false, permission_policy: null }];
    }), refunds)).toBe(true);
  });

  it("changes when another toolset or a skill is added", () => {
    expect(agentChanged(echoed(refunds, 1, (a) => { (a.tools as unknown[]).push({ type: "agent_toolset_20260401" }); }), refunds)).toBe(true);
    expect(agentChanged(echoed(refunds, 1, (a) => { a.skills = [{ type: "anthropic", skill_id: "xlsx" }]; }), refunds)).toBe(true);
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
}

function fakeClient(opts: {
  agents?: Agent[];
  envs?: Environment[];
  deployments?: FakeDeployment[];
  run?: { session_id: string | null; error?: { type: string; message: string } | null };
}) {
  const calls: string[] = [];
  const updates: Array<{ id: string; body: unknown }> = [];
  let n = 0;
  const client = {
    beta: {
      environments: {
        list: async function* () { yield* opts.envs ?? []; },
        create: async () => { calls.push("env.create"); return { id: "env_new" }; },
        update: async (id: string, body: unknown) => { calls.push(`env.update ${id}`); updates.push({ id, body }); return { id }; },
      },
      agents: {
        list: async function* () { yield* opts.agents ?? []; },
        create: async (p: { metadata: Record<string, string> }) => {
          calls.push(`agent.create ${p.metadata.tafs_job}`);
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
        create: async (p: { name: string }) => { calls.push("deployment.create"); return { id: "depl_new", name: p.name }; },
        run: async () => { calls.push("deployment.run"); return opts.run ?? { session_id: "sesn_1", error: null }; },
      },
      vaults: {
        create: async () => { calls.push("vault.create"); return { id: "vlt_1" }; },
        credentials: { create: async () => { calls.push("credential.create"); return { id: "cred_1" }; } },
      },
    },
  } as unknown as Anthropic;
  return { client, calls, updates };
}

const allEchoed = (change: (job: Job) => ((a: Record<string, unknown>) => void) | undefined = () => undefined) =>
  JOBS.map((j) => echoed(j, 1, change(j)));

const taf = (agentVersion: number): FakeDeployment => ({
  id: "depl_rae",
  name: "Rae",
  archived_at: null,
  metadata: { tafs_taf: "Rae", tafs_job: "refunds" },
  agent: { type: "agent", id: "agent_refunds", version: agentVersion },
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
    expect(body.skills).toEqual([]);
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

  it("leaves deployments it did not make alone", async () => {
    const agents = allEchoed((j) => (j.id === "refunds" ? (a) => { a.system = "old"; } : undefined));
    const other = { ...taf(1), id: "depl_other", metadata: {} };
    const { client, calls } = fakeClient({ agents, envs: [env()], deployments: [other] });
    await setup(client);
    expect(calls).toEqual(["agent.update agent_refunds"]);
  });
});

describe("startTaf", () => {
  const s = { environmentID: "env_tafs", agents: { refunds: "agent_refunds" } };

  it("returns the first run's session", async () => {
    const { client, calls } = fakeClient({});
    const r = await startTaf(client, s, { name: "Rae", job: "refunds", kiffKey: "k" });
    expect(r.sessionID).toBe("sesn_1");
    expect(calls).toEqual(["vault.create", "credential.create", "deployment.create", "deployment.run"]);
  });

  it("fails, naming the deployment, when the first run did not start", async () => {
    const { client } = fakeClient({ run: { session_id: null, error: { type: "session_rate_limited", message: "too many sessions" } } });
    const start = startTaf(client, s, { name: "Rae", job: "refunds", kiffKey: "k" });
    await expect(start).rejects.toThrow(/deployment depl_new/);
    await expect(start).rejects.toThrow(/session_rate_limited: too many sessions/);
    await expect(start).rejects.toThrow(/tafs run --name "Rae"/);
  });
});
