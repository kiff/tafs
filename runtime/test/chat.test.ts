import { describe, expect, it } from "vitest";
import { AgentsError } from "../src/cloud/agents.js";
import { CHAT, handle, type HTTPEvent, MEMORY, type Taf } from "../src/cloud/handler.js";
import { fakeAgents, memoryAccounts, memoryClaims, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

const ME = { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] };
const VIEWER = { ...ME, capabilities: ["read"] };
const NOW = new Date("2026-10-16T12:00:00Z");
const minutesAgo = (m: number) => new Date(NOW.getTime() - m * 60_000).toISOString();
const RAE = "taf-aaaa0000000000000000";
const MAX = "taf-bbbb0000000000000000";

function kiff(byToken: Record<string, object>) {
  return (async (_url: string, init?: RequestInit) => {
    const token = String((init?.headers as Record<string, string>).authorization).replace("Bearer ", "");
    const me = byToken[token];
    return me ? new Response(JSON.stringify(me), { status: 200 }) : new Response("{}", { status: 401 });
  }) as typeof fetch;
}

/** Rae (t_acme) and Max (t_other) on kiff/agents, with the clock at NOW unless a test moves it. */
function world() {
  let clock = NOW;
  const agents = fakeAgents(() => clock);
  const tafs = memoryTafs();
  for (const [tenant, id, name] of [["t_acme", RAE, "Rae"], ["t_other", MAX, "Max"]] as const) {
    const taf: Taf = { id, name, job: "refunds", kiffAgent: name.toLowerCase(), nameClaim: "c", created: "2026-10-15T09:00:00Z" };
    tafs.held.set(`${tenant}#${id}`, taf);
    void agents.api.putAgent(tenant, id, {} as never);
    void agents.api.createSchedule(tenant, { id, agent: id, cron: "*/30 * * * *", input: "Work the refund queue.", enabled: true });
  }
  agents.calls.length = 0;
  return { agents, tafs, slots: memorySlots(), at: (d: Date) => { clock = d; } };
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
    agents: w.agents.api, keys: memoryKeys(), accounts: memoryAccounts(), tafs: w.tafs,
    names: memoryNames(), slots: w.slots, claims: memoryClaims(), removals: memoryRemovals(),
    push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async () => "sent" as const },
    forward: async () => undefined,
    fetch: kiff({ ana: ME, viewer: VIEWER }),
    now: () => NOW,
  });
  return { status: r.statusCode, body: JSON.parse(r.body) };
}

/** A job run of Rae's at `at`: the schedule's instruction, then the taf's report. */
async function jobRun(w: World, at: Date, report: string) {
  w.at(at);
  const run = await w.agents.api.trigger("t_acme", RAE);
  w.agents.say(run, report, new Date(at.getTime() + 60_000).toISOString());
  return run;
}

describe("conversation", () => {
  it("reads the taf's runs and chats, oldest first, with each run's instruction marked", async () => {
    const w = world();
    const run = await jobRun(w, new Date(NOW.getTime() - 3 * 3600_000), "Refunded 3 orders.");
    w.at(new Date(NOW.getTime() - 3600_000));
    const chat = await w.agents.api.startRun("t_acme", RAE, "Why was order 7 held?");
    w.agents.say(chat, "It was over the Card's $50.");
    const r = await call(w, "GET", `/v1/tafs/${RAE}/conversation`, { token: "ana" });
    expect(r.status).toBe(200);
    expect(r.body.messages.map((m: { from: string; kind: string; text: string }) => [m.from, m.kind, m.text])).toEqual([
      ["owner", "kickoff", "Work the refund queue."],
      ["taf", "message", "Refunded 3 orders."],
      ["owner", "message", "Why was order 7 held?"],
      ["taf", "message", "It was over the Card's $50."],
    ]);
    expect(r.body.messages[0].session).toBe(run.session);
  });

  it("reads only the latest five runs", async () => {
    const w = world();
    for (let i = 0; i < 7; i++) await jobRun(w, new Date(NOW.getTime() - (10 - i) * 3600_000), `report ${i}`);
    const r = await call(w, "GET", `/v1/tafs/${RAE}/conversation`, { token: "ana" });
    const reports = r.body.messages.filter((m: { from: string }) => m.from === "taf").map((m: { text: string }) => m.text);
    expect(reports).toEqual(["report 2", "report 3", "report 4", "report 5", "report 6"]);
  });

  it("caps the thread to its newest messages", async () => {
    const w = world();
    w.at(new Date(NOW.getTime() - 3600_000));
    const chat = await w.agents.api.startRun("t_acme", RAE, "first");
    for (let i = 0; i < CHAT.maxThread + 20; i++) w.agents.say(chat, `answer ${i}`, new Date(NOW.getTime() - 3600_000 + (i + 1) * 1000).toISOString());
    const r = await call(w, "GET", `/v1/tafs/${RAE}/conversation`, { token: "ana" });
    expect(r.body.messages).toHaveLength(CHAT.maxThread);
    expect(r.body.messages.at(-1).text).toBe(`answer ${CHAT.maxThread + 19}`);
  });

  it("answers another account's taf like a missing one", async () => {
    const w = world();
    expect((await call(w, "GET", `/v1/tafs/${MAX}/conversation`, { token: "ana" })).status).toBe(404);
  });
});

describe("messages", () => {
  it("needs the right to manage Cards, and 1 to 2000 characters", async () => {
    const w = world();
    expect((await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "viewer", body: { text: "hi" } })).status).toBe(403);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: " " } })).status).toBe(400);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "x".repeat(2001) } })).status).toBe(400);
    expect(w.agents.calls).toEqual([]);
  });

  it("goes into the latest chat while it was active in the last 30 minutes", async () => {
    const w = world();
    w.at(new Date(minutesAgo(20)));
    const chat = await w.agents.api.startRun("t_acme", RAE, "Hello");
    const r = await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "And order 9?" } });
    expect(r.body).toEqual({ session: chat.session, new_session: false });
  });

  it("starts a new chat when the latest is older, even after a newer job run", async () => {
    const w = world();
    w.at(new Date(minutesAgo(45)));
    await w.agents.api.startRun("t_acme", RAE, "Hello");
    await jobRun(w, new Date(minutesAgo(5)), "Refunded 1 order.");
    w.agents.calls.length = 0;
    const r = await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "What did you just do?" } });
    expect(r.body.new_session).toBe(true);
    expect(w.agents.calls).toEqual([`startRun ${RAE} new`]);
  });

  it("starts a new chat when the live one refuses the message", async () => {
    const w = world();
    w.at(new Date(minutesAgo(5)));
    const chat = await w.agents.api.startRun("t_acme", RAE, "Hello");
    w.agents.fail.set("startRun", new AgentsError(409, "a run of this session is going"));
    const r = await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "Still there?" } });
    expect(r.body.new_session).toBe(true);
    expect(r.body.session).not.toBe(chat.session);
  });

  it("refuses an eleventh message in ten minutes, however many chats they start", async () => {
    const w = world();
    for (let i = 0; i < CHAT.floodMessages; i++) {
      expect((await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: `m${i}` } })).status).toBe(200);
    }
    const r = await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "one more" } });
    expect(r.status).toBe(429);
  });

  it("says the runtime didn't answer when kiff/agents is down", async () => {
    const w = world();
    w.agents.fail.set("sessions", new Error("connect ETIMEDOUT"));
    const r = await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "hi" } });
    expect(r.status).toBe(502);
    expect(r.body.error).not.toContain("ETIMEDOUT");
  });
});

describe("memory", () => {
  it("lists the taf's notes, newest first", async () => {
    const w = world();
    w.agents.notes.set(`t_acme|${RAE}`, [
      { agent: RAE, name: "acme", text: "Ask before refunding Acme.", updated: "2026-10-15T09:00:00.123456789Z" },
      { agent: RAE, name: "tone", text: "Short replies.", updated: "2026-10-16T09:00:00Z" },
    ]);
    const r = await call(w, "GET", `/v1/tafs/${RAE}/memory`, { token: "ana" });
    expect(r.body.notes).toEqual([
      { id: "tone", path: "tone", content: "Short replies.", updated_at: "2026-10-16T09:00:00.000Z" },
      { id: "acme", path: "acme", content: "Ask before refunding Acme.", updated_at: "2026-10-15T09:00:00.123Z" },
    ]);
  });

  it("shows the newest notes when there are more than it shows", async () => {
    const w = world();
    w.agents.notes.set(`t_acme|${RAE}`, Array.from({ length: MEMORY.shown + 5 }, (_, i) => ({
      agent: RAE, name: `n${i}`, text: "x", updated: new Date(Date.UTC(2026, 9, 1) + i * 60_000).toISOString(),
    })));
    const r = await call(w, "GET", `/v1/tafs/${RAE}/memory`, { token: "ana" });
    expect(r.body.notes).toHaveLength(MEMORY.shown);
    expect(r.body.notes[0].id).toBe(`n${MEMORY.shown + 4}`);
  });

  it("forgets a note by its name, as the app escapes it, for someone who can manage Cards", async () => {
    const w = world();
    w.agents.notes.set(`t_acme|${RAE}`, [{ agent: RAE, name: "customer prefs/acme", text: "x", updated: "2026-10-16T09:00:00Z" }]);
    expect((await call(w, "DELETE", `/v1/tafs/${RAE}/memory/customer%20prefs%2Facme`, { token: "viewer" })).status).toBe(403);
    const r = await call(w, "DELETE", `/v1/tafs/${RAE}/memory/customer%20prefs%2Facme`, { token: "ana" });
    expect(r.body).toEqual({ forgotten: "customer prefs/acme" });
    expect(w.agents.notes.get(`t_acme|${RAE}`)).toEqual([]);
  });

  it("can't forget another account's note", async () => {
    const w = world();
    expect((await call(w, "DELETE", `/v1/tafs/${MAX}/memory/acme`, { token: "ana" })).status).toBe(404);
    expect(w.agents.calls).toEqual([]);
  });
});
