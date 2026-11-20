import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import { type Deps, handle, type HTTPEvent, type Taf } from "../src/cloud/handler.js";
import { setupDefinition } from "../src/cloud/setupdef.js";
import { type Ask, FULL_MAX_BYTES, fullAnswer, tafLine, TAF_LINE_MAX, watchKeys } from "../src/cloud/watch-ask.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryCoach, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

/**
 * Chatting with a taf from the watch (TAFS_WATCH_TAF_CHAT, docs/watch-ask.md): a watch request with `taf_id` goes into that
 * taf's own chat as the person, through the phone's own message path, signed by the phone's device key as every watch
 * request is. Coach isn't involved, and the watch never approves.
 */

const T = "t_acme";
const ANA = "u_ana";
const BO = "u_bo";
const NOW = new Date("2026-11-16T19:00:00Z");
const COACH = "taf-cccc0000000000000000";
const CAL = "taf-ca100000000000000000";
const INBOX = "taf-1b0d0000000000000000";
const GONE = "taf-90e00000000000000000";
const SIGNED = { "kiff-device-signature": "t=2026-11-16T19:00:00Z,v1=sig" };

function world(opts: { chat?: boolean | string[]; signed?: boolean } = {}) {
  let clock = NOW;
  const agents = fakeAgents(() => clock);
  const tafs = memoryTafs();
  for (const taf of [
    { id: COACH, name: "Coach", job: "setup", kiffAgent: "agent_coach", nameClaim: "c0", created: "2026-11-01T09:00:00Z" },
    { id: CAL, name: "Calendar", job: "custom", kiffAgent: "agent_cal", nameClaim: "c1", created: "2026-11-01T09:00:00Z" },
    { id: INBOX, name: "Inbox", job: "custom", kiffAgent: "agent_inbox", nameClaim: "c2", created: "2026-11-01T09:00:00Z" },
    { id: GONE, name: "Old", job: "custom", kiffAgent: "agent_old", nameClaim: "c3", created: "2026-11-01T09:00:00Z", removing: true },
  ] as Taf[]) {
    tafs.held.set(`${T}#${taf.id}`, taf);
    void agents.api.putAgent(T, taf.id, taf.id === COACH ? setupDefinition(COACH) : ({} as never));
  }
  const coach = memoryCoach(() => clock);
  const pushed: string[] = [];
  const devices = [
    { token: "fcm-ana", platform: "android" as const, environment: "production" as const, subject: ANA, answers: true, at: "" },
    { token: "fcm-bo", platform: "android" as const, environment: "production" as const, subject: BO, answers: true, at: "" },
  ];
  // What the phone signed, by the body's digest: KIFF refuses a body that isn't the one signed (method, path, sha256).
  const signedBodies = new Set<string>();
  // KIFF's run grants taken away since: "<subject>:<agent>".
  const noUse = new Set<string>();
  const deps: Deps = {
    agents: agents.api, keys: memoryKeys(), accounts: memoryAccounts(), tafs, names: memoryNames(), slots: memorySlots(), claims: memoryClaims(),
    removals: memoryRemovals(), archived: memoryArchived(), aiConsent: false, watchAsk: true, watchTafChat: opts.chat ?? true,
    push: { get: async () => ({ devices, secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async (d) => { pushed.push(d.token); return "sent" as const; } },
    forward: async () => undefined, coach, kiffBase: "https://api.test", publicURL: "https://tafs.test", now: () => clock,
    fetch: (async (url: string, init?: RequestInit) => {
      const who = String(((init?.headers ?? {}) as Record<string, string>).authorization ?? "").replace("Bearer ", "");
      const subject = who === "bo" ? BO : ANA;
      if (url.endsWith("/v1/me")) return new Response(JSON.stringify({ tenant_id: T, subject, api_key: false, capabilities: ["read"] }));
      if (url.endsWith("/v1/me/device-signature")) {
        const sent = JSON.parse(String(init?.body)) as { body_sha256: string };
        const ok = opts.signed !== false && signedBodies.has(sent.body_sha256);
        return ok ? new Response(JSON.stringify({ signed: true, key: "device" })) : new Response(JSON.stringify({ error: { code: "signature_invalid" } }), { status: 403 });
      }
      if (url.endsWith("/v1/me/run-grants")) {
        // Bo can see Inbox but not use it: KIFF gives him no run grant for it.
        const { agent } = JSON.parse(String(init?.body)) as { agent: string };
        if ((subject === BO && agent === "agent_inbox") || noUse.has(`${subject}:${agent}`)) return new Response(JSON.stringify({ error: "no_access" }), { status: 403 });
        return new Response(JSON.stringify({ grant: `krg_${subject}` }));
      }
      return new Response("{}", { status: 404 });
    }) as unknown as typeof fetch,
  };
  const event = (method: string, path: string, body: unknown, who: string, headers: Record<string, string>): HTTPEvent => ({
    rawPath: path.split("?")[0]!, ...(path.includes("?") ? { rawQueryString: path.split("?")[1] } : {}), requestContext: { http: { method } },
    headers: { authorization: `Bearer ${who}`, "accept-language": "en", ...headers }, body: body === undefined ? undefined : JSON.stringify(body),
  });
  const call = async (method: string, path: string, body?: unknown, who = "ana", headers: Record<string, string> = SIGNED) => {
    const r = await handle(event(method, path, body, who, headers), deps);
    return { status: r.statusCode, body: JSON.parse(r.body || "{}") as Record<string, any> };
  };
  /** The phone signs the body it sends, and sends it. */
  const send = async (body: Record<string, unknown>, who = "ana") => {
    signedBodies.add(createHash("sha256").update(JSON.stringify(body), "utf8").digest("hex"));
    return call("POST", "/v1/watch/asks", body, who);
  };
  const ended = (ref: string, run: string, agent: string, status: string) => handle({
    rawPath: "/v1/runs/ended", requestContext: { http: { method: "POST" } }, headers: {}, body: JSON.stringify({ ref, run, agent, status }),
  }, deps);
  const askOf = async (id: string) => (await coach.get(watchKeys.ask(T, id))) as Ask;
  /** The run a request started, and how kiff/agents ends it. */
  const end = async (id: string, status: string, output = "") => {
    const ask = await askOf(id);
    const run = agents.runs.find((r) => r.id === ask.taf_run)!;
    Object.assign(run, { status, output, stop: "answered" });
    await ended(agents.notified.get(run.id)!.ref, run.id, CAL, status);
  };
  const starts = () => agents.calls.filter((c) => c.startsWith("startRun"));
  return { agents, deps, coach, call, send, signedBodies, noUse, ended, askOf, end, starts, pushed, tick: (ms: number) => { clock = new Date(clock.getTime() + ms); } };
}

const MSG = { client_id: "c_watch_0001", text: "Anything new on the Brio account?", taf_id: CAL };

describe("chatting with a taf from the watch", () => {
  it("is off until TAFS_WATCH_TAF_CHAT is on for the account, and asking Coach doesn't change", async () => {
    for (const chat of [false, ["t_other"]]) {
      const w = world({ chat });
      expect(await w.send(MSG)).toMatchObject({ status: 404, body: { code: "watch_taf_chat_off" } });
      expect(w.starts()).toEqual([]);
      expect((await w.send({ client_id: "c_watch_0002", text: "Move my call" })).status).toBe(200);
    }
    expect((await world({ chat: [T] }).send(MSG)).status).toBe(200);
  });

  it("takes {client_id, text, reply_to?, taf_id?} with a taf's id, and nothing else", async () => {
    const w = world();
    for (const body of [{ ...MSG, taf_id: "Calendar" }, { ...MSG, taf_id: "" }, { ...MSG, entry: "watch" }, { ...MSG, text: "" }]) {
      expect((await w.send(body)).status, JSON.stringify(body)).toBe(400);
    }
    expect(w.starts()).toEqual([]);
  });

  it("needs the phone's device signature over the body with its taf_id: a body changed after signing is refused", async () => {
    const w = world();
    // No signature at all, or a phone KIFF refuses.
    expect((await w.call("POST", "/v1/watch/asks", MSG, "ana", {})).status).toBe(403);
    expect((await world({ signed: false }).send(MSG)).status).toBe(403);
    // Signed for Calendar, sent for Inbox: the body's digest isn't the one signed.
    w.signedBodies.add(createHash("sha256").update(JSON.stringify(MSG), "utf8").digest("hex"));
    expect((await w.call("POST", "/v1/watch/asks", { ...MSG, taf_id: INBOX })).status).toBe(403);
    expect(w.starts()).toEqual([]);
  });

  it("goes into the taf's own live chat as the person, under their run grant, never through Coach", async () => {
    const w = world();
    const r = await w.send(MSG);
    expect(r.status).toBe(200);
    expect(r.body.ask).toMatchObject({ client_id: MSG.client_id, state: "working", taf: { id: CAL, name: "Calendar" }, words: "Calendar is answering" });
    expect(r.body.ask.kind).toBe("taf");
    expect(JSON.stringify(r.body)).not.toMatch(/session|taf_run|person/);
    expect(w.starts()).toEqual([`startRun ${CAL} live`]);
    const run = w.agents.runs.at(-1)!;
    expect(run.requester).toEqual({ kind: "member", id: ANA });
    // Its end comes back to the watch request: kiff/agents was given where to say so.
    expect(w.agents.notified.get(run.id)?.url).toBe("https://tafs.test/v1/runs/ended");
    // The watches tell a taf chat from a Coach request by it, in the list and on the watch days too: Coach's has none.
    const coach = (await w.send({ client_id: "c_watch_0009", text: "Move my call" })).body.ask;
    expect(coach.kind).toBeUndefined();
    const listed = (await w.call("GET", "/v1/watch/asks")).body.asks as Array<{ id: string; kind?: string }>;
    expect(listed.map((a) => [a.id, a.kind])).toEqual([[coach.id, undefined], [r.body.ask.id, "taf"]]);
  });

  it("is the same 404 for a taf in no account of the person's, one being removed and one they can't use; Coach is asked without taf_id", async () => {
    const w = world();
    for (const taf_id of ["taf-00000000000000000000", GONE]) expect(await w.send({ ...MSG, taf_id })).toMatchObject({ status: 404, body: { code: "no_taf" } });
    // Bo sees Inbox, but KIFF gives him no run grant for it: the phone's message is refused, so the watch's is too.
    expect(await w.send({ ...MSG, client_id: "c_watch_bo01", taf_id: INBOX }, "bo")).toMatchObject({ status: 404, body: { code: "no_taf" } });
    expect(await w.send({ ...MSG, taf_id: COACH })).toMatchObject({ status: 400, body: { code: "coach_taf" } });
    expect(w.starts()).toEqual([]);
    // A refused request leaves its client id free.
    expect((await w.send({ ...MSG, client_id: "c_watch_bo01", taf_id: CAL }, "bo")).status).toBe(200);
  });

  it("is once per person and client id: a resend gets the same request and starts nothing", async () => {
    const w = world();
    const a = await w.send(MSG);
    const b = await w.send(MSG);
    expect(b.body.ask).toEqual(a.body.ask);
    expect(w.starts()).toHaveLength(1);
    // Another person's same client id is their own message.
    await w.send(MSG, "bo");
    expect(w.starts()).toHaveLength(2);
    // Past its claim (kept a week), the same message sent again is still the phone's same message: kiff/agents starts nothing new.
    await w.coach.remove(`${watchKeys.client(T, ANA, MSG.client_id)}#claim`);
    await w.coach.remove(watchKeys.client(T, ANA, MSG.client_id));
    const again = await w.send(MSG);
    expect((await w.askOf(again.body.ask.id)).taf_run).toBe((await w.askOf(a.body.ask.id)).taf_run);
    expect(new Set(w.agents.runs.filter((r) => r.agent === CAL).map((r) => r.id)).size).toBe(2);
  });

  it("ends with the taf's own answer, checked: one line, no markdown or links, cut with the rest on the phone", async () => {
    const w = world();
    const id = (await w.send(MSG)).body.ask.id as string;
    const long = `**Brio** paid [the invoice](https://evil.test/x) today.\n\n${"They also asked about next month's order and the delivery window. ".repeat(10)}`;
    await w.end(id, "COMPLETED", long);
    const shown = (await w.call("GET", `/v1/watch/asks/${id}`)).body.ask;
    expect(shown).toMatchObject({ state: "done", more: true, words: "Done" });
    expect(shown.line).toMatch(/^Brio paid the invoice today\. They also asked/);
    expect(shown.line).not.toMatch(/\*|https?:|evil|\n/);
    expect([...shown.line].length).toBeLessThanOrEqual(TAF_LINE_MAX);
    // The person's Android phone hears the request's id; no one else's.
    expect(w.pushed).toEqual(["fcm-ana"]);
    // A short answer is whole.
    const short = (await w.send({ ...MSG, client_id: "c_watch_0002" })).body.ask.id as string;
    await w.end(short, "COMPLETED", "Nothing new since Monday.");
    expect((await w.call("GET", `/v1/watch/asks/${short}`)).body.ask).toEqual(expect.objectContaining({ state: "done", line: "Nothing new since Monday." }));
    expect((await w.call("GET", `/v1/watch/asks/${short}`)).body.ask.more).toBeUndefined();
  });

  it("says a failed run failed, and a paused one waits on the person on the phone", async () => {
    const w = world();
    const failed = (await w.send(MSG)).body.ask.id as string;
    await w.end(failed, "ERROR");
    expect((await w.askOf(failed)).state).toBe("failed");
    const paused = (await w.send({ ...MSG, client_id: "c_watch_0002" })).body.ask.id as string;
    await w.end(paused, "PAUSED");
    expect((await w.call("GET", `/v1/watch/asks/${paused}`)).body.ask).toMatchObject({ state: "done", held: true });
  });

  it("takes a run-ended note only for its own run", async () => {
    const w = world();
    const id = (await w.send(MSG)).body.ask.id as string;
    const ask = await w.askOf(id);
    const ref = w.agents.notified.get(ask.taf_run!)!.ref;
    // Another request's run, ended with its own answer: this request's ref with that run's id is dropped.
    const other = (await w.send({ ...MSG, client_id: "c_watch_0002" })).body.ask.id as string;
    await w.end(other, "COMPLETED", "Someone else's answer.");
    await w.ended(ref, (await w.askOf(other)).taf_run!, CAL, "COMPLETED");
    expect(await w.askOf(id)).toMatchObject({ state: "working" });
    expect((await w.askOf(id)).line).toBeUndefined();
  });

  it("goes on in the same chat for a reply, once its request has ended, and only for the same taf", async () => {
    const w = world();
    const first = (await w.send(MSG)).body.ask.id as string;
    const reply = { client_id: "c_watch_0002", text: "And the order?", taf_id: CAL, reply_to: first };
    expect(await w.send(reply)).toMatchObject({ status: 409, body: { code: "reply_too_soon" } });
    await w.end(first, "COMPLETED", "Paid today.");
    // Into another taf's chat, or into Coach's watch conversation: no such request.
    expect(await w.send({ ...reply, taf_id: INBOX })).toMatchObject({ status: 404, body: { code: "no_ask" } });
    const coachAsk = (await w.send({ client_id: "c_watch_0003", text: "Move my call" })).body.ask.id as string;
    expect(await w.send({ ...reply, client_id: "c_watch_0004", reply_to: coachAsk })).toMatchObject({ status: 404, body: { code: "no_ask" } });
    const r = await w.send(reply);
    expect(r.status).toBe(200);
    expect(w.starts().at(-1)).toBe(`startRun ${CAL} ${(await w.askOf(first)).session}`);
    expect((await w.askOf(r.body.ask.id)).session).toBe((await w.askOf(first)).session);
  });

  it("is never a watch day's turn or count: it's in the taf's own chat on the phone", async () => {
    const w = world();
    await w.send(MSG);
    await w.send({ client_id: "c_watch_0002", text: "Move my call" });
    const days = (await w.call("GET", "/v1/watch/days")).body.days as Array<{ date: string; count: number }>;
    expect(days).toEqual([{ date: "2026-11-16", count: 1 }]);
    const turns = (await w.call("GET", "/v1/watch/days/2026-11-16")).body.turns as Array<{ said: string; kind?: string }>;
    expect(turns.map((t) => [t.said, t.kind])).toEqual([["Move my call", undefined]]);
  });

  it("logs the request with its taf and the device's signature, never its words", async () => {
    const w = world();
    const logs: string[] = [];
    const log = console.log;
    console.log = (line: unknown) => { logs.push(String(line)); };
    try {
      await w.send(MSG);
    } finally {
      console.log = log;
    }
    const line = logs.find((l) => l.includes("watch_ask"))!;
    expect(JSON.parse(line)).toMatchObject({ event: "watch_ask", taf: CAL, signed: "device" });
    expect(logs.join("\n")).not.toContain("Brio");
  });
});

describe("Read more on a taf's answer (the owner's pick: never trimmed, shown partly, then expanded)", () => {
  const LONG = `**Brio** paid [the invoice](https://evil.test/x) today.\n\n${"They also asked about next month's order and the delivery window. ".repeat(10)}\n\nThat's all.`;

  it("gives the whole answer on ?full=1, read from the run each time: checked, its paragraphs kept, none cut", async () => {
    const w = world();
    const id = (await w.send(MSG)).body.ask.id as string;
    await w.end(id, "COMPLETED", LONG);
    // The first read stays small: the line, with more.
    const first = (await w.call("GET", `/v1/watch/asks/${id}`)).body;
    expect(first.text).toBeUndefined();
    expect(first.ask.more).toBe(true);
    const full = (await w.call("GET", `/v1/watch/asks/${id}?full=1`)).body;
    expect(full.ask).toEqual(first.ask);
    expect(full.text).toBe(`Brio paid the invoice today.\n\n${"They also asked about next month's order and the delivery window. ".repeat(10).trim()}\n\nThat's all.`);
    expect(full.text).not.toMatch(/\*|https?:|evil/);
    // Nothing of it is kept: the request holds the line alone.
    expect(JSON.stringify(await w.askOf(id))).not.toContain("That's all");
  });

  it("is only for the person's own finished taf chat that they may still use", async () => {
    const w = world();
    const id = (await w.send(MSG)).body.ask.id as string;
    // Still working: no text yet.
    expect((await w.call("GET", `/v1/watch/asks/${id}?full=1`)).body.text).toBeUndefined();
    await w.end(id, "COMPLETED", LONG);
    // Another member's read of it is no request of theirs.
    expect(await w.call("GET", `/v1/watch/asks/${id}?full=1`, undefined, "bo")).toMatchObject({ status: 404, body: { code: "no_ask" } });
    // KIFF no longer lets Ana use the taf: the line stays, the whole answer doesn't come.
    w.noUse.add(`${ANA}:agent_cal`);
    expect((await w.call("GET", `/v1/watch/asks/${id}?full=1`)).body.text).toBeUndefined();
    // A paused one waits on the person: there's no answer to read.
    w.noUse.clear();
    const held = (await w.send({ ...MSG, client_id: "c_watch_0002" })).body.ask.id as string;
    await w.end(held, "PAUSED", "Half an answer");
    expect((await w.call("GET", `/v1/watch/asks/${held}?full=1`)).body.text).toBeUndefined();
    // Coach's own watch requests have no whole answer to read.
    const coach = (await w.send({ client_id: "c_watch_0003", text: "Move my call" })).body.ask.id as string;
    expect((await w.call("GET", `/v1/watch/asks/${coach}?full=1`)).body.text).toBeUndefined();
  });

  it("is cut at 16 KB of UTF-8, at a word", () => {
    const huge = fullAnswer("é".repeat(FULL_MAX_BYTES) + " end");
    expect(Buffer.byteLength(huge!, "utf8")).toBeLessThanOrEqual(FULL_MAX_BYTES);
    expect(huge!.endsWith("…")).toBe(true);
    expect(fullAnswer("One.\n\n\n\nTwo [x](https://a.test).\n")).toBe("One.\n\nTwo x.");
    expect(fullAnswer("  ")).toBeUndefined();
  });
});

describe("a request that wasn't kept", () => {
  it("leaves its client id free: the Send again comes through with the same run, and its end still reaches the watch", async () => {
    const w = world();
    const put = w.coach.put.bind(w.coach);
    let failOnce = true;
    w.coach.put = (async (key: string, ...rest: unknown[]) => {
      if (failOnce && key.includes("#watch-ask#")) { failOnce = false; throw new Error("table unavailable"); }
      return (put as (...a: unknown[]) => Promise<void>)(key, ...rest);
    }) as typeof w.coach.put;
    expect((await w.send(MSG)).status).toBe(500);
    const again = await w.send(MSG);
    expect(again.status).toBe(200);
    expect(new Set(w.agents.runs.filter((r) => r.agent === CAL).map((r) => r.id)).size).toBe(1);
    await w.end(again.body.ask.id as string, "COMPLETED", "Paid today.");
    expect(await w.askOf(again.body.ask.id as string)).toMatchObject({ state: "done", line: "Paid today." });
  });
});

describe("a taf's line on the watch", () => {
  it("is checked as Coach's line is, at most 400 characters, and says when there was more", () => {
    expect(tafLine("Paid **today**.")).toEqual({ line: "Paid today.", more: false });
    expect(tafLine("")).toEqual({ more: false });
    expect(tafLine(undefined)).toEqual({ more: false });
    const cut = tafLine("word ".repeat(200));
    expect(cut.more).toBe(true);
    expect([...cut.line!].length).toBeLessThanOrEqual(TAF_LINE_MAX);
    expect(cut.line!.endsWith("…")).toBe(true);
  });
});
