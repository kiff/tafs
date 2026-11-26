import { readFileSync } from "node:fs";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CHROME_ROUTES, chromeMayCall, chromeOf } from "../src/cloud/chrome-chats.js";
import { handle, type HTTPEvent, SETUP_JOB, type Taf } from "../src/cloud/handler.js";
import { givePageConsent, PAGE_CONSENT_VERSION } from "../src/cloud/consent.js";
import { whoIs } from "../src/cloud/kiff.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryCoach, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

const NOW = new Date("2026-11-24T18:00:00Z");
const COACH = "taf-c0ac4000000000000000";
const RAE = "taf-aaaa0000000000000000";

/** Who each token is, as KIFF's /v1/me says it: Ana's phone, two Chromes she paired, and a Chrome before KIFF named platforms. */
const PERSON = { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] };
const WHO: Record<string, Record<string, unknown>> = {
  phone: { ...PERSON, device_session: true, approval_mode: "key", device_id: "dev_phone", device_platform: "ios" },
  chromeA: { ...PERSON, device_session: true, approval_mode: "view", device_id: "dev_a", device_platform: "chrome" },
  chromeB: { ...PERSON, device_session: true, approval_mode: "view", device_id: "dev_b", device_platform: "chrome" },
  unnamed: { ...PERSON, device_session: true, approval_mode: "view" },
  desktop: { ...PERSON, device_session: true, approval_mode: "view", device_id: "dev_pc", device_platform: "windows" },
  // A view-only computer paired before platforms were stored: KIFF names its device, and no platform.
  noPlatform: { ...PERSON, device_session: true, approval_mode: "view", device_id: "dev_old" },
};

async function world() {
  const agents = fakeAgents(() => NOW);
  const tafs = memoryTafs();
  const coach = memoryCoach(() => NOW);
  tafs.held.set(`t_acme#${COACH}`, { id: COACH, name: "Coach", job: SETUP_JOB, kiffAgent: "setup-acme", nameClaim: "c", created: "2026-10-15T09:00:00Z" } satisfies Taf);
  tafs.held.set(`t_acme#${RAE}`, { id: RAE, name: "Rae", job: "refunds", kiffAgent: "rae", nameClaim: "c", created: "2026-10-15T09:00:00Z" } satisfies Taf);
  await agents.api.putAgent("t_acme", COACH, {} as never);
  await agents.api.putAgent("t_acme", RAE, {} as never);
  await givePageConsent(coach, "t_acme", "u_ana", PAGE_CONSENT_VERSION, NOW);
  return { agents, tafs, coach };
}
type World = Awaited<ReturnType<typeof world>>;

/** What planque recorded for each token's Chrome (`GET …/chromes/self/runs`); undefined, planque isn't set. */
let PLANQUE: Record<string, Array<{ run: string; taf: string; at: string }>> | undefined;
let planqueAsked = 0;

function deps(w: World) {
  return {
    ...(PLANQUE ? { planqueBase: "https://planque.test" } : {}),
    agents: w.agents.api, keys: memoryKeys(), accounts: memoryAccounts(), tafs: w.tafs,
    names: memoryNames(), slots: memorySlots(), claims: memoryClaims(), removals: memoryRemovals(), archived: memoryArchived(), coach: w.coach,
    push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async () => "sent" as const },
    forward: async () => undefined,
    // KIFF answers each token as who it is; the request's own headers never say which device it comes from.
    fetch: (async (url: unknown, init?: RequestInit) => {
      const auth = new Headers(init?.headers).get("authorization") ?? "";
      if (String(url) === "https://planque.test/v1/accounts/t_acme/chromes/self/runs") {
        planqueAsked++;
        return new Response(JSON.stringify({ runs: PLANQUE?.[auth.replace("Bearer ", "")] ?? [] }), { status: 200 });
      }
      return new Response(JSON.stringify(WHO[auth.replace("Bearer ", "")] ?? WHO.phone), { status: 200 });
    }) as typeof fetch,
    now: () => NOW,
  };
}

function event(who: string, method: string, path: string, body?: unknown, headers: Record<string, string> = {}): HTTPEvent {
  return { rawPath: path, requestContext: { http: { method } }, headers: { Authorization: `Bearer ${who}`, ...headers }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) };
}

async function call(w: World, who: string, method: string, path: string, body?: unknown, headers?: Record<string, string>) {
  const r = await handle(event(who, method, path, body, headers), deps(w));
  return { status: r.statusCode, body: JSON.parse(r.body || "{}") as Record<string, unknown> };
}

/** Sends `text` as `who` to Coach (or `taf`), and the chat it went into. */
async function say(w: World, who: string, text: string, extra: Record<string, unknown> = {}, taf = COACH): Promise<string> {
  const r = await call(w, who, "POST", `/v1/tafs/${taf}/messages`, { text, ...extra });
  expect(r.status, JSON.stringify(r.body)).toBe(200);
  return r.body.session as string;
}

async function listed(w: World, who: string, archived = false, taf = COACH): Promise<string[]> {
  const r = await call(w, who, "GET", `/v1/tafs/${taf}/chats${archived ? "/archived" : ""}`);
  expect(r.status).toBe(200);
  return (r.body.chats as Array<{ session: string }>).map((c) => c.session);
}

async function threadOf(w: World, who: string, session?: string, taf = COACH) {
  return call(w, who, "GET", `/v1/tafs/${taf}/conversation${session ? `/${session}` : ""}`);
}

const texts = (body: Record<string, unknown>) => ((body.messages as Array<{ text: string }>) ?? []).map((m) => m.text);

afterEach(() => { vi.restoreAllMocks(); PLANQUE = undefined; planqueAsked = 0; });

describe("which Chrome is asking comes from KIFF alone", () => {
  // Invariant: a Chrome is the session KIFF paired as chrome, by KIFF's device id; nothing in the request names it
  it("reads the device and its platform from /v1/me, and nothing else", async () => {
    const asked = (who: string) => whoIs(who, (async (_u: unknown, init?: RequestInit) =>
      new Response(JSON.stringify(WHO[new Headers(init?.headers).get("authorization")!.replace("Bearer ", "")]), { status: 200 })) as typeof fetch);
    expect(chromeOf(await asked("chromeA"))).toEqual({ device: "dev_a" });
    expect(chromeOf(await asked("phone"))).toBeUndefined();
    expect(chromeOf(await asked("desktop"))).toBeUndefined();
    // Once KIFF names the device, only its platform says Chrome: a view-only device with no platform stored isn't one.
    expect(chromeOf(await asked("noPlatform"))).toBeUndefined();
    // Before KIFF names platforms, a device's view-only session is taken as a Chrome with no chats (fail closed).
    expect(chromeOf(await asked("unnamed"))).toEqual({ device: "" });
  });

  // Invariant: a header can't make a phone a Chrome, or a Chrome a phone
  it("takes no header's word for it", async () => {
    const w = await world();
    vi.spyOn(console, "log").mockImplementation(() => undefined);
    const fromPhone = await say(w, "phone", "From the phone");
    const spoof = { "x-device-platform": "ios", "x-device-id": "dev_phone", "kiff-device": "dev_phone" };
    expect((await call(w, "chromeA", "GET", `/v1/tafs/${COACH}/chats`, undefined, spoof)).body.chats).toEqual([]);
    expect((await threadOf(w, "chromeA", fromPhone)).status).toBe(404);
  });
});

describe("a Chrome sees only the chats begun in it", () => {
  // Invariant: the lists, live and archived, hold only this Chrome's chats; the phone still lists every chat
  it("lists only its own chats, live and archived, and the phone lists them all", async () => {
    const w = await world();
    vi.spyOn(console, "log").mockImplementation(() => undefined);
    const fromPhone = await say(w, "phone", "From the phone");
    const fromA = await say(w, "chromeA", "From Chrome A", { new_chat: true });
    const fromB = await say(w, "chromeB", "From Chrome B", { new_chat: true });
    expect(await listed(w, "chromeA")).toEqual([fromA]);
    expect(await listed(w, "chromeB")).toEqual([fromB]);
    expect((await listed(w, "phone")).sort()).toEqual([fromPhone, fromA, fromB].sort());

    // Archived: its own, archived from it; another's can't be archived or unarchived from it.
    expect((await call(w, "chromeA", "POST", `/v1/tafs/${COACH}/chats/${fromA}/archive`)).status).toBe(200);
    expect((await call(w, "chromeA", "POST", `/v1/tafs/${COACH}/chats/${fromPhone}/archive`)).status).toBe(404);
    expect((await call(w, "phone", "POST", `/v1/tafs/${COACH}/chats/${fromB}/archive`)).status).toBe(200);
    expect(await listed(w, "chromeA", true)).toEqual([fromA]);
    expect((await call(w, "chromeA", "POST", `/v1/tafs/${COACH}/chats/${fromB}/unarchive`)).status).toBe(404);
    expect((await call(w, "chromeA", "POST", `/v1/tafs/${COACH}/chats/${fromA}/unarchive`)).status).toBe(200);
  });

  // Invariant: a Chrome deletes no chat until its panel offers it (CHROME_ROUTES); the phone deletes its person's own
  it("deletes no chat from a Chrome, its own included, and the phone deletes one Chrome began", async () => {
    const w = await world();
    vi.spyOn(console, "log").mockImplementation(() => undefined);
    const fromPhone = await say(w, "phone", "From the phone");
    const fromA = await say(w, "chromeA", "From Chrome A", { new_chat: true });
    for (const s of [fromPhone, fromA]) expect((await call(w, "chromeA", "DELETE", `/v1/tafs/${COACH}/chats/${s}`)).status).toBe(404);
    expect(w.agents.calls.filter((c) => c.startsWith("deleteSession"))).toEqual([]);
    for (const r of w.agents.runs.filter((x) => x.session === fromA)) (r as { status: string }).status = "COMPLETED";
    expect((await call(w, "phone", "DELETE", `/v1/tafs/${COACH}/chats/${fromA}`)).status).toBe(200);
    expect(await listed(w, "phone")).toEqual([fromPhone]);
  });

  // Invariant: a chat it didn't begin can't be read or written into by its id: 404, as one that isn't there
  it("answers 404 for another chat's thread or a message into it", async () => {
    const w = await world();
    vi.spyOn(console, "log").mockImplementation(() => undefined);
    const fromPhone = await say(w, "phone", "My secret plan");
    const fromB = await say(w, "chromeB", "B's own", { new_chat: true });
    for (const other of [fromPhone, fromB, "sess-unknown"]) {
      expect((await threadOf(w, "chromeA", other)).status).toBe(404);
      expect((await call(w, "chromeA", "POST", `/v1/tafs/${COACH}/messages`, { text: "go on", session: other })).status).toBe(404);
    }
    const fromA = await say(w, "chromeA", "A's own", { new_chat: true });
    const mine = await threadOf(w, "chromeA", fromA);
    expect(mine.status).toBe(200);
    expect(texts(mine.body)).toContain("A's own");
    expect(JSON.stringify(mine.body)).not.toContain("My secret plan");
  });

  // Invariant: a Chrome attaches files as the apps do: declared by the person on that taf, sent once, never another's, and never read back from a Chrome
  // Category: privacy
  // Attack: a Chrome's message names a file it didn't declare (another chat's, the phone's, or one already sent), or reads a file back
  // Refused: kiff/agents takes only the person's own unsent file declared on that taf; GET …/attachments/{id} is closed to a Chrome
  it("declares a file, sends it with a message once, and never reads one back", async () => {
    const w = await world();
    vi.spyOn(console, "log").mockImplementation(() => undefined);
    const file = { media_type: "application/pdf", name: "brief.pdf", size: 2048, sha256: "a".repeat(64) };
    const declared = await call(w, "chromeA", "POST", `/v1/tafs/${COACH}/attachments`, file);
    expect(declared.status, JSON.stringify(declared.body)).toBe(200);
    const id = (declared.body.attachment as { id: string }).id;
    expect((declared.body.upload as { url: string; method: string }).method).toBe("PUT");

    const session = await say(w, "chromeA", "Read this", { new_chat: true, attachments: [id] });
    const mine = await threadOf(w, "chromeA", session);
    expect(JSON.stringify(mine.body)).toContain("brief.pdf");
    // Sent once: naming it again is refused, and nothing starts.
    const runs = w.agents.runs.length;
    const again = await call(w, "chromeA", "POST", `/v1/tafs/${COACH}/messages`, { text: "again", session, attachments: [id] });
    expect(again.status).toBe(409);
    expect(w.agents.runs.length).toBe(runs);
    // Another taf's file, or one that isn't there, is never sent.
    const rae = await call(w, "chromeA", "POST", `/v1/tafs/${RAE}/attachments`, file);
    const other = await call(w, "chromeA", "POST", `/v1/tafs/${COACH}/messages`, { text: "look", session, attachments: [(rae.body.attachment as { id: string }).id] });
    expect(other.status).toBe(404);
    const none = await call(w, "chromeA", "POST", `/v1/tafs/${COACH}/messages`, { text: "look", session, attachments: ["att_" + "0".repeat(31) + "9"] });
    expect(none.status).toBe(404);
    expect(w.agents.runs.length).toBe(runs);
    // A Chrome never reads a file back, its own included.
    expect((await call(w, "chromeA", "GET", `/v1/tafs/${COACH}/attachments/${id}`)).status).toBe(404);
    // A taf's own chat takes files from a Chrome too.
    const forRae = await call(w, "chromeA", "POST", `/v1/tafs/${RAE}/attachments`, file);
    await say(w, "chromeA", "For you", { new_chat: true, attachments: [(forRae.body.attachment as { id: string }).id] }, RAE);
  });

  // Invariant: its live thread, and where a message with no chat goes, are its newest own chat, never the person's newest elsewhere
  it("reads and goes on in its own newest chat, or starts one, never the phone's", async () => {
    const w = await world();
    vi.spyOn(console, "log").mockImplementation(() => undefined);
    await say(w, "phone", "My secret plan");
    const empty = await threadOf(w, "chromeA");
    expect(empty.status).toBe(200);
    expect(empty.body).toMatchObject({ messages: [], working: null });

    const first = await say(w, "chromeA", "Hello from Chrome");
    expect(await listed(w, "chromeA")).toEqual([first]);
    await say(w, "phone", "Another phone thought", { new_chat: true });
    const again = await say(w, "chromeA", "And again");
    expect(again).toBe(first);
    const live = await threadOf(w, "chromeA");
    expect(texts(live.body)).toEqual(expect.arrayContaining(["Hello from Chrome", "And again"]));
    expect(JSON.stringify(live.body)).not.toMatch(/secret plan|phone thought/);
  });

  // Invariant: the same holds for a taf's own chats, not only Coach's
  it("holds a taf's own chats the same way", async () => {
    const w = await world();
    vi.spyOn(console, "log").mockImplementation(() => undefined);
    const fromPhone = await say(w, "phone", "Refund order 42", {}, RAE);
    expect(await listed(w, "chromeA", false, RAE)).toEqual([]);
    expect((await threadOf(w, "chromeA", fromPhone, RAE)).status).toBe(404);
    expect(JSON.stringify((await threadOf(w, "chromeA", undefined, RAE)).body)).not.toContain("Refund order 42");
  });

  // Invariant: a re-pair is a new KIFF device, so it starts with no chats; the old pairing's stay on the phone
  it("starts empty after a re-pair", async () => {
    const w = await world();
    vi.spyOn(console, "log").mockImplementation(() => undefined);
    const fromA = await say(w, "chromeA", "Before unpairing", { new_chat: true });
    WHO.chromeA2 = { ...WHO.chromeA, device_id: "dev_a2" };
    expect(await listed(w, "chromeA2")).toEqual([]);
    expect((await threadOf(w, "chromeA2", fromA)).status).toBe(404);
    expect(await listed(w, "phone")).toContain(fromA);
  });

  // Invariant: with no device named, a Chrome has no chats at all, and what it starts is never listed back to it
  it("fails closed when KIFF names no device", async () => {
    const w = await world();
    vi.spyOn(console, "log").mockImplementation(() => undefined);
    const fromPhone = await say(w, "phone", "My secret plan");
    expect(await listed(w, "unnamed")).toEqual([]);
    expect((await threadOf(w, "unnamed", fromPhone)).status).toBe(404);
    expect((await threadOf(w, "unnamed")).body).toMatchObject({ messages: [] });
    await say(w, "unnamed", "Sent anyway");
    expect(await listed(w, "unnamed")).toEqual([]);
  });

  // Invariant: a desktop app's or a phone's session isn't held to this: it lists every chat, as before
  it("leaves phones and desktop apps as they were", async () => {
    const w = await world();
    vi.spyOn(console, "log").mockImplementation(() => undefined);
    const fromPhone = await say(w, "phone", "From the phone");
    const fromA = await say(w, "chromeA", "From Chrome A", { new_chat: true });
    expect((await listed(w, "desktop")).sort()).toEqual([fromPhone, fromA].sort());
    expect((await listed(w, "noPlatform")).sort()).toEqual([fromPhone, fromA].sort());
    expect((await threadOf(w, "desktop", fromPhone)).status).toBe(200);
  });
});

describe("a Chrome calls only its side panel's routes", () => {
  /** Every route the handler documents (its header comment), as a request with its ids filled in. */
  function documented(): Array<{ method: string; path: string }> {
    const src = readFileSync(new URL("../src/cloud/handler.ts", import.meta.url), "utf8");
    const out: Array<{ method: string; path: string }> = [];
    for (const m of src.matchAll(/^ \*   (GET|POST|PUT|PATCH|DELETE) +(\/v1\/\S*)/gm)) {
      const path = m[2]!.split("?")[0]!.replace(/\{[^}]+\}/g, (p) => (p === "{id}" ? COACH : "x"));
      out.push({ method: m[1]!, path });
    }
    return out;
  }
  // Routes that take no person's session at all (a logo, the zone and job lists, KIFF's and kiff/agents' own calls):
  // they read nothing of anyone's, so a Chrome's token changes nothing there.
  const sessionless = [/^\/v1\/logos\//, /^\/v1\/time-zones$/, /^\/v1\/jobs$/, /^\/v1\/kiff\/alerts$/, /^\/v1\/runs\/ended$/];

  // Invariant: default-deny: every documented route but the side panel's answers 404 to a Chrome, so a chat route added later is closed to it
  it("answers 404 on every documented route but its own", async () => {
    const w = await world();
    vi.spyOn(console, "log").mockImplementation(() => undefined);
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const routes = documented();
    expect(routes.length).toBeGreaterThan(60);
    const open: string[] = [];
    for (const r of routes) {
      if (chromeMayCall(r.method, r.path) || sessionless.some((s) => s.test(r.path))) continue;
      const got = await call(w, "chromeA", r.method, r.path, r.method === "GET" || r.method === "DELETE" ? undefined : {});
      if (got.status !== 404) open.push(`${r.method} ${r.path} → ${got.status}`);
    }
    expect(open).toEqual([]);
  });

  // Invariant: what the side panel calls is documented, so the walk above covers the same list the handler keeps
  it("lists only documented routes, and export is not one of them", async () => {
    const routes = documented();
    for (const allowed of CHROME_ROUTES) {
      expect(routes.some((r) => r.method === allowed.method && allowed.path.test(r.path)), `${allowed.method} ${allowed.path}`).toBe(true);
    }
    const w = await world();
    expect((await call(w, "chromeA", "GET", "/v1/me/chats/export")).status).toBe(404);
    expect((await call(w, "chromeA", "POST", `/v1/tafs/${COACH}/messages/stream`, { text: "hi" })).status).toBe(404);
    expect((await call(w, "chromeA", "POST", `/v1/tafs/${COACH}/messages/m1/report`, { reason: "wrong" })).status).toBe(404);
  });
});

describe("a Chrome sees the turn of a run that worked in it, wherever it was sent from", () => {
  /** The phone's chat with Rae: an earlier private turn, then "Toulouse", whose run worked in Chrome A; each answered. */
  async function phoneChat(w: World) {
    vi.spyOn(console, "log").mockImplementation(() => undefined);
    const session = await say(w, "phone", "My private note", {}, RAE);
    const answer = (run: string, text: string) => {
      const list = w.agents.events.get(session)!;
      list.push({ session, run, seq: list.length + 1, time: NOW.toISOString(), kind: "agent_message", body: { text } } as never);
    };
    const before = w.agents.runs.find((r) => r.session === session)!.id;
    answer(before, "Noted, privately.");
    for (const r of w.agents.runs) (r as { status: string }).status = "COMPLETED";
    expect(await say(w, "phone", "Toulouse", { session }, RAE)).toBe(session);
    const run = w.agents.runs.find((r) => r.session === session && r.id !== before)!.id;
    answer(run, "Toulouse is in Occitanie.");
    for (const r of w.agents.runs) (r as { status: string }).status = "COMPLETED";
    PLANQUE = { chromeA: [{ run, taf: "rae", at: NOW.toISOString() }] };
    return { session, run, before };
  }

  // Invariant: the chat of a run planque recorded in this Chrome is listed for it, titled and timed by that run's turn alone; another Chrome lists nothing
  // Category: privacy
  it("lists the run's chat for that Chrome alone, titled by the run's own message", async () => {
    const w = await world();
    const { session } = await phoneChat(w);
    const r = await call(w, "chromeA", "GET", `/v1/tafs/${RAE}/chats`);
    expect(r.status).toBe(200);
    expect(r.body.chats).toEqual([{ session, started: NOW.toISOString(), updated: NOW.toISOString(), title: "Toulouse" }]);
    expect(JSON.stringify(r.body)).not.toContain("private");
    expect(await listed(w, "chromeB", false, RAE)).toEqual([]);
    expect(await listed(w, "chromeA", false, COACH)).toEqual([]);
    expect(await listed(w, "phone", false, RAE)).toEqual([session]);
  });

  // Invariant: the panel reads only the listed runs' events, by each event's own run, never the chat's earlier turns
  // Category: privacy
  // Attack: a Chrome where a run worked opens the whole chat by its id
  // Refused: conversation() keeps only the events whose run planque listed for this Chrome
  it("opens only that run's turn: its message and its answer, never the earlier ones", async () => {
    const w = await world();
    const { session } = await phoneChat(w);
    const r = await threadOf(w, "chromeA", session, RAE);
    expect(r.status).toBe(200);
    expect(texts(r.body)).toEqual(["Toulouse", "Toulouse is in Occitanie."]);
    expect(JSON.stringify(r.body)).not.toMatch(/private|Noted/);
    expect((await threadOf(w, "chromeB", session, RAE)).status).toBe(404);
    expect(texts((await threadOf(w, "phone", session, RAE)).body)).toEqual(["My private note", "Noted, privately.", "Toulouse", "Toulouse is in Occitanie."]);
  });

  // Invariant: a run's chat is read-only from the panel: no message, archive or delete into it, and the live thread stays the Chrome's own
  it("never writes into the run's chat from the panel", async () => {
    const w = await world();
    const { session } = await phoneChat(w);
    expect((await call(w, "chromeA", "POST", `/v1/tafs/${RAE}/messages`, { text: "and Lyon?", session })).status).toBe(404);
    for (const a of ["archive", "unarchive"]) expect((await call(w, "chromeA", "POST", `/v1/tafs/${RAE}/chats/${session}/${a}`)).status).toBe(404);
    expect((await call(w, "chromeA", "DELETE", `/v1/tafs/${RAE}/chats/${session}`)).status).toBe(404);
    const live = await threadOf(w, "chromeA", undefined, RAE);
    expect(live.body.messages).toEqual([]);
    expect(w.agents.runs.filter((r) => r.session === session)).toHaveLength(2);
  });

  // Invariant: a Chrome reports only an answer its panel may show: one in the listed run, never one outside it, and always naming its chat
  // Category: privacy
  it("reports an answer in the run's turn, and 404 for one outside it", async () => {
    const w = await world();
    const reports = new Map<string, unknown>();
    const { session } = await phoneChat(w);
    const withReports = { ...deps(w), reports: { store: { create: async (k: string, v: unknown) => !reports.has(k) && !!reports.set(k, v), get: async (k: string) => reports.get(k) } } } as never;
    const report = async (message: string, body: Record<string, unknown>) => {
      const r = await handle(event("chromeA", "POST", `/v1/tafs/${RAE}/messages/${message}/report`, body), withReports);
      return r.statusCode;
    };
    const all = (await threadOf(w, "phone", session, RAE)).body.messages as Array<{ id: string; text: string }>;
    const inRun = all.find((m) => m.text === "Toulouse is in Occitanie.")!.id;
    const outside = all.find((m) => m.text === "Noted, privately.")!.id;
    expect(await report(outside, { reason: "wrong", session })).toBe(404);
    expect(await report(inRun, { reason: "wrong" })).toBe(404);
    expect(await report(inRun, { reason: "wrong", session })).toBe(200);
    expect(reports.size).toBe(1);
  });

  // Invariant: a run planque lists is read back from kiff/agents under the asker's account and this taf; one that isn't theirs is dropped
  // Category: privacy
  // Attack: planque's record names a run of another account or another taf
  // Refused: chromeRunChats reads each run with the person's tenant and the taf, and keeps only a run that is that taf's
  it("drops a listed run that isn't this account's or this taf's", async () => {
    const w = await world();
    const { run } = await phoneChat(w);
    PLANQUE = { chromeA: [{ run: "run-of-another-account", taf: "rae", at: NOW.toISOString() }, { run, taf: "setup-acme", at: NOW.toISOString() }] };
    expect(await listed(w, "chromeA", false, RAE)).toEqual([]);
    expect(await listed(w, "chromeA", false, COACH)).toEqual([]);
  });

  // Invariant: with nothing recorded (unpaired, moved, or planque unread) the run's chat leaves the panel, and a deleted chat is gone from it
  it("leaves the panel when planque lists the run no more, or the chat is deleted", async () => {
    const w = await world();
    const { session, run } = await phoneChat(w);
    expect(await listed(w, "chromeA", false, RAE)).toEqual([session]);
    PLANQUE = { chromeA: [] };
    expect(await listed(w, "chromeA", false, RAE)).toEqual([]);
    expect((await threadOf(w, "chromeA", session, RAE)).status).toBe(404);
    PLANQUE = { chromeA: [{ run, taf: "rae", at: NOW.toISOString() }] };
    expect((await call(w, "phone", "DELETE", `/v1/tafs/${RAE}/chats/${session}`)).status).toBe(200);
    expect(await listed(w, "chromeA", false, RAE)).toEqual([]);
    expect((await threadOf(w, "chromeA", session, RAE)).status).toBe(404);
  });

  // Invariant: planque is asked with the panel's own session, once per read, and never for a phone or a desktop
  it("asks planque with the panel's session only", async () => {
    const w = await world();
    await phoneChat(w);
    planqueAsked = 0;
    await listed(w, "phone", false, RAE);
    await listed(w, "desktop", false, RAE);
    expect(planqueAsked).toBe(0);
    await listed(w, "chromeA", false, RAE);
    expect(planqueAsked).toBe(1);
  });
});
