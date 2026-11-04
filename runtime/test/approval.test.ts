import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import { ASK_KEPT_MS, WORDS } from "../src/cloud/approval.js";
import { handle, type HTTPEvent, type Taf } from "../src/cloud/handler.js";
import { sha256 } from "../src/cloud/jcs.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryCoach, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

/**
 * An approval card's outcome is tafs cloud's to decide (kiff/tafs#413): the phone says
 * Approve was tapped, tafs cloud reads planque's grant and KIFF's draft and says what to
 * sign, and answers Coach itself when nothing is. The report is checked with KIFF before
 * Coach hears "approved".
 */

const ME = { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] };
const NOW = new Date("2026-10-16T12:00:00Z");
const COACH = "taf-cccc0000000000000000";
const ANA = { requester: { kind: "member" as const, id: "u_ana" } };
const PROOF = "t=2026-10-16T12:00:00Z,v1=synthetic,d=dev_1";
const PROFILE = { kind: "kiff.profile.v1", tools: { refund: { unit: "cents" } } };

/** A grant as planque sends it, its digest the hash of its request, as planque makes it. */
function grant(over: Record<string, unknown> = {}, request: Record<string, unknown> = {}) {
  const req = { agent: "refunds-x", server: "mcp.stripe.com", tools: [{ tool: "refund" }], ...request };
  return { id: "g_1", status: "pending", vendor: "Stripe", digest: sha256(req), request: req, events: [], ...over };
}

interface Services {
  grants: unknown[];
  drafts: Record<string, { status: string; version?: string; agent_id?: string; expires_at?: string }>;
  /** What KIFF answers the device-signature check: signed, or a status. */
  signed: true | number;
  /** Every signature check KIFF was asked, as sent. */
  checks: Array<Record<string, unknown>>;
  proofs: string[];
}

function services(): Services {
  return { grants: [grant()], drafts: {}, signed: true, checks: [], proofs: [] };
}

function net(s: Services) {
  return (async (url: string, init?: RequestInit) => {
    const headers = (init?.headers ?? {}) as Record<string, string>;
    if (url.startsWith("https://planque.test/")) {
      s.proofs.push(headers["Planque-Device-Signature"] ?? "");
      if (!headers["Planque-Device-Signature"]) return new Response("{}", { status: 401 });
      return new Response(JSON.stringify({ grants: s.grants }), { status: 200 });
    }
    const token = String(headers.authorization ?? "").replace("Bearer ", "");
    if (token !== "ana") return new Response("{}", { status: 401 });
    if (url.endsWith("/v1/me/access")) return new Response(JSON.stringify({ agents: { "setup-acme": { use: true, oversee: false, manage: true } } }), { status: 200 });
    if (url.endsWith("/v1/me")) return new Response(JSON.stringify(ME), { status: 200 });
    if (url.endsWith("/v1/me/device-signature")) {
      s.checks.push(JSON.parse(String(init?.body)) as Record<string, unknown>);
      return s.signed === true ? new Response(JSON.stringify({ signed: true, grant: "dg_1", grant_until: "2026-10-16T12:01:30Z" }), { status: 200 }) : new Response(JSON.stringify({ error: "no" }), { status: s.signed });
    }
    const draft = /\/v1\/me\/agent-drafts\/([^/?]+)$/.exec(url);
    if (draft) {
      const d = s.drafts[decodeURIComponent(draft[1]!)];
      return d ? new Response(JSON.stringify({ id: draft[1], ...d }), { status: 200 }) : new Response("{}", { status: 404 });
    }
    return new Response("{}", { status: 404 });
  }) as typeof fetch;
}

/** Coach (t_acme) paused on an approval card `a1` for grant g_1, with a draft when named. */
async function world(s: Services, card: Record<string, unknown> = {}) {
  let clock = NOW;
  const agents = fakeAgents(() => clock);
  const tafs = memoryTafs();
  const taf: Taf = { id: COACH, name: "Coach", job: "setup", kiffAgent: "setup-acme", nameClaim: "c", created: "2026-10-15T09:00:00Z" };
  tafs.held.set(`t_acme#${COACH}`, taf);
  await agents.api.putAgent("t_acme", COACH, {} as never);
  agents.access.set("u_ana", { cards_manage: true });
  const run = await agents.api.startRun("t_acme", COACH, "Set up refunds", ANA);
  const stored = agents.runs.find((r) => r.id === run.id)!;
  agents.ask(stored, { type: "approval", id: "a1", grant: "g_1", ...card });
  agents.calls.length = 0;
  const coach = memoryCoach(() => clock);
  const call = async (method: string, path: string, body: unknown, headers: Record<string, string> = {}, query?: string) => {
    const event: HTTPEvent = {
      rawPath: path, ...(query ? { rawQueryString: query } : {}), requestContext: { http: { method } },
      headers: { authorization: "Bearer ana", "planque-device-signature": PROOF, ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
    };
    const r = await handle(event, {
      agents: agents.api, keys: memoryKeys(), accounts: memoryAccounts(), tafs, names: memoryNames(), slots: memorySlots(), claims: memoryClaims(),
      removals: memoryRemovals(), archived: memoryArchived(),
      push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
      apns: { send: async () => "sent" as const }, forward: async () => undefined,
      fetch: net(s), now: () => clock, coach, planqueBase: "https://planque.test", kiffBase: "https://api.test",
    });
    return { status: r.statusCode, body: JSON.parse(r.body) as Record<string, unknown> };
  };
  /** What Coach heard for the card, if anything. */
  const told = () => (stored.requirements ?? []).find((q) => (q.input as { id?: string }).id === "a1")?.result as { answer?: unknown } | undefined;
  return { agents, stored, call, told, coach, at: (d: Date) => { clock = d; } };
}

const ASK = `/v1/tafs/${COACH}/components/a1/approval`;

describe("an approval card: tafs cloud decides what needs signing", () => {
  it("a new connection: one message for the device's key, and planque's approve body as the phone must send it", async () => {
    const s = services();
    const w = await world(s);
    const r = await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant().request) });
    expect(r.status).toBe(200);
    expect(r.body).toMatchObject({
      outcome: "sign",
      ask: { step: "connect", message: `tafs.connect.v1|t_acme|g_1|${sha256(grant().request)}`, reason: "Connect Stripe to refunds-x", planque: { grant: "g_1", body: { digest: sha256(grant().request) } } },
    });
    expect((r.body.ask as { planque: { body: object } }).planque.body).not.toHaveProperty("kiff_profile");
    expect(w.told()).toBeUndefined();
    // planque was read as the phone: its proof forwarded, nothing signed here.
    expect(s.proofs).toEqual([PROOF]);
  });

  it("reads the grant only with the phone's proof, and says so without it", async () => {
    const s = services();
    const w = await world(s);
    const r = await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant().request) }, { "planque-device-signature": "" });
    expect(r.status).toBe(503);
    expect(w.told()).toBeUndefined();
  });

  it("a connection with a profile is signed only with KIFF's words for exactly that profile", async () => {
    const s = services();
    s.grants = [grant({}, { profile: PROFILE })];
    const w = await world(s);
    const digest = sha256(grant({}, { profile: PROFILE }).request);
    const unread = await w.call("POST", ASK, { grant: "g_1", shown_digest: digest });
    expect(unread.body).toEqual({ outcome: "read_again", note: WORDS.wordsUnread });
    const other = await w.call("POST", ASK, { grant: "g_1", shown_digest: digest, words: { profile_sha256: "0".repeat(64), words_sha256: "1".repeat(64) } });
    expect(other.body).toEqual({ outcome: "read_again", note: WORDS.wordsUnread });
    const words = { profile_sha256: sha256(PROFILE), words_sha256: "1".repeat(64) };
    const ok = await w.call("POST", ASK, { grant: "g_1", shown_digest: digest, words });
    expect(ok.body).toMatchObject({ outcome: "sign", ask: { step: "connect", planque: { body: { digest, kiff_profile: words } } } });
    expect(w.told()).toBeUndefined();
  });

  it("a card whose terms changed since it was shown is read again, and Coach hears nothing", async () => {
    const s = services();
    const w = await world(s);
    const r = await w.call("POST", ASK, { grant: "g_1", shown_digest: "old" });
    expect(r.body).toEqual({ outcome: "read_again", note: WORDS.outOfDate });
    expect(w.told()).toBeUndefined();
    expect(await w.coach.held.size).toBe(0);
  });

  it("a request whose digest isn't its hash is never signed: Coach hears it failed", async () => {
    const s = services();
    s.grants = [grant({ digest: "forged" })];
    const w = await world(s);
    const r = await w.call("POST", ASK, { grant: "g_1", shown_digest: "forged" });
    expect(r.body).toMatchObject({ outcome: "answered", answer: `failed: ${WORDS.digestMismatch}`, note: WORDS.digestMismatch });
    expect(w.told()).toEqual({ answer: `failed: ${WORDS.digestMismatch}`, words: `failed: ${WORDS.digestMismatch}` });
  });

  it("a connection planque failed, or none by that id, is told to Coach as failed with why", async () => {
    const s = services();
    s.grants = [grant({ status: "failed", reason: "the tool name is taken" })];
    const w = await world(s);
    const failed = await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant().request) });
    expect(failed.body).toMatchObject({ outcome: "answered", answer: "failed: This connection is failed: the tool name is taken" });
    const w2 = await world(services());
    const none = await w2.call("POST", ASK, { grant: "g_9" });
    expect(none.body).toMatchObject({ outcome: "answered", answer: `failed: ${WORDS.noGrant}`, note: WORDS.noGrant });
    expect(w2.told()).toMatchObject({ answer: `failed: ${WORDS.noGrant}` });
  });

  it("a connection already connected with no Card to confirm signs nothing: Coach hears why, never approved", async () => {
    const s = services();
    s.grants = [grant({ status: "connected" })];
    const w = await world(s);
    const r = await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant().request) });
    expect(r.body).toMatchObject({ outcome: "answered", answer: "failed: Stripe is already connected; nothing new to approve", note: WORDS.nothingToApprove("Stripe") });
    expect(w.told()).toMatchObject({ answer: "failed: Stripe is already connected; nothing new to approve" });
  });

  it("a Card KIFF already confirmed was approved earlier, with its own signature: already approved, never failed", async () => {
    const s = services();
    s.grants = [grant({ status: "connected" })];
    s.drafts.drf_1 = { status: "confirmed", version: "v3", agent_id: "refunds-x" };
    const w = await world(s, { draft: "drf_1" });
    const r = await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant().request), draft: "drf_1", shown_draft: "v3" });
    expect(r.body).toMatchObject({ outcome: "answered", answer: "already approved: Stripe is connected and its Card is confirmed", note: WORDS.alreadyApproved });
    expect(w.told()).toMatchObject({ answer: "already approved: Stripe is connected and its Card is confirmed" });
  });

  it("the Cards over a connected vendor: KIFF's draft at the version shown, one message to sign", async () => {
    const s = services();
    s.grants = [grant({ status: "connected" })];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x" };
    const w = await world(s, { draft: "drf_1" });
    const old = await w.call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v2" });
    expect(old.body).toEqual({ outcome: "read_again", note: WORDS.outOfDate });
    const r = await w.call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" });
    expect(r.body).toMatchObject({ outcome: "sign", ask: { step: "cards", message: "tafs.cards.v1|t_acme|drf_1|v3", reason: "Give refunds-x its Card", kiff: { draft: "drf_1", version: "v3" } } });
    expect(r.body.ask).not.toHaveProperty("planque");
    expect(w.told()).toBeUndefined();
  });

  it("a draft KIFF dropped, or has no more, is told as failed", async () => {
    const s = services();
    s.grants = [grant({ status: "connected" })];
    s.drafts.drf_1 = { status: "draft", version: "v3", expires_at: "2026-10-16T11:00:00Z" };
    const w = await world(s, { draft: "drf_1" });
    const expired = await w.call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" });
    expect(expired.body).toMatchObject({ outcome: "answered", answer: "failed: the Card draft is expired" });
    const w2 = await world(s, { draft: "drf_9" });
    const gone = await w2.call("POST", ASK, { grant: "g_1", draft: "drf_9", shown_draft: "v3" });
    expect(gone.body).toMatchObject({ outcome: "answered", answer: "failed: KIFF has no Card draft drf_9 on this account" });
  });

  it("refuses an ask that names no grant, or a card that isn't an approval of theirs", async () => {
    const s = services();
    const w = await world(s);
    expect((await w.call("POST", ASK, {})).status).toBe(400);
    expect((await w.call("POST", `/v1/tafs/${COACH}/components/nope/approval`, { grant: "g_1" })).status).toBe(404);
  });
});

describe("the report: Coach hears approved only after KIFF says the phone signed it and the service holds it", () => {
  async function asked(s: Services, card: Record<string, unknown> = {}, ask: Record<string, unknown> = { grant: "g_1", shown_digest: sha256(grant().request) }) {
    const w = await world(s, card);
    const r = await w.call("POST", ASK, ask);
    expect(r.body.outcome).toBe("sign");
    const id = (r.body.ask as { id: string }).id;
    const report = (body: unknown, headers: Record<string, string> = { "kiff-device-signature": "t=2026-10-16T12:00:30Z,v1=sig" }) =>
      w.call("POST", `${ASK}/${id}`, body, headers);
    return { ...w, id, report };
  }

  it("connected: the signed report is checked with KIFF as sent, planque says connected, and Coach hears approved", async () => {
    const s = services();
    const w = await asked(s);
    s.grants = [grant({ status: "connected" })];
    const r = await w.report({ result: "connected" });
    expect(r.status).toBe(200);
    expect(r.body).toMatchObject({ outcome: "answered", answer: "approved" });
    expect(w.told()).toMatchObject({ answer: "approved" });
    // KIFF was asked about exactly this request: its path and the hash of the body as sent.
    expect(s.checks).toEqual([{ method: "POST", uri: `${ASK}/${w.id}`, body_sha256: createHash("sha256").update(JSON.stringify({ result: "connected" })).digest("hex"), signature: "t=2026-10-16T12:00:30Z,v1=sig" }]);
    // The ask is spent: a second report finds nothing waiting.
    expect((await w.report({ result: "connected" })).status).toBe(404);
  });

  it("KIFF is asked about the public path exactly as the phone signed it: no prefix, the query kept, the device grant dropped", async () => {
    // tafs cloud is a function URL: `rawPath` is the public URL's path, with no stage or base
    // prefix, so the phone signs what tafs cloud sees. A query goes into the signed uri too.
    const s = services();
    const w = await asked(s);
    s.grants = [grant({ status: "connected" })];
    const r = await w.call("POST", `${ASK}/${w.id}`, { result: "connected" }, { "kiff-device-signature": "t=2026-10-16T12:00:30Z,v1=sig" }, "from=phone");
    expect(r.status).toBe(200);
    expect(r.body).toMatchObject({ answer: "approved" });
    expect(s.checks.at(-1)).toMatchObject({ method: "POST", uri: `${ASK}/${w.id}?from=phone` });
    // KIFF's answer also carried a one-minute device grant (the fake sends one): nothing of it
    // reaches the answer, and nothing is kept of it.
    expect(JSON.stringify(r.body)).not.toContain("dg_1");
    expect([...w.coach.held.values()].some((v) => JSON.stringify(v).includes("dg_1"))).toBe(false);
  });

  it("listed counts as connected: planque holds the sign-in and the vendor's tools", async () => {
    const s = services();
    const w = await asked(s);
    s.grants = [grant({ status: "listed" })];
    expect((await w.report({ result: "listed" })).body).toMatchObject({ answer: "approved" });
  });

  it("a report this phone didn't sign is refused, and Coach hears nothing", async () => {
    const s = services();
    const w = await asked(s);
    s.grants = [grant({ status: "connected" })];
    const unsigned = await w.report({ result: "connected" }, {});
    expect(unsigned.status).toBe(403);
    s.signed = 403;
    const forged = await w.report({ result: "connected" });
    expect(forged.status).toBe(403);
    s.signed = 409;
    expect((await w.report({ result: "connected" })).status).toBe(409);
    expect(w.told()).toBeUndefined();
    // Still waiting: a signed report after KIFF is back goes through.
    s.signed = true;
    expect((await w.report({ result: "connected" })).body).toMatchObject({ answer: "approved" });
  });

  it("the service is the truth: a reported success planque doesn't hold is failed, in tafs cloud's words", async () => {
    const s = services();
    const w = await asked(s);
    s.grants = [grant({ status: "signed_in" })];
    const r = await w.report({ result: "connected" });
    expect(r.body).toMatchObject({ answer: `failed: ${WORDS.notConnected("Stripe", "signed_in")}`, note: WORDS.notConnected("Stripe", "signed_in") });
    expect(w.told()).toMatchObject({ answer: `failed: ${WORDS.notConnected("Stripe", "signed_in")}` });
  });

  it("each failure the phone can report has tafs cloud's words, for Coach and for the card", async () => {
    const cases: Array<[Record<string, unknown>, string]> = [
      [{ result: "sign_in_closed" }, WORDS.signInClosed],
      [{ result: "no_code" }, WORDS.noCode("Stripe")],
      [{ result: "not_connected", status: "approved" }, WORDS.cameBack("approved")],
      [{ result: "failed", why: "planque answered 502" }, WORDS.failed("planque answered 502")],
    ];
    for (const [body, why] of cases) {
      const s = services();
      const w = await asked(s);
      s.grants = [grant({ status: "approved" })];
      const r = await w.report(body);
      expect(r.body).toMatchObject({ outcome: "answered", answer: `failed: ${why}`, note: why });
      expect(w.told()).toMatchObject({ answer: `failed: ${why}` });
    }
  });

  it("a reported failure the service contradicts is approved: what planque holds is what happened", async () => {
    const s = services();
    const w = await asked(s);
    s.grants = [grant({ status: "connected" })];
    expect((await w.report({ result: "sign_in_closed" })).body).toMatchObject({ answer: "approved" });
  });

  it("the Cards: approved once KIFF holds the draft confirmed, failed with why otherwise", async () => {
    const s = services();
    s.grants = [grant({ status: "connected" })];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x" };
    const w = await asked(s, { draft: "drf_1" }, { grant: "g_1", draft: "drf_1", shown_draft: "v3" });
    const notYet = await w.report({ result: "failed", why: "a Card moved on" });
    expect(notYet.body).toMatchObject({ answer: `failed: ${WORDS.cardsNotIssued("a Card moved on")}`, note: WORDS.cardsNotIssued("a Card moved on") });
    const again = await asked(s, { draft: "drf_1" }, { grant: "g_1", draft: "drf_1", shown_draft: "v3" });
    s.drafts.drf_1 = { status: "confirmed", version: "v3" };
    expect((await again.report({ result: "confirmed" })).body).toMatchObject({ answer: "approved" });
    expect(again.told()).toMatchObject({ answer: "approved" });
  });

  it("an ask lapses: a report after its time finds nothing waiting", async () => {
    const s = services();
    const w = await asked(s);
    s.grants = [grant({ status: "connected" })];
    w.at(new Date(NOW.getTime() + ASK_KEPT_MS + 1000));
    expect((await w.report({ result: "connected" })).status).toBe(404);
    expect(w.told()).toBeUndefined();
  });

  it("refuses a report that says nothing tafs cloud takes", async () => {
    const s = services();
    const w = await asked(s);
    expect((await w.report({ result: "maybe" })).status).toBe(400);
    expect((await w.report({})).status).toBe(400);
  });
});
