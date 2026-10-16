import { describe, expect, it } from "vitest";
import { AgentsError } from "../src/cloud/agents.js";
import { calmEnough, handle, type HTTPEvent, type Taf } from "../src/cloud/handler.js";
import { KIFF_GATEWAY, MODEL } from "../src/cloud/tafdef.js";
import { fakeAgents, memoryAccounts, memoryClaims, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

const ME = { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] };
const VIEWER = { ...ME, capabilities: ["read"] };
const AGENT_KEY = { tenant_id: "t_acme", subject: "k_1", api_key: true, agent_id: "rae", capabilities: ["actions.propose"] };

/** Drafts KIFF would let be claimed: draft id → its claim secret and agent key. Each claims once. */
type Drafts = Record<string, { secret: string; key: string; agent: string; status?: number }>;

/** KIFF's /v1/me, answering per bearer token, and its draft claims. */
function kiff(byToken: Record<string, object>, drafts: Drafts, claimed: string[]) {
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

const ids = (...hex: string[]) => hex.map((h) => `taf-${h.padEnd(20, "0")}`);
const [RAE, MAX, NEW, NEW2] = ids("aaaa", "bbbb", "cccc", "dddd") as [string, string, string, string];

/** tafs cloud's world: KIFF, kiff/agents, and its own stores, with Rae (t_acme) and Max (t_other) running. */
function world() {
  const agents = fakeAgents();
  const tafs = memoryTafs();
  const w = {
    agents, tafs,
    names: memoryNames(), claims: memoryClaims(), removals: memoryRemovals(), keys: memoryKeys(), accounts: memoryAccounts(),
    claimed: [] as string[], drafts: DRAFTS, newIDs: [NEW, NEW2],
  };
  for (const [tenant, id, name] of [["t_acme", RAE, "Rae"], ["t_other", MAX, "Max"]] as const) {
    const taf: Taf = { id, name, job: "refunds", kiffAgent: name.toLowerCase(), nameClaim: `claim-${id}`, created: "2026-10-15T09:00:00Z" };
    tafs.held.set(`${tenant}#${id}`, taf);
    w.names.held.set(`${tenant}#${name}`, taf.nameClaim);
    w.keys.held.set(`${tenant}#${id}`, `key_${name}`);
    void agents.api.putAgent(tenant, id, {} as never);
    void agents.api.createSchedule(tenant, { id, agent: id, cron: "*/30 * * * *", input: "Work the refund queue.", enabled: true });
  }
  agents.calls.length = 0;
  return w;
}
type World = ReturnType<typeof world>;

async function call(w: World, method: string, path: string, opts: { token?: string; body?: unknown } = {}) {
  const event: HTTPEvent = {
    rawPath: path,
    requestContext: { http: { method } },
    headers: opts.token ? { Authorization: `Bearer ${opts.token}` } : {},
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
  };
  const r = await handle(event, {
    agents: w.agents.api, keys: w.keys, accounts: w.accounts, tafs: w.tafs,
    names: w.names, slots: memorySlots(), claims: w.claims, removals: w.removals,
    push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async () => "sent" as const },
    forward: async () => undefined,
    fetch: kiff({ ana: ME, viewer: VIEWER, key_rae: AGENT_KEY, key_other: { ...AGENT_KEY, tenant_id: "t_other" }, key_unbound: { ...AGENT_KEY, agent_id: "" } }, w.drafts, w.claimed),
    newID: () => w.newIDs.shift() ?? "taf-ffffffffffffffffffff",
  });
  return { status: r.statusCode, body: JSON.parse(r.body) };
}

const START = { name: "Bo", job: "refunds", agent_key: "key_rae" };

describe("tafs cloud", () => {
  it("lists the jobs without signing in", async () => {
    const r = await call(world(), "GET", "/v1/jobs");
    expect(r.status).toBe(200);
    expect(r.body.jobs.map((j: { id: string }) => j.id)).toContain("refunds");
  });

  it("needs a KIFF sign-in, and a person rather than a key", async () => {
    const w = world();
    expect((await call(w, "GET", "/v1/tafs")).status).toBe(401);
    expect((await call(w, "GET", "/v1/tafs", { token: "key_rae" })).status).toBe(403);
  });

  it("lists only the caller's account's tafs, as the app reads them", async () => {
    const r = await call(world(), "GET", "/v1/tafs", { token: "ana" });
    expect(r.body.tafs).toEqual([{ id: RAE, name: "Rae", job: "refunds", kiff_agent: "rae", status: "active", next_run: "2026-10-16T12:30:00.000Z", last_run: null }]);
    expect(r.body.removing).toEqual([]);
  });

  it("treats another account's taf, or an id that isn't one, as missing", async () => {
    const w = world();
    for (const path of [`/v1/tafs/${MAX}/runs`, `/v1/tafs/${MAX}/run`, "/v1/tafs/depl_1/runs", `/v1/tafs/${RAE}x/runs`]) {
      const r = await call(w, path.endsWith("/run") ? "POST" : "GET", path, { token: "ana" });
      expect(r.status, path).toBe(404);
    }
    expect(w.agents.calls).toEqual([]);
  });

  it("starts a taf on kiff/agents: its key kept, its definition, its schedule, and a first run", async () => {
    const w = world();
    const r = await call(w, "POST", "/v1/tafs", { token: "ana", body: { ...START, cron: "0 9 * * 1-5", timezone: "Europe/Madrid", budget_cents: 300 } });
    expect(r.status).toBe(200);
    expect(r.body.taf).toMatchObject({ id: NEW, name: "Bo", job: "refunds", kiff_agent: "rae", status: "active" });
    expect(r.body.session).toMatch(/^sess/);
    // The key is kept where kiff/agents reads it; the definition only names it.
    expect(w.keys.held.get(`t_acme#${NEW}`)).toBe("key_rae");
    const def = w.agents.agents.get(`t_acme|${NEW}`)!;
    expect(def).toMatchObject({ model: MODEL, gateway: KIFF_GATEWAY, key_ref: `kiff/${NEW}`, max_cost_micro_usd: 3_000_000 });
    expect(JSON.stringify(def)).not.toContain("key_rae");
    expect(def.keep.tool_result).toEqual({ Keep: 1, Retention: 0 });
    expect(def.keep.owner_message.Keep).toBe(2);
    expect(def.instructions).toContain("Your job: Refunds");
    expect(def.instructions).toContain("name: kiff");
    expect(w.agents.calls).toEqual([`putAgent ${NEW}`, `createSchedule ${NEW} 0 9 * * 1-5 Europe/Madrid`, `trigger ${NEW}`]);
    expect(w.tafs.held.get(`t_acme#${NEW}`)).toMatchObject({ name: "Bo", kiffAgent: "rae" });
  });

  it("starts a taf from a confirmed KIFF draft, claiming its key itself", async () => {
    const w = world();
    const r = await call(w, "POST", "/v1/tafs", { token: "ana", body: { name: "Bo", job: "refunds", draft_id: "drf_1", claim_secret: "dcs_1" } });
    expect(r.status).toBe(200);
    expect(JSON.stringify(r.body)).not.toContain("key_rae");
    expect(w.keys.held.get(`t_acme#${NEW}`)).toBe("key_rae");
    expect(w.claims.held.size).toBe(0);
  });

  it("claims a draft only once the name is held, so a taken name or a second tap never spends it", async () => {
    const w = world();
    const taken = await call(w, "POST", "/v1/tafs", { token: "ana", body: { name: "Rae", job: "refunds", draft_id: "drf_1", claim_secret: "dcs_1" } });
    expect(taken.status).toBe(409);
    w.names.held.set("t_acme#Bo", "someone-else");
    const racing = await call(w, "POST", "/v1/tafs", { token: "ana", body: { name: "Bo", job: "refunds", draft_id: "drf_1", claim_secret: "dcs_1" } });
    expect(racing.status).toBe(409);
    expect(w.claimed).toEqual([]);
  });

  it("frees the name when KIFF won't give the draft's key, and says why", async () => {
    const w = world();
    const r = await call(w, "POST", "/v1/tafs", { token: "ana", body: { name: "Bo", job: "refunds", draft_id: "drf_gone", claim_secret: "dcs_g" } });
    expect(r.status).toBe(410);
    expect(w.names.held.has("t_acme#Bo")).toBe(false);
  });

  it("resumes a start that failed after the one-time claim, under the same taf and key", async () => {
    const w = world();
    w.agents.fail.set("putAgent", new Error("kiff/agents is down"));
    w.agents.fail.set("deleteAgent", new Error("kiff/agents is down"));
    const first = await call(w, "POST", "/v1/tafs", { token: "ana", body: { name: "Bo", job: "refunds", draft_id: "drf_1", claim_secret: "dcs_1" } });
    expect(first.status).toBe(502);
    // Nothing proved the start undone, so the name stays this start's, and the key and the claim stay on record.
    expect(w.claims.held.get("t_acme#drf_1")).toMatchObject({ name: "Bo", tafID: NEW });
    expect(w.keys.held.get(`t_acme#${NEW}`)).toBe("key_rae");
    w.names.held.delete("t_acme#Bo"); // the owner retries once the outcome is known: the name came free
    const second = await call(w, "POST", "/v1/tafs", { token: "ana", body: { name: "Bo", job: "refunds", draft_id: "drf_1", claim_secret: "dcs_1" } });
    expect(second.status).toBe(200);
    expect(second.body.taf.id).toBe(NEW);
    expect(w.claimed).toEqual(["drf_1"]);
    // Bo started, so the draft is spent: it can't start a second taf.
    const other = await call(w, "POST", "/v1/tafs", { token: "ana", body: { name: "Cy", job: "refunds", draft_id: "drf_1", claim_secret: "dcs_1" } });
    expect(other.status).toBe(410);
  });

  it("says a draft is spent when its key was claimed and nothing kept it", async () => {
    const w = world();
    w.claimed.push("drf_1");
    const r = await call(w, "POST", "/v1/tafs", { token: "ana", body: { name: "Bo", job: "refunds", draft_id: "drf_1", claim_secret: "dcs_1" } });
    expect(r.status).toBe(410);
  });

  it("needs both a draft id and its secret, or an agent key", async () => {
    const w = world();
    for (const body of [{ name: "Bo", job: "refunds" }, { name: "Bo", job: "refunds", draft_id: "drf_1" }, { name: "Bo", job: "refunds", draft_id: "x_1", claim_secret: "s" }]) {
      expect((await call(w, "POST", "/v1/tafs", { token: "ana", body })).status).toBe(400);
    }
  });

  it("gives the taf what the owner said about the job, in its instructions", async () => {
    const w = world();
    await call(w, "POST", "/v1/tafs", { token: "ana", body: { ...START, notes: "Refund anything under $20 without asking." } });
    expect(w.agents.agents.get(`t_acme|${NEW}`)!.instructions).toContain("Refund anything under $20 without asking.");
  });

  it("refuses to start without the right, the right key, or sane settings", async () => {
    const w = world();
    const bad: Array<[string, object, number]> = [
      ["viewer", START, 403],
      ["ana", { ...START, agent_key: "key_other" }, 400],
      ["ana", { ...START, agent_key: "key_unbound" }, 400],
      ["ana", { ...START, agent_key: "nope" }, 400],
      ["ana", { ...START, cron: "*/5 * * * *" }, 400],
      ["ana", { ...START, budget_cents: 5000 }, 400],
      ["ana", { ...START, name: "" }, 400],
      ["ana", { ...START, job: "nope" }, 400],
    ];
    for (const [token, body, status] of bad) expect((await call(w, "POST", "/v1/tafs", { token, body })).status, JSON.stringify(body)).toBe(status);
    expect(w.agents.calls).toEqual([]);
    expect(w.keys.held.has(`t_acme#${NEW}`)).toBe(false);
  });

  it("starts one taf when two requests race for the same name", async () => {
    const w = world();
    const [a, b] = await Promise.all([
      call(w, "POST", "/v1/tafs", { token: "ana", body: START }),
      call(w, "POST", "/v1/tafs", { token: "ana", body: START }),
    ]);
    expect([a.status, b.status].sort()).toEqual([200, 409]);
    expect([...w.tafs.held.values()].filter((t) => t.name === "Bo")).toHaveLength(1);
  });

  it("undoes a start kiff/agents refused, and frees the name", async () => {
    const w = world();
    w.agents.fail.set("createSchedule", new AgentsError(400, "schedule: a time zone is an IANA name"));
    const r = await call(w, "POST", "/v1/tafs", { token: "ana", body: { ...START, timezone: "Mars/Olympus" } });
    expect(r.status).toBe(400);
    expect(r.body.error).toContain("IANA");
    expect(w.agents.agents.has(`t_acme|${NEW}`)).toBe(false);
    expect(w.keys.held.has(`t_acme#${NEW}`)).toBe(false);
    expect(w.names.held.has("t_acme#Bo")).toBe(false);
    expect(w.tafs.held.has(`t_acme#${NEW}`)).toBe(false);
  });

  it("leaves no ghost when the taf's record was written but its answer was lost", async () => {
    const w = world();
    const put = w.tafs.put.bind(w.tafs);
    let lost = true;
    w.tafs.put = async (t, taf) => { await put(t, taf); if (lost) { lost = false; throw new Error("the answer was lost"); } };
    const first = await call(w, "POST", "/v1/tafs", { token: "ana", body: START });
    expect(first.status).toBe(502);
    expect((await call(w, "GET", "/v1/tafs", { token: "ana" })).body.tafs.map((t: { name: string }) => t.name)).toEqual(["Rae"]);
    expect(w.agents.agents.has(`t_acme|${NEW}`)).toBe(false);
    expect(w.names.held.has("t_acme#Bo")).toBe(false);
    const retry = await call(w, "POST", "/v1/tafs", { token: "ana", body: START });
    expect(retry.status).toBe(200);
  });

  it("keeps the name when what a failed start made can't be undone", async () => {
    const w = world();
    w.agents.fail.set("createSchedule", new Error("timeout"));
    w.agents.fail.set("deleteAgent", new Error("timeout"));
    const r = await call(w, "POST", "/v1/tafs", { token: "ana", body: START });
    expect(r.status).toBe(502);
    expect(w.names.held.has("t_acme#Bo")).toBe(true);
  });

  it("says when a taf started but its first run didn't", async () => {
    const w = world();
    w.agents.fail.set("trigger", new Error("timeout"));
    const r = await call(w, "POST", "/v1/tafs", { token: "ana", body: START });
    expect(r.status).toBe(502);
    expect(r.body.error).toContain("Run it now");
    expect(w.tafs.held.has(`t_acme#${NEW}`)).toBe(true);
  });

  it("pauses, resumes and runs the caller's taf, through its schedule", async () => {
    const w = world();
    const paused = await call(w, "POST", `/v1/tafs/${RAE}/pause`, { token: "ana" });
    expect(paused.body.taf).toMatchObject({ status: "paused", next_run: null });
    const resumed = await call(w, "POST", `/v1/tafs/${RAE}/resume`, { token: "ana" });
    expect(resumed.body.taf).toMatchObject({ status: "active" });
    const run = await call(w, "POST", `/v1/tafs/${RAE}/run`, { token: "ana" });
    expect(run.body.session).toMatch(/^sess/);
    expect(w.agents.calls).toEqual([`switchSchedule ${RAE} off`, `switchSchedule ${RAE} on`, `trigger ${RAE}`]);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/pause`, { token: "viewer" })).status).toBe(403);
  });

  it("lists the taf's runs, and reads a run's report only for its own runs", async () => {
    const w = world();
    const started = await call(w, "POST", `/v1/tafs/${RAE}/run`, { token: "ana" });
    const run = w.agents.runs[0]!;
    const runs = await call(w, "GET", `/v1/tafs/${RAE}/runs`, { token: "ana" });
    expect(runs.body.runs).toEqual([{ session: started.body.session, at: "2026-10-16T12:00:00.000Z", error: null }]);
    expect((await call(w, "GET", `/v1/tafs/${RAE}/runs/${started.body.session}`, { token: "ana" })).body.report).toEqual({ status: "running", text: "" });
    // Waiting on the owner is still working.
    run.status = "PAUSED";
    expect((await call(w, "GET", `/v1/tafs/${RAE}/runs/${started.body.session}`, { token: "ana" })).body.report.status).toBe("running");
    Object.assign(run, { status: "COMPLETED", output: "Refunded 3 orders; 1 waits on you." });
    expect((await call(w, "GET", `/v1/tafs/${RAE}/runs/${started.body.session}`, { token: "ana" })).body.report).toEqual({ status: "idle", text: "Refunded 3 orders; 1 waits on you." });
    expect((await call(w, "GET", `/v1/tafs/${RAE}/runs/sess_other`, { token: "ana" })).status).toBe(404);
  });
});

describe("calmEnough", () => {
  it("allows every 15 minutes or less often", () => {
    for (const c of ["*/15 * * * *", "0 * * * *", "0,30 * * * *", "0 9 * * 1-5"]) expect(calmEnough(c), c).toBe(true);
    for (const c of ["*/10 * * * *", "* * * * *", "0,10 * * * *", "0 9 * *"]) expect(calmEnough(c), c).toBe(false);
  });

  it("agrees on equivalent forms", () => {
    expect(calmEnough("0,15,30,45 * * * *")).toBe(calmEnough("*/15 * * * *"));
    expect(calmEnough("50,5 * * * *")).toBe(true);
  });
});

describe("removing a taf", () => {
  it("removes it from kiff/agents, then its key, then frees its name", async () => {
    const w = world();
    const r = await call(w, "DELETE", `/v1/tafs/${RAE}`, { token: "ana" });
    expect(r.status).toBe(200);
    expect(r.body).toEqual({ taf: RAE, name: "Rae", kiff_agent: "rae", done: true, removed: ["schedule", "history", "memory", "key", "name"], failed: [] });
    expect(w.agents.calls).toEqual([`deleteAgent ${RAE}`]);
    expect(w.keys.held.has(`t_acme#${RAE}`)).toBe(false);
    expect(w.names.held.has("t_acme#Rae")).toBe(false);
    expect(w.tafs.held.has(`t_acme#${RAE}`)).toBe(false);
    expect(w.removals.held.size).toBe(0);
  });

  it("takes the right to manage Cards, and another account's taf answers like a missing one", async () => {
    const w = world();
    expect((await call(w, "DELETE", `/v1/tafs/${RAE}`, { token: "viewer" })).status).toBe(403);
    expect((await call(w, "DELETE", `/v1/tafs/${MAX}`, { token: "ana" })).status).toBe(404);
    expect(w.agents.calls).toEqual([]);
  });

  it("deletes nothing else while kiff/agents may still run it, and a second remove finishes", async () => {
    const w = world();
    w.agents.fail.set("deleteAgent", new Error("timeout"));
    const first = await call(w, "DELETE", `/v1/tafs/${RAE}`, { token: "ana" });
    expect(first.body).toMatchObject({ done: false, removed: [], failed: ["schedule", "history", "memory"] });
    expect(w.keys.held.has(`t_acme#${RAE}`)).toBe(true);
    // Gone from the list but for finishing it.
    const list = await call(w, "GET", "/v1/tafs", { token: "ana" });
    expect(list.body.tafs).toEqual([]);
    expect(list.body.removing).toEqual([{ id: RAE, name: "Rae" }]);
    expect((await call(w, "GET", `/v1/tafs/${RAE}/runs`, { token: "ana" })).status).toBe(404);
    const second = await call(w, "DELETE", `/v1/tafs/${RAE}`, { token: "ana" });
    expect(second.body).toMatchObject({ done: true, failed: [] });
    expect((await call(w, "GET", "/v1/tafs", { token: "ana" })).body.removing).toEqual([]);
  });

  it("finishes a removal whose last step failed after the taf's record was gone", async () => {
    const w = world();
    const done = w.removals.done.bind(w.removals);
    let fail = true;
    w.removals.done = async (t, id) => { if (fail) { fail = false; throw new Error("timeout"); } await done(t, id); };
    expect((await call(w, "DELETE", `/v1/tafs/${RAE}`, { token: "ana" })).status).toBe(500);
    expect((await call(w, "GET", "/v1/tafs", { token: "ana" })).body.removing).toEqual([{ id: RAE, name: "Rae" }]);
    const again = await call(w, "DELETE", `/v1/tafs/${RAE}`, { token: "ana" });
    expect(again.body).toEqual({ taf: RAE, name: "Rae", kiff_agent: null, done: true, removed: [], failed: [] });
    expect((await call(w, "GET", "/v1/tafs", { token: "ana" })).body.removing).toEqual([]);
    // Another account's leftover removal can't be cleared from here.
    w.removals.held.set(`t_other#${MAX}`, { id: MAX, name: "Max" });
    w.tafs.held.delete(`t_other#${MAX}`);
    expect((await call(w, "DELETE", `/v1/tafs/${MAX}`, { token: "ana" })).status).toBe(404);
    expect(w.removals.held.has(`t_other#${MAX}`)).toBe(true);
  });

  it("keeps the name for a newer taf, however often an old removal is replayed", async () => {
    const w = world();
    w.keys.fail.add("remove");
    await call(w, "DELETE", `/v1/tafs/${RAE}`, { token: "ana" });
    w.names.held.set("t_acme#Rae", "newer-start");
    await call(w, "DELETE", `/v1/tafs/${RAE}`, { token: "ana" });
    expect(w.names.held.get("t_acme#Rae")).toBe("newer-start");
  });
});
