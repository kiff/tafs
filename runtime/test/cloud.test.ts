import { describe, expect, it } from "vitest";
import type Anthropic from "@anthropic-ai/sdk";
import { calmEnough, handle, type HTTPEvent, type Names } from "../src/cloud/handler.js";

/** Names claimed atomically, like the DynamoDB conditional write. */
function memoryNames(): Names & { held: Set<string> } {
  const held = new Set<string>();
  return {
    held,
    async claim(t, n) { const k = `${t}#${n}`; if (held.has(k)) return false; held.add(k); return true; },
    async release(t, n) { held.delete(`${t}#${n}`); },
  };
}

const ME = { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] };
const VIEWER = { ...ME, capabilities: ["read"] };
const AGENT_KEY = { tenant_id: "t_acme", subject: "k_1", api_key: true, agent_id: "rae", capabilities: ["actions.propose"] };

/** KIFF's /v1/me, answering per bearer token. */
function kiff(byToken: Record<string, object>) {
  return (async (_url: string, init?: RequestInit) => {
    const token = String((init?.headers as Record<string, string>).authorization).replace("Bearer ", "");
    const me = byToken[token];
    return me ? new Response(JSON.stringify(me), { status: 200 }) : new Response("{}", { status: 401 });
  }) as typeof fetch;
}

function deployment(id: string, tenant: string, name: string) {
  return { id, name, status: "active", metadata: { tafs_tenant: tenant, tafs_taf: name, tafs_job: "refunds" }, schedule: { upcoming_runs_at: ["2026-10-15T09:00:00Z"], last_run_at: null } };
}

function fakeAnthropic() {
  const calls: string[] = [];
  const deployments = [deployment("depl_acme", "t_acme", "Rae"), deployment("depl_other", "t_other", "Max")];
  const client = {
    beta: {
      deployments: {
        list: async function* () { yield* deployments; },
        retrieve: async (id: string) => { const d = deployments.find((x) => x.id === id); if (!d) throw new Error("404"); return d; },
        create: async (p: { name: string; metadata: Record<string, string> }) => { calls.push(`deployment.create ${JSON.stringify(p.metadata)}`); return deployment("depl_new", p.metadata.tafs_tenant ?? "", p.name); },
        run: async (id: string) => { calls.push(`run ${id}`); return { session_id: "sesn_1" }; },
        pause: async (id: string) => { calls.push(`pause ${id}`); return { ...deployment(id, "t_acme", "Rae"), status: "paused" }; },
        unpause: async (id: string) => deployment(id, "t_acme", "Rae"),
      },
      deploymentRuns: {
        list: async function* (p: { deployment_id: string }) { yield { session_id: `sesn_of_${p.deployment_id}`, created_at: "2026-10-14T09:00:00Z", error: null }; },
      },
      vaults: {
        create: async () => { calls.push("vault.create"); return { id: "vlt_1" }; },
        credentials: { create: async (_id: string, p: { auth: { token: string } }) => { calls.push(`credential ${p.auth.token}`); return {}; } },
      },
      sessions: {
        retrieve: async () => ({ status: "idle" }),
        events: { list: async function* () { yield { type: "agent.message", content: [{ type: "text", text: "Refunded 3 orders; 1 waits on you." }] }; } },
      },
    },
  } as unknown as Anthropic;
  return { client, calls };
}

function call(method: string, path: string, opts: { token?: string; body?: unknown; fetchAs?: Record<string, object>; names?: Names; anthropic?: ReturnType<typeof fakeAnthropic> } = {}) {
  const { client, calls } = opts.anthropic ?? fakeAnthropic();
  const event: HTTPEvent = {
    rawPath: path,
    requestContext: { http: { method } },
    headers: opts.token ? { Authorization: `Bearer ${opts.token}` } : {},
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
  };
  const result = handle(event, {
    anthropic: async () => client,
    setup: async () => ({ environmentID: "env_1", agents: { refunds: "agent_refunds" } }),
    names: opts.names ?? memoryNames(),
    fetch: kiff(opts.fetchAs ?? { ana: ME, viewer: VIEWER, key_rae: AGENT_KEY, key_other: { ...AGENT_KEY, tenant_id: "t_other" }, key_unbound: { ...AGENT_KEY, agent_id: "" } }),
  });
  return result.then((r) => ({ status: r.statusCode, body: JSON.parse(r.body), calls }));
}

describe("tafs cloud", () => {
  it("lists the jobs without signing in", async () => {
    const r = await call("GET", "/v1/jobs");
    expect(r.status).toBe(200);
    expect(r.body.jobs).toHaveLength(5);
  });

  it("needs a KIFF sign-in, and a person rather than a key", async () => {
    expect((await call("GET", "/v1/tafs")).status).toBe(401);
    expect((await call("GET", "/v1/tafs", { token: "nobody" })).status).toBe(401);
    expect((await call("GET", "/v1/tafs", { token: "key_rae" })).status).toBe(403);
  });

  it("lists only the caller's account's tafs", async () => {
    const r = await call("GET", "/v1/tafs", { token: "ana" });
    expect(r.body.tafs.map((t: { name: string }) => t.name)).toEqual(["Rae"]);
  });

  it("treats another account's taf as missing", async () => {
    for (const [m, p] of [["POST", "/v1/tafs/depl_other/pause"], ["POST", "/v1/tafs/depl_other/run"], ["GET", "/v1/tafs/depl_other/runs"]] as const) {
      const r = await call(m, p, { token: "ana" });
      expect(r.status).toBe(404);
      expect(r.calls).toEqual([]);
    }
  });

  it("starts a taf with an agent key from the same account", async () => {
    const r = await call("POST", "/v1/tafs", { token: "ana", body: { name: "Ivy", job: "refunds", agent_key: "key_rae" } });
    expect(r.status).toBe(200);
    expect(r.body.session).toBe("sesn_1");
    expect(r.calls).toEqual([
      "vault.create",
      "credential key_rae",
      `deployment.create {"tafs_tenant":"t_acme","tafs_kiff_agent":"rae","tafs_job":"refunds","tafs_taf":"Ivy"}`,
      "run depl_new",
    ]);
  });

  it("refuses to start without the right, the right key, or sane settings", async () => {
    const base = { name: "Ivy", job: "refunds", agent_key: "key_rae" };
    const cases: Array<[string, object, number]> = [
      ["viewer", base, 403],
      ["ana", { ...base, agent_key: "key_other" }, 400],
      ["ana", { ...base, agent_key: "key_unbound" }, 400],
      ["ana", { ...base, agent_key: "ana" }, 400],
      ["ana", { ...base, agent_key: "garbage" }, 400],
      ["ana", { ...base, name: "Rae" }, 409],
      ["ana", { ...base, job: "payroll" }, 400],
      ["ana", { ...base, cron: "*/5 * * * *" }, 400],
      ["ana", { ...base, budget_cents: 5000 }, 400],
      ["ana", { ...base, name: "" }, 400],
    ];
    for (const [token, body, status] of cases) {
      const r = await call("POST", "/v1/tafs", { token, body });
      expect(r.status, JSON.stringify(body) + " as " + token).toBe(status);
      expect(r.calls).toEqual([]);
    }
  });

  it("starts one taf when two requests race for the same name", async () => {
    const shared = fakeAnthropic();
    const names = memoryNames();
    const body = { name: "Ivy", job: "refunds", agent_key: "key_rae" };
    const [a, b] = await Promise.all([
      call("POST", "/v1/tafs", { token: "ana", body, names, anthropic: shared }),
      call("POST", "/v1/tafs", { token: "ana", body, names, anthropic: shared }),
    ]);
    expect([a.status, b.status].sort()).toEqual([200, 409]);
    expect(shared.calls.filter((c) => c.startsWith("deployment.create"))).toHaveLength(1);
    expect(shared.calls.filter((c) => c.startsWith("run "))).toHaveLength(1);
  });

  it("frees the name when the taf could not be created, and keeps it once it exists", async () => {
    const names = memoryNames();
    const broken = fakeAnthropic();
    (broken.client.beta.vaults as unknown as { create: () => Promise<never> }).create = async () => { throw new Error("vaults are down"); };
    const body = { name: "Ivy", job: "refunds", agent_key: "key_rae" };
    expect((await call("POST", "/v1/tafs", { token: "ana", body, names, anthropic: broken })).status).toBe(502);
    expect(names.held.size).toBe(0);

    const noRun = fakeAnthropic();
    (noRun.client.beta.deployments as unknown as { run: () => Promise<object> }).run = async () => ({ session_id: null, error: { type: "session_rate_limited_error", message: "busy" } });
    expect((await call("POST", "/v1/tafs", { token: "ana", body, names, anthropic: noRun })).status).toBe(502);
    expect([...names.held]).toEqual(["t_acme#Ivy"]);
  });

  it("pauses and runs the caller's taf", async () => {
    expect((await call("POST", "/v1/tafs/depl_acme/pause", { token: "ana" })).body.taf.status).toBe("paused");
    expect((await call("POST", "/v1/tafs/depl_acme/run", { token: "ana" })).body.session).toBe("sesn_1");
    expect((await call("POST", "/v1/tafs/depl_acme/run", { token: "viewer" })).status).toBe(403);
  });

  it("reads a run's report only for the taf's own runs", async () => {
    const ok = await call("GET", "/v1/tafs/depl_acme/runs/sesn_of_depl_acme", { token: "ana" });
    expect(ok.body.report).toEqual({ status: "idle", text: "Refunded 3 orders; 1 waits on you." });
    expect((await call("GET", "/v1/tafs/depl_acme/runs/sesn_of_depl_other", { token: "ana" })).status).toBe(404);
  });
});

describe("calmEnough", () => {
  it("allows every 15 minutes or less often", () => {
    for (const ok of ["*/15 * * * *", "*/20 * * * *", "*/30 * * * *", "*/60 * * * *", "0 * * * *", "0,30 * * * *", "0 9 * * 1-5", "0,15,30,45 * * * *", "5/30 * * * *"]) expect(calmEnough(ok), ok).toBe(true);
    for (const no of ["* * * * *", "*/5 * * * *", "0,10 * * * *", "0,50 * * * *", "1-59 * * * *", "0 9 * *", "61 * * * *", "*/59 * * * *", "*/17 * * * *", "*/50 * * * *", "*/0 * * * *", "50/5 * * * *"]) expect(calmEnough(no), no).toBe(false);
  });

  it("agrees on equivalent forms", () => {
    expect(calmEnough("*/50 * * * *")).toBe(calmEnough("0,50 * * * *"));
    expect(calmEnough("*/20 * * * *")).toBe(calmEnough("0,20,40 * * * *"));
  });
});
