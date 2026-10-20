import { describe, expect, it } from "vitest";
import { AgentsError } from "../src/cloud/agents.js";
import { calmEnough, type Deps, handle, type HTTPEvent, SETUP_JOB, type Taf, upgradeDefinitions } from "../src/cloud/handler.js";
import { setupDefinition } from "../src/cloud/setupdef.js";
import { definition, digest, instructions, KIFF_GATEWAY, MODEL, ownerNotesIn } from "../src/cloud/tafdef.js";
import type { Mandate } from "../src/cloud/home.js";
import type { ActivityCall } from "../src/cloud/improve.js";
import { jobById } from "../src/jobs.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

const ME = { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] };
const VIEWER = { ...ME, capabilities: ["read"] };
const AGENT_KEY = { tenant_id: "t_acme", subject: "k_1", api_key: true, agent_id: "rae", capabilities: ["actions.propose"] };

/** Drafts KIFF would let be claimed: draft id → its claim secret and agent key. Each claims once. */
type Drafts = Record<string, { secret: string; key: string; agent: string; status?: number }>;

/** What KIFF holds for the signed-in owner's account: its Cards and held calls, or a status it answers instead. */
interface Account { mandates: Mandate[]; held: { subject: string }[]; calls?: ActivityCall[]; status?: number }

/** KIFF's /v1/me, answering per bearer token, its draft claims, and the account's Cards and held calls. */
function kiff(byToken: Record<string, object>, drafts: Drafts, claimed: string[], account: Account = { mandates: [], held: [] }) {
  return (async (url: string, init?: RequestInit) => {
    const token = String((init?.headers as Record<string, string>).authorization).replace("Bearer ", "");
    if (url.includes("/v1/me/activity")) {
      if (!byToken[token]) return new Response("{}", { status: 401 });
      return new Response(JSON.stringify({ calls: account.calls ?? [] }), { status: 200 });
    }
    if (url.includes("/v1/me/mandates") || url.includes("/v1/me/card-exceptions")) {
      if (!byToken[token]) return new Response("{}", { status: 401 });
      if (account.status) return new Response(JSON.stringify({ error: "down" }), { status: account.status });
      return new Response(JSON.stringify(url.includes("mandates") ? { mandates: account.mandates } : { exceptions: account.held }), { status: 200 });
    }
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
  drf_setup: { secret: "dcs_s", key: "key_setup", agent: "setup-acme" },
};

const ids = (...hex: string[]) => hex.map((h) => `taf-${h.padEnd(20, "0")}`);
const [RAE, MAX, NEW, NEW2] = ids("aaaa", "bbbb", "cccc", "dddd") as [string, string, string, string];

/** tafs cloud's world: KIFF, kiff/agents, and its own stores, with Rae (t_acme) and Max (t_other) running. */
function world() {
  const agents = fakeAgents();
  const tafs = memoryTafs();
  const w = {
    agents, tafs,
    names: memoryNames(), claims: memoryClaims(), removals: memoryRemovals(), archived: memoryArchived(), keys: memoryKeys(), accounts: memoryAccounts(),
    claimed: [] as string[], drafts: DRAFTS, newIDs: [NEW, NEW2],
    account: { mandates: [], held: [] } as Account,
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
  const r = await handle(event, depsOf(w));
  return { status: r.statusCode, body: JSON.parse(r.body) };
}

function depsOf(w: World): Deps {
  return {
    agents: w.agents.api, keys: w.keys, accounts: w.accounts, tafs: w.tafs,
    names: w.names, slots: memorySlots(), claims: w.claims, removals: w.removals, archived: w.archived,
    push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async () => "sent" as const },
    forward: async () => undefined,
    fetch: kiff({ ana: ME, viewer: VIEWER, key_rae: AGENT_KEY, key_other: { ...AGENT_KEY, tenant_id: "t_other" }, key_unbound: { ...AGENT_KEY, agent_id: "" }, key_setup: { ...AGENT_KEY, agent_id: "setup-acme", capabilities: [] } }, w.drafts, w.claimed, w.account),
    newID: () => w.newIDs.shift() ?? "taf-ffffffffffffffffffff",
  };
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
    expect(def.instructions).toContain("## Handle a KIFF result");
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

describe("the home list", () => {
  /** Rae's refund Card: €100 a call, €1,000 a day with €200 used, 20 calls a day. */
  const raeCard: Mandate = {
    id: "card_rae", subject: "rae", active: true, on_exceed: "hold", valid_until: "2026-12-31T00:00:00Z",
    grants: [{ action: "stripe_refund", title: "Create refund", per_action_max: [{ quantity: "sum(amount)", max: 10000, unit: "EUR", scale: 2 }] }],
    aggregates: [{ quantity: "sum(amount)", limit: 100000, window: "1d", unit: "EUR", scale: 2 }, { quantity: "count", limit: 20, window: "1d" }],
    limits: [{ quantity: "sum(amount)", window: "1d", limit: 100000, used: 20000, status: "ok", unit: "EUR", scale: 2 }],
  };
  const demoCard: Mandate = { id: "card_demo", subject: "support-demo", active: true, grants: [{ action: "lookup" }], aggregates: [] };

  it("gives each taf its state, colour and Card summary, so the app combines nothing", async () => {
    const w = world();
    w.account.mandates = [raeCard];
    const r = await call(w, "GET", "/v1/home", { token: "ana" });
    expect(r.status).toBe(200);
    expect(r.body.tafs).toEqual([{
      id: RAE, name: "Rae", job: "refunds", kiff_agent: "rae", status: "active", next_run: "2026-10-16T12:30:00.000Z", last_run: null,
      color: 0, state: "working", waiting: 0,
      card: {
        ids: ["card_rae"], active: true, expires: "2026-12-31T00:00:00Z", tools: ["Create refund"],
        rules: [{
          action: "stripe_refund", label: "Create refund", outside: "hold",
          per_action: { minor: 10000, unit: "EUR", scale: 2 }, per_window: { minor: 100000, unit: "EUR", scale: 2 },
          count_per_window: 20, window: "day",
        }],
        used: { stripe_refund: { minor: 20000, unit: "EUR", scale: 2 } }, usage_unknown: [],
      },
    }]);
    expect(r.body.others).toEqual([]);
    expect(r.body.removing).toEqual([]);
    // The colour is kept, so the next taf takes another.
    expect(w.tafs.held.get(`t_acme#${RAE}`)?.color).toBe(0);
  });

  it("says paused, waiting and stopped from tafs cloud's schedule and KIFF's holds and Cards", async () => {
    const w = world();
    w.account.mandates = [raeCard];
    await call(w, "POST", `/v1/tafs/${RAE}/pause`, { token: "ana" });
    expect((await call(w, "GET", "/v1/home", { token: "ana" })).body.tafs[0].state).toBe("paused");

    w.account.held = [{ subject: "rae" }, { subject: "rae" }];
    const waiting = (await call(w, "GET", "/v1/home", { token: "ana" })).body.tafs[0];
    expect(waiting).toMatchObject({ state: "waiting", waiting: 2 });

    w.account.held = [];
    w.account.mandates = [{ ...raeCard, revoked_at: "2026-10-19T10:00:00Z", active: false }];
    const stopped = (await call(w, "GET", "/v1/home", { token: "ana" })).body.tafs[0];
    expect(stopped.state).toBe("stopped");
    expect(stopped.card).toMatchObject({ ids: [], active: false, rules: [] });
  });

  it("lists the account's other agents with Cards apart: idle, and never Setup's or a taf being removed", async () => {
    const w = world();
    await w.tafs.put("t_acme", { id: NEW, name: "Setup", job: SETUP_JOB, kiffAgent: "setup-acme", nameClaim: "c", created: "2026-10-14T09:00:00Z" });
    await w.tafs.put("t_acme", { id: NEW2, name: "Old", job: "refunds", kiffAgent: "old", nameClaim: "c2", created: "2026-10-13T09:00:00Z", removing: true });
    w.account.mandates = [raeCard, demoCard, { ...demoCard, id: "card_setup", subject: "setup-acme" }, { ...demoCard, id: "card_old", subject: "old" }];
    const r = (await call(w, "GET", "/v1/home", { token: "ana" })).body;
    expect(r.tafs.map((t: { name: string }) => t.name)).toEqual(["Rae"]);
    expect(r.others).toEqual([{
      id: null, name: "support-demo", job: null, kiff_agent: "support-demo", status: null, next_run: null, last_run: null,
      color: 1, state: "idle", waiting: 0,
      card: { ids: ["card_demo"], active: true, expires: null, tools: ["lookup"], rules: [{ action: "lookup", label: "lookup", outside: "hold", per_action: null, per_window: null, count_per_window: null, window: "day" }], used: {}, usage_unknown: [] },
    }]);
  });

  it("keeps each taf's colour, and gives a new one the first nobody has", async () => {
    const w = world();
    w.tafs.held.set(`t_acme#${RAE}`, { ...w.tafs.held.get(`t_acme#${RAE}`)!, color: 2 });
    await w.tafs.put("t_acme", { id: NEW, name: "Bo", job: "refunds", kiffAgent: "bo", nameClaim: "c", created: "2026-10-01T09:00:00Z" });
    const r = (await call(w, "GET", "/v1/home", { token: "ana" })).body;
    // Bo was made first and sorts first, but doesn't take Rae's colour.
    expect(r.tafs.map((t: { name: string; color: number }) => [t.name, t.color])).toEqual([["Bo", 0], ["Rae", 2]]);
  });

  it("gives a new taf its colour at its start: the first no other taf has", async () => {
    const w = world();
    w.tafs.held.set(`t_acme#${RAE}`, { ...w.tafs.held.get(`t_acme#${RAE}`)!, color: 0 });
    expect((await call(w, "POST", "/v1/tafs", { token: "ana", body: START })).status).toBe(200);
    expect(w.tafs.held.get(`t_acme#${NEW}`)?.color).toBe(1);
  });

  it("never writes back over a removal that lands while it lists", async () => {
    const w = world();
    const list = w.tafs.list.bind(w.tafs);
    // The owner removes Rae just after the list read it.
    w.tafs.list = async (t) => {
      const read = await list(t);
      const rae = w.tafs.held.get(`t_acme#${RAE}`)!;
      w.tafs.held.set(`t_acme#${RAE}`, { ...rae, removing: true });
      return read;
    };
    expect((await call(w, "GET", "/v1/home", { token: "ana" })).status).toBe(200);
    const rae = w.tafs.held.get(`t_acme#${RAE}`)!;
    expect(rae.removing).toBe(true);
    expect(rae.color).toBeUndefined();
  });

  it("shows use KIFF couldn't read as unknown, and a total in the unit KIFF declares, else bare", async () => {
    const w = world();
    w.account.mandates = [{ ...raeCard, limits: [{ ...raeCard.limits![0]!, status: "unavailable", used: null }] }, {
      ...demoCard, subject: "rae", id: "card_count", grants: [{ action: "send_email" }],
      aggregates: [{ quantity: "sum(units)", limit: 10, window: "7d" }], limits: [{ quantity: "sum(units)", window: "7d", limit: 10, used: 3, status: "ok" }],
    }];
    const card = (await call(w, "GET", "/v1/home", { token: "ana" })).body.tafs[0].card;
    expect(card.usage_unknown).toEqual(["stripe_refund"]);
    expect(card.used).toEqual({ send_email: { minor: 3, unit: null, scale: 0 } });
    expect(card.rules[1]).toMatchObject({ action: "send_email", per_window: { minor: 10, unit: null, scale: 0 }, window: "week" });
  });

  it("pairs a total with its own use: the day's ceiling with its extra room, and the day's use, not the month's", async () => {
    const w = world();
    w.account.mandates = [{
      ...raeCard,
      aggregates: [{ quantity: "sum(amount)", limit: 50000, window: "1d", unit: "EUR", scale: 2 }, { quantity: "sum(amount)", limit: 900000, window: "30d", unit: "EUR", scale: 2 }],
      limits: [
        { quantity: "sum(amount)", window: "30d", limit: 900000, used: 400000, status: "ok", unit: "EUR", scale: 2 },
        { quantity: "sum(amount)", window: "1d", limit: 60000, used: 21200, status: "ok", unit: "EUR", scale: 2 },
      ],
    }, { ...demoCard, id: "card_old_api", subject: "rae", grants: [{ action: "pay_invoice" }], aggregates: [{ quantity: "sum(amount)", limit: 50000, window: "1d" }] }];
    const card = (await call(w, "GET", "/v1/home", { token: "ana" })).body.tafs[0].card;
    expect(card.rules[0]).toMatchObject({ window: "day", per_window: { minor: 60000, unit: "EUR", scale: 2 }, count_per_window: null });
    expect(card.used).toEqual({ stripe_refund: { minor: 21200, unit: "EUR", scale: 2 } });
    expect(card.usage_unknown).toEqual(["pay_invoice"]);
  });

  it("fails rather than list tafs without their Cards, and a sign-in KIFF refuses is a 401", async () => {
    const w = world();
    w.account.status = 503;
    expect((await call(w, "GET", "/v1/home", { token: "ana" })).status).toBe(502);
    expect((await call(w, "GET", "/v1/home")).status).toBe(401);
    expect((await call(w, "GET", "/v1/home", { token: "key_rae" })).status).toBe(403);
  });
});

describe("removing a taf", () => {
  it("removes it from kiff/agents, then its key, then frees its name", async () => {
    const w = world();
    await w.archived.add("t_acme", RAE, "sess_x", new Date("2026-11-16T12:00:00Z"));
    const r = await call(w, "DELETE", `/v1/tafs/${RAE}`, { token: "ana" });
    expect(r.status).toBe(200);
    expect(r.body).toEqual({ taf: RAE, name: "Rae", kiff_agent: "rae", done: true, removed: ["schedule", "history", "memory", "key", "name"], failed: [] });
    expect(w.agents.calls).toEqual([`deleteAgent ${RAE}`]);
    expect(w.keys.held.has(`t_acme#${RAE}`)).toBe(false);
    expect(w.names.held.has("t_acme#Rae")).toBe(false);
    expect(w.tafs.held.has(`t_acme#${RAE}`)).toBe(false);
    expect(w.removals.held.size).toBe(0);
    expect(w.archived.held.size).toBe(0); // its archived chats are forgotten too
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
    // A removal begun on Managed Agents keeps its entry: its deployment, vaults
    // and name are still there, for retiring Managed Agents to delete.
    w.removals.held.set("t_acme#depl_old", { id: "depl_old", name: "Old" });
    expect((await call(w, "DELETE", "/v1/tafs/depl_old", { token: "ana" })).status).toBe(404);
    expect((await call(w, "GET", "/v1/tafs", { token: "ana" })).body.removing).toEqual([{ id: "depl_old", name: "Old" }]);
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

describe("Setup (kiff/agents#91)", () => {
  const SETUP = { draft_id: "drf_setup", claim_secret: "dcs_s" };

  it("starts once from the phone's no-tools draft: its key claimed here, its definition, no schedule", async () => {
    const w = world();
    expect((await call(w, "GET", "/v1/setup", { token: "ana" })).body).toEqual({ setup: null });
    const r = await call(w, "POST", "/v1/setup", { token: "ana", body: SETUP });
    expect(r.status).toBe(200);
    expect(r.body.setup).toMatchObject({ id: NEW, name: "Setup", job: "setup", kiff_agent: "setup-acme" });
    expect(JSON.stringify(r.body)).not.toContain("key_setup");
    expect(w.keys.held.get(`t_acme#${NEW}`)).toBe("key_setup");
    const def = w.agents.agents.get(`t_acme|${NEW}`)!;
    expect(def).toMatchObject({ show: true, planque: "https://planque.kiff.dev", key_ref: `kiff/${NEW}`, gateway: KIFF_GATEWAY });
    expect(w.agents.calls).toEqual([`putAgent ${NEW}`]);
    expect((await call(w, "GET", "/v1/setup", { token: "ana" })).body.setup).toMatchObject({ id: NEW, name: "Setup" });
    // It's not listed with the tafs, and its chat uses the taf routes by its id.
    expect((await call(w, "GET", "/v1/tafs", { token: "ana" })).body.tafs.map((t: { name: string }) => t.name)).toEqual(["Rae"]);
    expect((await call(w, "GET", `/v1/tafs/${NEW}/conversation`, { token: "ana" })).status).toBe(200);
  });

  it("starts once, needs the right to manage Cards and a draft, and never takes a taf's name", async () => {
    const w = world();
    expect((await call(w, "POST", "/v1/setup", { token: "viewer", body: SETUP })).status).toBe(403);
    expect((await call(w, "POST", "/v1/setup", { token: "ana", body: {} })).status).toBe(400);
    expect((await call(w, "POST", "/v1/setup", { token: "ana", body: { draft_id: "x", claim_secret: "y" } })).status).toBe(400);
    expect((await call(w, "POST", "/v1/setup", { token: "ana", body: SETUP })).status).toBe(200);
    expect((await call(w, "POST", "/v1/setup", { token: "ana", body: SETUP })).status).toBe(409);
    // A taf may still be named Setup.
    expect((await call(w, "POST", "/v1/tafs", { token: "ana", body: { ...START, name: "Setup" } })).status).toBe(200);
  });

  it("refuses a draft whose key is for another account", async () => {
    const w = world();
    const r = await call(w, "POST", "/v1/setup", { token: "ana", body: { draft_id: "drf_other", claim_secret: "dcs_o" } });
    expect(r.status).toBe(400);
    expect(w.tafs.held.size).toBe(2);
    expect((await call(w, "GET", "/v1/setup", { token: "ana" })).body).toEqual({ setup: null });
  });
});

describe("a taf Setup made (kiff/agents#91, #94)", () => {
  const BODY = { name: "Refunds", job: "refunds", draft_id: "drf_s1", from_setup: true };

  it("starts from Setup's draft: kiff/agents claims the key into the taf's reference", async () => {
    const w = world();
    w.agents.setupDrafts.set("drf_s1", { tenant: "t_acme", agent: "refunds-x" });
    const r = await call(w, "POST", "/v1/tafs", { token: "ana", body: BODY });
    expect(r.status).toBe(200);
    expect(r.body.taf).toMatchObject({ id: NEW, name: "Refunds", job: "refunds", kiff_agent: "refunds-x" });
    expect(w.agents.setupDrafts.get("drf_s1")?.keyRef).toBe(`kiff/${NEW}`);
    expect(w.keys.held.has(`t_acme#${NEW}`)).toBe(false);
    expect(w.agents.agents.get(`t_acme|${NEW}`)).toMatchObject({ key_ref: `kiff/${NEW}` });
    expect(w.agents.calls).toEqual([`claimTafDraft drf_s1 kiff/${NEW}`, `putAgent ${NEW}`, `createSchedule ${NEW} */30 * * * * `, `trigger ${NEW}`]);
    expect(w.claims.held.size).toBe(0);
  });

  it("takes a draft_id and nothing that holds a key, and Setup's draft only", async () => {
    const w = world();
    w.agents.setupDrafts.set("drf_s1", { tenant: "t_other", agent: "refunds-x" });
    expect((await call(w, "POST", "/v1/tafs", { token: "ana", body: { ...BODY, draft_id: undefined } })).status).toBe(400);
    expect((await call(w, "POST", "/v1/tafs", { token: "ana", body: { ...BODY, claim_secret: "dcs_1" } })).status).toBe(400);
    expect((await call(w, "POST", "/v1/tafs", { token: "ana", body: { ...BODY, agent_key: "key_rae" } })).status).toBe(400);
    expect((await call(w, "POST", "/v1/tafs", { token: "viewer", body: BODY })).status).toBe(403);
    // Another account's draft is missing here.
    expect((await call(w, "POST", "/v1/tafs", { token: "ana", body: BODY })).status).toBe(404);
  });

  it("resumes with the same taf id after a start that failed past the claim", async () => {
    const w = world();
    w.agents.setupDrafts.set("drf_s1", { tenant: "t_acme", agent: "refunds-x" });
    w.agents.fail.set("putAgent", new Error("boom"));
    expect((await call(w, "POST", "/v1/tafs", { token: "ana", body: BODY })).status).toBe(502);
    const again = await call(w, "POST", "/v1/tafs", { token: "ana", body: BODY });
    expect(again.status).toBe(200);
    expect(again.body.taf.id).toBe(NEW);
    expect(w.agents.setupDrafts.get("drf_s1")?.keyRef).toBe(`kiff/${NEW}`);
  });

  it("keeps the taf id on record before the claim, so a retry after an unknown outcome reuses it", async () => {
    const w = world();
    w.agents.setupDrafts.set("drf_s1", { tenant: "t_acme", agent: "refunds-x" });
    w.agents.fail.set("claimTafDraft", new Error("timed out"));
    expect((await call(w, "POST", "/v1/tafs", { token: "ana", body: BODY })).status).toBe(502);
    expect(w.claims.held.get("t_acme#drf_s1")).toMatchObject({ secret: "", name: "Refunds", tafID: NEW });
    const again = await call(w, "POST", "/v1/tafs", { token: "ana", body: BODY });
    expect(again.status).toBe(200);
    expect(again.body.taf.id).toBe(NEW);
    expect(w.agents.calls.filter((c) => c.startsWith("claimTafDraft"))).toEqual([`claimTafDraft drf_s1 kiff/${NEW}`, `claimTafDraft drf_s1 kiff/${NEW}`]);
  });

  it("leaves no record for a draft Setup didn't submit, so the phone's own claim still works", async () => {
    const w = world();
    expect((await call(w, "POST", "/v1/tafs", { token: "ana", body: { ...BODY, draft_id: "drf_1" } })).status).toBe(404);
    expect(w.claims.held.size).toBe(0);
    const own = await call(w, "POST", "/v1/tafs", { token: "ana", body: { name: "Refunds", job: "refunds", draft_id: "drf_1", claim_secret: "dcs_1" } });
    expect(own.status).toBe(200);
  });
});

describe("upgrading running tafs (kiff/tafs#69)", () => {
  it("reads a taf's start notes back from its instructions, paragraphs and all", () => {
    const notes = "Only EU customers.\n\nNever after 6pm.";
    expect(ownerNotesIn(instructions(jobById("refunds"), notes))).toBe(notes);
    expect(ownerNotesIn(instructions(jobById("refunds")))).toBeUndefined();
  });

  it("digests a definition the same whatever its field order, and without kiff/agents' own fields", () => {
    const d = definition(RAE, jobById("refunds"));
    const reordered = Object.fromEntries(Object.entries(d).reverse()) as typeof d;
    expect(digest(reordered)).toBe(digest(d));
    expect(digest({ ...d, tenant: "t_acme", id: RAE })).toBe(digest(d));
    expect(digest({ ...d, instructions: d.instructions + " " })).not.toBe(digest(d));
  });

  it("keeps a new taf's budget, start notes and digest on its record, so it's current at once", async () => {
    const w = world();
    const r = await call(w, "POST", "/v1/tafs", { token: "ana", body: { ...START, budget_cents: 150, notes: "Only EU customers." } });
    expect(r.status).toBe(200);
    const rec = (await w.tafs.list("t_acme")).find((t) => t.name === "Bo")!;
    expect(rec).toMatchObject({ budgetCents: 150, ownerNotes: "Only EU customers.", definition: expect.any(String) });
    const out = await upgradeDefinitions(depsOf(w), { dryRun: false });
    expect(out.find((o) => o.taf === rec.id)?.outcome).toBe("current");
  });

  it("rewrites an older taf whose definition is out of date, once, its budget and notes kept; a dry run writes nothing", async () => {
    const w = world();
    const notes = "Only EU customers.\n\nNever after 6pm.";
    const old = definition(RAE, jobById("refunds"), { budgetCents: 150, ownerNotes: notes });
    await w.agents.api.putAgent("t_acme", RAE, { ...old, instructions: old.instructions.replace("four ways", "three ways") });
    // Max (t_other) is held as it is built: only its digest is new.
    await w.agents.api.putAgent("t_other", MAX, definition(MAX, jobById("refunds")));
    w.agents.calls.length = 0;

    const dry = await upgradeDefinitions(depsOf(w), { dryRun: true });
    expect(dry.find((o) => o.taf === RAE)?.outcome).toBe("would write");
    expect(dry.find((o) => o.taf === MAX)?.outcome).toBe("current");
    expect(w.agents.calls.filter((c) => c.startsWith("putAgent"))).toEqual([]);
    expect((await w.tafs.get("t_acme", RAE))?.definition).toBeUndefined();

    const done = await upgradeDefinitions(depsOf(w), { dryRun: false });
    expect(done.find((o) => o.taf === RAE)?.outcome).toBe("written");
    expect(w.agents.calls.filter((c) => c.startsWith("putAgent"))).toEqual([`putAgent ${RAE}`]);
    const stored = (await w.agents.api.getAgent("t_acme", RAE))!;
    expect(digest(stored)).toBe(digest(old));
    expect(stored.max_cost_micro_usd).toBe(1_500_000);
    expect(ownerNotesIn(stored.instructions)).toBe(notes);
    expect(await w.tafs.get("t_acme", RAE)).toMatchObject({ budgetCents: 150, ownerNotes: notes, definition: digest(old) });
    expect((await w.tafs.get("t_other", MAX))?.definition).toBe(digest(definition(MAX, jobById("refunds"))));

    w.agents.calls.length = 0;
    const again = await upgradeDefinitions(depsOf(w), { dryRun: false });
    expect(again.every((o) => o.outcome === "current")).toBe(true);
    expect(w.agents.calls.filter((c) => c.startsWith("putAgent") || c.startsWith("getAgent"))).toEqual([]);
  });

  it("builds Setup from its own definition, skips a taf being removed, and says why one fails", async () => {
    const w = world();
    const SETUP = "taf-5e70000000000000000a";
    w.tafs.held.set(`t_acme#${SETUP}`, { id: SETUP, name: "Setup", job: SETUP_JOB, kiffAgent: "setup-acme", nameClaim: "c", created: "2026-10-17T09:00:00Z" });
    await w.agents.api.putAgent("t_acme", SETUP, { ...setupDefinition(SETUP), instructions: "an older Setup" });
    w.tafs.held.set("t_acme#taf-gone00000000000000000", { id: "taf-gone00000000000000000", name: "Gone", job: "refunds", kiffAgent: "g", nameClaim: "c", created: "2026-10-17T09:00:00Z", removing: true });
    w.tafs.held.set("t_acme#taf-odd000000000000000000", { id: "taf-odd000000000000000000", name: "Odd", job: "payroll", kiffAgent: "o", nameClaim: "c", created: "2026-10-17T09:00:00Z", budgetCents: 200 });
    const out = await upgradeDefinitions(depsOf(w), { dryRun: false });
    expect(out.find((o) => o.taf === SETUP)?.outcome).toBe("written");
    expect(digest((await w.agents.api.getAgent("t_acme", SETUP))!)).toBe(digest(setupDefinition(SETUP)));
    expect(out.find((o) => o.taf === "taf-gone00000000000000000")).toBeUndefined();
    expect(out.find((o) => o.taf === "taf-odd000000000000000000")).toMatchObject({ outcome: "failed", error: expect.stringContaining("payroll") });
    // Rae's world definition is empty ({}): no budget to read back, so it fails rather than guess.
    expect(out.find((o) => o.taf === RAE)).toMatchObject({ outcome: "failed", error: "its stored definition has no budget or instructions to keep" });
  });

  it("says why one failed by kind only: an AWS or kiff/agents message can name the account or tenant", async () => {
    const w = world();
    const denied = Object.assign(new Error("User: arn:aws:sts::123456789012:assumed-role/tafs-cloud-lambda/x is not authorized"), { name: "AccessDeniedException" });
    w.agents.fail.set("getAgent", denied);
    let out = await upgradeDefinitions(depsOf(w), { dryRun: true });
    const first = out.find((o) => o.outcome === "failed")!;
    expect(first.error).toBe("AWS or the runtime: AccessDeniedException");
    expect(JSON.stringify(out)).not.toContain("123456789012");
    w.agents.fail.set("getAgent", new AgentsError(500, "no agent t_acme/taf-aaaa0000000000000000"));
    out = await upgradeDefinitions(depsOf(w), { dryRun: true });
    expect(out.find((o) => o.outcome === "failed")?.error).toBe("kiff/agents answered 500");
  });
});

describe("improving a taf (kiff/tafs#107)", () => {
  const SETUP = "taf-5e70000000000000000a";
  const raeCard: Mandate = {
    id: "card_rae", subject: "rae", active: true, on_exceed: "hold",
    grants: [{ action: "create_refund_k3f9", title: "Refund a charge", per_action_max: [{ quantity: "sum(amount)", max: 10000, unit: "EUR", scale: 2 }] }],
    aggregates: [],
  };
  const withSetup = () => {
    const w = world();
    w.tafs.held.set(`t_acme#${SETUP}`, { id: SETUP, name: "Setup", job: SETUP_JOB, kiffAgent: "setup-acme", nameClaim: "c", created: "2026-10-17T09:00:00Z" });
    void w.agents.api.putAgent("t_acme", SETUP, setupDefinition(SETUP));
    w.account.mandates = [raeCard];
    w.account.calls = [
      { actor_id: "rae", action_name: "create_refund_k3f9", title: "Refund a charge", outcome: "allowed", created_at: "2026-10-19T09:00:00Z", tool_outcome: { status: "done" } },
      { actor_id: "rae", action_name: "create_refund_k3f9", title: "Refund a charge", outcome: "owner_required", created_at: "2026-10-19T10:00:00Z" },
      { actor_id: "someone-else", action_name: "payout", outcome: "allowed", created_at: "2026-10-19T11:00:00Z" },
    ];
    return w;
  };

  it("opens a new Setup conversation on the taf: its job, Card and latest calls, then the owner's words", async () => {
    const w = withSetup();
    const r = await call(w, "POST", `/v1/tafs/${RAE}/improve`, { token: "ana", body: { text: "It can't find the payments to refund." } });
    expect(r.status).toBe(200);
    expect(r.body).toMatchObject({ setup: SETUP, new_session: true });
    const first = w.agents.events.get(r.body.session)?.[0]?.body as { text: string };
    expect(first.text.startsWith(`[improve taf ${RAE}]`)).toBe(true);
    expect(first.text).toContain("Name: Rae. Job: refunds. Its agent in KIFF: rae.");
    expect(first.text).toContain("Tools on its Card: Refund a charge.");
    expect(first.text).toContain("- Refund a charge: at most 100.00 EUR a call, held for the owner beyond that");
    // Newest first, its own calls only.
    expect(first.text.indexOf("owner_required")).toBeLessThan(first.text.indexOf("allowed, done"));
    expect(first.text).not.toContain("payout");
    expect(first.text.endsWith("The owner says: It can't find the payments to refund.")).toBe(true);
  });

  it("shows the owner only their own words, in the thread and the chat's title", async () => {
    const w = withSetup();
    const r = await call(w, "POST", `/v1/tafs/${RAE}/improve`, { token: "ana", body: { text: "It can't find the payments to refund." } });
    const thread = await call(w, "GET", `/v1/tafs/${SETUP}/conversation`, { token: "ana" });
    const mine = (thread.body.messages as Array<{ from: string; text: string }>).filter((m) => m.from === "owner");
    expect(mine.map((m) => m.text)).toEqual(["It can't find the payments to refund."]);
    const chats = await call(w, "GET", `/v1/tafs/${SETUP}/chats`, { token: "ana" });
    expect((chats.body.chats as Array<{ session: string; title: string }>).find((c) => c.session === r.body.session)?.title).toBe("It can't find the payments to refund.");
  });

  it("needs the account's Setup and a manager, and never improves Setup itself", async () => {
    const w = world();
    expect((await call(w, "POST", `/v1/tafs/${RAE}/improve`, { token: "ana", body: { text: "x" } })).status).toBe(409);
    const v = withSetup();
    expect((await call(v, "POST", `/v1/tafs/${RAE}/improve`, { token: "viewer", body: { text: "x" } })).status).toBe(403);
    expect((await call(v, "POST", `/v1/tafs/${SETUP}/improve`, { token: "ana", body: { text: "x" } })).status).toBe(400);
    expect((await call(v, "POST", `/v1/tafs/${MAX}/improve`, { token: "ana", body: { text: "x" } })).status).toBe(404);
  });
});
