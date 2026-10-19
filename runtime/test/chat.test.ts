import { describe, expect, it } from "vitest";
import { AgentsError } from "../src/cloud/agents.js";
import { CHAT, chatTitle, handle, type HTTPEvent, MEMORY, type Taf } from "../src/cloud/handler.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

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
  return { agents, tafs, slots: memorySlots(), archived: memoryArchived(), at: (d: Date) => { clock = d; } };
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
    names: memoryNames(), slots: w.slots, claims: memoryClaims(), removals: memoryRemovals(), archived: w.archived,
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

  it("says whether the taf is at work in its latest chat, for the app's thinking and Stop", async () => {
    const w = world();
    w.at(new Date(NOW.getTime() - 60_000));
    const chat = await w.agents.api.startRun("t_acme", RAE, "Why was order 7 held?");
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

  it("says what the owner wrote to the latest run that it hasn't read, while it goes and once it's over", async () => {
    const w = world();
    w.at(new Date(NOW.getTime() - 60_000));
    const chat = await w.agents.api.startRun("t_acme", RAE, "Work the queue");
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

describe("components (kiff/agents#91)", () => {
  it("returns what the agent showed next to the messages, in order, the messages unchanged", async () => {
    const w = world();
    w.at(new Date(NOW.getTime() - 3600_000));
    const chat = await w.agents.api.startRun("t_acme", RAE, "Connect my Stripe");
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
    const chat = await w.agents.api.startRun("t_acme", RAE, "Connect my Stripe");
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
    const mine = await w.agents.api.startRun("t_acme", RAE, "hi");
    const theirs = await w.agents.api.startRun("t_other", MAX, "hi");
    expect((await call(w, "POST", `/v1/tafs/${RAE}/stop`, { token: "viewer", body: { session: mine.session } })).status).toBe(403);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/stop`, { token: "ana", body: {} })).status).toBe(400);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/stop`, { token: "ana", body: { session: theirs.session } })).status).toBe(404);
    expect(w.agents.runs.find((x) => x.id === theirs.id)?.status).toBe("PENDING");
    // Another taf's session on the same account is not this taf's to stop.
    const other = "taf-cccc0000000000000000";
    await w.agents.api.putAgent("t_acme", other, {} as never);
    const sibling = await w.agents.api.startRun("t_acme", other, "hi");
    w.agents.calls.length = 0;
    expect((await call(w, "POST", `/v1/tafs/${RAE}/stop`, { token: "ana", body: { session: sibling.session } })).status).toBe(404);
    expect(w.agents.calls.filter((c) => c.startsWith("cancelRun"))).toEqual([]);
    expect(w.agents.runs.find((x) => x.id === sibling.id)?.status).toBe("PENDING");
  });
});

describe("steer", () => {
  it("writes to the run at work, which reads it at its next step; the flood guard counts it", async () => {
    const w = world();
    const chat = await w.agents.api.startRun("t_acme", RAE, "Work the queue");
    w.agents.calls.length = 0;
    const r = await call(w, "POST", `/v1/tafs/${RAE}/steer`, { token: "ana", body: { run: chat.id, text: "  only the €20 one  " } });
    expect(r.status).toBe(200);
    expect(r.body).toEqual({ run: chat.id, status: "PENDING" });
    expect(w.agents.calls).toEqual([`steerRun ${RAE} ${chat.id}`]);
    expect(w.agents.runs.find((x) => x.id === chat.id)?.steering?.map((m) => m.text)).toEqual(["only the €20 one"]);
    for (let n = 1; n < CHAT.floodMessages; n++) {
      await call(w, "POST", `/v1/tafs/${RAE}/steer`, { token: "ana", body: { run: chat.id, text: `more ${n}` } });
    }
    expect((await call(w, "POST", `/v1/tafs/${RAE}/steer`, { token: "ana", body: { run: chat.id, text: "one too many" } })).status).toBe(429);
  });

  it("says a run that ended is over, so the app sends it as a new message", async () => {
    const w = world();
    const chat = await w.agents.api.startRun("t_acme", RAE, "hi");
    (w.agents.runs.find((x) => x.id === chat.id) as { status: string }).status = "COMPLETED";
    const r = await call(w, "POST", `/v1/tafs/${RAE}/steer`, { token: "ana", body: { run: chat.id, text: "and the other one" } });
    expect(r.status).toBe(409);
    expect(r.body.error).toContain("send it as a new message");
  });

  it("needs the right to manage Cards, a run, a message, and one of this taf's runs", async () => {
    const w = world();
    const mine = await w.agents.api.startRun("t_acme", RAE, "hi");
    const theirs = await w.agents.api.startRun("t_other", MAX, "hi");
    expect((await call(w, "POST", `/v1/tafs/${RAE}/steer`, { token: "viewer", body: { run: mine.id, text: "x" } })).status).toBe(403);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/steer`, { token: "ana", body: { text: "x" } })).status).toBe(400);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/steer`, { token: "ana", body: { run: mine.id, text: " " } })).status).toBe(400);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/steer`, { token: "ana", body: { run: theirs.id, text: "x" } })).status).toBe(404);
    expect(w.agents.runs.find((x) => x.id === theirs.id)?.steering).toBeUndefined();
  });
});

describe("chats (kiff/tafs#73)", () => {
  it("lists the taf's chats, newest first, each by its first words; job runs and other tafs' chats left out", async () => {
    const w = world();
    w.at(new Date(NOW.getTime() - 3 * 3600_000));
    const old = await w.agents.api.startRun("t_acme", RAE, "Connect my Stripe for refunds, please, and make sure nothing over fifty euros goes out without me");
    w.at(new Date(NOW.getTime() - 3600_000));
    const recent = await w.agents.api.startRun("t_acme", RAE, "Answer customer questions");
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
    const run = await w.agents.api.startRun("t_acme", RAE, "[approval connect-stripe] declined");
    await w.agents.api.startRun("t_acme", RAE, "Connect my Stripe for refunds", run.session);
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
    const old = await w.agents.api.startRun("t_acme", RAE, "Connect my Stripe");
    (w.agents.runs.find((x) => x.id === old.id) as { status: string }).status = "COMPLETED";
    w.at(new Date(NOW.getTime() - 60_000));
    const live = await w.agents.api.startRun("t_acme", RAE, "Answer customer questions");
    (w.agents.runs.find((x) => x.id === live.id) as { status: string }).status = "COMPLETED";

    expect((await call(w, "POST", `/v1/tafs/${RAE}/chats/${live.session}/archive`, { token: "viewer" })).status).toBe(403);
    const r = await call(w, "POST", `/v1/tafs/${RAE}/chats/${live.session}/archive`, { token: "ana" });
    expect(r.status).toBe(200);
    expect(r.body).toEqual({ session: live.session, archived: true });
    expect(w.archived.held.get(`t_acme#${RAE}`)).toEqual(new Set([live.session]));

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
    const old = await w.agents.api.startRun("t_acme", RAE, "Connect my Stripe");
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
    expect(w.archived.held.size).toBe(0);
  });

  it("reads one chat on its own; another taf's chat or a job run is not found", async () => {
    const w = world();
    w.at(new Date(NOW.getTime() - 3600_000));
    const old = await w.agents.api.startRun("t_acme", RAE, "First topic");
    w.agents.say(old, "On it.");
    w.at(new Date(NOW.getTime() - 60_000));
    const recent = await w.agents.api.startRun("t_acme", RAE, "Second topic");
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
    const old = await w.agents.api.startRun("t_acme", RAE, "First topic");
    (w.agents.runs.find((x) => x.id === old.id) as { status: string }).status = "COMPLETED";
    w.at(new Date(NOW.getTime() - 60_000));
    const live = await w.agents.api.startRun("t_acme", RAE, "Second topic");
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
