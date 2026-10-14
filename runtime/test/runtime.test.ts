import { describe, expect, it } from "vitest";
import type Anthropic from "@anthropic-ai/sdk";
import { agentParams, credentialParams, deploymentParams, environmentParams, KIFF_MCP_URL, MODEL } from "../src/config.js";
import { JOBS, jobById, systemPrompt } from "../src/jobs.js";
import { agentChanged, setup } from "../src/runtime.js";

const refunds = jobById("refunds");

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

  it("is updated only when the prompt, model or summary changed", () => {
    const p = agentParams(refunds);
    const same = { system: p.system, description: p.description, model: { id: MODEL } } as Anthropic.Beta.Agents.BetaManagedAgentsAgent;
    expect(agentChanged(same, refunds)).toBe(false);
    expect(agentChanged({ ...same, system: "old" }, refunds)).toBe(true);
    expect(agentChanged({ ...same, model: { id: "claude-opus-5" } } as typeof same, refunds)).toBe(true);
  });
});

describe("environment", () => {
  it("installs nothing and reaches only the MCP servers", () => {
    expect(environmentParams().config).toEqual({
      type: "cloud",
      networking: { type: "limited", allow_package_managers: false, allow_mcp_servers: true },
    });
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

describe("setup", () => {
  function fakeClient(agents: Array<Partial<Anthropic.Beta.Agents.BetaManagedAgentsAgent>>, envs: Array<{ id: string; name: string }>) {
    const calls: string[] = [];
    let n = 0;
    const client = {
      beta: {
        environments: {
          list: async function* () { yield* envs; },
          create: async () => { calls.push("env.create"); return { id: "env_new" }; },
        },
        agents: {
          list: async function* () { yield* agents; },
          create: async (p: { metadata: Record<string, string> }) => { calls.push(`agent.create ${p.metadata.tafs_job}`); return { id: `agent_${++n}` }; },
          update: async (id: string) => { calls.push(`agent.update ${id}`); return { id, version: 2 }; },
        },
      },
    } as unknown as Anthropic;
    return { client, calls };
  }

  it("creates what is missing", async () => {
    const { client, calls } = fakeClient([], []);
    const s = await setup(client);
    expect(s.environmentID).toBe("env_new");
    expect(Object.keys(s.agents)).toEqual(JOBS.map((j) => j.id));
    expect(calls).toEqual(["env.create", ...JOBS.map((j) => `agent.create ${j.id}`)]);
  });

  it("keeps what matches and updates what changed", async () => {
    const current = JOBS.map((j, i) => {
      const p = agentParams(j);
      return { id: `agent_${j.id}`, version: 1, archived_at: null, metadata: { tafs_job: j.id }, system: i === 0 ? "old" : p.system, description: p.description, model: { id: MODEL } };
    });
    const { client, calls } = fakeClient(current as never, [{ id: "env_tafs", name: "tafs" }]);
    const s = await setup(client);
    expect(s.environmentID).toBe("env_tafs");
    expect(s.agents.refunds).toBe("agent_refunds");
    expect(calls).toEqual(["agent.update agent_refunds"]);
  });
});
