import { describe, expect, it } from "vitest";
import { type Deps, handle, type HTTPEvent, type Taf } from "../src/cloud/handler.js";
import { setupDefinition } from "../src/cloud/setupdef.js";
import { type Ask, watchKeys } from "../src/cloud/watch-ask.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryCoach, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

/**
 * W11, "From your watch" (docs/watch-ask.md): a day is a read of the person's own watch requests made on it, in the
 * account's time zone. Nothing new is kept, and nothing is sent, approved or reopened.
 */

const T = "t_acme";
const T2 = "t_other";
const ANA = "u_ana";
const BO = "u_bo";
// 19:00 UTC is 20:00 in Paris, the account's zone.
const NOW = new Date("2026-11-16T19:00:00Z");
const COACH = "taf-cccc0000000000000000";
const CAL = "taf-ca100000000000000000";
const SIGNED = { "kiff-device-signature": "t=2026-11-16T19:00:00Z,v1=sig" };
const DAY = 24 * 3600_000;

type Turn = { id: string; said: string; said_at: string; state: string; words: string; line?: string; taf?: { id: string; name: string }; reply_to?: string };

function world(opts: { on?: boolean | string[]; zone?: string } = {}) {
  let clock = NOW;
  const agents = fakeAgents(() => clock);
  const tafs = memoryTafs();
  for (const tenant of [T, T2]) {
    for (const taf of [
      { id: COACH, name: "Coach", job: "setup", kiffAgent: "agent_coach", nameClaim: "c0", created: "2026-11-01T09:00:00Z" },
      { id: CAL, name: "Calendar", job: "custom", kiffAgent: "agent_cal", nameClaim: "c1", created: "2026-11-01T09:00:00Z", summary: "Moves and books the owner's meetings", tools: ["calendar.events_update"] },
    ] as Taf[]) {
      tafs.held.set(`${tenant}#${taf.id}`, taf);
      void agents.api.putAgent(tenant, taf.id, taf.id === COACH ? setupDefinition(COACH) : ({} as never));
    }
  }
  const coach = memoryCoach(() => clock);
  const zone = opts.zone ?? "Europe/Paris";
  const deps: Deps = {
    agents: agents.api, keys: memoryKeys(), accounts: memoryAccounts(), tafs, names: memoryNames(), slots: memorySlots(), claims: memoryClaims(),
    removals: memoryRemovals(), archived: memoryArchived(), aiConsent: false, watchAsk: opts.on ?? true,
    push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async () => "sent" as const },
    forward: async () => undefined, coach, kiffBase: "https://api.test", publicURL: "https://tafs.test", now: () => clock,
    fetch: (async (url: string, init?: RequestInit) => {
      const who = String(((init?.headers ?? {}) as Record<string, string>).authorization ?? "").replace("Bearer ", "");
      // "zed" is a person of another account, with the same subject as Ana's.
      const subject = who === "bo" ? BO : ANA;
      const tenant = who === "zed" ? T2 : T;
      if (url.endsWith("/v1/me")) return new Response(JSON.stringify({ tenant_id: tenant, subject, api_key: false, capabilities: ["read"], time_zone: zone }));
      if (url.endsWith("/v1/me/device-signature")) return new Response(JSON.stringify({ signed: true, key: "device" }));
      if (url.endsWith("/v1/me/run-grants")) return new Response(JSON.stringify({ grant: `krg_${subject}` }));
      if (url.endsWith("/v1/coach/act")) {
        const body = JSON.parse(String(init?.body)) as { agent?: string };
        const may = { use: true, oversee: false, manage: false, grant: `krg_${subject}` };
        return new Response(JSON.stringify(body.agent ? may : { agents: { agent_cal: may } }));
      }
      return new Response("{}", { status: 404 });
    }) as unknown as typeof fetch,
  };
  const call = async (method: string, path: string, body?: unknown, who = "ana", headers: Record<string, string> = {}) => {
    const [rawPath, rawQueryString] = path.split("?");
    const event: HTTPEvent = {
      rawPath: rawPath!, ...(rawQueryString ? { rawQueryString } : {}), requestContext: { http: { method } },
      headers: { authorization: `Bearer ${who}`, "accept-language": "en", ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
    };
    const r = await handle(event, deps);
    return { status: r.statusCode, body: JSON.parse(r.body || "{}") as Record<string, unknown> };
  };
  let n = 0;
  /** A request from the watch, through the route, at the clock's time. */
  const ask = async (text: string, who = "ana", replyTo?: string) => {
    const r = await call("POST", "/v1/watch/asks", { client_id: `c_watch_${String(++n).padStart(4, "0")}`, text, ...(replyTo ? { reply_to: replyTo } : {}) }, who, SIGNED);
    expect(r.status, JSON.stringify(r.body)).toBe(200);
    return (r.body.ask as { id: string }).id;
  };
  const tenantOf = (who: string) => (who === "zed" ? T2 : T);
  const askOf = async (id: string, who = "ana") => (await coach.get(watchKeys.ask(tenantOf(who), id))) as Ask;
  /** Coach answers the request itself: done, with its line. */
  const answer = async (id: string, line: string, who = "ana") => {
    const a = await askOf(id, who);
    const run = agents.runs.find((r) => r.id === a.coach_run)!;
    Object.assign(run, { status: "COMPLETED", output: line });
    await handle({ rawPath: "/v1/runs/ended", requestContext: { http: { method: "POST" } }, headers: {},
      body: JSON.stringify({ ref: agents.notified.get(run.id)!.ref, run: run.id, agent: COACH, status: "COMPLETED" }) }, deps);
  };
  /** kiff/agents' retention: the conversation and everything in it are gone. */
  const expire = (session: string) => {
    const i = agents.sessions.findIndex((s) => s.id === session);
    if (i >= 0) agents.sessions.splice(i, 1);
    agents.events.delete(session);
  };
  const days = async (who = "ana") => (await call("GET", "/v1/watch/days", undefined, who)).body as { today: string; days: Array<{ date: string; count: number }> };
  const turns = async (date: string, who = "ana") => ((await call("GET", `/v1/watch/days/${date}`, undefined, who)).body.turns ?? []) as Turn[];
  return { agents, deps, coach, call, ask, askOf, answer, expire, days, turns,
    at: (iso: string) => { clock = new Date(iso); }, tick: (ms: number) => { clock = new Date(clock.getTime() + ms); } };
}

describe("watch days (W11)", () => {
  it("are 404 watch_off on both routes while the switch is off, as the asks routes are", async () => {
    for (const on of [false, [T2]] as const) {
      const w = world({ on: on as boolean | string[] });
      expect(await w.call("GET", "/v1/watch/days")).toMatchObject({ status: 404, body: { code: "watch_off" } });
      expect(await w.call("GET", "/v1/watch/days/2026-11-16")).toMatchObject({ status: 404, body: { code: "watch_off" } });
      expect(await w.call("GET", "/v1/watch/asks")).toMatchObject({ status: 404, body: { code: "watch_off" } });
    }
  });

  it("list only the person's own requests: never another member's, the owner included, nor another account's", async () => {
    const w = world();
    await w.ask("Move my four o'clock call to six");
    w.tick(60_000);
    await w.ask("What's on tomorrow?", "bo");
    w.tick(60_000);
    // Another account, the same subject as Ana's.
    await w.ask("Cancel the dentist", "zed");
    expect(await w.days()).toEqual({ today: "2026-11-16", days: [{ date: "2026-11-16", count: 1 }] });
    expect((await w.turns("2026-11-16")).map((t) => t.said)).toEqual(["Move my four o'clock call to six"]);
    expect((await w.days("bo")).days).toEqual([{ date: "2026-11-16", count: 1 }]);
    expect((await w.turns("2026-11-16", "bo")).map((t) => t.said)).toEqual(["What's on tomorrow?"]);
    expect((await w.turns("2026-11-16", "zed")).map((t) => t.said)).toEqual(["Cancel the dentist"]);
    const all = JSON.stringify([await w.turns("2026-11-16"), await w.days()]);
    expect(all).not.toMatch(/tomorrow|dentist/);
    // Even if Ana's list ever named Bo's request, it stays Bo's.
    const bos = (await w.coach.get(watchKeys.recent(T, BO))) as string[];
    const anas = (await w.coach.get(watchKeys.recent(T, ANA))) as string[];
    await w.coach.put(watchKeys.recent(T, ANA), [...bos, ...anas], new Date(NOW.getTime() + 30 * DAY));
    expect((await w.turns("2026-11-16")).map((t) => t.said)).toEqual(["Move my four o'clock call to six"]);
    expect((await w.days()).days).toEqual([{ date: "2026-11-16", count: 1 }]);
  });

  it("put a request at 23:59 and one at 00:01 in the account's zone on two days", async () => {
    const w = world();
    // 23:59 and 00:01 in Paris (UTC+1): both on 16 Nov in UTC.
    w.at("2026-11-16T22:59:00Z");
    await w.ask("Remind me to call Mum");
    w.at("2026-11-16T23:01:00Z");
    await w.ask("Book the gym for seven");
    expect((await w.days()).days).toEqual([{ date: "2026-11-17", count: 1 }, { date: "2026-11-16", count: 1 }]);
    expect((await w.turns("2026-11-16")).map((t) => t.said)).toEqual(["Remind me to call Mum"]);
    expect((await w.turns("2026-11-17")).map((t) => t.said)).toEqual(["Book the gym for seven"]);
    expect((await w.turns("2026-11-17"))[0]!.said_at).toBe("2026-11-16T23:01:00.000Z");
  });

  it("say today as the account's date, not UTC's", async () => {
    const w = world();
    w.at("2026-11-16T23:30:00Z"); // 00:30 on 17 Nov in Paris
    expect((await w.days()).today).toBe("2026-11-17");
    expect((await world({ zone: "America/Los_Angeles" }).days()).today).toBe("2026-11-16"); // 11:00 there, 19:00 UTC
    const late = world({ zone: "America/Los_Angeles" });
    late.at("2026-11-17T03:00:00Z"); // 19:00 on 16 Nov in Los Angeles
    expect((await late.days()).today).toBe("2026-11-16");
  });

  it("take what was said from the request's own conversation, never from text that looks like a watch message", async () => {
    const w = world();
    const id = await w.ask("Move my four o'clock call to six");
    // Sent with the account's zone and language lines; only the person's words are theirs.
    const a = await w.askOf(id);
    const sent = w.agents.events.get(a.session!)!.find((e) => e.run === a.coach_run)!;
    expect(String((sent.body as { text: string }).text)).toContain("[tafs time zone]");
    expect((await w.turns("2026-11-16"))[0]).toMatchObject({ id, said: "Move my four o'clock call to six", state: "accepted", words: "Sent · Coach has it" });

    // A phone chat that reads like a watch request is never one.
    expect((await w.call("POST", `/v1/tafs/${COACH}/messages`, { text: "⌚ Sent from your watch: book the gym" })).status).toBe(200);
    const phone = w.agents.runs.find((r) => r.agent === COACH && r.session !== a.session)!;
    // Even with a request record pointing at it: the watch route never marked that conversation.
    const forged: Ask = { id: "wa_forged0000000000000000", client_id: "c_forged_01", person: ANA, state: "done", line: "Booked.", session: phone.session, coach_run: phone.id, at: NOW.toISOString(), updated: NOW.toISOString() };
    await w.coach.put(watchKeys.ask(T, forged.id), forged, new Date(NOW.getTime() + 30 * DAY));
    const index = (await w.coach.get(watchKeys.recent(T, ANA))) as string[];
    await w.coach.put(watchKeys.recent(T, ANA), [forged.id, ...index], new Date(NOW.getTime() + 30 * DAY));
    expect((await w.turns("2026-11-16")).map((t) => t.id)).toEqual([id]);
    expect((await w.days()).days).toEqual([{ date: "2026-11-16", count: 1 }]);
    expect(JSON.stringify(await w.turns("2026-11-16"))).not.toContain("gym");
  });

  it("order a reply straight after the request it replies to, with its own words", async () => {
    const w = world();
    const first = await w.ask("Move my four o'clock call to six");
    w.tick(60_000);
    const other = await w.ask("What's the weather tomorrow?");
    w.tick(60_000);
    await w.answer(first, "Moved your call to 18:00.");
    const reply = await w.ask("and tell the guests", "ana", first);
    expect((await w.askOf(reply)).session).toBe((await w.askOf(first)).session);
    const day = await w.turns("2026-11-16");
    expect(day.map((t) => t.id)).toEqual([first, reply, other]);
    expect(day[0]).toMatchObject({ said: "Move my four o'clock call to six", state: "done", line: "Moved your call to 18:00.", words: "Done" });
    expect(day[0]).not.toHaveProperty("reply_to");
    expect(day[1]).toMatchObject({ said: "and tell the guests", reply_to: first, state: "accepted" });
    expect(day[2]).toMatchObject({ said: "What's the weather tomorrow?" });
    expect((await w.days()).days).toEqual([{ date: "2026-11-16", count: 3 }]);
  });

  it("lose a day when its conversations go, and keep it while they're kept, past the requests' week", async () => {
    const w = world();
    const id = await w.ask("Move my four o'clock call to six");
    w.tick(8 * DAY);
    expect((await w.days()).days).toEqual([{ date: "2026-11-16", count: 1 }]);
    w.expire((await w.askOf(id)).session!);
    expect((await w.days()).days).toEqual([]);
    expect(await w.call("GET", "/v1/watch/days/2026-11-16")).toEqual({ status: 200, body: { date: "2026-11-16", turns: [] } });
    // And what tafs cloud keeps of it goes by itself once the conversation's 30 days are past.
    w.tick(23 * DAY);
    expect(await w.askOf(id)).toBeUndefined();
  });

  it("count only the requests whose conversation is still kept", async () => {
    const w = world();
    const gone = await w.ask("Remind me to call Mum");
    w.tick(60_000);
    await w.ask("Book the gym for seven");
    w.expire((await w.askOf(gone)).session!);
    expect((await w.days()).days).toEqual([{ date: "2026-11-16", count: 1 }]);
    expect((await w.turns("2026-11-16")).map((t) => t.said)).toEqual(["Book the gym for seven"]);
  });

  it("are read only: nothing is started, sent or kept", async () => {
    const w = world();
    await w.ask("Move my four o'clock call to six");
    const calls = w.agents.calls.length;
    const kept = JSON.stringify([...w.coach.held]);
    await w.days();
    await w.turns("2026-11-16");
    expect(w.agents.calls.slice(calls).filter((c) => !c.startsWith("session"))).toEqual([]);
    expect(JSON.stringify([...w.coach.held])).toBe(kept);
    expect((await w.call("POST", "/v1/watch/days")).status).toBe(404);
  });

  it("answer a date that isn't one with 400, and a day with nothing with no turns", async () => {
    const w = world();
    for (const bad of ["2026-13-01", "2026-02-30", "yesterday", "20261116", "2026-11-1"]) {
      expect((await w.call("GET", `/v1/watch/days/${bad}`)).status, bad).toBe(400);
    }
    expect(await w.call("GET", "/v1/watch/days/2026-11-15")).toEqual({ status: 200, body: { date: "2026-11-15", turns: [] } });
    expect(await w.days()).toEqual({ today: "2026-11-16", days: [] });
  });
});
