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

function deps(w: World) {
  return {
    agents: w.agents.api, keys: memoryKeys(), accounts: memoryAccounts(), tafs: w.tafs,
    names: memoryNames(), slots: memorySlots(), claims: memoryClaims(), removals: memoryRemovals(), archived: memoryArchived(), coach: w.coach,
    push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async () => "sent" as const },
    forward: async () => undefined,
    // KIFF answers each token as who it is; the request's own headers never say which device it comes from.
    fetch: (async (_url: unknown, init?: RequestInit) => {
      const auth = new Headers(init?.headers).get("authorization") ?? "";
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

afterEach(() => vi.restoreAllMocks());

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
