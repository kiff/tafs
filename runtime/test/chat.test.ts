import { describe, expect, it } from "vitest";
import { AgentsError } from "../src/cloud/agents.js";
import { CHAT, chatTitle, handle, type HTTPEvent, MEMORY, type Taf } from "../src/cloud/handler.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

const ME = { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] };
const VIEWER = { ...ME, capabilities: ["read"] };
const APPROVER = { ...ME, subject: "u_bo", capabilities: ["read", "holds.answer"] };
const NOW = new Date("2026-10-16T12:00:00Z");
/** Ana as kiff/agents knows her: the requester of her runs, and her chats' owner. */
const ANA = { requester: { kind: "member" as const, id: "u_ana" } };
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
  return { agents, tafs, slots: memorySlots(), archived: memoryArchived(), at: (d: Date) => { clock = d; } };
}
type World = ReturnType<typeof world>;

async function call(w: World, method: string, path: string, opts: { token?: string; body?: unknown; fetch?: typeof fetch; headers?: Record<string, string> } = {}) {
  const event: HTTPEvent = {
    rawPath: path,
    requestContext: { http: { method } },
    headers: { ...(opts.token ? { Authorization: `Bearer ${opts.token}` } : {}), ...opts.headers },
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
  };
  const r = await handle(event, {
    agents: w.agents.api, keys: memoryKeys(), accounts: memoryAccounts(), tafs: w.tafs,
    names: memoryNames(), slots: w.slots, claims: memoryClaims(), removals: memoryRemovals(), archived: w.archived,
    push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async () => "sent" as const },
    forward: async () => undefined,
    fetch: opts.fetch ?? kiff({ ana: ME, viewer: VIEWER, approver: APPROVER }),
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
    const chat = await w.agents.api.startRun("t_acme", RAE, "Why was order 7 held?", ANA);
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
    const chat = await w.agents.api.startRun("t_acme", RAE, "first", ANA);
    for (let i = 0; i < CHAT.maxThread + 20; i++) w.agents.say(chat, `answer ${i}`, new Date(NOW.getTime() - 3600_000 + (i + 1) * 1000).toISOString());
    const r = await call(w, "GET", `/v1/tafs/${RAE}/conversation`, { token: "ana" });
    expect(r.body.messages).toHaveLength(CHAT.maxThread);
    expect(r.body.messages.at(-1).text).toBe(`answer ${CHAT.maxThread + 19}`);
  });

  it("says whether the taf is at work in its latest chat, for the app's thinking and Stop", async () => {
    const w = world();
    w.at(new Date(NOW.getTime() - 60_000));
    const chat = await w.agents.api.startRun("t_acme", RAE, "Why was order 7 held?", ANA);
    let r = await call(w, "GET", `/v1/tafs/${RAE}/conversation`, { token: "ana" });
    expect(r.body.working).toEqual({ session: chat.session, run: chat.id });
    w.agents.say(chat, "It was over the Card's $50.");
    (chat as { status: string }).status = "COMPLETED";
    r = await call(w, "GET", `/v1/tafs/${RAE}/conversation`, { token: "ana" });
    expect(r.body.working).toBeNull();
    // kiff/agents not answering about the run reads as not working; the thread still loads.
    (chat as { status: string }).status = "RUNNING";
    w.agents.fail.set("getRun", new Error("down"));
    r = await call(w, "GET", `/v1/tafs/${RAE}/conversation`, { token: "ana" });
    expect(r.status).toBe(200);
    expect(r.body.working).toBeNull();
  });

  it("says when the latest run stopped at a cap, for the app to word", async () => {
    const w = world();
    const chat = await w.agents.api.startRun("t_acme", RAE, "Refund every charge this month", ANA);
    Object.assign(w.agents.runs.find((r) => r.id === chat.id)!, { status: "COMPLETED", stop: "cost_cap" });
    const r = await call(w, "GET", `/v1/tafs/${RAE}/conversation`, { token: "ana" });
    expect(r.body.capped).toEqual({ run: chat.id, stop: "cost_cap" });
    Object.assign(w.agents.runs.find((r) => r.id === chat.id)!, { stop: "answered" });
    expect((await call(w, "GET", `/v1/tafs/${RAE}/conversation`, { token: "ana" })).body.capped).toBeUndefined();
  });

  it("says what the owner wrote to the latest run that it hasn't read, while it goes and once it's over", async () => {
    const w = world();
    w.at(new Date(NOW.getTime() - 60_000));
    const chat = await w.agents.api.startRun("t_acme", RAE, "Work the queue", ANA);
    await w.agents.api.steerRun("t_acme", RAE, chat.id, "only the €20 one");
    await w.agents.api.steerRun("t_acme", RAE, chat.id, "and say when it's done");
    const run = w.agents.runs.find((x) => x.id === chat.id)!;
    run.steering![0]!.taken = true;
    run.steering![1]!.at = "2026-10-16T11:59:00.123456789Z"; // as kiff/agents writes it
    let r = await call(w, "GET", `/v1/tafs/${RAE}/conversation`, { token: "ana" });
    expect(r.body.working).toEqual({ session: chat.session, run: chat.id });
    expect(r.body.steering).toEqual({ run: chat.id, status: "PENDING", untaken: [{ text: "and say when it's done", at: "2026-10-16T11:59:00.123Z" }] });
    (run as { status: string }).status = "CANCELLED";
    r = await call(w, "GET", `/v1/tafs/${RAE}/conversation`, { token: "ana" });
    expect(r.body.working).toBeNull();
    expect(r.body.steering).toMatchObject({ status: "CANCELLED", untaken: [{ text: "and say when it's done" }] });
    run.steering![1]!.taken = true;
    r = await call(w, "GET", `/v1/tafs/${RAE}/conversation`, { token: "ana" });
    expect(r.body.steering).toBeNull();
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

  it("carries the person's run grant from KIFF, and stops when KIFF says the taf isn't theirs", async () => {
    const w = world();
    let answer: { status: number; body: object } = { status: 200, body: { grant: "krg_u_ana", subject: "u_ana", agent: "rae" } };
    const asked: string[] = [];
    const me = kiff({ ana: ME });
    const fetchFor = (async (url: string, init?: RequestInit) => {
      if (!url.endsWith("/v1/me/run-grants")) return me(url, init);
      asked.push(String(init?.body));
      return new Response(JSON.stringify(answer.body), { status: answer.status });
    }) as typeof fetch;
    // Ana's grant for Rae's KIFF agent goes on her run: kiff/agents takes her from it.
    expect((await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "Refund 42" }, fetch: fetchFor })).status).toBe(200);
    expect(asked).toEqual([JSON.stringify({ agent: "rae" })]);
    expect(w.agents.runs.at(-1)!.requester).toEqual(ANA.requester);
    // KIFF says the taf isn't shared with her: nothing starts.
    answer = { status: 403, body: { error: "no_access", need: "use" } };
    const runs = w.agents.runs.length;
    const r = await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "Refund 43", new_chat: true }, fetch: fetchFor });
    expect(r.status).toBe(403);
    expect(w.agents.runs.length).toBe(runs);
    // KIFF gives none for another reason (not yet able to): the run goes as before.
    answer = { status: 404, body: { error: "not found" } };
    expect((await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "Refund 44", new_chat: true }, fetch: fetchFor })).status).toBe(200);
  });

  it("goes into the latest chat while it was active in the last 30 minutes", async () => {
    const w = world();
    w.at(new Date(minutesAgo(20)));
    const chat = await w.agents.api.startRun("t_acme", RAE, "Hello", ANA);
    const r = await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "And order 9?" } });
    expect(r.body).toEqual({ session: chat.session, new_session: false });
    expect(w.agents.runs[0]!.requester).toEqual(ANA.requester);
  });

  it("starts a new chat when the latest is older, even after a newer job run", async () => {
    const w = world();
    w.at(new Date(minutesAgo(45)));
    await w.agents.api.startRun("t_acme", RAE, "Hello", ANA);
    await jobRun(w, new Date(minutesAgo(5)), "Refunded 1 order.");
    w.agents.calls.length = 0;
    const r = await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "What did you just do?" } });
    expect(r.body.new_session).toBe(true);
    expect(w.agents.calls).toEqual([`startRun ${RAE} live`]);
  });

  it("never goes on in another person's chat, nor opens one by its id", async () => {
    const w = world();
    w.at(new Date(minutesAgo(5)));
    const bos = await w.agents.api.startRun("t_acme", RAE, "Bo's question", { requester: { kind: "member", id: "u_bo" } });
    const r = await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "Still there?" } });
    expect(r.body.new_session).toBe(true);
    expect(r.body.session).not.toBe(bos.session);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "hi", session: bos.session } })).status).toBe(404);
    expect((await call(w, "GET", `/v1/tafs/${RAE}/conversation/${bos.session}`, { token: "ana" })).status).toBe(404);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/chats/${bos.session}/archive`, { token: "ana" })).status).toBe(404);
    const listed = await call(w, "GET", `/v1/tafs/${RAE}/chats`, { token: "ana" });
    expect(listed.body.chats.map((c: { session: string }) => c.session)).toEqual([r.body.session]);
    const thread = await call(w, "GET", `/v1/tafs/${RAE}/conversation`, { token: "ana" });
    expect(thread.body.messages.map((m: { text: string }) => m.text)).not.toContain("Bo's question");
  });

  it("shows the person's own chat in the thread however many other people chat with the taf", async () => {
    const w = world();
    w.at(new Date(minutesAgo(120)));
    await w.agents.api.startRun("t_acme", RAE, "Ana's question", ANA);
    for (let i = 0; i < CHAT.sessionsScanned + 5; i++) {
      w.at(new Date(minutesAgo(100 - i)));
      await w.agents.api.startRun("t_acme", RAE, `others ${i}`, { requester: { kind: "member", id: `u_${i}` } });
    }
    const thread = await call(w, "GET", `/v1/tafs/${RAE}/conversation`, { token: "ana" });
    expect(thread.body.messages.map((m: { text: string }) => m.text)).toEqual(["Ana's question"]);
  });

  it("passes on kiff/agents' refusal of an eleventh message in ten minutes, however many chats they start", async () => {
    const w = world();
    for (let i = 0; i < 10; i++) {
      expect((await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: `m${i}` } })).status).toBe(200);
    }
    const r = await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "one more", new_chat: true } });
    expect(r.status).toBe(429);
    expect(r.body.error).toMatch(/10 messages in 10 minutes/);
  });

  it("says the runtime didn't answer when kiff/agents is down", async () => {
    const w = world();
    w.agents.fail.set("startRun", new Error("connect ETIMEDOUT"));
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
      { id: "tone", path: "tone", content: "Short replies.", updated_at: "2026-10-16T09:00:00.000Z", scope: "shared" },
      { id: "acme", path: "acme", content: "Ask before refunding Acme.", updated_at: "2026-10-15T09:00:00.123Z", scope: "shared" },
    ]);
  });

  it("shows the person the shared notes and their own, never another person's, and forgets their own apart", async () => {
    const w = world();
    w.agents.notes.set(`t_acme|${RAE}`, [
      { agent: RAE, name: "tone", text: "Short replies.", updated: "2026-10-16T09:00:00Z" },
      { agent: RAE, scope: "member:u_ana", name: "tone", text: "Ana likes bullet points.", updated: "2026-10-16T10:00:00Z" },
      { agent: RAE, scope: "member:u_bo", name: "bo", text: "Bo's own.", updated: "2026-10-16T11:00:00Z" },
    ]);
    const r = await call(w, "GET", `/v1/tafs/${RAE}/memory`, { token: "ana" });
    expect(r.body.notes.map((n: { id: string; scope: string }) => [n.id, n.scope])).toEqual([["@tone", "mine"], ["tone", "shared"]]);
    expect((await call(w, "DELETE", `/v1/tafs/${RAE}/memory/%40tone`, { token: "ana" })).body).toEqual({ forgotten: "@tone" });
    expect(w.agents.calls).toContain(`forget ${RAE} member:u_ana tone`);
    expect(w.agents.notes.get(`t_acme|${RAE}`)!.map((n) => n.text)).toEqual(["Short replies.", "Bo's own."]);
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

describe("components (kiff/agents#91)", () => {
  it("returns what the agent showed next to the messages, in order, the messages unchanged", async () => {
    const w = world();
    w.at(new Date(NOW.getTime() - 3600_000));
    const chat = await w.agents.api.startRun("t_acme", RAE, "Connect my Stripe", ANA);
    w.agents.show(chat, { type: "steps", id: "plan", steps: [{ label: "Sign in to Stripe", status: "active" }] }, minutesAgo(59));
    w.agents.say(chat, "Here's the plan.", minutesAgo(58));
    w.agents.show(chat, { type: "approval", id: "connect-stripe", grant: "grt_1" }, minutesAgo(57));
    w.agents.show(chat, { type: "steps", id: "plan", steps: [{ label: "Sign in to Stripe", status: "done" }] }, minutesAgo(56));
    const r = await call(w, "GET", `/v1/tafs/${RAE}/conversation`, { token: "ana" });
    expect(r.status).toBe(200);
    expect(r.body.messages.map((m: { text: string }) => m.text)).toEqual(["Connect my Stripe", "Here's the plan."]);
    expect(r.body.components.map((c: { component: { id: string } }) => c.component.id)).toEqual(["plan", "connect-stripe", "plan"]);
    expect(r.body.components[0]).toMatchObject({ session: chat.session, run: chat.id });
    expect(r.body.components[2].component.steps[0].status).toBe("done");
  });

  it("a taf that shows nothing has no components", async () => {
    const w = world();
    const r = await call(w, "GET", `/v1/tafs/${RAE}/conversation`, { token: "ana" });
    expect(r.body.components).toEqual([]);
  });
});

describe("stop", () => {
  it("cancels the session's latest run, once; one already over stays as it ended", async () => {
    const w = world();
    const chat = await w.agents.api.startRun("t_acme", RAE, "Connect my Stripe", ANA);
    w.agents.calls.length = 0;
    const r = await call(w, "POST", `/v1/tafs/${RAE}/stop`, { token: "ana", body: { session: chat.session } });
    expect(r.status).toBe(200);
    expect(r.body).toMatchObject({ stopped: true, run: chat.id, status: "CANCELLED" });
    expect(w.agents.calls).toEqual([`cancelRun ${RAE} ${chat.id}`]);
    const again = await call(w, "POST", `/v1/tafs/${RAE}/stop`, { token: "ana", body: { session: chat.session } });
    expect(again.body).toEqual({ stopped: false });
  });

  it("needs the right to manage Cards, a session, and one of this taf's", async () => {
    const w = world();
    const mine = await w.agents.api.startRun("t_acme", RAE, "hi", ANA);
    const theirs = await w.agents.api.startRun("t_other", MAX, "hi");
    expect((await call(w, "POST", `/v1/tafs/${RAE}/stop`, { token: "viewer", body: { session: mine.session } })).status).toBe(403);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/stop`, { token: "ana", body: {} })).status).toBe(400);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/stop`, { token: "ana", body: { session: theirs.session } })).status).toBe(404);
    expect(w.agents.runs.find((x) => x.id === theirs.id)?.status).toBe("PENDING");
    // Another taf's session on the same account is not this taf's to stop.
    const other = "taf-cccc0000000000000000";
    await w.agents.api.putAgent("t_acme", other, {} as never);
    const sibling = await w.agents.api.startRun("t_acme", other, "hi", ANA);
    w.agents.calls.length = 0;
    expect((await call(w, "POST", `/v1/tafs/${RAE}/stop`, { token: "ana", body: { session: sibling.session } })).status).toBe(404);
    expect(w.agents.calls.filter((c) => c.startsWith("cancelRun"))).toEqual([]);
    expect(w.agents.runs.find((x) => x.id === sibling.id)?.status).toBe("PENDING");
  });
});

describe("steer", () => {
  it("writes to the run at work, which reads it at its next step", async () => {
    const w = world();
    const chat = await w.agents.api.startRun("t_acme", RAE, "Work the queue", ANA);
    w.agents.calls.length = 0;
    const r = await call(w, "POST", `/v1/tafs/${RAE}/steer`, { token: "ana", body: { run: chat.id, text: "  only the €20 one  " } });
    expect(r.status).toBe(200);
    expect(r.body).toEqual({ run: chat.id, status: "PENDING" });
    expect(w.agents.calls).toEqual([`steerRun ${RAE} ${chat.id}`]);
    expect(w.agents.runs.find((x) => x.id === chat.id)?.steering?.map((m) => m.text)).toEqual(["only the €20 one"]);
  });

  it("never writes to another person's run", async () => {
    const w = world();
    const bos = await w.agents.api.startRun("t_acme", RAE, "Work the queue", { requester: { kind: "member", id: "u_bo" } });
    expect((await call(w, "POST", `/v1/tafs/${RAE}/steer`, { token: "ana", body: { run: bos.id, text: "stop that" } })).status).toBe(404);
    expect(w.agents.runs.find((x) => x.id === bos.id)?.steering).toBeUndefined();
    // Nor stops it: Bo's session isn't Ana's to read, and kiff/agents refuses his run to her.
    expect((await call(w, "POST", `/v1/tafs/${RAE}/stop`, { token: "ana", body: { session: bos.session } })).status).toBe(404);
    expect(w.agents.runs.find((x) => x.id === bos.id)?.status).toBe("PENDING");
  });

  it("says a run that ended is over, so the app sends it as a new message", async () => {
    const w = world();
    const chat = await w.agents.api.startRun("t_acme", RAE, "hi", ANA);
    (w.agents.runs.find((x) => x.id === chat.id) as { status: string }).status = "COMPLETED";
    const r = await call(w, "POST", `/v1/tafs/${RAE}/steer`, { token: "ana", body: { run: chat.id, text: "and the other one" } });
    expect(r.status).toBe(409);
    expect(r.body.error).toContain("send it as a new message");
  });

  it("needs the right to manage Cards, a run, a message, and one of this taf's runs", async () => {
    const w = world();
    const mine = await w.agents.api.startRun("t_acme", RAE, "hi", ANA);
    const theirs = await w.agents.api.startRun("t_other", MAX, "hi");
    expect((await call(w, "POST", `/v1/tafs/${RAE}/steer`, { token: "viewer", body: { run: mine.id, text: "x" } })).status).toBe(403);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/steer`, { token: "ana", body: { text: "x" } })).status).toBe(400);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/steer`, { token: "ana", body: { run: mine.id, text: " " } })).status).toBe(400);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/steer`, { token: "ana", body: { run: theirs.id, text: "x" } })).status).toBe(404);
    expect(w.agents.runs.find((x) => x.id === theirs.id)?.steering).toBeUndefined();
  });
});

describe("a held call answered (kiff/tafs#206)", () => {
  it("has the run that paused on it go on, by the held call, without waiting for KIFF's alert", async () => {
    const w = world();
    const run = await w.agents.api.startRun("t_acme", RAE, "Refund pi_1", ANA);
    Object.assign(w.agents.runs.find((x) => x.id === run.id)!, {
      status: "PAUSED",
      requirements: [{ call_id: "c1", tool: "refund", input: {}, operation: "op1", exception: "exc_1" }],
    });
    w.agents.calls.length = 0;
    const r = await call(w, "POST", `/v1/tafs/${RAE}/holds/exc_1/continue`, { token: "approver" });
    expect(r.status).toBe(200);
    expect(r.body).toEqual({ run: run.id, status: "PAUSED" });
    expect(w.agents.calls).toEqual([`continueHeld ${RAE} exc_1`]);
  });

  it("says waiting when the run went on already, and 404 for a call no run of this taf waits on", async () => {
    const w = world();
    const run = await w.agents.api.startRun("t_acme", RAE, "Refund pi_1", ANA);
    Object.assign(w.agents.runs.find((x) => x.id === run.id)!, {
      status: "RUNNING",
      requirements: [{ call_id: "c1", tool: "refund", input: {}, operation: "op1", exception: "exc_1" }],
    });
    expect((await call(w, "POST", `/v1/tafs/${RAE}/holds/exc_1/continue`, { token: "approver" })).body).toEqual({ status: "waiting" });
    expect((await call(w, "POST", `/v1/tafs/${RAE}/holds/exc_none/continue`, { token: "approver" })).status).toBe(404);
    expect((await call(w, "POST", `/v1/tafs/${MAX}/holds/exc_1/continue`, { token: "approver" })).status).toBe(404);
  });

  it("is only for a role that may answer held calls", async () => {
    const w = world();
    const run = await w.agents.api.startRun("t_acme", RAE, "Refund pi_1", ANA);
    Object.assign(w.agents.runs.find((x) => x.id === run.id)!, {
      status: "PAUSED",
      requirements: [{ call_id: "c1", tool: "refund", input: {}, operation: "op1", exception: "exc_1" }],
    });
    w.agents.calls.length = 0;
    for (const token of ["ana", "viewer"]) {
      expect((await call(w, "POST", `/v1/tafs/${RAE}/holds/exc_1/continue`, { token })).status).toBe(403);
    }
    expect(w.agents.calls).toEqual([]);
  });
});

describe("chats (kiff/tafs#73)", () => {
  it("lists the taf's chats, newest first, each by its first words; job runs and other tafs' chats left out", async () => {
    const w = world();
    w.at(new Date(NOW.getTime() - 3 * 3600_000));
    const old = await w.agents.api.startRun("t_acme", RAE, "Connect my Stripe for refunds, please, and make sure nothing over fifty euros goes out without me", ANA);
    w.at(new Date(NOW.getTime() - 3600_000));
    const recent = await w.agents.api.startRun("t_acme", RAE, "Answer customer questions", ANA);
    await jobRun(w, new Date(NOW.getTime() - 1800_000), "Refunded 3 orders.");
    await w.agents.api.startRun("t_other", MAX, "Max's own chat");
    const r = await call(w, "GET", `/v1/tafs/${RAE}/chats`, { token: "ana" });
    expect(r.status).toBe(200);
    expect(r.body.chats.map((c: { session: string }) => c.session)).toEqual([recent.session, old.session]);
    expect(r.body.chats[0].title).toBe("Answer customer questions");
    expect(r.body.chats[1].title).toHaveLength(80);
    expect(r.body.chats[1].title.endsWith("…")).toBe(true);
  });

  it("names a chat by what the owner asked, never by a component's answer (kiff/tafs#82)", async () => {
    const w = world();
    w.at(new Date(NOW.getTime() - 3600_000));
    const run = await w.agents.api.startRun("t_acme", RAE, "[approval connect-stripe] declined", ANA);
    await w.agents.api.startRun("t_acme", RAE, "Connect my Stripe for refunds", { session: run.session, ...ANA });
    const r = await call(w, "GET", `/v1/tafs/${RAE}/chats`, { token: "ana" });
    expect(r.body.chats.map((c: { title: string }) => c.title)).toEqual(["Connect my Stripe for refunds"]);
    expect(chatTitle(["[confirm which] live", "[sign_in vendor] not now", "Refunds, please"])).toBe("Refunds, please");
    expect(chatTitle(["[approval connect-stripe] declined"])).toBe("declined");
    expect(chatTitle(["[my note] for later"])).toBe("[my note] for later");
    expect(chatTitle([])).toBe("");
  });

  it("archives a chat: it leaves the list and the thread, and the next message starts a new one (kiff/tafs#82)", async () => {
    const w = world();
    w.at(new Date(NOW.getTime() - 3600_000));
    const old = await w.agents.api.startRun("t_acme", RAE, "Connect my Stripe", ANA);
    (w.agents.runs.find((x) => x.id === old.id) as { status: string }).status = "COMPLETED";
    w.at(new Date(NOW.getTime() - 60_000));
    const live = await w.agents.api.startRun("t_acme", RAE, "Answer customer questions", ANA);
    (w.agents.runs.find((x) => x.id === live.id) as { status: string }).status = "COMPLETED";

    expect((await call(w, "POST", `/v1/tafs/${RAE}/chats/${live.session}/archive`, { token: "viewer" })).status).toBe(403);
    const r = await call(w, "POST", `/v1/tafs/${RAE}/chats/${live.session}/archive`, { token: "ana" });
    expect(r.status).toBe(200);
    expect(r.body).toEqual({ session: live.session, archived: true });
    expect(w.agents.calls).toContain(`archive ${live.session}`);

    const listed = await call(w, "GET", `/v1/tafs/${RAE}/chats`, { token: "ana" });
    expect(listed.body.chats.map((c: { session: string }) => c.session)).toEqual([old.session]);
    const thread = await call(w, "GET", `/v1/tafs/${RAE}/conversation`, { token: "ana" });
    expect(thread.body.messages.map((m: { text: string }) => m.text)).not.toContain("Answer customer questions");
    // Opened by its id, it's still there: archived, not deleted.
    expect((await call(w, "GET", `/v1/tafs/${RAE}/conversation/${live.session}`, { token: "ana" })).body.messages).toHaveLength(1);

    const next = await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "Something else" } });
    expect(next.body.new_session).toBe(true);
    expect(next.body.session).not.toBe(live.session);
  });

  it("lists the archived chats and puts one back (kiff/tafs#97)", async () => {
    const w = world();
    w.at(new Date(NOW.getTime() - 3600_000));
    const old = await w.agents.api.startRun("t_acme", RAE, "Connect my Stripe", ANA);
    (w.agents.runs.find((x) => x.id === old.id) as { status: string }).status = "COMPLETED";
    expect((await call(w, "POST", `/v1/tafs/${RAE}/chats/${old.session}/archive`, { token: "ana" })).status).toBe(200);

    const archived = await call(w, "GET", `/v1/tafs/${RAE}/chats/archived`, { token: "viewer" });
    expect(archived.status).toBe(200);
    expect(archived.body.chats.map((c: { session: string; title: string }) => [c.session, c.title])).toEqual([[old.session, "Connect my Stripe"]]);
    expect((await call(w, "GET", `/v1/tafs/${RAE}/chats`, { token: "ana" })).body.chats).toEqual([]);

    expect((await call(w, "POST", `/v1/tafs/${RAE}/chats/${old.session}/unarchive`, { token: "viewer" })).status).toBe(403);
    const back = await call(w, "POST", `/v1/tafs/${RAE}/chats/${old.session}/unarchive`, { token: "ana" });
    expect(back.body).toEqual({ session: old.session, archived: false });
    expect((await call(w, "GET", `/v1/tafs/${RAE}/chats`, { token: "ana" })).body.chats.map((c: { session: string }) => c.session)).toEqual([old.session]);
    expect((await call(w, "GET", `/v1/tafs/${RAE}/chats/archived`, { token: "ana" })).body.chats).toEqual([]);
  });

  it("archives only the taf's own chats", async () => {
    const w = world();
    const theirs = await w.agents.api.startRun("t_other", MAX, "hi");
    const job = await jobRun(w, NOW, "Done.");
    expect((await call(w, "POST", `/v1/tafs/${RAE}/chats/${theirs.session}/archive`, { token: "ana" })).status).toBe(404);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/chats/${job.session}/archive`, { token: "ana" })).status).toBe(404);
    expect((await call(w, "POST", `/v1/tafs/${MAX}/chats/${theirs.session}/archive`, { token: "ana" })).status).toBe(404);
    expect(w.agents.calls.filter((c) => c.startsWith("archive"))).toEqual([]);
  });

  it("moves the chats archived before kiff/agents kept archives there, once", async () => {
    const w = world();
    w.at(new Date(NOW.getTime() - 3600_000));
    const old = await w.agents.api.startRun("t_acme", RAE, "Connect my Stripe", ANA);
    const kept = await w.agents.api.startRun("t_acme", RAE, "Answer customer questions", ANA);
    await w.archived.add("t_acme", RAE, old.session);
    await w.archived.add("t_acme", RAE, "sess_gone"); // kiff/agents deleted it already
    const listed = await call(w, "GET", `/v1/tafs/${RAE}/chats`, { token: "ana" });
    expect(listed.body.chats.map((c: { session: string }) => c.session)).toEqual([kept.session]);
    expect(w.agents.calls.filter((c) => c.startsWith("archive"))).toEqual([`archive ${old.session}`, "archive sess_gone"]);
    expect(w.archived.held.size).toBe(0);
    await call(w, "GET", `/v1/tafs/${RAE}/conversation`, { token: "ana" });
    expect(w.agents.calls.filter((c) => c.startsWith("archive"))).toHaveLength(2);
    // kiff/agents down: the archive is kept, to move next time.
    await w.archived.add("t_acme", RAE, kept.session);
    w.agents.fail.set("archive", new Error("down"));
    expect((await call(w, "GET", `/v1/tafs/${RAE}/chats`, { token: "ana" })).status).toBe(502);
    expect(w.archived.held.size).toBe(1);
  });

  it("lists what the taf's calls through KIFF acted on, from the runs' call_refs, only its own", async () => {
    const w = world();
    const job = await jobRun(w, new Date(NOW.getTime() - 3600_000), "Refunded pi_9.");
    const list = w.agents.events.get(job.session)!;
    list.push({ session: job.session, run: job.id, seq: list.length + 1, time: minutesAgo(59), kind: "call_ref", body: { invocation: "tc-88", value: "pi_9" } });
    // Anything else in a run is not a call's reference.
    list.push({ session: job.session, run: job.id, seq: list.length + 1, time: minutesAgo(59), kind: "agent_message", body: { invocation: "tc-x", value: "no" } });
    const theirs = await w.agents.api.startRun("t_other", MAX, "hi");
    w.agents.events.set(theirs.session, [{ session: theirs.session, run: theirs.id, seq: 1, time: minutesAgo(1), kind: "call_ref", body: { invocation: "tc-99", value: "pi_other" } }]);
    const r = await call(w, "GET", `/v1/tafs/${RAE}/calls`, { token: "ana" });
    expect(r.status).toBe(200);
    expect(r.body.calls).toEqual([{ invocation: "tc-88", value: "pi_9", at: minutesAgo(59) }]);
    expect((await call(w, "GET", `/v1/tafs/${RAE}/calls`, { token: "viewer" })).status).toBe(200);
  });

  it("reads one chat on its own; another taf's chat or a job run is not found", async () => {
    const w = world();
    w.at(new Date(NOW.getTime() - 3600_000));
    const old = await w.agents.api.startRun("t_acme", RAE, "First topic", ANA);
    w.agents.say(old, "On it.");
    w.at(new Date(NOW.getTime() - 60_000));
    const recent = await w.agents.api.startRun("t_acme", RAE, "Second topic", ANA);
    const r = await call(w, "GET", `/v1/tafs/${RAE}/conversation/${old.session}`, { token: "ana" });
    expect(r.status).toBe(200);
    expect(r.body.messages.map((m: { text: string }) => m.text)).toEqual(["First topic", "On it."]);
    expect(r.body.messages.every((m: { session: string }) => m.session === old.session)).toBe(true);
    expect((await call(w, "GET", `/v1/tafs/${RAE}/conversation/${recent.session}`, { token: "ana" })).body.messages).toHaveLength(1);
    const theirs = await w.agents.api.startRun("t_other", MAX, "hi");
    expect((await call(w, "GET", `/v1/tafs/${RAE}/conversation/${theirs.session}`, { token: "ana" })).status).toBe(404);
    const job = await jobRun(w, NOW, "Done.");
    expect((await call(w, "GET", `/v1/tafs/${RAE}/conversation/${job.session}`, { token: "ana" })).status).toBe(404);
  });

  it("starts a new chat when asked, even with a live one, and goes on in a chat the owner reopened", async () => {
    const w = world();
    w.at(new Date(NOW.getTime() - 3600_000));
    const old = await w.agents.api.startRun("t_acme", RAE, "First topic", ANA);
    (w.agents.runs.find((x) => x.id === old.id) as { status: string }).status = "COMPLETED";
    w.at(new Date(NOW.getTime() - 60_000));
    const live = await w.agents.api.startRun("t_acme", RAE, "Second topic", ANA);
    (w.agents.runs.find((x) => x.id === live.id) as { status: string }).status = "COMPLETED";
    w.agents.calls.length = 0;
    const fresh = await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "A new topic", new_chat: true } });
    expect(fresh.body.new_session).toBe(true);
    expect(fresh.body.session).not.toBe(live.session);
    const back = await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "Back to the first", session: old.session } });
    expect(back.body).toEqual({ session: old.session, new_session: false });
    const theirs = await w.agents.api.startRun("t_other", MAX, "hi");
    expect((await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "x", session: theirs.session } })).status).toBe(404);
  });
});

describe("another person's chats", () => {
  it("are never read: there is no route to them", async () => {
    const w = world();
    const bos = await w.agents.api.startRun("t_acme", RAE, "Refund order 7 for Ivo", { requester: { kind: "member", id: "u_bo" } });
    for (const path of ["people/u_bo/chats", `people/u_bo/conversation/${bos.session}`]) {
      const r = await call(w, "GET", `/v1/tafs/${RAE}/${path}`, { token: "ana", headers: { "Kiff-Read-Grant": "krd_any" } });
      expect([path, r.status]).toEqual([path, 404]);
    }
    expect((await call(w, "GET", `/v1/tafs/${RAE}/conversation/${bos.session}`, { token: "ana" })).status).toBe(404);
  });
});
