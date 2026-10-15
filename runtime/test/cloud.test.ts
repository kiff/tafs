import { describe, expect, it } from "vitest";
import type Anthropic from "@anthropic-ai/sdk";
import { calmEnough, type Claim, type Claims, handle, type HTTPEvent, type Names } from "../src/cloud/handler.js";

/** Names claimed atomically, like the DynamoDB conditional write. */
function memoryNames(): Names & { held: Map<string, Date> } {
  const held = new Map<string, Date>();
  return {
    held,
    async claim(t, n) {
      const k = `${t}#${n}`;
      if (held.has(k)) return false;
      held.set(k, new Date());
      return true;
    },
    async release(t, n) { held.delete(`${t}#${n}`); },
  };
}

/** Claimed drafts on record, like the DynamoDB items. */
function memoryClaims(): Claims & { held: Map<string, Claim> } {
  const held = new Map<string, Claim>();
  return {
    held,
    async get(t, d) { return held.get(`${t}#${d}`); },
    async put(t, d, c) { held.set(`${t}#${d}`, c); },
    async remove(t, d) { held.delete(`${t}#${d}`); },
  };
}

const ME = { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] };
const VIEWER = { ...ME, capabilities: ["read"] };
const AGENT_KEY = { tenant_id: "t_acme", subject: "k_1", api_key: true, agent_id: "rae", capabilities: ["actions.propose"] };

/** Drafts KIFF would let be claimed: draft id → its claim secret and agent key. Each claims once. */
type Drafts = Record<string, { secret: string; key: string; agent: string; status?: number }>;

/** KIFF's /v1/me, answering per bearer token, and its draft claims. */
function kiff(byToken: Record<string, object>, drafts: Drafts = {}, claimed: string[] = []) {
  return (async (url: string, init?: RequestInit) => {
    const token = String((init?.headers as Record<string, string>).authorization).replace("Bearer ", "");
    const claim = /\/v1\/agent-drafts\/([^/]+)\/claim$/.exec(url);
    if (claim) {
      const id = claim[1]!;
      const d = drafts[id];
      if (!d || d.secret !== token) return new Response(JSON.stringify({ error: "the draft's claim secret is required" }), { status: 401 });
      if (d.status) return new Response(JSON.stringify({ error: "not claimable" }), { status: d.status });
      if (claimed.includes(id)) return new Response(JSON.stringify({ error: "already claimed" }), { status: 409 });
      claimed.push(id);
      return new Response(JSON.stringify({ agent_id: d.agent, key_id: "key_1", plaintext: d.key, card_ids: [] }), { status: 200 });
    }
    const me = byToken[token];
    return me ? new Response(JSON.stringify(me), { status: 200 }) : new Response("{}", { status: 401 });
  }) as typeof fetch;
}

const DRAFTS: Drafts = {
  drf_1: { secret: "dcs_1", key: "key_rae", agent: "rae" },
  drf_other: { secret: "dcs_o", key: "key_other", agent: "max" },
  drf_gone: { secret: "dcs_g", key: "key_rae", agent: "rae", status: 410 },
};

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
        create: async (p: { name: string; metadata: Record<string, string> }) => {
          calls.push(`deployment.create ${JSON.stringify(p.metadata)}`);
          const d = deployment("depl_new", p.metadata.tafs_tenant ?? "", p.name);
          deployments.push(d);
          return d;
        },
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
      memoryStores: {
        create: async (p: { name: string }) => { calls.push(`memory ${p.name}`); return { id: "memstore_1" }; },
        memories: { create: async (id: string, p: { path: string; content: string }) => { calls.push(`note ${id} ${p.path} ${p.content}`); return { id: "mem_1" }; } },
      },
      sessions: {
        retrieve: async () => ({ status: "idle" }),
        events: { list: async function* () { yield { type: "agent.message", content: [{ type: "text", text: "Refunded 3 orders; 1 waits on you." }] }; } },
      },
    },
  } as unknown as Anthropic;
  return { client, calls, deployments };
}

function call(method: string, path: string, opts: { token?: string; body?: unknown; fetchAs?: Record<string, object>; names?: Names; anthropic?: ReturnType<typeof fakeAnthropic>; drafts?: Drafts; claimed?: string[]; claims?: Claims } = {}) {
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
    slots: { claim: async () => true, keep: async () => undefined, held: async () => false, release: async () => undefined },
    claims: opts.claims ?? memoryClaims(),
    push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async () => "sent" as const },
    fetch: kiff(opts.fetchAs ?? { ana: ME, viewer: VIEWER, key_rae: AGENT_KEY, key_other: { ...AGENT_KEY, tenant_id: "t_other" }, key_unbound: { ...AGENT_KEY, agent_id: "" } },
      opts.drafts ?? DRAFTS, opts.claimed ?? []),
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
    expect(r.body.tafs[0]).toHaveProperty("kiff_agent");
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
      "memory tafs-ivy",
      `deployment.create {"tafs_tenant":"t_acme","tafs_kiff_agent":"rae","tafs_job":"refunds","tafs_taf":"Ivy","tafs_memory":"memstore_1"}`,
      "run depl_new",
    ]);
  });

  it("starts a taf from a confirmed KIFF draft, claiming its key itself", async () => {
    const claimed: string[] = [];
    const r = await call("POST", "/v1/tafs", { token: "ana", claimed, body: { name: "Ivy", job: "refunds", draft_id: "drf_1", claim_secret: "dcs_1" } });
    expect(r.status).toBe(200);
    expect(claimed).toEqual(["drf_1"]);
    expect(r.calls).toContain("credential key_rae");
    expect(r.calls).toContain(`deployment.create {"tafs_tenant":"t_acme","tafs_kiff_agent":"rae","tafs_job":"refunds","tafs_taf":"Ivy","tafs_memory":"memstore_1"}`);
    expect(JSON.stringify(r.body)).not.toContain("key_rae");
  });

  it("claims a draft only once the name is held, so a taken name or a second tap never spends it", async () => {
    const claimed: string[] = [];
    const taken = await call("POST", "/v1/tafs", { token: "ana", claimed, body: { name: "Rae", job: "refunds", draft_id: "drf_1", claim_secret: "dcs_1" } });
    expect(taken.status).toBe(409);
    expect(claimed).toEqual([]);

    const shared = fakeAnthropic();
    const names = memoryNames();
    const body = { name: "Ivy", job: "refunds", draft_id: "drf_1", claim_secret: "dcs_1" };
    const [a, b] = await Promise.all([
      call("POST", "/v1/tafs", { token: "ana", body, names, anthropic: shared, claimed }),
      call("POST", "/v1/tafs", { token: "ana", body, names, anthropic: shared, claimed }),
    ]);
    expect([a.status, b.status].sort()).toEqual([200, 409]);
    expect(claimed).toEqual(["drf_1"]);
  });

  it("frees the name when KIFF won't give the draft's key, and says why", async () => {
    const names = memoryNames();
    const gone = await call("POST", "/v1/tafs", { token: "ana", names, body: { name: "Ivy", job: "refunds", draft_id: "drf_gone", claim_secret: "dcs_g" } });
    expect(gone.status).toBe(410);
    expect(gone.body.error).toContain("KIFF did not give the draft's key");
    expect(gone.calls).toEqual([]);
    const wrong = await call("POST", "/v1/tafs", { token: "ana", names, body: { name: "Ivy", job: "refunds", draft_id: "drf_1", claim_secret: "nope" } });
    expect(wrong.status).toBe(401);
    const other = await call("POST", "/v1/tafs", { token: "ana", names, body: { name: "Ivy", job: "refunds", draft_id: "drf_other", claim_secret: "dcs_o" } });
    expect(other.status).toBe(400);
    expect(other.calls).toEqual([]);
    // The name was freed each time.
    expect((await call("POST", "/v1/tafs", { token: "ana", names, body: { name: "Ivy", job: "refunds", draft_id: "drf_1", claim_secret: "dcs_1" } })).status).toBe(200);
  });

  it("resumes a start that failed after the one-time claim, with the key already in a vault", async () => {
    const names = memoryNames();
    const claims = memoryClaims();
    const claimed: string[] = [];
    const shared = fakeAnthropic();
    const realCreate = shared.client.beta.memoryStores.create.bind(shared.client.beta.memoryStores);
    let down = true;
    (shared.client.beta.memoryStores as unknown as { create: (p: never) => Promise<unknown> }).create = async (p: never) => {
      if (down) throw Object.assign(new Error("memory stores are down"), { status: 503 });
      return realCreate(p);
    };
    const body = { name: "Ivy", job: "refunds", draft_id: "drf_1", claim_secret: "dcs_1" };
    const opts = { token: "ana", body, names, claims, claimed, anthropic: shared };

    const failed = await call("POST", "/v1/tafs", opts);
    expect(failed.status).toBe(502);
    expect(claimed).toEqual(["drf_1"]);
    expect(names.held.size).toBe(0);
    const kept = claims.held.get("t_acme#drf_1");
    expect(kept).toMatchObject({ name: "Ivy", agentID: "rae", vaultID: "vlt_1" });
    expect(JSON.stringify(kept)).not.toContain("dcs_1"); // only a hash of the secret
    expect(JSON.stringify(kept)).not.toContain("key_rae");

    // Someone without the secret, or with another name, can't use the kept vault.
    expect((await call("POST", "/v1/tafs", { ...opts, body: { ...body, claim_secret: "nope" } })).status).toBe(401);
    expect((await call("POST", "/v1/tafs", { ...opts, body: { ...body, name: "Other" } })).status).toBe(409);

    down = false;
    const vaultsBefore = shared.calls.filter((c) => c === "vault.create").length;
    const retry = await call("POST", "/v1/tafs", opts);
    expect(retry.status).toBe(200);
    expect(claimed).toEqual(["drf_1"]); // not claimed twice
    expect(shared.calls.filter((c) => c === "vault.create").length).toBe(vaultsBefore);
    expect(shared.calls).toContain(`deployment.create {"tafs_tenant":"t_acme","tafs_kiff_agent":"rae","tafs_job":"refunds","tafs_taf":"Ivy","tafs_memory":"memstore_1"}`);
    expect(claims.held.size).toBe(0);
  });

  it("says a draft is spent when its key was claimed and nothing kept it", async () => {
    const names = memoryNames();
    const spent = await call("POST", "/v1/tafs", { token: "ana", names, claimed: ["drf_1"], body: { name: "Ivy", job: "refunds", draft_id: "drf_1", claim_secret: "dcs_1" } });
    expect(spent.status).toBe(410);
    expect(spent.body.error).toMatch(/Start again/);
    expect(spent.calls).toEqual([]);
    expect(names.held.size).toBe(0);
  });

  it("needs both a draft id and its secret, or an agent key", async () => {
    for (const body of [
      { name: "Ivy", job: "refunds", draft_id: "drf_1" },
      { name: "Ivy", job: "refunds", claim_secret: "dcs_1" },
      { name: "Ivy", job: "refunds" },
      { name: "Ivy", job: "refunds", draft_id: "../x", claim_secret: "dcs_1" },
    ]) {
      const r = await call("POST", "/v1/tafs", { token: "ana", body });
      expect(r.status, JSON.stringify(body)).toBe(400);
      expect(r.calls).toEqual([]);
    }
  });

  it("keeps what the owner said about the job as the taf's first note", async () => {
    const r = await call("POST", "/v1/tafs", { token: "ana", body: { name: "Ivy", job: "refunds", agent_key: "key_rae", notes: " Never refund twice. " } });
    expect(r.status).toBe(200);
    expect(r.calls).toContain("note memstore_1 /from-the-owner.md Never refund twice.");
    const long = await call("POST", "/v1/tafs", { token: "ana", body: { name: "Ivy", job: "refunds", agent_key: "key_rae", notes: "x".repeat(2001) } });
    expect(long.status).toBe(400);
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
    expect([...names.held.keys()]).toEqual(["t_acme#Ivy"]);
  });

  it("keeps the name for good when a create's answer is lost, however late the taf lands", async () => {
    const names = memoryNames();
    const shared = fakeAnthropic();
    const late: Array<() => void> = [];
    const realCreate = shared.client.beta.deployments.create.bind(shared.client.beta.deployments);
    let first = true;
    (shared.client.beta.deployments as unknown as { create: (p: never) => Promise<unknown> }).create = async (p: never) => {
      if (!first) return realCreate(p);
      first = false;
      late.push(() => { void realCreate(p); });
      throw Object.assign(new Error("Request timed out."), { name: "APIConnectionTimeoutError" });
    };
    const opts = { token: "ana", body: { name: "Ivy", job: "refunds", agent_key: "key_rae" }, names, anthropic: shared };

    expect((await call("POST", "/v1/tafs", opts)).status).toBe(502);
    for (let i = 0; i < 3; i++) {
      const retry = await call("POST", "/v1/tafs", opts);
      expect(retry.status).toBe(409);
      expect(retry.body.error).toMatch(/Choose another name/);
    }
    late.forEach((f) => f()); // the lost create lands, at any time
    expect((await call("POST", "/v1/tafs", opts)).status).toBe(409);
    expect(shared.deployments.filter((d) => d.metadata.tafs_taf === "Ivy")).toHaveLength(1);

    // Another name works at once.
    expect((await call("POST", "/v1/tafs", { ...opts, body: { ...opts.body, name: "Ivy 2" } })).status).toBe(200);
  });

  it("frees the name at once when Anthropic refuses the create", async () => {
    const names = memoryNames();
    const shared = fakeAnthropic();
    (shared.client.beta.deployments as unknown as { create: () => Promise<never> }).create = async () => {
      throw Object.assign(new Error("invalid schedule"), { status: 400 });
    };
    expect((await call("POST", "/v1/tafs", { token: "ana", body: { name: "Ivy", job: "refunds", agent_key: "key_rae" }, names, anthropic: shared })).status).toBe(502);
    expect(names.held.size).toBe(0);
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
