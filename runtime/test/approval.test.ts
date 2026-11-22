import { wordsIn } from "../src/cloud/words/index.js";
import { createHash } from "node:crypto";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AGREE_FIRST, askedInSession, checkDraft, type DecideContext, connectedKey, SHOW_NOW, approvalWords, ASK_KEPT_MS, COUNT_NEVER_A_RULE, DRAW_AGAIN, KEEP_THE_REST, READS_A_DAY, REDRAFT_CODES, REFUSAL_CLOSED, splitApproval, WAITING_DRAFT_KEPT_MS, waitingDraftKey, WORDS , type ApprovalGrant } from "../src/cloud/approval.js";
import { unpinnedConnection } from "../src/cloud/rules.js";
import { approvalAnswerWords, checkedOnReadFor, handle, settledApprovalAnswer, type HTTPEvent, type Taf } from "../src/cloud/handler.js";
import { sha256 } from "../src/cloud/jcs.js";
import { contract } from "./contract.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryCoach, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

/**
 * An approval card's outcome is tafs cloud's to decide (kiff/tafs#413): the phone says
 * Approve was tapped, tafs cloud reads planque's grant and KIFF's draft and says what to
 * sign, and answers Coach itself when nothing is. The report is checked with KIFF before
 * Coach hears "approved".
 */

const ME = { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] };
/** Ana's colleague, who may read but not manage Cards. */
const CY = { tenant_id: "t_acme", subject: "u_cy", api_key: false, capabilities: ["read"] };
const BO = { tenant_id: "t_other", subject: "u_bo", api_key: false, capabilities: ["read", "cards.manage"] };
const NOW = new Date("2026-10-16T12:00:00Z");
const COACH = "taf-cccc0000000000000000";
const ANA = { requester: { kind: "member" as const, id: "u_ana" } };
const PROOF = "t=2026-10-16T12:00:00Z,v1=synthetic,d=dev_1";
const PROFILE = { kind: "kiff.profile.v1", tools: { refund: { unit: "cents" } } };

/** A draft's Cards where a test is about something else: KIFF never shows a draft without them that Approve signs. */
const ONE_CARD = [{ id: "c1", subject: "refunds-x", grants: [], aggregates: [] }];

/** A grant as planque sends it, its digest the hash of its request, as planque makes it. */
function grant(over: Record<string, unknown> = {}, request: Record<string, unknown> = {}) {
  const req = { agent: "refunds-x", server: "mcp.stripe.com", tools: [{ tool: "refund" }], ...request };
  return { id: "g_1", status: "pending", vendor: "Stripe", digest: sha256(req), request: req, events: [], ...over };
}

interface Services {
  grants: unknown[];
  drafts: Record<string, { status: string; version?: string; agent_id?: string; expires_at?: string; cards?: unknown[]; confirm_key?: string; label?: string }>;
  /** What KIFF answers the device-signature check: signed, or a status. */
  signed: true | number;
  /** KIFF's code with a refused signature (`error`), as its 400s and 403s send it. */
  signedCode?: string;
  /** Which key KIFF says signed, and the window it named (kiff/tafs#494); none, as a KIFF from before. */
  signedKey?: string;
  signedWindow?: { id: string; open: boolean };
  /** Every signature check KIFF was asked, as sent. */
  checks: Array<Record<string, unknown>>;
  proofs: string[];
  /** The account's Cards, as KIFF lists them (GET /v1/me/mandates); a status when the list fails. */
  mandates: unknown[] | number;
  /** The account's agents, as KIFF lists them (GET /v1/me/agents); none, as a test that doesn't say. */
  agents?: Array<{ id: string; archived_at?: string }>;
  /** KIFF's draft read failing (a status), as when it's slow past the chat's read. */
  draftDown?: number;
}

function services(): Services {
  return { grants: [grant()], drafts: {}, signed: true, checks: [], proofs: [], mandates: [] };
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
    // ana-ro: Ana on a session whose role can't manage Cards (view only).
    const me = token === "ana" ? ME : token === "ana-ro" ? { ...ME, capabilities: ["read"] } : token === "bo" ? BO : token === "cy" ? CY : undefined;
    if (!me) return new Response("{}", { status: 401 });
    if (url.endsWith("/v1/me/access")) return new Response(JSON.stringify({ agents: { "setup-acme": { use: true, oversee: false, manage: true } } }), { status: 200 });
    if (url.endsWith("/v1/me")) return new Response(JSON.stringify(me), { status: 200 });
    if (url.endsWith("/v1/me/device-signature")) {
      s.checks.push(JSON.parse(String(init?.body)) as Record<string, unknown>);
      return s.signed === true ? new Response(JSON.stringify({ signed: true, grant: "dg_1", grant_until: "2026-10-16T12:01:30Z", ...(s.signedKey ? { key: s.signedKey } : {}), ...(s.signedWindow ? { window: s.signedWindow } : {}) }), { status: 200 }) : new Response(JSON.stringify({ error: s.signedCode ?? "no" }), { status: s.signed });
    }
    if (url.endsWith("/v1/me/agents") && s.agents) return new Response(JSON.stringify({ agents: s.agents }), { status: 200 });
    if (url.endsWith("/v1/me/mandates")) {
      return typeof s.mandates === "number" ? new Response("{}", { status: s.mandates }) : new Response(JSON.stringify({ mandates: s.mandates }), { status: 200 });
    }
    const draft = /\/v1\/me\/agent-drafts\/([^/?]+)$/.exec(url);
    if (draft) {
      if (s.draftDown) return new Response("{}", { status: s.draftDown });
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
  // The taf being set up, whose agent the grant is for: the phone's prompt names it.
  tafs.held.set("t_acme#taf-rrrr0000000000000000", { id: "taf-rrrr0000000000000000", name: "Refunds", job: "refunds", kiffAgent: "refunds-x", nameClaim: "r", created: "2026-10-15T09:00:00Z" });
  await agents.api.putAgent("t_acme", COACH, {} as never);
  agents.access.set("u_ana", { cards_manage: true });
  const run = await agents.api.startRun("t_acme", COACH, "Set up refunds", ANA);
  const stored = agents.runs.find((r) => r.id === run.id)!;
  agents.ask(stored, { type: "approval", id: "a1", grant: "g_1", ...card });
  // Bo, on another account, has a Coach by the same id paused on a card by the same id.
  tafs.held.set(`t_other#${COACH}`, { ...taf });
  await agents.api.putAgent("t_other", COACH, {} as never);
  agents.access.set("u_bo", { cards_manage: true });
  const bosRun = await agents.api.startRun("t_other", COACH, "Set up refunds", { requester: { kind: "member", id: "u_bo" } });
  const bosStored = agents.runs.find((r) => r.id === bosRun.id)!;
  agents.ask(bosStored, { type: "approval", id: "a1", grant: "g_1", ...card });
  agents.calls.length = 0;
  const coach = memoryCoach(() => clock);
  const call = async (method: string, path: string, body: unknown, headers: Record<string, string> = {}, query?: string, token = "ana") => {
    const event: HTTPEvent = {
      rawPath: path, ...(query ? { rawQueryString: query } : {}), requestContext: { http: { method } },
      headers: { authorization: `Bearer ${token}`, "planque-device-signature": PROOF, ...headers },
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
  const boTold = () => (bosStored.requirements ?? []).find((q) => (q.input as { id?: string }).id === "a1")?.result as { answer?: unknown } | undefined;
  return { agents, tafs, stored, call, told, boTold, coach, at: (d: Date) => { clock = d; } };
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
      ask: { step: "connect", message: `tafs.connect.v1|t_acme|g_1|${sha256(grant().request)}`, reason: "Connect Stripe to Refunds", planque: { grant: "g_1", body: { digest: sha256(grant().request) } } },
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

  // Gap 14: the grant's tools that send outside the company, as its profile says, are noted for its taf's agent
  // as the owner signs, so Coach's result can be checked against them; a card read again notes nothing.
  it("notes the tools a connection's profile says send outside the company, as the owner signs", async () => {
    const s = services();
    const sends = { v: "kiff.profile.v1", operations: [{ tool: "send_email", kind: "write", effect: "sends", resource: { noun: "email", outside: true } }, { tool: "list", kind: "read" }] };
    s.grants = [grant({}, { profile: sends })];
    const w = await world(s);
    const digest = sha256(grant({}, { profile: sends }).request);
    expect((await w.call("POST", ASK, { grant: "g_1", shown_digest: "old" })).body).toEqual({ outcome: "read_again", note: WORDS.outOfDate });
    expect(await w.coach.get("t_acme#sends-outside#refunds-x")).toBeUndefined();
    const words = { profile_sha256: sha256(sends), words_sha256: "1".repeat(64) };
    expect((await w.call("POST", ASK, { grant: "g_1", shown_digest: digest, words })).body).toMatchObject({ outcome: "sign", ask: { step: "connect" } });
    expect(await w.coach.get("t_acme#sends-outside#refunds-x")).toMatchObject({ grants: { g_1: ["send_email"] } });
  });

  // Eval 43975057022, Replay run 2: an API-key connection carries no profile. Its writes planque marked with text fields
  // (a draft's body) are noted as sending; a write with none (a status) and a read aren't.
  it("notes an API-key connection's writes with text fields as sending, as the owner signs", async () => {
    const s = services();
    const tools = [{ tool: "createDraft", name: "create_draft_k3", title: "Create a draft", text_fields: ["body"] },
      { tool: "setStatus", name: "set_status_k3", title: "Set a status" },
      { tool: "listMail", name: "list_mail_k3", title: "List mail", read_only: true }];
    s.grants = [grant({}, { api: { url: "https://mail.example/openapi.json" }, tools })];
    const w = await world(s);
    const digest = sha256(grant({}, { api: { url: "https://mail.example/openapi.json" }, tools }).request);
    expect((await w.call("POST", ASK, { grant: "g_1", shown_digest: digest })).body).toMatchObject({ outcome: "sign", ask: { step: "connect" } });
    expect(await w.coach.get("t_acme#sends-outside#refunds-x")).toMatchObject({ grants: { g_1: ["create_draft_k3"] } });
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
    // Coach hears the status and why; the person reads plain words, never "This connection is failed".
    expect(failed.body).toMatchObject({ outcome: "answered", answer: "failed: This connection is failed: the tool name is taken", note: "This Stripe connection can't be approved any more, so nothing was signed." });
    for (const lang of ["en", "de", "fr", "es", "it", "pt", "nl", "pl"] as const) {
      const L = approvalWords(lang);
      for (const line of [L.notApprovable("Gmail", "listed"), L.notApprovable("Gmail", "failed"), L.notApprovable("Gmail", "disconnected")]) {
        expect(line).toContain("Gmail");
        expect(line).not.toMatch(/listed|failed|disconnected|signed_in|connection is/);
      }
    }
    // Signed in already: the line says so, and the way forward to another account of the same app.
    expect(WORDS.notApprovable("Gmail", "listed")).toBe("You're already signed in to Gmail on this connection, so there's nothing to sign in to here. To use another Gmail account, ask Coach to connect it.");
    expect(approvalWords("es").notApprovable("Gmail", "signed_in")).toBe("Ya has iniciado sesión en Gmail en esta conexión, así que aquí no hace falta iniciar sesión. Para usar otra cuenta de Gmail, pide a Coach que la conecte.");
    expect(approvalWords("pt").notApprovable("Gmail", "listed")).toBe("Já tem sessão iniciada em Gmail nesta ligação, por isso não é preciso iniciar sessão aqui. Para usar outra conta Gmail, peça ao Coach para a ligar.");
    for (const lang of ["en", "de", "fr", "es", "it", "pt", "nl", "pl"] as const) {
      const line = approvalWords(lang).notApprovable("Outlook", "listed");
      expect(line.split("Outlook").length - 1).toBe(2); // the app's name, never a hard-coded one, in both halves
      expect(line).toContain("Coach");
      expect(line).not.toMatch(/nada que iniciar|nada para iniciar/);
    }
    const w2 = await world(services());
    const none = await w2.call("POST", ASK, { grant: "g_9" });
    expect(none.body).toMatchObject({ outcome: "answered", answer: `failed: ${WORDS.noGrant}`, note: WORDS.noGrant });
    expect(w2.told()).toMatchObject({ answer: `failed: ${WORDS.noGrant}` });
  });

  // The owner's sign-in card read "it didn't go through: This connection is listed.": the phone worded planque's status itself.
  // A sign-in card asks tafs cloud too when its connection takes no approval now, and draws tafs cloud's words.
  it("a sign-in card whose connection takes no approval now is answered in the person's words; anything to sign stays the phone's", async () => {
    const s = services();
    s.grants = [grant({ status: "failed", reason: "the tool name is taken" })];
    const w = await world(s, { type: "sign_in" });
    const failed = await w.call("POST", ASK, { grant: "g_1" });
    expect(failed.body).toMatchObject({ outcome: "answered", note: "This Stripe connection can't be approved any more, so nothing was signed." });
    expect(w.told()).toMatchObject({ answer: "failed: This connection is failed: the tool name is taken" });
    // Signed in already, with nothing to confirm: Coach hears "signed in", the card's own answer.
    const s2 = services();
    s2.grants = [grant({ status: "listed" })];
    const w2 = await world(s2, { type: "sign_in" });
    const listed = await w2.call("POST", ASK, { grant: "g_1" });
    expect(listed.body).toMatchObject({ outcome: "answered", answer: "signed in" });
    expect(String(listed.body.note)).not.toMatch(/connection is|listed/);
    expect(w2.told()).toMatchObject({ answer: "signed in" });
    // A connection still to sign in: tafs cloud decides nothing here, and Coach hears nothing; the phone signs in as before.
    const w3 = await world(services(), { type: "sign_in" });
    const pending = await w3.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant().request) });
    expect(pending.body).toEqual({ outcome: "sign_in" });
    expect(w3.told()).toBeUndefined();
  });

  it("a connection already connected with no Card to confirm signs nothing: Coach hears why, never approved", async () => {
    const s = services();
    s.grants = [grant({ status: "connected" })];
    const w = await world(s);
    const r = await w.call("POST", ASK, { grant: "g_1", shown_digest: "an older request's digest" });
    expect(r.body).toMatchObject({ outcome: "answered", answer: "failed: Stripe is already connected; nothing new to approve", note: WORDS.nothingToApprove("Stripe") });
    expect(w.told()).toMatchObject({ answer: "failed: Stripe is already connected; nothing new to approve" });
  });

  it("a connection signed in already (its report lost) is already approved on a new tap, never failed", async () => {
    const s = services();
    s.grants = [grant({ status: "listed" })];
    const w = await world(s);
    const r = await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant().request) });
    expect(r.body).toMatchObject({ outcome: "answered", answer: "already approved: Stripe is signed in; its tools wait for approval", note: WORDS.alreadyApproved });
    expect(w.told()).toMatchObject({ answer: "already approved: Stripe is signed in; its tools wait for approval" });
  });

  it("a connection asking for more tools again loses its connected note, so its card shows what it asks", async () => {
    const s = services();
    s.grants = [grant({ status: "connected" })];
    const w = await world(s);
    await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant().request) });
    expect(await w.coach.get(connectedKey("t_acme", "g_1"))).toEqual({ vendor: "Stripe", read_only: false });
    // Tools added once connected: planque shows it pending again, and the card is no longer marked connected.
    s.grants = [grant({ status: "pending" })];
    await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant().request) });
    expect(await w.coach.get(connectedKey("t_acme", "g_1"))).toBeUndefined();
  });

  it("a connection connected as the card showed it (its report lost) is already approved on a new tap, never failed", async () => {
    const s = services();
    s.grants = [grant({ status: "connected" })];
    const w = await world(s);
    const r = await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant().request) });
    expect(r.body).toMatchObject({ outcome: "answered", answer: "already approved: Stripe is connected", note: WORDS.alreadyApproved });
    expect(w.told()).toMatchObject({ answer: "already approved: Stripe is connected" });
    // tafs cloud notes it, so a later card naming this grant says only that it's connected (docs/secure-connection.md).
    expect(await w.coach.get(connectedKey("t_acme", "g_1"))).toEqual({ vendor: "Stripe", read_only: false });
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

  it("the phone's signing prompt names the app and the taf in the person's language, never a server slug or agent id", async () => {
    // As the French iPad showed it: planque's vendor was the server's slug and the agent its id.
    const s = services();
    s.grants = [grant({ vendor: "stripe-mcp" })];
    const w = await world(s);
    const fr = await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant().request) }, { "accept-language": "fr-FR" });
    expect((fr.body.ask as { reason: string }).reason).toBe("Connecter Stripe à Refunds");
    // A taf tafs cloud doesn't hold by that agent: the app alone, never the agent id.
    s.grants = [grant({}, { agent: "taf-x9" })];
    const unnamed = await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant({}, { agent: "taf-x9" }).request) }, { "accept-language": "fr-FR" });
    expect((unnamed.body.ask as { reason: string }).reason).toBe("Connecter Stripe");
    expect(JSON.stringify(unnamed.body)).not.toContain("taf-x9\"");
  });

  it("names a managed app by the app, never by the sign-in partner whose platform its server is", async () => {
    // 14 Nov: the owner's fingerprint prompt for Gmail named the sign-in partner, from the grant's server host.
    const s = services();
    const managed = { kind: "connector", server: "https://backend.partner-platform.example/api/v3", tools: [] };
    s.grants = [grant({ vendor: "Gmail" }, managed)];
    const w = await world(s);
    const r = await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant({}, managed).request) });
    expect((r.body.ask as { reason: string }).reason).toBe("Connect Gmail to Refunds");
    expect(JSON.stringify(r.body.ask)).not.toMatch(/partner-platform/i);
  });

  it("a connect while Coach sets the taf up, before it's listed: the prompt names it as planque keeps it, and a Card by the draft's label", async () => {
    const s = services();
    const setup = { agent: "remb-x2" };
    s.grants = [grant({ vendor: "stripe-mcp", agent_name: "Remboursements du lundi" }, setup)];
    const w = await world(s);
    const fr = await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant({}, setup).request) }, { "accept-language": "fr-FR" });
    expect((fr.body.ask as { reason: string }).reason).toBe("Connecter Stripe à Remboursements du lundi");
    // Its Cards, the vendor connected: the draft's label names it, and with none, planque's agent_name does.
    s.grants = [grant({ status: "connected", agent_name: "Remboursements du lundi" }, setup)];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "remb-x2", label: "Remboursements", cards: ONE_CARD };
    const card = await w.call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" });
    expect((card.body.ask as { reason: string }).reason).toBe("Give Remboursements its Card");
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "remb-x2", cards: ONE_CARD };
    const unlabelled = await w.call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" });
    expect((unlabelled.body.ask as { reason: string }).reason).toBe("Give Remboursements du lundi its Card");
    expect(JSON.stringify([fr.body, card.body, unlabelled.body])).not.toContain("remb-x2\"");
  });

  // 14 Nov: one fingerprint signed a removed taf's Gmail connection with a new taf's Card, and the new taf ran as the
  // removed taf's agent. A grant asked for a removed taf (its agent archived in KIFF, or the other form of an archived
  // agent's name) is never approved again, and a Card drafted for another agent than the grant's is never signed.
  it("refuses to approve over a removed taf's connection, or a Card for another taf than the grant's, signing nothing", async () => {
    const s = services();
    s.agents = [{ id: "news-digest", archived_at: "2026-11-13T20:26:00Z" }, { id: "refunds-x" }];
    s.grants = [grant({ status: "connected" }, { agent: "news-digest-k3f9x2ab", created_at: "2026-11-13T20:10:00Z" })];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "news-digest-k3f9x2ab", cards: ONE_CARD };
    const removed = await (await world(s, { draft: "drf_1" })).call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" });
    expect(removed.body).toMatchObject({ outcome: "answered", note: wordsIn("en").approval_removed_tafs_connection("Stripe") });
    expect(String(removed.body.answer)).toContain("asked for a taf that was removed");
    expect(removed.body).not.toHaveProperty("ask");

    // The Card is for another agent than the grant's: refused before anything is signed.
    const t = services();
    t.grants = [grant({ status: "connected" })];
    t.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "another-taf-a1b2c3d4", cards: ONE_CARD };
    const other = await (await world(t, { draft: "drf_1" })).call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" });
    expect(other.body).toMatchObject({ outcome: "answered", note: wordsIn("en").approval_other_tafs_connection("Stripe") });
    expect(other.body).not.toHaveProperty("ask");

    // A taf re-made under the removed one's name: its new agent's connection, asked after the archive day, is its own.
    const r = services();
    r.agents = [{ id: "news-digest", archived_at: "2026-11-13T20:26:00Z" }];
    r.grants = [grant({ status: "connected" }, { agent: "news-digest-a1b2c3d4", created_at: "2026-11-14T09:00:00Z" })];
    r.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "news-digest-a1b2c3d4", cards: ONE_CARD };
    expect((await (await world(r, { draft: "drf_1" })).call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" })).body).toMatchObject({ outcome: "sign", ask: { step: "cards" } });

    // The same day: a taf removed at 20:26 and made again at 22:00 is its own, by the full times.
    const d = services();
    d.agents = [{ id: "news-digest", archived_at: "2026-11-13T20:26:00Z" }];
    d.grants = [grant({ status: "connected" }, { agent: "news-digest-a1b2c3d4", created_at: "2026-11-13T22:00:00Z" })];
    d.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "news-digest-a1b2c3d4", cards: ONE_CARD };
    expect((await (await world(d, { draft: "drf_1" })).call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" })).body).toMatchObject({ outcome: "sign", ask: { step: "cards" } });

    // A live taf on the other form of an archived name is its own: approved as before.
    const u = services();
    u.agents = [{ id: "refunds", archived_at: "2026-11-13T20:26:00Z" }];
    u.grants = [grant({ status: "connected" })];
    u.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: ONE_CARD };
    expect((await (await world(u, { draft: "drf_1" })).call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" })).body).toMatchObject({ outcome: "sign", ask: { step: "cards" } });
  });

  it("the Cards over a connected vendor: KIFF's draft at the version shown, one message to sign", async () => {
    const s = services();
    s.grants = [grant({ status: "connected" })];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: ONE_CARD };
    const w = await world(s, { draft: "drf_1" });
    const old = await w.call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v2" });
    expect(old.body).toEqual({ outcome: "read_again", note: WORDS.outOfDate });
    const r = await w.call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" });
    expect(r.body).toMatchObject({ outcome: "sign", ask: { step: "cards", message: "tafs.cards.v1|t_acme|drf_1|v3", reason: "Give Refunds its Card", kiff: { draft: "drf_1", version: "v3" } } });
    expect(r.body.ask).not.toHaveProperty("planque");
    expect(w.told()).toBeUndefined();
  });

  // Gap 14: the Card's approval notes the grant's sending tools again, as its profile says now, so a connection
  // that changed since it was connected counts as it is when the taf's Card is approved.
  it("notes the connection's sending tools again as its Card is approved, as its profile says now", async () => {
    const s = services();
    const sends = { v: "kiff.profile.v1", operations: [{ tool: "reply", kind: "write", effect: "sends", resource: { noun: "ticket", outside: true } }] };
    s.grants = [grant({ status: "connected" }, { profile: sends })];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: ONE_CARD };
    const w = await world(s, { draft: "drf_1" });
    await w.coach.put("t_acme#sends-outside#refunds-x", { grants: { g_1: [], g_other: ["post"] }, at: "x" }, new Date("2027-01-01"));
    expect((await w.call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" })).body).toMatchObject({ outcome: "sign", ask: { step: "cards" } });
    expect(await w.coach.get("t_acme#sends-outside#refunds-x")).toMatchObject({ grants: { g_1: ["reply"], g_other: ["post"] } });
  });

  // Eval 42781271564: Setup gave Refunds get_charge and read_charge, and drafted its Card over read_charge
  // alone; KIFF refused every get_charge, and the taf asked its owner for a Card it shouldn't need.
  it("a draft that leaves one of the taf's tools uncovered, with no Card in force covering it, isn't signed", async () => {
    const s = services();
    const tools = [{ tool: "create_refund", name: "refund_k3", title: "Refund a payment" }, { tool: "stripe_api_read", name: "get_charge", title: "Read a charge", read_only: true },
      { tool: "stripe_api_read", name: "read_charge", title: "Check a charge", read_only: true }, { tool: "stripe_api_details", name: "fields_ab", read_only: true, setup_reads: true },
      // Listed with no KIFF name: left out, never compared by the vendor's own name, which no Card covers.
      { tool: "vendor_only_name", title: "A tool with no KIFF name" }];
    s.grants = [grant({ status: "connected" }, { tools })];
    const card = { id: "c1", subject: "refunds-x", grants: [{ action: "refund_k3" }, { action: " READ_CHARGE " }], on_exceed: "owner", aggregates: [{ quantity: "count", limit: 20, window: "calendar_day" }] };
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: [card] };
    const ask = async () => (await (await world(s, { draft: "drf_1" })).call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" })).body;
    const r = await ask();
    expect(r).toMatchObject({ outcome: "answered", note: WORDS.uncovered("Read a charge") });
    expect(String(r.answer)).toMatch(/^failed: no Card covers "Read a charge".*no_mandate_issued/);
    // A Card in force covers it, the agent's own: the draft revises only the Cards it names, so it's signed.
    s.mandates = [{ id: "c0", subject: "refunds-x", active: true, grants: [{ action: "get_charge" }] }];
    expect(await ask()).toMatchObject({ outcome: "sign" });
    // Another agent's Card, or one no longer in force, covers nothing of this taf's.
    s.mandates = [{ id: "c0", subject: "other-y", active: true, grants: [{ action: "get_charge" }] }, { id: "c9", subject: "refunds-x", active: false, grants: [{ action: "get_charge" }] }];
    expect(await ask()).toMatchObject({ outcome: "answered", note: WORDS.uncovered("Read a charge") });
    // The draft covering it: signed. Setup's own read (fields_ab) is never asked for, nor the tool with no KIFF name.
    s.mandates = [];
    s.drafts.drf_1.cards = [{ ...card, grants: [...card.grants, { action: "get_charge" }] }];
    expect(await ask()).toMatchObject({ outcome: "sign" });
    // The Cards in force can't be read: read again, never signed on trust.
    s.mandates = 503;
    expect(await ask()).toEqual({ outcome: "read_again", note: WORDS.cardsUnread });
  });

  // Eval 43657637947: the taf held a read from a connection the draft didn't touch, which no Card covered; KIFF refused
  // it, and the session's latch then stopped the refund KIFF would have allowed. Every connected app of the taf is checked.
  it("a draft that leaves a tool of the taf's other connected app uncovered, with no Card in force covering it, isn't signed", async () => {
    const s = services();
    const refunds = grant({ status: "connected" }, { tools: [{ tool: "create_refund", name: "refund_k3", title: "Refund a payment" }] });
    const reads = (over: Record<string, unknown> = {}, request: Record<string, unknown> = {}) => grant({ id: "g_2", status: "connected", vendor: "Stripe reads", ...over },
      { tools: [{ tool: "stripe_api_read", name: "get_charge", title: "Read a charge", read_only: true }, { tool: "stripe_api_details", name: "fields_ab", read_only: true, setup_reads: true }], ...request });
    s.grants = [refunds, reads()];
    const card = { id: "c1", subject: "refunds-x", grants: [{ action: "refund_k3" }], on_exceed: "owner", aggregates: [{ quantity: "count", limit: 20, window: "calendar_day" }] };
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: [card] };
    const ask = async () => (await (await world(s, { draft: "drf_1" })).call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" })).body;
    const r = await ask();
    expect(r).toMatchObject({ outcome: "answered", note: WORDS.uncovered("Read a charge") });
    expect(String(r.answer)).toMatch(/^failed: no Card covers "Read a charge", which the taf's Stripe reads connection gives it.*no_mandate_issued/);
    // The reads' Card's one count, as draft-the-card names it: KIFF's, never the owner's.
    expect(String(r.answer)).toContain(`the reads' Card, which carries KIFF's count of ${READS_A_DAY.toLocaleString("en")} a day, not the owner's`);
    // The draft covering it, or a Card of the agent's in force: signed. Setup's own read (fields_ab) is never asked for.
    s.drafts.drf_1.cards = [{ ...card, grants: [...card.grants, { action: " GET_CHARGE " }] }];
    expect(await ask()).toMatchObject({ outcome: "sign" });
    s.drafts.drf_1.cards = [card];
    s.mandates = [{ id: "c0", subject: "refunds-x", active: true, grants: [{ action: "get_charge" }] }];
    expect(await ask()).toMatchObject({ outcome: "sign" });
    s.mandates = [];
    // Another taf's connection, a removed one, or one whose tools still wait for their own approval: not this check's.
    for (const other of [reads({}, { agent: "chargebacks-y" }), reads({ status: "disconnected" }), reads({ status: "pending" })]) {
      s.grants = [refunds, other];
      expect((await ask()).note).not.toBe(WORDS.uncovered("Read a charge"));
    }
  });

  // Eval 43666453786: a held per-refund Card (50, owner) also got a day's total, beside the refused day's total (200,
  // refuse), so past the day's total the refund waited for the owner where the rules said refuse. One day's total per tool.
  it("a draft that counts a tool's amount over a day twice isn't signed; different totals on the same tool are", async () => {
    const s = services();
    const tools = [{ tool: "refund_charge", name: "refund_charge_k3", title: "Refund a charge", amount_argument: "amount" }];
    s.grants = [grant({ status: "connected" }, { tools })];
    const day = (limit: number, extra: Record<string, unknown> = {}) => ({ quantity: "sum(amount)", limit, window: "calendar_day", ...extra });
    const each = (aggregates: unknown[]) => ({ id: "each", subject: "refunds-x", on_exceed: "owner", grants: [{ action: "refund_charge_k3", per_action_max: [{ quantity: "sum(amount)", max: 5000 }] }], aggregates });
    const daily = (aggregates: unknown[], on_exceed = "refuse") => ({ id: "daily", subject: "refunds-x", on_exceed, grants: [{ action: " REFUND_CHARGE_K3 " }], aggregates });
    const ask = async () => (await (await world(s, { draft: "drf_1" })).call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" })).body;
    const draft = (cards: unknown[]) => { s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards }; };
    // 1. Held per refund, refused past the day's total: signed.
    draft([each([]), daily([day(20_000)])]);
    expect(await ask()).toMatchObject({ outcome: "sign" });
    // 2. The same, plus a day's total on the held Card: not signed, and Coach hears both totals and what each does.
    draft([each([day(5000)]), daily([day(20_000)])]);
    const r = await ask();
    expect(r).toMatchObject({ outcome: "answered", note: WORDS.dayTotalTwice("Refund a charge") });
    expect(String(r.answer)).toContain(`failed: the draft counts "Refund a charge"'s amount over a day on two Cards: 5000 on one whose on_exceed is "owner", and 20000 on one whose on_exceed is "refuse"`);
    // 3. Two day totals on the same amount, both refused, even on one Card: not signed.
    draft([daily([day(20_000), day(30_000)])]);
    expect((await ask()).note).toBe(WORDS.dayTotalTwice("Refund a charge"));
    // 4. An amount over a day beside a count over a day: not twice, but a count on a write with an amount limit isn't
    // signed either (count_on_amount, below).
    draft([daily([day(20_000), { quantity: "count", limit: 10, window: "calendar_day" }])]);
    expect((await ask()).note).toBe(WORDS.countOnAmount("Refund a charge"));
    // 5. An amount over a day beside an hour's, or a total for each item: different totals, signed.
    draft([daily([day(20_000), { ...day(5000), window: "rolling_1h" }, day(1000, { per: "charge" })])]);
    expect(await ask()).toMatchObject({ outcome: "sign" });
  });

  // Evals 10 to 12: the refund Card also counted refunds, 1,000 a day held for the owner, though the rules set only each
  // refund's cap and the day's total, so 1,001 one-cent refunds under the cap would wait for the owner. Coach's words
  // alone didn't hold. A write with an amount limit carries no count of its calls.
  it("a draft that counts a write's calls beside its amount limits isn't signed; a count of 0, the owner's per item, or on a read is", async () => {
    const s = services();
    const tools = [{ tool: "refund_charge", name: "refund_charge_k3", title: "Refund a charge", amount_argument: "amount", resource_argument: "charge" },
      { tool: "send_reply", name: "send_reply_k3", title: "Send a reply" },
      { tool: "get_charge", name: "get_charge_k3", title: "Read a charge", read_only: true }];
    s.grants = [grant({ status: "connected" }, { tools })];
    const day = (limit: number) => ({ quantity: "sum(amount)", limit, window: "calendar_day" });
    const count = (limit: number, extra: Record<string, unknown> = {}) => ({ quantity: "count", limit, window: "calendar_day", ...extra });
    const each = (aggregates: unknown[]) => ({ id: "each", subject: "refunds-x", on_exceed: "owner", grants: [{ action: "refund_charge_k3", per_action_max: [{ quantity: "sum(amount)", max: 5000 }] }], aggregates });
    const daily = (aggregates: unknown[]) => ({ id: "daily", subject: "refunds-x", on_exceed: "refuse", grants: [{ action: "refund_charge_k3" }], aggregates });
    const others = { id: "others", subject: "refunds-x", on_exceed: "owner", grants: [{ action: "send_reply_k3" }, { action: "get_charge_k3" }], aggregates: [count(500)] };
    const ask = async () => (await (await world(s, { draft: "drf_1" })).call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" })).body;
    const draft = (cards: unknown[]) => { s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards }; };
    // 1. As agreed: €50 a refund, held; €200 a day, refused. A reply and a read keep their count: signed.
    draft([each([]), daily([day(20_000)]), others]);
    expect(await ask()).toMatchObject({ outcome: "sign" });
    // 2. The Eval's draft: 1,000 refunds a day on the held Card too. Not signed, and Coach hears the count and why.
    draft([each([count(1000)]), daily([day(20_000)]), others]);
    const r = await ask();
    expect(r).toMatchObject({ outcome: "answered", note: WORDS.countOnAmount("Refund a charge") });
    expect(String(r.answer)).toContain(`failed: the draft counts "Refund a charge"'s calls (1000 per calendar_day) beside its amount limits`);
    expect(String(r.answer)).toContain(`${REFUSAL_CLOSED} ${COUNT_NEVER_A_RULE} ${DRAW_AGAIN} ${KEEP_THE_REST}`);
    expect(String(r.answer)).not.toContain(SHOW_NOW);
    expect(REDRAFT_CODES.has("count_on_amount")).toBe(true);
    // 3. The count on the day's Card, or with only a per-call cap and no day's total: not signed either.
    draft([each([]), daily([day(20_000), count(30)]), others]);
    expect((await ask()).note).toBe(WORDS.countOnAmount("Refund a charge"));
    draft([each([count(30)]), others]);
    expect((await ask()).note).toBe(WORDS.countOnAmount("Refund a charge"));
    // 4. A count of 0 held (each refund asks the owner), or the owner's limit for each item, per the tool's own
    // resource_argument over its life or any 7 days (one refund per charge, ever): signed.
    draft([each([count(0)]), daily([day(20_000)]), others]);
    expect(await ask()).toMatchObject({ outcome: "sign" });
    draft([each([count(1, { per: "charge", window: "lifetime" })]), daily([day(20_000)]), others]);
    expect(await ask()).toMatchObject({ outcome: "sign" });
    draft([each([count(1, { per: " Charge ", window: "rolling_7d" })]), daily([day(20_000)]), others]);
    expect(await ask()).toMatchObject({ outcome: "sign" });
    // 4b. KIFF's own count, 10,000 a day, as the held Card's only total: KIFF refuses a Card with no total, and this one
    // is never the owner's (Eval 43975057022, Setup run 3: refused here, then refused by KIFF with none, and Coach changed
    // the agreed rule to get out). Signed. On the refused Card, beside another total, or at another figure: not signed.
    draft([each([count(10_000)]), daily([day(20_000)]), others]);
    expect(await ask()).toMatchObject({ outcome: "sign" });
    // Alone, with no Card bounding the refunds' amount: 10,000 refunds under the cap a day would pass unasked.
    draft([each([count(10_000)]), others]);
    expect((await ask()).note).toBe(WORDS.countOnAmount("Refund a charge"));
    draft([each([count(10_000)]), { ...daily([day(20_000)]), grants: [{ action: "send_reply_k3" }] }, others]);
    expect((await ask()).note).toBe(WORDS.countOnAmount("Refund a charge"));
    // Beside a total for each customer only: one item's, so nothing bounds the day's refunds over all customers.
    draft([each([count(10_000)]), daily([{ ...day(20_000), per: "customer" }]), others]);
    expect((await ask()).note).toBe(WORDS.countOnAmount("Refund a charge"));
    draft([each([]), daily([day(20_000), count(10_000)]), others]);
    expect((await ask()).note).toBe(WORDS.countOnAmount("Refund a charge"));
    draft([each([count(10_000), count(1, { per: "charge", window: "lifetime" })]), daily([day(20_000)]), others]);
    expect((await ask()).note).toBe(WORDS.countOnAmount("Refund a charge"));
    for (const other of [count(9_999), count(10_000, { window: "rolling_24h" }), count(10_000, { per: "customer" })]) {
      draft([each([other]), daily([day(20_000)]), others]);
      expect((await ask()).note).toBe(WORDS.countOnAmount("Refund a charge"));
    }
    // 5. A live test's "1 a day for each payment intent" on the money tool: a count per item the owner never gave, so
    // not signed, and Coach hears the item. Another item than the tool's own, even over its life: not signed either.
    draft([each([count(1, { per: "payment_intent" })]), daily([day(20_000)]), others]);
    const p = await ask();
    expect(p).toMatchObject({ outcome: "answered", note: WORDS.countOnAmount("Refund a charge") });
    expect(String(p.answer)).toContain(`failed: the draft counts "Refund a charge"'s calls (1 per calendar_day for each payment_intent) beside its amount limits`);
    draft([each([count(1, { per: "charge" })]), daily([day(20_000)]), others]);
    expect((await ask()).note).toBe(WORDS.countOnAmount("Refund a charge"));
    draft([each([count(1, { per: "payment_intent", window: "lifetime" })]), daily([day(20_000)]), others]);
    expect((await ask()).note).toBe(WORDS.countOnAmount("Refund a charge"));
    // 6. With no resource_argument, no count per item is the owner's shape: it goes in the taf's instructions.
    s.grants = [grant({ status: "connected" }, { tools: tools.map(({ resource_argument: _, ...t }) => t) })];
    draft([each([count(1, { per: "charge", window: "lifetime" })]), daily([day(20_000)]), others]);
    expect((await ask()).note).toBe(WORDS.countOnAmount("Refund a charge"));
  });

  // Eval 43402441612: "one invoice per order, ever" and "up to 10 drafts a day" signed as 1 create_invoice in the Card's
  // whole life, across every order, though the tool named its order (resource_argument). The skill's words alone didn't hold.
  it("a draft that counts a write once in its whole life, across every item, isn't signed: a limit for each item is per its argument", async () => {
    const s = services();
    const tools = [{ tool: "create_invoice", name: "create_invoice_k3", title: "Create a draft invoice", resource_argument: "order" },
      { tool: "list_orders", name: "list_orders_k3", title: "List orders", read_only: true }];
    s.grants = [grant({ status: "connected" }, { tools })];
    const card = (aggregates: unknown[]) => ({ id: "c1", subject: "refunds-x", grants: [{ action: "create_invoice_k3" }, { action: "list_orders_k3" }], on_exceed: "owner", aggregates });
    const daily = { quantity: "count", limit: 10, window: "calendar_day" };
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: [card([daily, { quantity: "count", limit: 1, window: "lifetime" }])] };
    const ask = async () => (await (await world(s, { draft: "drf_1" })).call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" })).body;
    const r = await ask();
    expect(r).toMatchObject({ outcome: "answered", note: WORDS.lifetimeCap("Create a draft invoice") });
    // Coach hears what to draft instead, with the argument to count per.
    expect(String(r.answer)).toMatch(/^failed: the draft counts "Create a draft invoice" 1 in its whole life, across every item/);
    expect(String(r.answer)).toContain('per: "order" (its resource_argument)');
    expect(String(r.answer)).toContain("With no resource_argument, it goes in the taf's instructions");
    // Counted for each order: signed. So is a lifetime count on a read. (A lifetime sum on a write isn't: see below.)
    s.drafts.drf_1.cards = [card([daily, { quantity: "count", limit: 1, window: "lifetime", per: "order" }])];
    expect(await ask()).toMatchObject({ outcome: "sign" });
    s.drafts.drf_1.cards = [card([daily]), { id: "c2", subject: "refunds-x", grants: [{ action: "list_orders_k3" }], aggregates: [{ quantity: "count", limit: 1000, window: "lifetime" }] }];
    expect(await ask()).toMatchObject({ outcome: "sign" });
    // KIFF folds case and spaces: so does the check.
    s.drafts.drf_1.cards = [card([daily, { quantity: " Count ", limit: 1, window: "LIFETIME", per: " " }])];
    expect(await ask()).toMatchObject({ outcome: "answered", note: WORDS.lifetimeCap("Create a draft invoice") });
  });

  // Eval 43674391351: the owner agreed €5 a refund and €50 a day, and Coach also drafted a lifetime sum(amount) of
  // 1,000,000 on refund_charge, a total nobody agreed to. The skill's words ("never a lifetime total on the tool",
  // "never add a limit the owner didn't agree") didn't hold, so the code refuses it.
  it("a draft that caps a write's amount over its whole life, across every item, isn't signed: no total the owner didn't agree", async () => {
    const s = services();
    const tools = [{ tool: "refund_charge", name: "refund_charge_r7", title: "Refund a charge", resource_argument: "charge" },
      { tool: "read_charge", name: "read_charge_r7", title: "Read a charge", read_only: true }];
    s.grants = [grant({ status: "connected" }, { tools })];
    const each = { id: "c1", subject: "refunds-x", grants: [{ action: "refund_charge_r7", limits: { amount: 500 } }], on_exceed: "owner", aggregates: [] as unknown[] };
    const day = { id: "c2", subject: "refunds-x", grants: [{ action: "refund_charge_r7" }], on_exceed: "refuse", aggregates: [{ quantity: "sum(amount)", limit: 5000, window: "calendar_day" }] as unknown[] };
    const reads = { id: "c3", subject: "refunds-x", grants: [{ action: "read_charge_r7" }], aggregates: [{ quantity: "count", limit: 1000, window: "calendar_day" }] };
    const lifetime = { quantity: "sum(amount)", limit: 1_000_000, window: "lifetime" };
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: [each, { ...day, aggregates: [...day.aggregates, lifetime] }, reads] };
    const ask = async () => (await (await world(s, { draft: "drf_1" })).call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" })).body;
    const r = await ask();
    expect(r).toMatchObject({ outcome: "answered", note: WORDS.lifetimeSum("Refund a charge") });
    // Coach hears what's wrong and what to draft instead; nothing is signed.
    expect(String(r.answer)).toMatch(/^failed: the draft caps "Refund a charge"'s sum\(amount\) at 1000000 over its whole life, across every item/);
    expect(String(r.answer)).toContain("no lifetime total on an amount");
    // On the per-call Card instead: still refused.
    s.drafts.drf_1.cards = [{ ...each, aggregates: [lifetime] }, day, reads];
    expect((await ask()).note).toBe(WORDS.lifetimeSum("Refund a charge"));
    // KIFF folds case and spaces: so does the check.
    s.drafts.drf_1.cards = [each, { ...day, aggregates: [...day.aggregates, { quantity: " SUM(amount) ", limit: 1, window: " Lifetime ", per: " " }] }, reads];
    expect((await ask()).note).toBe(WORDS.lifetimeSum("Refund a charge"));
    // The agreed limits alone: signed. A sum for each charge (per its argument) is a limit for each item: signed.
    s.drafts.drf_1.cards = [each, day, reads];
    expect(await ask()).toMatchObject({ outcome: "sign" });
    s.drafts.drf_1.cards = [each, { ...day, aggregates: [...day.aggregates, { ...lifetime, limit: 500, per: "charge" }] }, reads];
    expect(await ask()).toMatchObject({ outcome: "sign" });
    // A lifetime count on a read is a read's call count: signed.
    s.drafts.drf_1.cards = [each, day, { ...reads, aggregates: [{ quantity: "count", limit: 100_000, window: "lifetime" }] }];
    expect(await ask()).toMatchObject({ outcome: "sign" });
  });

  // Eval 43681227913: list_charges was taken in KIFF by another connection, so planque granted this taf "list_charges_noyy". Coach drafted
  // the Card over "list_charges", the other connection's name, and it was signed: the taf's own list tool had no Card. A Card
  // covers a renamed tool by its registered name (request.tools[].name) only, never the vendor's (tool).
  it("a Card over a renamed tool's vendor name isn't signed: Coach hears the name planque registered for this taf", async () => {
    const s = services();
    const tools = [{ tool: "stripe_api_details", name: "stripe_api_details_noyy", title: "Stripe API details", setup_reads: true },
      { tool: "list_charges", name: "list_charges_noyy", title: "List charges", read_only: true },
      { tool: "stripe_api_read", name: "read_charge", title: "Read a charge", read_only: true },
      { tool: "create_refund", name: "refund_charge", title: "Refund a charge", resource_argument: "charge" }];
    // The same taf's earlier Stripe connection, still connected, holds "list_charges": the stray check alone let the Card through.
    const earlier = grant({ id: "g_0", status: "connected" }, { tools: [{ tool: "list_charges", name: "list_charges", read_only: true }] });
    s.grants = [earlier, grant({ status: "connected" }, { tools })];
    const reads = (action: string) => ({ id: "list-charges", subject: "refunds-x", grants: [{ action }], aggregates: [{ quantity: "count", limit: 1000, window: "calendar_day" }] });
    const others = [
      { id: "refund-each", subject: "refunds-x", grants: [{ action: "refund_charge", limits: { amount: 500 } }], on_exceed: "owner", aggregates: [] },
      { id: "refund-day", subject: "refunds-x", grants: [{ action: "refund_charge" }], on_exceed: "refuse", aggregates: [{ quantity: "sum(amount)", limit: 5000, window: "calendar_day" }] },
      { id: "read-charge", subject: "refunds-x", grants: [{ action: "read_charge" }], aggregates: [{ quantity: "count", limit: 1000, window: "calendar_day" }] },
    ];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: [others[0], others[1], reads("list_charges"), others[2]] };
    const ask = async () => (await (await world(s, { draft: "drf_1" })).call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" })).body;
    const r = await ask();
    expect(r).toMatchObject({ outcome: "answered", note: WORDS.registeredName("list_charges", "list_charges_noyy") });
    expect(String(r.answer)).toBe(`failed: the Card covers "list_charges", but this taf's connection gives that tool as "list_charges_noyy": "list_charges" is another connection's name in KIFF, so it isn't signed. Draft it again with "list_charges_noyy" in its place. ${REFUSAL_CLOSED} ${DRAW_AGAIN} ${KEEP_THE_REST}`);
    // The list tool marked as Setup's own read (setup_reads): the coverage check leaves it out, and the earlier connection's
    // "list_charges" passes the stray check, so before this check the draft was signed with the taf's list tool on no Card.
    const setupList = tools.map((t) => (t.tool === "list_charges" ? { ...t, setup_reads: true } : t));
    s.grants = [{ ...earlier, request: { ...earlier.request } }, grant({ status: "connected" }, { tools: setupList })];
    s.drafts.drf_1.cards = [others[0], others[1], { ...reads("list_charges"), grants: [{ action: "list_charges" }] }, others[2]];
    expect((await ask()).note).toBe(WORDS.registeredName("list_charges", "list_charges_noyy"));
    s.drafts.drf_1.cards = [others[0], others[1], reads("list_charges"), others[2]];
    // With no other connection holding the name: the same answer, not the stray one, so Coach hears the name to use.
    s.grants = [grant({ status: "connected" }, { tools })];
    expect((await ask()).note).toBe(WORDS.registeredName("list_charges", "list_charges_noyy"));
    // KIFF folds case and spaces: so does the check.
    s.drafts.drf_1.cards = [others[0], others[1], reads(" LIST_Charges "), others[2]];
    expect((await ask()).note).toBe(WORDS.registeredName(" LIST_Charges ", "list_charges_noyy"));
    // A renamed tool of the same taf's other connection is checked too.
    s.grants = [grant({ id: "g_2", status: "connected", vendor: "Mail" }, { server: "mcp.mail.example", tools: [{ tool: "send", name: "send_ab12", title: "Send" }] }),
      grant({ status: "connected" }, { tools: tools.map((t) => (t.tool === "list_charges" ? { ...t, name: "list_charges" } : t)) })];
    s.drafts.drf_1.cards = [...others, reads("list_charges"), { id: "mail", subject: "refunds-x", grants: [{ action: "send" }], aggregates: [] }];
    expect((await ask()).note).toBe(WORDS.registeredName("send", "send_ab12"));
    // Over the registered name: signed. (The earlier connection's "list_charges" would need its own Card: uncoveredElsewhere.)
    s.grants = [grant({ status: "connected" }, { tools })];
    s.drafts.drf_1.cards = [others[0], others[1], reads("list_charges_noyy"), others[2]];
    expect(await ask()).toMatchObject({ outcome: "sign" });
    // Two of the taf's apps with the same vendor name, each under its own name, on one Card: both covered, signed.
    s.grants = [grant({ id: "g_2", status: "connected", vendor: "Mail" }, { server: "mcp.mail.example", tools: [{ tool: "list_charges", name: "list_charges", read_only: true }] }),
      grant({ status: "connected" }, { tools })];
    s.drafts.drf_1.cards = [others[0], others[1], { ...reads("list_charges_noyy"), grants: [{ action: "list_charges_noyy" }, { action: "list_charges" }] }, others[2]];
    expect(await ask()).toMatchObject({ outcome: "sign" });
    // Its registered name on a Card in force already: the vendor name beside it is the other app's, signed.
    s.mandates = [{ id: "c0", subject: "refunds-x", active: true, grants: [{ action: "list_charges_noyy" }] }];
    s.drafts.drf_1.cards = [others[0], others[1], reads("list_charges"), others[2]];
    expect(await ask()).toMatchObject({ outcome: "sign" });
  });

  // On #1067: every coverage check ran under `if (draft.cards)`, so a draft read without its Cards skipped them
  // all, and only an on-demand grant insisted on them. KIFF shows a draft with no Card as `cards: null`. Fail closed.
  it("a draft read without its Cards is never signed, whatever the grant: read again, every check runs or nothing is signed", async () => {
    const s = services();
    s.grants = [grant({ status: "connected" }, { tools: [{ tool: "create_refund", name: "refund_charge", title: "Refund a charge" }] })];
    const covered = [{ id: "c1", subject: "refunds-x", grants: [{ action: "refund_charge" }], on_exceed: "owner", aggregates: [] }];
    const ask = async () => (await (await world(s, { draft: "drf_1" })).call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" })).body;
    // A read that didn't bring the Cards: read again, nothing to sign, Coach told nothing.
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x" };
    expect(await ask()).toEqual({ outcome: "read_again", note: WORDS.cardsUnread });
    // A draft with no Card (KIFF's null, or an empty list): answered, so Coach drafts one, never the same draft read
    // again (#1067). The app's answer carries no reason code.
    for (const cards of [null, []]) {
      s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: cards as unknown[] };
      const r = await ask();
      expect(r).toEqual({ outcome: "answered", answer: expect.stringMatching(/^failed: the Card draft drf_1 has no Card, so nothing in it covers the taf's tools/), note: WORDS.noCard, session: expect.anything(), run: expect.anything(), status: expect.anything() });
    }
    // With its Cards, the checks run: one covering the tool is asked; one leaving it out is refused, as before.
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: covered };
    expect(await ask()).toMatchObject({ outcome: "sign", ask: { step: "cards" } });
    s.drafts.drf_1.cards = [{ ...covered[0], grants: [] }];
    expect(await ask()).toMatchObject({ outcome: "answered", note: WORDS.uncovered("Refund a charge") });
  });

  it("an app set up on demand asks for every change: a draft that lets one of its writes run outright isn't signed (kiff/planque#87)", async () => {
    const s = services();
    const tools = [{ tool: "send", name: "send_x", title: "Send an email" }, { tool: "list", name: "list_x", title: "List emails", read_only: true }];
    s.grants = [grant({ status: "connected", on_demand: true }, { tools })];
    const card = (over: Record<string, unknown>) => ({ id: "c1", subject: "refunds-x", grants: [{ action: "send_x" }, { action: "list_x" }], on_exceed: "owner", aggregates: [{ quantity: "count", limit: 0, window: "calendar_day" }], ...over });
    // Allowed outright: refused in words, Coach hears why, nothing to sign.
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: [card({ aggregates: [{ quantity: "count", limit: 20, window: "calendar_day" }] })] };
    const w = await world(s, { draft: "drf_1" });
    const r = await w.call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" });
    expect(r.body).toMatchObject({ outcome: "answered", note: WORDS.onDemandLetsRun("Stripe", "Send an email") });
    expect(String(r.body.answer)).toMatch(/^failed: Stripe was set up on demand.*"Send an email" run without asking/);
    expect(w.told()).toMatchObject({ answer: String(r.body.answer) });
    // KIFF matches an action ignoring case and trims a quantity: so does the check, each way.
    s.drafts.drf_1.cards = [card({ grants: [{ action: "SEND_X" }, { action: "list_x" }], aggregates: [{ quantity: "count", limit: 20, window: "calendar_day" }] })];
    expect((await (await world(s, { draft: "drf_1" })).call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" })).body).toMatchObject({ outcome: "answered", note: WORDS.onDemandLetsRun("Stripe", "Send an email") });
    s.drafts.drf_1.cards = [card({ grants: [{ action: "Send_X" }, { action: "list_x" }], aggregates: [{ quantity: " count ", limit: 0, window: "calendar_day" }] })];
    expect((await (await world(s, { draft: "drf_1" })).call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" })).body).toMatchObject({ outcome: "sign" });
    // A draft read without its Cards can't be checked: read again, never signed on trust.
    delete s.drafts.drf_1.cards;
    expect((await (await world(s, { draft: "drf_1" })).call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" })).body).toEqual({ outcome: "read_again", note: WORDS.cardsUnread });
    // Refused past the limit rather than held: not asking either.
    s.drafts.drf_1.cards = [card({ on_exceed: "refuse" })];
    expect((await (await world(s, { draft: "drf_1" })).call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" })).body).toMatchObject({ outcome: "answered" });
    // Held for every call, reads open: signed as any other Card.
    s.drafts.drf_1.cards = [card({}), { id: "c2", subject: "refunds-x", grants: [{ action: "list_x" }], on_exceed: "refuse", aggregates: [{ quantity: "count", limit: 1000, window: "calendar_day" }] }];
    const ok = await (await world(s, { draft: "drf_1" })).call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" });
    expect(ok.body).toMatchObject({ outcome: "sign", ask: { step: "cards" } });
    // Not on demand: the Card is the owner's to read, whatever it allows.
    s.grants = [grant({ status: "connected" }, { tools })];
    s.drafts.drf_1.cards = [card({ aggregates: [{ quantity: "count", limit: 20, window: "calendar_day" }] })];
    expect((await (await world(s, { draft: "drf_1" })).call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" })).body).toMatchObject({ outcome: "sign" });
  });

  it("a draft KIFF dropped, or has no more, is told as failed", async () => {
    const s = services();
    s.grants = [grant({ status: "connected" })];
    s.drafts.drf_1 = { status: "draft", version: "v3", expires_at: "2026-10-16T11:00:00Z", cards: ONE_CARD };
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

// A half-made connection of the same taf from an earlier conversation (asked before this one began, still pending or
// approved and never signed in to) is resumed only with this conversation's Card draft for its taf, inside its seven days,
// its tools all on that Card, and a fresh tap; anything else, Coach asks for the app again for this taf.
describe("a half-made connection from an earlier conversation: resumed only for its taf, in its time, under this conversation's Card", () => {
  // The Coach run (this conversation) began at NOW; a day earlier is an earlier conversation's.
  const EARLIER = "2026-10-15T12:00:00Z";
  const TOOLS = [{ tool: "refund", name: "refund" }];
  const CARD = (...actions: string[]) => [{ id: "c1", subject: "refunds-x", grants: actions.map((action) => ({ action })), aggregates: [] }];
  const resumed = (over: Record<string, unknown> = {}, request: Record<string, unknown> = {}) =>
    grant({ status: "pending", ...over }, { created_at: EARLIER, tools: TOOLS, ...request });
  const ask = (g: ReturnType<typeof grant>, draft?: string) =>
    ({ grant: "g_1", shown_digest: g.digest, ...(draft ? { draft, shown_draft: "v3" } : {}) });
  /** A refusal of the resumed rule: the earlier-connection note, Coach asked to request the app again, nothing to sign. */
  const refusedAsResumed = (body: Record<string, unknown>, why: string) => {
    expect(body).toMatchObject({ outcome: "answered", note: wordsIn("en").approval_earlier_connection("Stripe") });
    expect(String(body.answer)).toContain(why);
    expect(String(body.answer)).toContain("request Stripe again for this taf as a new connection");
    expect(body).not.toHaveProperty("ask");
  };

  it("the same taf's connection, with this conversation's Card over all its tools: signed on a fresh tap", async () => {
    for (const status of ["pending", "approved"]) {
      const s = services();
      const g = resumed({ status });
      s.grants = [g];
      s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: CARD("refund") };
      const r = await (await world(s, { draft: "drf_1" })).call("POST", ASK, ask(g, "drf_1"));
      expect(r.body, status).toMatchObject({ outcome: "sign" });
      expect((r.body.ask as { message: string }).message).toContain("|g_1|");
    }
  });

  it("with no Card draft: refused, so it's never signed alone", async () => {
    const s = services();
    const g = resumed();
    s.grants = [g];
    refusedAsResumed((await (await world(s)).call("POST", ASK, ask(g))).body, "only with this conversation's Card draft");
  });

  it("with a Card draft for another taf: refused, naming neither that taf nor the grant", async () => {
    const s = services();
    const g = resumed();
    s.grants = [g];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "another-taf-a1b2c3d4", cards: CARD("refund") };
    const r = await (await world(s, { draft: "drf_1" })).call("POST", ASK, ask(g, "drf_1"));
    refusedAsResumed(r.body, "isn't for its taf");
    expect(String(r.body.answer)).not.toContain("another-taf");
    expect(String(r.body.answer)).not.toContain("g_1");
  });

  it("asked over seven days ago: refused, even with its taf's Card", async () => {
    const s = services();
    const g = resumed({}, { created_at: "2026-10-09T11:59:00Z" });
    s.grants = [g];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: CARD("refund") };
    refusedAsResumed((await (await world(s, { draft: "drf_1" })).call("POST", ASK, ask(g, "drf_1"))).body, "over seven days ago");
  });

  it("asking for a tool this conversation's Card doesn't cover: refused, whatever Cards were in force before", async () => {
    const s = services();
    const g = resumed({}, { tools: [...TOOLS, { tool: "payout", name: "payout", title: "Pay out" }] });
    s.grants = [g];
    s.mandates = [{ id: "m_old", subject: "refunds-x", active: true, grants: [{ action: "payout" }] }];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: CARD("refund") };
    const r = await (await world(s, { draft: "drf_1" })).call("POST", ASK, ask(g, "drf_1"));
    refusedAsResumed(r.body, "this conversation's Card doesn't cover");
    expect(String(r.body.answer)).toContain("Pay out");
  });

  it("a grant this conversation asked for, or one connected already, isn't resumed: the rule leaves it as it was", async () => {
    // Asked after this conversation began, past the clocks' margin: this conversation's own, no draft needed to connect.
    const s = services();
    const g = resumed({}, { created_at: "2026-10-16T12:00:30Z" });
    s.grants = [g];
    expect((await (await world(s)).call("POST", ASK, ask(g))).body).toMatchObject({ outcome: "sign", ask: { step: "connect" } });
    // Connected in an earlier conversation, with a new version's Card over one tool here and the other on a Card in
    // force: the Cards step, as before. Read as resumed, the payout this Card leaves to the earlier one would refuse it.
    const c = services();
    const connected = resumed({ status: "connected" }, { tools: [...TOOLS, { tool: "payout", name: "payout" }] });
    c.grants = [connected];
    c.mandates = [{ id: "m_old", subject: "refunds-x", active: true, grants: [{ action: "payout" }] }];
    c.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: CARD("refund") };
    expect((await (await world(c, { draft: "drf_1" })).call("POST", ASK, ask(connected, "drf_1"))).body).toMatchObject({ outcome: "sign", ask: { step: "cards" } });
  });

  it("asked a few seconds after this conversation began is still read as earlier, unless this conversation's own request answered with it", async () => {
    const s = services();
    const g = resumed({}, { created_at: "2026-10-16T12:00:05Z" });
    s.grants = [g];
    refusedAsResumed((await (await world(s)).call("POST", ASK, ask(g))).body, "only with this conversation's Card draft");

    // Coach's own planque request in this conversation answered with g_1: it's this conversation's, not resumed.
    const w = await world(s);
    const list = w.agents.events.get(w.stored.session) ?? [];
    const at = { session: w.stored.session, run: w.stored.id, time: "2026-10-16T12:00:04Z" };
    list.push({ ...at, seq: list.length + 1, kind: "tool_call", body: { call_id: "c_req", tool: "planque", input: { action: "request", entry: "stripe@3" } } });
    list.push({ ...at, seq: list.length + 1, kind: "tool_result", body: { call_id: "c_req", text: JSON.stringify({ grant: "g_1", status: "pending" }) } });
    w.agents.events.set(w.stored.session, list);
    expect((await w.call("POST", ASK, ask(g))).body).toMatchObject({ outcome: "sign", ask: { step: "connect" } });
  });

  it("a request's answer names the grant only when it's this conversation's own request that answered: never a read, never a failure", () => {
    const call = (id: string, action: string) => ({ kind: "tool_call", body: { call_id: id, tool: "planque", input: { action } } });
    const result = (id: string, text: string, is_error = false) => ({ kind: "tool_result", body: { call_id: id, text, is_error } });
    expect(askedInSession([call("c1", "request"), result("c1", `{"grant":"g_1"}`)], "g_1")).toBe(true);
    expect(askedInSession([call("c1", "status"), result("c1", `{"grant":"g_1"}`)], "g_1")).toBe(false);
    expect(askedInSession([call("c1", "request"), result("c1", `{"grant":"g_1"}`, true)], "g_1")).toBe(false);
    expect(askedInSession([call("c1", "request"), result("c1", `{"grant":"g_2"}`)], "g_1")).toBe(false);
    expect(askedInSession([call("c1", "request"), result("c1", `{"grant":"g_1"}`)], "")).toBe(false);
    // Another tool's "request" isn't Coach's connection request.
    expect(askedInSession([{ kind: "tool_call", body: { call_id: "c1", tool: "other", input: { action: "request" } } }, result("c1", `{"grant":"g_1"}`)], "g_1")).toBe(false);
    // The id as a whole token: g_12 isn't g_1.
    expect(askedInSession([call("c1", "request"), result("c1", `{"grant":"g_12"}`)], "g_1")).toBe(false);
  });

  it("a grant asked before this conversation began isn't its own, even when its own request answered with it (planque handing back a pending grant)", async () => {
    const s = services();
    const g = resumed();
    s.grants = [g];
    const w = await world(s);
    const list = w.agents.events.get(w.stored.session) ?? [];
    const at = { session: w.stored.session, run: w.stored.id, time: "2026-10-16T12:00:04Z" };
    list.push({ ...at, seq: list.length + 1, kind: "tool_call", body: { call_id: "c_req", tool: "planque", input: { action: "request", entry: "stripe@3" } } });
    list.push({ ...at, seq: list.length + 1, kind: "tool_result", body: { call_id: "c_req", text: JSON.stringify({ grant: "g_1", status: "pending" }) } });
    w.agents.events.set(w.stored.session, list);
    refusedAsResumed((await w.call("POST", ASK, ask(g))).body, "only with this conversation's Card draft");
  });

  describe("the rule as the chat's read checks a draft (checkDraft), and when the conversation can't be read", () => {
    const ctxOf = (s: Services, over: Partial<DecideContext>): DecideContext => ({
      tenant: "t_acme", taf: COACH, component: "a1", token: "ana", proof: PROOF, store: memoryCoach(() => NOW),
      planqueBase: "https://planque.test", kiffBase: "https://api.test", fetch: net(s), now: NOW, newID: () => "id_1", lang: "en",
      session: "sess1", sessionStarted: async () => NOW.toISOString(), ...over,
    });
    const DRAFT = { grant: "g_1", draft: "drf_1", shown_draft: "v3" };

    it("another taf's draft over an earlier grant: refused on the read too, before a card is drawn", async () => {
      const s = services();
      const g = resumed();
      s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "another-taf-a1b2c3d4", cards: CARD("refund") };
      expect(await checkDraft(DRAFT, ctxOf(s, {}), [g as unknown as ApprovalGrant])).toMatchObject({ outcome: "answered", code: "resumed_other_taf" });
    });

    it("its start unread, or none: read again, never taken as this conversation's", async () => {
      const s = services();
      const g = resumed();
      s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: CARD("refund") };
      for (const sessionStarted of [async () => { throw new Error("agents down"); }, async () => undefined]) {
        expect(await checkDraft(DRAFT, ctxOf(s, { sessionStarted }), [g as unknown as ApprovalGrant])).toMatchObject({ outcome: "read_again", code: "session_unread" });
      }
    });

    it("its requests unread, for a grant inside the clocks' margin: read again, never taken as its own", async () => {
      const s = services();
      const g = resumed({}, { created_at: "2026-10-16T12:00:05Z" });
      s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: CARD("refund") };
      const askedHere = async () => { throw new Error("agents down"); };
      expect(await checkDraft(DRAFT, ctxOf(s, { askedHere }), [g as unknown as ApprovalGrant])).toMatchObject({ outcome: "read_again", code: "session_unread" });
    });
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

  // A taf moved to a new connection of an app (the phone's retest, 18 Nov): its kept tools held only the old
  // connection's, so its dial couldn't name the new connection's read. Approved, the connection's tools are the taf's.
  it("approved: the connection's tools are its taf's from then on, beside the ones it kept, and a failed approval keeps none", async () => {
    const s = services();
    const RAE = "t_acme#taf-rrrr0000000000000000";
    const w = await asked(s);
    w.tafs.held.set(RAE, { ...w.tafs.held.get(RAE)!, tools: ["list_invoices_3ojw"] });
    s.grants = [grant({ status: "connected" }, { tools: [{ tool: "list_invoices", name: "list_invoices_iyjq" }, { tool: "send_invoice" }] })];
    expect((await w.report({ result: "connected" })).body).toMatchObject({ answer: "approved" });
    // A tool asked for with no name has planque's: the vendor's name and the grant's suffix.
    expect(w.tafs.held.get(RAE)!.tools).toEqual(["list_invoices_3ojw", "list_invoices_iyjq", "send_invoice_g1"]);
    // Coach's own taf keeps nothing of it: the connection is the Refunds taf's.
    expect(w.tafs.held.get(`t_acme#${COACH}`)!.tools).toBeUndefined();
    const f = await asked(services());
    f.tafs.held.set(RAE, { ...f.tafs.held.get(RAE)!, tools: ["list_invoices_3ojw"] });
    expect((await f.report({ result: "sign_in_closed" })).body.answer).toMatch(/^failed/);
    expect(f.tafs.held.get(RAE)!.tools).toEqual(["list_invoices_3ojw"]);
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

  // Go-to-market job 1 (owner's phone, 13 Nov): the account's Google sign-in was held, so planque answered signed_in
  // with no link, and Coach was told "Nothing was connected". signed_in is planque's token held, unused, until the
  // approving phone confirms it with planque's one-use code; unconfirmed, planque revokes it. So it's "not yet":
  // never approved (it could still go), never failed, and the ask waits for the report after the phone's confirm.
  it("signed in but not confirmed is not yet: neither approved nor failed, and approved once planque lists it after the confirm", async () => {
    const s = services();
    const w = await asked(s);
    s.grants = [grant({ status: "signed_in" })];
    const before = s.proofs.length;
    const early = await w.report({ result: "not_connected", status: "signed_in" });
    expect(early.status).toBe(409);
    expect(early.body).toMatchObject({ code: "sign_in_not_confirmed", error: WORDS.confirmFirst });
    expect(JSON.stringify(early.body)).not.toContain("Nothing was connected");
    expect(w.told()).toBeUndefined();
    // planque was read twice: once more in case the phone had just confirmed.
    expect(s.proofs.length - before).toBe(2);
    // The phone confirms with planque's code, planque lists the tools, and the next report is approved.
    s.grants = [grant({ status: "listed" })];
    expect((await w.report({ result: "listed" })).body).toMatchObject({ answer: "approved" });
    expect(w.told()).toMatchObject({ answer: "approved" });
  });

  it("a sign-in confirmed between the report and tafs cloud's read is approved on the second read", async () => {
    const s = services();
    const w = await asked(s);
    let reads = 0;
    Object.defineProperty(s, "grants", { get: () => [grant({ status: reads++ === 0 ? "signed_in" : "connected" })], configurable: true });
    expect((await w.report({ result: "connected" })).body).toMatchObject({ answer: "approved" });
  });

  // Go-to-market job 3 (owner's phone, 13 Nov): a message typed while the owner approved GitHub (a fingerprint and a
  // sign-in) started a new run, the card stopped waiting, and it read "connected" beside Approve and "Your answer
  // didn't reach Setup". A message during an approval waits until it's settled; it never voids the card.
  it("a message sent between the fingerprint and the report waits in the approval's run, and the approval goes through", async () => {
    const s = services();
    const w = await asked(s);
    w.agents.calls.length = 0;
    const sent = await w.call("POST", `/v1/tafs/${COACH}/messages`, { text: "Also post weekly, please" });
    expect(sent.status).toBe(200);
    expect(sent.body).toMatchObject({ run: w.stored.id, new_session: false, held: "approval" });
    expect(w.agents.calls).toContain(`steerRun ${COACH} ${w.stored.id}`);
    expect(w.agents.calls.some((c) => c.startsWith("startRun"))).toBe(false);
    // The card still waits, so the report reaches Coach as the owner's answer.
    s.grants = [grant({ status: "connected" })];
    expect((await w.report({ result: "connected" })).body).toMatchObject({ outcome: "answered", answer: "approved" });
    expect(w.told()).toMatchObject({ answer: "approved" });
    // Settled: the next message is a message again.
    w.agents.calls.length = 0;
    const next = await w.call("POST", `/v1/tafs/${COACH}/messages`, { text: "Thanks" });
    expect(next.body.held).toBeUndefined();
  });

  it("an approval settled after its card stopped waiting still reaches Coach, and the card shows it, never a conflict", async () => {
    const s = services();
    const w = await asked(s);
    // The run went on before the report came in (an app that sent the message as a new run).
    w.stored.status = "COMPLETED";
    s.grants = [grant({ status: "connected" })];
    const r = await w.report({ result: "connected" });
    expect(r.status).toBe(200);
    expect(r.body).toMatchObject({ outcome: "answered", answer: "approved" });
    // Kept for the card's chat alone: a new chat's first words never carry it, that chat's next words do, once.
    const settled = "[approval a1] The owner's approval was settled after their message: approved";
    expect(await w.coach.get("t_acme#coach-notes#u_ana")).toBeFalsy();
    // One kept for the person before this, which named no chat, reaches no chat either.
    await w.coach.append("t_acme#coach-notes#u_ana", { at: NOW.toISOString(), text: "[approval a0] The owner's approval was settled after their message: approved" }, new Date(NOW.getTime() + 3600_000));
    const said = (session: string) => (w.agents.events.get(session)!.filter((e) => e.kind === "owner_message").at(-1)!.body as { text: string }).text;
    const fresh = await w.call("POST", `/v1/tafs/${COACH}/messages`, { text: "Connect Slack", new_chat: true });
    expect(fresh.status).toBe(200);
    expect(fresh.body.session).not.toBe(w.stored.session);
    expect(said(fresh.body.session as string)).not.toContain("settled after their message");
    for (const r of w.agents.runs) Object.assign(r, { status: "COMPLETED" });
    expect((await w.call("POST", `/v1/tafs/${COACH}/messages`, { text: "Thanks", session: w.stored.session })).status).toBe(200);
    expect(said(w.stored.session)).toContain(settled);
    expect(said(w.stored.session)).not.toContain("[approval a0]");
    expect(await w.coach.get(`t_acme#coach-chat-notes#u_ana#${w.stored.session}`)).toBeFalsy();
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

  it("KIFF's refusal of a signature reaches the app with its code and words for this device, never 'this phone'", async () => {
    // A view-only Mac heard "this phone didn't sign that answer": the reason was KIFF's, and it was lost here.
    const SIG = { "kiff-device-signature": "t=2026-10-16T12:00:30Z,v1=sig" };
    const cases: Array<[number, string, string, string]> = [
      [403, "view_only", WORDS.signViewOnly, "view_only"],
      [403, "device_session_required", WORDS.signSession, "device_session_required"],
      [403, "passkey_not_this_session", WORDS.signPasskey, "passkey_not_this_session"],
      [403, "signature_invalid", WORDS.signInvalid, "signature_invalid"],
      [403, "a_code_from_later", WORDS.signInvalid, "signature_invalid"],
      [400, "signed_at_out_of_range", WORDS.signClock, "signed_at_out_of_range"],
      [409, "replayed", WORDS.signTaken, "signature_taken"],
      [503, "", WORDS.signUnchecked, "signature_unchecked"],
    ];
    for (const [status, code, words, said] of cases) {
      const s = services();
      const w = await world(s);
      const id = ((await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant().request) })).body.ask as { id: string }).id;
      s.grants = [grant({ status: "connected" })];
      s.signed = status;
      s.signedCode = code;
      const r = await w.call("POST", `${ASK}/${id}`, { result: "connected" }, SIG);
      expect(r.body, code).toEqual({ error: words, code: said });
      expect(r.status, code).toBe(status === 503 ? 502 : status);
      // Still waiting: the same answer, signed where KIFF takes it, goes through.
      s.signed = true;
      expect((await w.call("POST", `${ASK}/${id}`, { result: "connected" }, SIG)).body, code).toMatchObject({ answer: "approved" });
    }
    // Not signed at all, and in the person's language.
    const s = services();
    const w = await world(s);
    const id = ((await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant().request) })).body.ask as { id: string }).id;
    expect((await w.call("POST", `${ASK}/${id}`, { result: "connected" }, {})).body).toEqual({ error: WORDS.signMissing, code: "signature_missing" });
    s.signed = 403;
    s.signedCode = "view_only";
    const pl = await w.call("POST", `${ASK}/${id}`, { result: "connected" }, { ...SIG, "accept-language": "pl" });
    expect(pl.body).toEqual({ error: approvalWords("pl").signViewOnly, code: "view_only" });
    // Said of this device, whichever it is: no language's line names a phone.
    for (const lang of ["en", "de", "fr", "es", "it", "pt", "nl", "pl"] as const) {
      const W = approvalWords(lang);
      for (const line of [W.signMissing, W.signInvalid, W.signViewOnly, W.signSession, W.signPasskey, W.signClock, W.signTaken, W.signUnchecked]) {
        expect(line, `${lang}: ${line}`).not.toMatch(/phone|telefon|teléfono|téléphone|telefono|telefoon|telemóvel|telefone|móvil|portable/i);
        expect(line.length, `${lang}: ${line}`).toBeGreaterThan(20);
      }
    }
  });

  it("the service is the truth: a reported success planque doesn't hold is failed, in tafs cloud's words", async () => {
    const s = services();
    const w = await asked(s);
    // Approved but never signed in: planque holds nothing (signed_in waits for the phone's confirm: the test above).
    s.grants = [grant({ status: "approved" })];
    const r = await w.report({ result: "connected" });
    expect(r.body).toMatchObject({ answer: `failed: ${WORDS.notConnected("Stripe", "approved")}`, note: WORDS.notConnected("Stripe", "approved") });
    expect(w.told()).toMatchObject({ answer: `failed: ${WORDS.notConnected("Stripe", "approved")}` });
  });

  it("words each note in the approving request's language, the reason Coach hears with it, and keeps the protocol's words", async () => {
    // On #716: "Your approval window has ended…" read English in a Polish chat.
    const PL = approvalWords("pl");
    const SIG = { "kiff-device-signature": "t=2026-10-16T12:00:30Z,v1=sig", "accept-language": "pl-PL,pl;q=0.9" };
    const cases: Array<[Record<string, unknown>, string]> = [
      [{ result: "sign_in_closed" }, PL.signInClosed],
      [{ result: "no_code" }, PL.noCode("Stripe")],
      [{ result: "not_connected", status: "needs_reconnect" }, PL.cameBack("Stripe", "needs_reconnect")],
      [{ result: "failed" }, PL.failed()],
    ];
    for (const [body, why] of cases) {
      const s = services();
      const w = await asked(s);
      s.grants = [grant({ status: "approved" })];
      const r = await w.report(body, SIG);
      expect(r.body).toMatchObject({ outcome: "answered", answer: `failed: ${why}`, note: why });
      expect(w.told()).toMatchObject({ answer: `failed: ${why}` });
    }
    expect(PL.cameBack("Stripe", "needs_reconnect")).toBe("Nie otworzyła się strona logowania Stripe, więc nic nie zostało połączone. Spróbuj ponownie.");
    expect(PL.failed()).toBe("Nie połączono: nie powiodło się");
    // A note on a tap, and a refusal's words, the same.
    const w = await world(services());
    const none = await w.call("POST", ASK, { grant: "g_9" }, { "accept-language": "pl" });
    expect(none.body).toMatchObject({ outcome: "answered", answer: `failed: ${PL.noGrant}`, note: PL.noGrant });
    const s = services();
    const w2 = await world(s);
    const r = await w2.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant().request) });
    const id = (r.body.ask as { id: string }).id;
    s.signedKey = "device";
    s.signedWindow = { id: "win_abcdefghijklmnopqrstuvwxyz", open: false };
    const ended = await w2.call("POST", `${ASK}/${id}`, { result: "connected" }, { ...SIG, "kiff-device-signature": "t=2026-10-16T12:00:30Z,v2=sig,w=win_abcdefghijklmnopqrstuvwxyz" });
    expect(ended.body).toEqual({ error: "Czas na zatwierdzenie minął. Nic nie zostało zatwierdzone; zatwierdź ponownie odciskiem palca.", code: "window_required" });
    // English is as it was: no Accept-Language, or an English one.
    // Never planque's status ("the connection is needs reconnect"): what happened, and what to do.
    expect(WORDS.notConnected("Stripe")).toBe("The sign-in at Stripe didn't finish, so nothing was connected. Try signing in again.");
    // Already signed in: no page opened because none was needed, and the actions come next (14 Nov: "came back signed in with no sign-in link").
    expect(WORDS.cameBack("Gmail", "signed_in")).toBe("You're already signed in to Gmail, so no sign-in page opened, and nothing new is connected yet. Its actions come next.");
    expect(WORDS.cameBack("Gmail", "listed")).toBe(WORDS.cameBack("Gmail", "signed_in"));
    for (const lang of ["en", "de", "fr", "es", "it", "pt", "nl", "pl"] as const) {
      const L = approvalWords(lang);
      for (const line of [L.cameBack("Gmail", "signed_in"), L.cameBack("Gmail", "approved"), L.notConnected("Gmail")]) {
        expect(line).toContain("Gmail");
        expect(line).not.toMatch(/signed_in|needs_reconnect|needs reconnect|approved|listed|\(/);
      }
    }
  });

  it("each failure the phone can report has tafs cloud's words, for Coach and for the card", async () => {
    const cases: Array<[Record<string, unknown>, string]> = [
      [{ result: "sign_in_closed" }, WORDS.signInClosed],
      [{ result: "no_code" }, WORDS.noCode("Stripe")],
      [{ result: "not_connected", status: "approved" }, WORDS.cameBack("Stripe", "approved")],
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
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: ONE_CARD };
    const w = await asked(s, { draft: "drf_1" }, { grant: "g_1", draft: "drf_1", shown_draft: "v3" });
    const notYet = await w.report({ result: "failed", why: "a Card moved on" });
    expect(notYet.body).toMatchObject({ answer: `failed: ${WORDS.cardsNotIssued("a Card moved on")}`, note: WORDS.cardsNotIssued("a Card moved on") });
    const again = await asked(s, { draft: "drf_1" }, { grant: "g_1", draft: "drf_1", shown_draft: "v3" });
    s.drafts.drf_1 = { status: "confirmed", version: "v3" };
    expect((await again.report({ result: "confirmed" })).body).toMatchObject({ answer: "approved" });
    expect(again.told()).toMatchObject({ answer: "approved" });
  });

  // Coach found something wrong after the owner approved the Card (a rule pointed at the wrong tool) and drafted another
  // over the same connection: doubled Cards, a third approval, and a taf that never started. Once a connection's Card is
  // approved in a conversation, no other draft over it is approved there; the same draft is still what it was, and
  // another conversation (a later Improve) is its own.
  it("never approves a second draft over a connection whose Card this conversation approved", async () => {
    const s = services();
    s.grants = [grant({ status: "connected" })];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: ONE_CARD };
    const w = await asked(s, { draft: "drf_1" }, { grant: "g_1", draft: "drf_1", shown_draft: "v3" });
    s.drafts.drf_1 = { status: "confirmed", version: "v3", agent_id: "refunds-x" };
    expect((await w.report({ result: "confirmed" })).body).toMatchObject({ answer: "approved" });

    // The re-draft, in the same conversation: refused, nothing to sign, and Coach hears why.
    s.drafts.drf_2 = { status: "draft", version: "v1", agent_id: "refunds-x", cards: ONE_CARD };
    w.agents.ask(w.stored, { type: "approval", id: "a2", grant: "g_1", draft: "drf_2" });
    const redraft = await w.call("POST", `/v1/tafs/${COACH}/components/a2/approval`, { grant: "g_1", draft: "drf_2", shown_draft: "v1" });
    expect(redraft.body).toMatchObject({ outcome: "answered" });
    expect(String(redraft.body.answer)).toContain("failed: Stripe's Card was approved earlier in this conversation (draft drf_1), so no second draft over it is approved here");
    // The approved draft itself is still what it was: already approved.
    w.agents.ask(w.stored, { type: "approval", id: "a3", grant: "g_1", draft: "drf_1" });
    const same = await w.call("POST", `/v1/tafs/${COACH}/components/a3/approval`, { grant: "g_1", draft: "drf_1", shown_draft: "v3" });
    expect(String(same.body.answer)).toMatch(/^already approved/);

    // Another conversation (an Improve later) drafts over the connection as its own.
    const later = await w.agents.api.startRun("t_acme", COACH, "Change Refunds", ANA);
    const laterRun = w.agents.runs.find((r) => r.id === later.id)!;
    w.agents.ask(laterRun, { type: "approval", id: "b1", grant: "g_1", draft: "drf_2" });
    const improve = await w.call("POST", `/v1/tafs/${COACH}/components/b1/approval`, { grant: "g_1", draft: "drf_2", shown_draft: "v1", session: later.session });
    expect(improve.body).toMatchObject({ outcome: "sign", ask: { step: "cards", kiff: { draft: "drf_2" } } });
  });

  it("an ask is taken once: two signed reports that arrive together settle it once, and Coach hears once", async () => {
    const s = services();
    const w = await asked(s);
    s.grants = [grant({ status: "connected" })];
    const [a, b] = await Promise.all([w.report({ result: "connected" }), w.report({ result: "connected" })]);
    expect([a.status, b.status].sort()).toEqual([200, 404]);
    expect(w.told()).toMatchObject({ answer: "approved" });
    expect(w.agents.calls.filter((c) => c.startsWith("answerCard")).length).toBe(1);
  });

  it("another account's session can't report on this account's ask, even with a card and ask id of the same name", async () => {
    const s = services();
    const w = await asked(s);
    s.grants = [grant({ status: "connected" })];
    const r = await w.call("POST", `${ASK}/${w.id}`, { result: "connected" }, { "kiff-device-signature": "t=2026-10-16T12:00:30Z,v1=sig" }, undefined, "bo");
    expect(r.status).toBe(404);
    expect(w.told()).toBeUndefined();
    expect(w.boTold()).toBeUndefined();
    expect(s.checks).toEqual([]);
    // Ana's ask still waits for Ana.
    expect((await w.report({ result: "connected" })).body).toMatchObject({ answer: "approved" });
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

describe("the approval window (kiff/tafs#494): which key an ask takes, and the report's check of it", () => {
  const SIG = { "kiff-device-signature": "t=2026-10-16T12:00:30Z,v1=sig" };
  const WIN = { "kiff-device-signature": "t=2026-10-16T12:00:30Z,v2=sig,w=win_abcdefghijklmnopqrstuvwxyz" };

  it("says the key: connecting an app is a tap inside a window; a draft takes the fingerprint unless KIFF says it widens nothing", async () => {
    const s = services();
    const w = await world(s);
    const connect = await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant().request) });
    expect(connect.body).toMatchObject({ outcome: "sign", ask: { step: "connect", key: "window" } });
    s.grants = [grant({ status: "connected" })];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: ONE_CARD };
    const cards = await (await world(s, { draft: "drf_1" })).call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" });
    expect(cards.body).toMatchObject({ outcome: "sign", ask: { step: "cards", key: "critical" } });
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", confirm_key: "window", cards: ONE_CARD };
    const routine = await (await world(s, { draft: "drf_1" })).call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" });
    expect(routine.body).toMatchObject({ outcome: "sign", ask: { step: "cards", key: "window" } });
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", confirm_key: "critical", cards: ONE_CARD };
    const widening = await (await world(s, { draft: "drf_1" })).call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" });
    expect(widening.body).toMatchObject({ outcome: "sign", ask: { step: "cards", key: "critical" } });
  });

  it("takes a critical ask's report signed by the device key outside a window, as every app does, and refuses one signed inside a window", async () => {
    const s = services();
    s.grants = [grant({ status: "connected" })];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: ONE_CARD };
    const w = await world(s, { draft: "drf_1" });
    const r = await w.call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" });
    const id = (r.body.ask as { id: string }).id;
    // The phone took the window path for a critical call: refused, with the code and the words, and the ask stays.
    s.signedKey = "device";
    s.signedWindow = { id: "win_abcdefghijklmnopqrstuvwxyz", open: true };
    const refused = await w.call("POST", `${ASK}/${id}`, { result: "confirmed" }, WIN);
    expect(refused.status).toBe(403);
    expect(refused.body).toEqual({ error: WORDS.criticalKey, code: "critical_key_required" });
    expect(w.told()).toBeUndefined();
    // The report as the apps sign it (the device key, v1): the fingerprint went on KIFF's confirm, and the
    // draft confirmed is the proof (the apps installed today).
    s.signedWindow = undefined;
    s.drafts.drf_1 = { status: "confirmed", version: "v3", agent_id: "refunds-x" };
    expect((await w.call("POST", `${ASK}/${id}`, { result: "confirmed" }, SIG)).body).toMatchObject({ answer: "approved" });
  });

  // A computer approves with its passkey (KIFF-Passkey-Assertion), never a device key (16 Nov): tafs cloud
  // sent KIFF only `signature`, so every computer's report was refused. KIFF takes `passkey` in its place, one of the two.
  it("checks a computer's passkey with KIFF in place of a signature, refuses both at once, and says KIFF's refusals in tafs cloud's words", async () => {
    const PASSKEY = { "kiff-passkey-assertion": "t=2026-10-16T12:00:30Z,a=assertion" };
    const s = services();
    const w = await world(s);
    const r = await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant().request) });
    const id = (r.body.ask as { id: string }).id;
    s.grants = [grant({ status: "connected" })];
    // Both at once: refused before KIFF is asked, and nothing is settled.
    const both = await w.call("POST", `${ASK}/${id}`, { result: "connected" }, { ...SIG, ...PASSKEY });
    expect(both.status).toBe(400);
    expect(both.body).toEqual({ error: WORDS.signBoth, code: "signature_twice" });
    expect(s.checks).toEqual([]);
    // A session that doesn't approve with a passkey: KIFF's code, in tafs cloud's words.
    s.signed = 403;
    s.signedCode = "passkey_not_this_session";
    const session = await w.call("POST", `${ASK}/${id}`, { result: "connected" }, PASSKEY);
    expect(session.status).toBe(403);
    expect(session.body).toEqual({ error: WORDS.signPasskey, code: "passkey_not_this_session" });
    // The same assertion again: KIFF takes each once.
    s.signed = 409;
    expect((await w.call("POST", `${ASK}/${id}`, { result: "connected" }, PASSKEY)).body).toEqual({ error: WORDS.signTaken, code: "signature_taken" });
    // The passkey KIFF checks: sent as `passkey`, as it came, with no `signature`; KIFF answers it as the approval key.
    s.signed = true;
    s.signedKey = "critical";
    expect((await w.call("POST", `${ASK}/${id}`, { result: "connected" }, PASSKEY)).body).toMatchObject({ answer: "approved" });
    expect(s.checks.at(-1)).toEqual({ method: "POST", uri: `${ASK}/${id}`, body_sha256: createHash("sha256").update(JSON.stringify({ result: "connected" })).digest("hex"), passkey: PASSKEY["kiff-passkey-assertion"] });
  });

  it("takes a routine ask signed inside an open window, and refuses one whose window has ended", async () => {
    const s = services();
    const w = await world(s);
    const r = await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant().request) });
    const id = (r.body.ask as { id: string }).id;
    s.grants = [grant({ status: "connected" })];
    s.signedKey = "device";
    s.signedWindow = { id: "win_abcdefghijklmnopqrstuvwxyz", open: false };
    const ended = await w.call("POST", `${ASK}/${id}`, { result: "connected" }, WIN);
    expect(ended.status).toBe(403);
    expect(ended.body).toEqual({ error: WORDS.windowClosed, code: "window_required" });
    s.signedWindow = { id: "win_abcdefghijklmnopqrstuvwxyz", open: true };
    expect((await w.call("POST", `${ASK}/${id}`, { result: "connected" }, WIN)).body).toMatchObject({ answer: "approved" });
    // A KIFF from before the window says no key: taken as today.
    const s2 = services();
    const w2 = await world(s2);
    const r2 = await w2.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant().request) });
    s2.grants = [grant({ status: "connected" })];
    expect((await w2.call("POST", `${ASK}/${(r2.body.ask as { id: string }).id}`, { result: "connected" }, SIG)).body).toMatchObject({ answer: "approved" });
  });
});

describe("one signature for a connection's added tools and its Card (tools_and_cards, docs/secure-connection.md)", () => {
  const SIGNED = { "kiff-device-signature": "t=2026-10-16T12:00:30Z,v1=sig" };
  // A connected grant whose new tools wait for their approval: what it had before is in connected.
  const adding = (over: Record<string, unknown> = {}) => grant({ status: "pending", connected: [{ tool: "list_charges" }], ...over });
  afterEach(() => { delete process.env.TAFS_TOOLS_AND_CARDS; });

  async function addWorld(on: boolean, can = ["tools_and_cards"]) {
    if (on) process.env.TAFS_TOOLS_AND_CARDS = "on";
    else delete process.env.TAFS_TOOLS_AND_CARDS;
    const s = services();
    s.grants = [adding()];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: ONE_CARD };
    const w = await world(s, { draft: "drf_1" });
    const r = await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(adding().request), draft: "drf_1", shown_draft: "v3", can });
    return { s, w, r };
  }

  it("asks the addition's approve and the Card's confirm as one signature, behind the fingerprint", async () => {
    const { r, w } = await addWorld(true);
    expect(r.body).toMatchObject({
      outcome: "sign",
      ask: { step: "tools_and_cards", scheme: "kiff.requests.v1", message: "", key: "critical", planque: { grant: "g_1", body: { digest: adding().digest } }, kiff: { draft: "drf_1", version: "v3" } },
    });
    expect(w.told()).toBeUndefined();
  });

  it("keeps the two steps for an app that can't sign it, a first connection, a card without a draft, or the flag off", async () => {
    expect((await addWorld(true, [])).r.body).toMatchObject({ ask: { step: "connect" } });
    expect((await addWorld(false)).r.body).toMatchObject({ ask: { step: "connect" } });
    process.env.TAFS_TOOLS_AND_CARDS = "on";
    const s = services();
    // A first connection (nothing connected before): its tools come with the sign-in, so the two steps.
    s.grants = [grant()];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: ONE_CARD };
    const w = await world(s, { draft: "drf_1" });
    expect((await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant().request), draft: "drf_1", shown_draft: "v3", can: ["tools_and_cards"] })).body).toMatchObject({ ask: { step: "connect" } });
    // No draft on the card: the addition alone.
    s.grants = [adding()];
    expect((await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(adding().request), can: ["tools_and_cards"] })).body).toMatchObject({ ask: { step: "connect" } });
  });

  it("answers approved only when the tools are in and the Card is issued, and says which half didn't", async () => {
    const both = await addWorld(true);
    both.s.grants = [adding({ status: "connected" })];
    both.s.drafts.drf_1 = { status: "confirmed", version: "v3", agent_id: "refunds-x" };
    expect((await both.w.call("POST", `${ASK}/${(both.r.body.ask as { id: string }).id}`, { result: "confirmed" }, SIGNED)).body).toMatchObject({ answer: "approved" });
    const half = await addWorld(true);
    half.s.grants = [adding({ status: "connected" })];
    const r = await half.w.call("POST", `${ASK}/${(half.r.body.ask as { id: string }).id}`, { result: "failed", why: "the confirm wasn't answered" }, SIGNED);
    expect(r.body.answer).toMatch(/^failed: Stripe is connected; /);
    const none = await addWorld(true);
    const r2 = await none.w.call("POST", `${ASK}/${(none.r.body.ask as { id: string }).id}`, { result: "failed", why: "the approve was refused" }, SIGNED);
    expect(r2.body.answer).toMatch(/^failed: /);
    expect(r2.body.answer).not.toMatch(/is connected/);
  });
});

// First tools whose sign-in planque doesn't hold yet (or that aren't declared in KIFF) can't go with the Card, so
// the combined step isn't offered: the card's connection is signed alone. Coach was told "approved" for the whole card, handed over a taf whose Card was
// never signed, and the hand-over waited for a confirm nothing asked for (13 Nov).
describe("a card with a grant and a draft, signed as the connection alone", () => {
  const SIGNED = { "kiff-device-signature": "t=2026-10-16T12:00:30Z,v1=sig" };
  afterEach(() => { delete process.env.TAFS_TOOLS_AND_CARDS; });

  async function reused(draft: Services["drafts"][string] = { status: "draft", version: "v3", agent_id: "refunds-x", cards: ONE_CARD }) {
    process.env.TAFS_TOOLS_AND_CARDS = "on";
    const s = services();
    // Its first tools asked, nothing connected before them, and no sign-in held to approve them on.
    s.grants = [grant({ status: "pending" })];
    s.drafts.drf_1 = draft;
    const w = await world(s, { draft: "drf_1" });
    const r = await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant().request), draft: "drf_1", shown_draft: "v3", can: ["tools_and_cards"] });
    return { s, w, r };
  }

  it("never reads as a failure when it worked by design: the card's words say it's connected and the Card is next, in the person's language", () => {
    expect(approvalAnswerWords({ type: "approval", answer: "failed: Gmail is connected; its Card is next: show the approval again for its Card alone" }, "en")).toBe("Gmail is connected. Next, its Card.");
    expect(approvalAnswerWords({ type: "approval", answer: "failed: Gmail is connected; its Card is next: show the approval again for its Card alone" }, "fr")).toBe("Gmail est connecté. Ensuite, la Card.");
    // A real failure still says so, in owner words: the reason after "failed: " is written for Coach, never shown (21 Nov,
    // the owner on the emulator read "Show the rules again as a new notice…"). With the owner's note, that's shown.
    expect(approvalAnswerWords({ type: "approval", answer: "failed: Gmail is connected; KIFF didn't issue the taf's Cards: the draft is revoked; show the approval again for its Card alone" }, "en"))
      .toBe("Approve · it didn't go through");
    expect(approvalAnswerWords({ type: "approval", answer: "failed: draft it again over the taf's connected tools only", note: "Nothing was approved. Coach drafts it again." }, "en"))
      .toBe("Approve · it didn't go through: Nothing was approved. Coach drafts it again.");
    expect(approvalAnswerWords({ type: "approval", answer: "failed: draft it again" }, "fr")).toBe("Approuver · cela n'a pas abouti");
  });

  // The owner's Reject read "Approved · signed in to Stripe" on Windows (17 Nov): an app read anything that wasn't the
  // English "Declined" as approved. tafs cloud sends the answer settled, in no language, so every app maps the same values.
  it("settles an approval card's answer for the apps: approved, already approved, connected, declined or failed, nothing else", () => {
    const next = "failed: Gmail is connected; its Card is next: show the approval again for its Card alone";
    expect(settledApprovalAnswer("approved")).toBe("approved");
    expect(settledApprovalAnswer("declined")).toBe("declined");
    expect(settledApprovalAnswer("already approved: Stripe is connected and its Card is confirmed")).toBe("already approved");
    expect(settledApprovalAnswer("already approved")).toBe("already approved");
    expect(settledApprovalAnswer(next)).toBe("connected");
    const failed = "failed: Gmail is connected; KIFF didn't issue the taf's Cards: the draft is revoked; show the approval again for its Card alone";
    expect(settledApprovalAnswer(failed)).toBe(failed);
    expect(settledApprovalAnswer("failed: planque answered 409")).toBe("failed: planque answered 409");
    // Anything else is nothing: the apps read it as not approved.
    for (const other of [undefined, null, 3, "", "Approved", "approve", "approvedish", "signed in", "Declined"]) expect(settledApprovalAnswer(other)).toBeUndefined();
  });

  it("says it didn't go through when the Card isn't the draft the card showed, or isn't a draft any more", async () => {
    for (const draft of [{ status: "draft", version: "v4", agent_id: "refunds-x", cards: ONE_CARD }, { status: "revoked", version: "v3", agent_id: "refunds-x", cards: ONE_CARD }]) {
      const { s, w, r } = await reused(draft as Services["drafts"][string]);
      s.grants = [grant({ status: "connected" })];
      const rep = await w.call("POST", `${ASK}/${(r.body.ask as { id: string }).id}`, { result: "connected" }, SIGNED);
      expect(String(rep.body.answer), draft.status + draft.version).toMatch(/^failed: Stripe is connected; KIFF didn't issue the taf's Cards: /);
    }
  });

  it("signs the connection alone, then tells Coach the Card isn't approved, and the next card asks the Card with its limits", async () => {
    const { s, w, r } = await reused();
    expect(r.body).toMatchObject({ outcome: "sign", ask: { step: "connect" } });
    expect(r.body.ask).not.toHaveProperty("kiff");
    // The phone's approve went through: Gmail is connected, the draft is still a draft.
    s.grants = [grant({ status: "connected" })];
    const rep = await w.call("POST", `${ASK}/${(r.body.ask as { id: string }).id}`, { result: "connected" }, SIGNED);
    // By design, the Card next: Coach shows it again alone, and the person never reads it as a failure.
    expect(rep.body.answer).toBe("failed: Stripe is connected; its Card is next: show the approval again for its Card alone");
    expect(rep.body.note).toBe("Stripe is connected. Next, its Card.");
    expect(w.told()?.answer).toBe(rep.body.answer);
    expect(w.stored.status).toBe("RUNNING");
    // Coach shows the approval again: the connection is in, so the Card alone, under the fingerprint.
    w.agents.ask(w.stored, { type: "approval", id: "a2", grant: "g_1", draft: "drf_1" });
    const ASK2 = `/v1/tafs/${COACH}/components/a2/approval`;
    const again = await w.call("POST", ASK2, { grant: "g_1", shown_digest: grant().digest, draft: "drf_1", shown_draft: "v3" });
    expect(again.body).toMatchObject({ outcome: "sign", ask: { step: "cards", key: "critical", kiff: { draft: "drf_1", version: "v3" } } });
    s.drafts.drf_1 = { status: "confirmed", version: "v3", agent_id: "refunds-x" };
    const done = await w.call("POST", `${ASK2}/${(again.body.ask as { id: string }).id}`, { result: "confirmed" }, SIGNED);
    expect(done.body.answer).toBe("approved");
    const a2 = (w.stored.requirements ?? []).find((q) => (q.input as { id?: string }).id === "a2")?.result as { answer?: unknown };
    expect(a2.answer).toBe("approved");
  });

  it("is approved when the draft is issued at the version shown, and never for another version or a draft that's gone", async () => {
    const ok = await reused({ status: "confirmed", version: "v3", agent_id: "refunds-x" });
    ok.s.grants = [grant({ status: "connected" })];
    expect((await ok.w.call("POST", `${ASK}/${(ok.r.body.ask as { id: string }).id}`, { result: "connected" }, SIGNED)).body.answer).toBe("approved");
    const other = await reused();
    other.s.grants = [grant({ status: "connected" })];
    other.s.drafts.drf_1 = { status: "confirmed", version: "v4", agent_id: "refunds-x" };
    const r = await other.w.call("POST", `${ASK}/${(other.r.body.ask as { id: string }).id}`, { result: "connected" }, SIGNED);
    expect(r.body.answer).toMatch(/^failed: Stripe is connected; KIFF didn't issue the taf's Cards: What you're asked to approve changed/);
    const gone = await reused();
    gone.s.grants = [grant({ status: "connected" })];
    delete gone.s.drafts.drf_1;
    const g = await gone.w.call("POST", `${ASK}/${(gone.r.body.ask as { id: string }).id}`, { result: "connected" }, SIGNED);
    expect(g.body.answer).toMatch(/^failed: Stripe is connected; KIFF didn't issue the taf's Cards: KIFF has no such draft/);
    // No code of KIFF's or kiff/agents' reaches Coach or the owner.
    for (const said of [r.body, g.body]) expect(JSON.stringify(said)).not.toMatch(/draft_not_ready|_not_|drf_/);
  });

  it("a card with no draft is approved on its connection, as before", async () => {
    const s = services();
    s.grants = [grant()];
    const w = await world(s);
    const r = await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant().request) });
    s.grants = [grant({ status: "connected" })];
    expect((await w.call("POST", `${ASK}/${(r.body.ask as { id: string }).id}`, { result: "connected" }, SIGNED)).body.answer).toBe("approved");
  });
});

// A sign-in planque already holds, reused for another taf: its first tools are declared in KIFF while they wait, and
// approving them connects at once, with no sign-in between. So they go with the Card under one fingerprint.
describe("a held sign-in's first tools and the Card, under one fingerprint", () => {
  const SIGNED = { "kiff-device-signature": "t=2026-10-16T12:00:30Z,v1=sig" };
  afterEach(() => { delete process.env.TAFS_TOOLS_AND_CARDS; });
  const held = (over: Record<string, unknown> = {}) => grant({ status: "pending", signed_in: true, declared: true, ...over });

  async function heldWorld(g = held(), can = ["tools_and_cards", "signs"]) {
    process.env.TAFS_TOOLS_AND_CARDS = "on";
    const s = services();
    s.grants = [g];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: ONE_CARD };
    const w = await world(s, { draft: "drf_1" });
    const r = await w.call("POST", ASK, { grant: "g_1", shown_digest: g.digest, draft: "drf_1", shown_draft: "v3", can });
    return { s, w, r };
  }

  it("asks the tools and the Card as one signature, marks the card so, and is approved once both hold", async () => {
    const { s, w, r } = await heldWorld();
    expect(r.body).toMatchObject({ outcome: "sign", ask: { step: "tools_and_cards", scheme: "kiff.requests.v1", key: "critical", kiff: { draft: "drf_1", version: "v3" } } });
    const chat = await w.call("GET", `/v1/tafs/${COACH}/conversation`, undefined, {}, "can=tools_and_cards,signs");
    expect((chat.body.components as Array<{ component: { id?: string; signs?: unknown } }>).find((c) => c.component.id === "a1")?.component.signs).toEqual({ card: true, takes: "fingerprint" });
    s.grants = [held({ status: "connected" })];
    s.drafts.drf_1 = { status: "confirmed", version: "v3", agent_id: "refunds-x" };
    const rep = await w.call("POST", `${ASK}/${(r.body.ask as { id: string }).id}`, { result: "confirmed" }, SIGNED);
    expect(rep.body.answer).toBe("approved");
    expect(w.told()?.answer).toBe("approved");
  });

  it("says which half didn't hold, so nothing is approved unsigned", async () => {
    const { s, w, r } = await heldWorld();
    s.grants = [held({ status: "connected" })];
    const rep = await w.call("POST", `${ASK}/${(r.body.ask as { id: string }).id}`, { result: "failed", why: "the confirm wasn't answered" }, SIGNED);
    expect(rep.body.answer).toMatch(/^failed: Stripe is connected; /);
  });

  it("keeps the two steps when the tools aren't declared, the sign-in isn't held, or the phone can't", async () => {
    expect((await heldWorld(held({ declared: false }))).r.body).toMatchObject({ ask: { step: "connect" } });
    expect((await heldWorld(held({ signed_in: false }))).r.body).toMatchObject({ ask: { step: "connect" } });
    expect((await heldWorld(held(), [])).r.body).toMatchObject({ ask: { step: "connect" } });
  });

  // A phone that signs both but draws the Card only for a connected grant (builds already in people's hands) would
  // sign this Card without its limits on screen: the new path is only for one that draws by the mark (can: signs).
  it("is offered only to a phone that draws the card by its mark", async () => {
    const old = await heldWorld(held(), ["tools_and_cards"]);
    expect(old.r.body).toMatchObject({ ask: { step: "connect" } });
    const chat = await old.w.call("GET", `/v1/tafs/${COACH}/conversation`, undefined, {}, "can=tools_and_cards");
    expect((chat.body.components as Array<{ component: { id?: string; signs?: unknown } }>).find((c) => c.component.id === "a1")?.component.signs)
      .toEqual({ card: false, words: "Its Card: you approve it next", takes: "tap" });
    // Added tools on a connected app are as before: a phone that signs both takes them with the Card.
    const added = await heldWorld(grant({ status: "pending", connected: [{ tool: "list_charges" }] }), ["tools_and_cards"]);
    expect(added.r.body).toMatchObject({ ask: { step: "tools_and_cards" } });
  });
});

// What the fingerprint signs is on the card before Approve (signs), from decide's own rule: the apps can't know the
// step before the tap, and a card that signs the connection alone must never draw the Card as being signed.
describe("each waiting approval card says what its fingerprint signs", () => {
  afterEach(() => { delete process.env.TAFS_TOOLS_AND_CARDS; delete process.env.TAFS_KEY_AND_CARDS; });
  const CHAT = `/v1/tafs/${COACH}/conversation`;
  const signs = async (w: Awaited<ReturnType<typeof world>>, query?: string, headers: Record<string, string> = {}) => {
    const r = await w.call("GET", CHAT, undefined, headers, query);
    expect(r.status).toBe(200);
    return ((r.body.components as Array<{ component: { id?: string; signs?: unknown } }>).find((c) => c.component.id === "a1"))?.component.signs;
  };

  it("marks the Card signed or next exactly as decide then asks, for each kind of grant", async () => {
    process.env.TAFS_TOOLS_AND_CARDS = "on";
    const s = services();
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: ONE_CARD };
    const w = await world(s, { draft: "drf_1" });
    // First tools with no held sign-in: the connection alone, the Card next.
    s.grants = [grant({ status: "pending" })];
    expect(await signs(w, "can=tools_and_cards")).toEqual({ card: false, words: "Its Card: you approve it next", takes: "tap" });
    const asked = await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant().request), draft: "drf_1", shown_draft: "v3", can: ["tools_and_cards"] });
    expect(asked.body).toMatchObject({ ask: { step: "connect" } });
    // Added tools on a connected app, with a phone that can sign both: the Card is signed now.
    const adding = grant({ status: "pending", connected: [{ tool: "list_charges" }] });
    s.grants = [adding];
    expect(await signs(w, "can=tools_and_cards")).toEqual({ card: true, takes: "fingerprint" });
    // The same, from a phone that can't: the connection alone.
    expect(await signs(w)).toEqual({ card: false, words: "Its Card: you approve it next", takes: "tap" });
    // Connected already: the card signs the Card.
    s.grants = [grant({ status: "connected" })];
    expect(await signs(w)).toEqual({ card: true, takes: "fingerprint" });
  });

  it("says it in the reader's language, and an app's first connection says it before its draft exists", async () => {
    const s = services();
    s.grants = [grant({ status: "pending" })];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: ONE_CARD };
    const fr = await world(s, { draft: "drf_1" });
    expect(await signs(fr, undefined, { "accept-language": "fr" })).toEqual({ card: false, words: "Sa Card : vous l'approuvez ensuite", takes: "tap" });
    // The first sign-in's card, before its tools: no draft can exist yet, and a Card always follows.
    const first = await world(s);
    expect(await signs(first)).toEqual({ card: false, words: "Its Card: you approve it next", takes: "tap" });
    expect(await signs(first, undefined, { "accept-language": "de" })).toEqual({ card: false, words: "Seine Card: Du genehmigst sie als Nächstes", takes: "tap" });
    // A sign-in again keeps the Card in force, and tools added to a connected app with no draft name no Card: no words.
    s.grants = [grant({ status: "needs_reconnect" })];
    expect(await signs(first)).toEqual({ card: false, takes: "tap" });
    s.grants = [grant({ status: "pending", connected: [{ tool: "list_charges" }] })];
    expect(await signs(first)).toEqual({ card: false, takes: "tap" });
  });

  it("leaves the card as it was without the phone's proof, when the connections can't be read, or once answered", async () => {
    const s = services();
    s.grants = [grant({ status: "pending" })];
    const w = await world(s, { draft: "drf_1" });
    expect(await signs(w, undefined, { "planque-device-signature": "" })).toBeUndefined();
    s.grants = [];
    expect(await signs(w)).toBeUndefined();
    s.grants = [grant({ status: "connected" })];
    s.drafts.drf_1 = { status: "confirmed", version: "v3", agent_id: "refunds-x" };
    await w.agents.api.answerCard("t_acme", COACH, w.stored.id, "a1", "approved", ANA.requester);
    expect(await signs(w)).toBeUndefined();
  });

  // The owner on Android 95: "No fingerprint for the next 4 minutes" showed on a card whose Approve took the fingerprint
  // (the tools and their Card, signed together). Each card says which it is, from the key decide then asks with.
  it("says whether Approve is a tap in the approval window or the fingerprint, as decide then asks", async () => {
    process.env.TAFS_TOOLS_AND_CARDS = "on";
    const s = services();
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: ONE_CARD };
    const w = await world(s, { draft: "drf_1" });
    // Tools and their Card under one signature: always the approval key.
    s.grants = [grant({ status: "pending", connected: [{ tool: "list_charges" }] })];
    expect(await signs(w, "can=tools_and_cards")).toMatchObject({ takes: "fingerprint" });
    const both = await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(s.grants[0].request), draft: "drf_1", shown_draft: "v3", can: ["tools_and_cards"] });
    expect(both.body).toMatchObject({ ask: { key: "critical" } });
    // Connecting alone: a tap inside the window.
    s.grants = [grant({ status: "pending" })];
    expect(await signs(w)).toMatchObject({ takes: "tap" });
    // A Card alone: the key KIFF names for its draft.
    s.grants = [grant({ status: "connected" })];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", confirm_key: "window", cards: ONE_CARD };
    expect(await signs(w)).toMatchObject({ takes: "tap" });
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", confirm_key: "critical", cards: ONE_CARD };
    expect(await signs(w)).toMatchObject({ takes: "fingerprint" });
  });

  it("says nothing of the key for a Card whose draft KIFF doesn't answer", async () => {
    const s = services();
    s.grants = [grant({ status: "connected" })];
    const w = await world(s, { draft: "drf_gone" });
    const said = await signs(w) as { card?: boolean; takes?: string } | undefined;
    expect(said?.card).toBe(true);
    expect(said?.takes).toBeUndefined();
  });
});

// Negative 1 on #919: a draft whose Cards also allow a tool this connection doesn't give is never signed under it,
// whichever step signs the Card; a tool of the same taf's other connection is its own, so it may be on the same Card.
describe("never signs a Card that allows more than the taf's connections give", () => {
  const TOOLS = { tools: [{ tool: "refund", name: "refund_k3f9" }] };
  const API = { kind: "api_key", api: { url: "https://example.com/desk/openapi.json", digest: "a".repeat(64), base: "https://desk.example.com/v1", auth: { in: "header", name: "X-Desk-Key" } }, ...TOOLS };
  const cards = (extra: string[]) => [{ grants: [{ action: "refund_k3f9" }, { action: "list_charges_k3f9" }, ...extra.map((action) => ({ action }))], aggregates: [] }];
  afterEach(() => { delete process.env.TAFS_TOOLS_AND_CARDS; delete process.env.TAFS_KEY_AND_CARDS; });

  for (const [step, flag, made] of [
    ["tools_and_cards", "TAFS_TOOLS_AND_CARDS", () => grant({ status: "pending", connected: [{ tool: "list_charges", name: "list_charges_k3f9" }] }, TOOLS)],
    ["key_and_cards", "TAFS_KEY_AND_CARDS", () => grant({}, { ...API, tools: [{ tool: "refund", name: "refund_k3f9" }, { tool: "list_charges", name: "list_charges_k3f9" }] })],
  ] as const) {
    it(`refuses ${step} when the Card also allows a tool no connection of the taf gives, and signs it once another of its connections does`, async () => {
      process.env[flag] = "on";
      const s = services();
      const g = made();
      s.grants = [g];
      s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: cards(["send_email_everyone"]) };
      const w = await world(s, { draft: "drf_1" });
      const ask = { grant: "g_1", shown_digest: g.digest, draft: "drf_1", shown_draft: "v3", can: [step] };
      const r = await w.call("POST", ASK, ask);
      expect(r.body).toMatchObject({ outcome: "answered", answer: expect.stringMatching(/^failed: the Card covers "send_email_everyone"/) });
      expect((r.body as { note: string }).note).toContain("send_email_everyone");
      // The same taf's mailbox gives it: one Card over both apps is the taf's own, so it's asked.
      s.grants = [g, grant({ id: "g_2", status: "connected", vendor: "Mail" }, { server: "mcp.mail.example", tools: [{ tool: "send", name: "send_email_everyone" }] })];
      expect((await w.call("POST", ASK, ask)).body).toMatchObject({ outcome: "sign", ask: { step } });
    });
  }
});

// Eval 18, intros replay: two key apps on one host (a sheet and a mailbox). The Card over both was refused with "show
// desk.example.com's approval first", the same name as the app asked, so Coach couldn't tell which went first: it asked
// the mailbox's key alone, then put the Card on the mailbox again (one grant approved twice). The waiting app is named
// so it differs from this one, by its tools, with its grant id for Coach.
describe("the app that waits is named so it differs from this one (other_app_first, Eval 18)", () => {
  const base = (name: string, tool: string, title: string) => ({ kind: "api_key", server: "desk.example.com",
    api: { url: `https://desk.example.com/${name}/openapi.json`, digest: "a".repeat(64), base: "https://desk.example.com/v1", auth: { in: "header", name: "X-Desk-Key" } },
    tools: [{ tool, name: `${tool}_k3`, title }] });
  const sheet = (over: Record<string, unknown> = {}) => grant({ id: "g_1", vendor: "desk.example.com", title: "desk.example.com", ...over }, base("sheet", "append_row", "Append a row"));
  const mailbox = (over: Record<string, unknown> = {}) => grant({ id: "g_2", vendor: "desk.example.com", title: "desk.example.com", ...over }, base("mail", "send_mail", "Send an email"));
  const both = [{ id: "c1", subject: "refunds-x", grants: [{ action: "append_row_k3" }, { action: "send_mail_k3" }], aggregates: [] }];

  it("names the waiting app by its tools and grant, and this one too, when both are one host", async () => {
    const s = services();
    s.grants = [sheet({ status: "connected" }), mailbox()];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: both };
    const w = await world(s, { draft: "drf_1" });
    const r = await w.call("POST", ASK, { grant: "g_1", shown_digest: sheet({ status: "connected" }).digest, draft: "drf_1", shown_draft: "v3" });
    expect(r.body).toMatchObject({ outcome: "answered" });
    const answer = String(r.body.answer);
    expect(answer).toContain("desk.example.com (Send an email)'s tools (grant g_2)");
    expect(answer).toContain("desk.example.com (Append a row) (grant g_1) is connected already: never show its approval again");
    expect(answer).not.toMatch(/show desk\.example\.com's approval/);
    // Eval 19, intros: the Card comes with the waiting app's approval, with the same draft; this one, connected, is never
    // asked again (told "then show this one again: it carries the Card", Coach showed the sheet's approval twice).
    expect(answer).toContain("The Card comes with the last app's approval: show desk.example.com (Send an email)'s approval with this same draft: it carries the Card.");
    expect(answer).not.toMatch(/again: it carries the Card|then show this one/);
    // The person reads the waiting app's name with its tools, never two apps called the same.
    expect(String(r.body.note)).toContain("desk.example.com (Send an email)");
  });

  it("names an app on another host by its name alone", async () => {
    const s = services();
    s.grants = [sheet({ status: "connected" }), grant({ id: "g_2", vendor: "Mail", title: "Mail · Demo Shop" }, { server: "mcp.mail.example", tools: [{ tool: "send_mail", name: "send_mail_k3" }] })];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: both };
    const w = await world(s, { draft: "drf_1" });
    const r = await w.call("POST", ASK, { grant: "g_1", shown_digest: sheet({ status: "connected" }).digest, draft: "drf_1", shown_draft: "v3" });
    expect(r.body).toMatchObject({ outcome: "answered" });
    expect(String(r.body.answer)).toContain("show Mail · Demo Shop's approval with this same draft: it carries the Card");
    expect(String(r.body.note)).not.toContain("(");
  });

  // Eval 44162956815's night: five taps on two approval cards were answered 200, and the log said nothing of what tafs cloud
  // decided. Each tap now leaves its decision's code in the log (approvalAskLine), never in the phone's answer.
  it("logs each tap's decision with its code: other_app_first, and out_of_date for a card shown before it changed", async () => {
    const lines: string[] = [];
    const log = vi.spyOn(console, "log").mockImplementation((...a: unknown[]) => { lines.push(a.map(String).join(" ")); });
    try {
      const s = services();
      s.grants = [sheet({ status: "connected" }), mailbox()];
      s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: both };
      const w = await world(s, { draft: "drf_1" });
      const first = await w.call("POST", ASK, { grant: "g_1", shown_digest: sheet({ status: "connected" }).digest, draft: "drf_1", shown_draft: "v3" });
      expect(first.body).toMatchObject({ outcome: "answered" });
      expect(first.body).not.toHaveProperty("code");
      const asks = () => lines.filter((l) => l.includes('"event":"approval_ask"')).map((l) => JSON.parse(l));
      expect(asks().at(-1)).toEqual({ event: "approval_ask", component: "a1", kind: "approval", grant: "g_1", card_draft: true, shown_draft: "v3",
        outcome: "answered", code: "other_app_first", step: null });
      // A card the phone drew before its connection changed: read again, said in the log as out_of_date.
      const s2 = services();
      const w2 = await world(s2);
      const stale = await w2.call("POST", ASK, { grant: "g_1", shown_digest: "old" });
      expect(stale.body).toEqual({ outcome: "read_again", note: WORDS.outOfDate });
      expect(asks().at(-1)).toMatchObject({ grant: "g_1", card_draft: false, outcome: "read_again", code: "out_of_date", step: null });
    } finally {
      log.mockRestore();
    }
  });
});

// Eval 20b, intros run 1: told other_app_first, Coach showed the mailbox's key approval without the draft, the owner
// approved it, and the Card came on a third approval. tafs cloud now keeps the draft the waiting app's approval must
// carry, and answers that approval shown without it on the chat's read, before the owner sees Approve.
describe("the waiting app's approval is never shown without the waiting draft (other_app_first, Eval 20b)", () => {
  const CHAT = `/v1/tafs/${COACH}/conversation`;
  const base = (name: string, tool: string, title: string) => ({ kind: "api_key", server: "desk.example.com",
    api: { url: `https://desk.example.com/${name}/openapi.json`, digest: "a".repeat(64), base: "https://desk.example.com/v1", auth: { in: "header", name: "X-Desk-Key" } },
    tools: [{ tool, name: `${tool}_k3`, title }] });
  const sheet = (over: Record<string, unknown> = {}) => grant({ id: "g_1", vendor: "desk.example.com", title: "desk.example.com", ...over }, base("sheet", "append_row", "Append a row"));
  const mailbox = (over: Record<string, unknown> = {}) => grant({ id: "g_2", vendor: "desk.example.com", title: "desk.example.com", ...over }, base("mail", "send_mail", "Send an email"));
  const both = [{ id: "c1", subject: "refunds-x", grants: [{ action: "append_row_k3" }, { action: "send_mail_k3" }], aggregates: [] }];
  const services2 = () => {
    const s = services();
    s.grants = [sheet({ status: "connected" }), mailbox()];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: both };
    return s;
  };
  const a1 = (r: { body: Record<string, unknown> }) =>
    (r.body.components as Array<{ component: { id?: string; signs?: unknown }; answer?: { state: string } }>).find((c) => c.component.id === "a1");
  const NAME = "desk.example.com (Send an email)";

  it("keeps the draft and the waiting app's name when the Card is refused for the other app first", async () => {
    const w = await world(services2(), { draft: "drf_1" });
    const r = await w.call("POST", ASK, { grant: "g_1", shown_digest: sheet({ status: "connected" }).digest, draft: "drf_1", shown_draft: "v3" });
    expect(String(r.body.answer)).toMatch(/^failed: the Card also covers /);
    expect(await w.coach.get(waitingDraftKey("t_acme", "g_2"))).toEqual({ draft: "drf_1", name: NAME, at: NOW.toISOString() });
    // Kept against the waiting app only: the first app's own approval isn't held to it.
    expect(await w.coach.get(waitingDraftKey("t_acme", "g_1"))).toBeUndefined();
  });

  it("answers that app's approval shown with no draft on the chat's read, once, so the owner never approves its key alone", async () => {
    const w = await world(services2(), { grant: "g_2" });
    await w.coach.put(waitingDraftKey("t_acme", "g_2"), { draft: "drf_1", name: NAME, at: NOW.toISOString() }, new Date(NOW.getTime() + WAITING_DRAFT_KEPT_MS));
    const first = await w.call("GET", CHAT, undefined);
    expect(first.status).toBe(200);
    const answer = String(w.told()?.answer);
    expect(answer).toBe(`failed: ${NAME}'s approval was shown without the Card draft drf_1, which waits for it: ${NAME}'s connection and the Card go under one approval, so this one is never shown to the owner. ${REFUSAL_CLOSED} Show ${NAME}'s approval again with draft drf_1: it carries the Card. Show it once.`);
    // Drawn answered on this read: no signs, so no Approve.
    expect(a1(first)?.answer?.state).toBe("answered");
    expect(a1(first)?.component.signs).toBeUndefined();
    // Another read draws it answered and tells nothing more; another account's card by the same id is untouched.
    expect(a1(await w.call("GET", CHAT, undefined))?.answer?.state).toBe("answered");
    expect(w.boTold()).toBeUndefined();
    // The tap on it finds it waits no more.
    expect((await w.call("POST", ASK, { grant: "g_2", shown_digest: mailbox().digest })).status).toBe(409);
  });

  it("never signs that app's key alone on a tap either, as when a phone drew Approve before the chat's check", async () => {
    const s = services2();
    const w = await world(s, { grant: "g_2" });
    await w.coach.put(waitingDraftKey("t_acme", "g_2"), { draft: "drf_1", name: NAME, at: NOW.toISOString() }, new Date(NOW.getTime() + WAITING_DRAFT_KEPT_MS));
    const r = await w.call("POST", ASK, { grant: "g_2", shown_digest: mailbox().digest });
    expect(r.body).toMatchObject({ outcome: "answered", note: "desk.example.com is approved together with its Card. Coach shows it again with the Card." });
    expect(String(r.body.answer)).toMatch(/^failed: desk\.example\.com \(Send an email\)'s approval was shown without the Card draft drf_1/);
    expect(s.checks).toEqual([]);
    // With the draft, the tap goes on as before (no split answer).
    const w2 = await world(services2(), { grant: "g_2", draft: "drf_1" });
    await w2.coach.put(waitingDraftKey("t_acme", "g_2"), { draft: "drf_1", name: NAME, at: NOW.toISOString() }, new Date(NOW.getTime() + WAITING_DRAFT_KEPT_MS));
    const r2 = await w2.call("POST", ASK, { grant: "g_2", shown_digest: mailbox().digest, draft: "drf_1", shown_draft: "v3" });
    expect(String(r2.body.answer ?? "")).not.toContain("was shown without the Card draft");
  });

  it("forgets the note once its draft is confirmed, so a later draftless approval of that app is drawn and tapped as normal", async () => {
    // Run 1's sequence done right: the mailbox approved with the draft, so KIFF shows it confirmed. Then a Sign in again
    // (or a tools addition) of the same app, with no draft.
    const s = services2();
    s.drafts.drf_1 = { ...s.drafts.drf_1!, status: "confirmed" };
    const w = await world(s, { grant: "g_2" });
    const put = () => w.coach.put(waitingDraftKey("t_acme", "g_2"), { draft: "drf_1", name: NAME, at: NOW.toISOString() }, new Date(NOW.getTime() + WAITING_DRAFT_KEPT_MS));
    await put();
    expect(a1(await w.call("GET", CHAT, undefined))?.answer).toBeUndefined();
    expect(w.told()).toBeUndefined();
    expect(await w.coach.get(waitingDraftKey("t_acme", "g_2"))).toBeUndefined();
    await put();
    expect((await w.call("POST", ASK, { grant: "g_2", shown_digest: mailbox().digest })).body).toMatchObject({ outcome: "sign", ask: { step: "connect" } });
    expect(await w.coach.get(waitingDraftKey("t_acme", "g_2"))).toBeUndefined();
    // A draft KIFF no longer has: the same.
    delete s.drafts.drf_1;
    await put();
    expect((await w.call("POST", "/v1/connect/grants/g_2/check", { draft: "" })).body).toEqual({ outcome: "ok", checked_on_read: false, rules_unchecked: true });
  });

  it("answers the e2e's /check for a draftless card as the chat's read does (split_approval), and ok with no note", async () => {
    const w = await world(services2(), { grant: "g_2" });
    const CHECK = "/v1/connect/grants/g_2/check";
    expect((await w.call("POST", CHECK, { draft: "" })).body).toEqual({ outcome: "ok", checked_on_read: false, rules_unchecked: true });
    await w.coach.put(waitingDraftKey("t_acme", "g_2"), { draft: "drf_1", name: NAME, at: NOW.toISOString() }, new Date(NOW.getTime() + WAITING_DRAFT_KEPT_MS));
    const r = await w.call("POST", CHECK, { draft: "" });
    expect(r.status).toBe(200);
    expect(r.body).toMatchObject({ outcome: "answered", code: "split_approval", redraft: true, checked_on_read: true, rules_unchecked: true });
    expect(String(r.body.answer)).toMatch(new RegExp(`^failed: ${NAME.replace(/[().]/g, "\\$&")}'s approval was shown without the Card draft drf_1`));
    // Only checked: Coach heard nothing, and the card still waits.
    expect(w.told()).toBeUndefined();
  });

  it("leaves the approval with the draft, a redraft's, and any app with no waiting draft as they were", async () => {
    const note = { draft: "drf_1", name: NAME, at: NOW.toISOString() };
    expect(splitApproval({ grant: "g_2", draft: "drf_1" }, note)).toBeUndefined();
    expect(splitApproval({ grant: "g_2", draft: "drf_2" }, note)).toBeUndefined();
    expect(splitApproval({ grant: "g_2" }, undefined)).toBeUndefined();
    expect(splitApproval({ grant: "g_2" }, { draft: "", name: NAME })).toBeUndefined();
    expect(splitApproval({ grant: "g_2" }, note)).toMatch(/^failed: /);
    // On the chat's read: the waiting app's approval with the draft isn't answered by this check.
    const w = await world(services2(), { grant: "g_2", draft: "drf_1" });
    await w.coach.put(waitingDraftKey("t_acme", "g_2"), note, new Date(NOW.getTime() + WAITING_DRAFT_KEPT_MS));
    await w.call("GET", CHAT, undefined);
    expect(String(w.told()?.answer ?? "")).not.toContain("was shown without the Card draft");
  });
});

describe("one signature for a key and its Card (key_and_cards, docs/secure-connection.md)", () => {
  const API = { kind: "api_key", api: { url: "https://example.com/desk/openapi.json", digest: "a".repeat(64), base: "https://desk.example.com/v1", auth: { in: "header", name: "X-Desk-Key" } } };
  const keyGrant = (over: Record<string, unknown> = {}) => grant(over, API);
  const SIGNED = { "kiff-device-signature": "t=2026-10-16T12:00:30Z,v1=sig" };
  afterEach(() => { delete process.env.TAFS_KEY_AND_CARDS; });

  async function keyWorld(on: boolean) {
    if (on) process.env.TAFS_KEY_AND_CARDS = "on";
    const s = services();
    s.grants = [keyGrant()];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: ONE_CARD };
    const w = await world(s, { draft: "drf_1" });
    const r = await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(keyGrant().request), draft: "drf_1", shown_draft: "v3", can: ["key_and_cards"] });
    return { s, w, r };
  }

  it("asks a key grant with its Card's draft as one signature over both requests, behind the fingerprint", async () => {
    const { r, w } = await keyWorld(true);
    expect(r.body).toMatchObject({
      outcome: "sign",
      ask: { step: "key_and_cards", scheme: "kiff.requests.v1", message: "", key: "critical", planque: { grant: "g_1", body: { digest: keyGrant().digest } }, kiff: { draft: "drf_1", version: "v3" } },
    });
    expect(w.told()).toBeUndefined();
  });

  it("asks it only of a phone that says it can sign it, only for a grant that takes a key, and only with a draft", async () => {
    process.env.TAFS_KEY_AND_CARDS = "on";
    const s = services();
    s.grants = [keyGrant()];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: ONE_CARD };
    const w = await world(s, { draft: "drf_1" });
    const shown = sha256(keyGrant().request);
    // An app from before (Android up to 67, today's iOS) doesn't say it: the two steps, flag on.
    expect((await w.call("POST", ASK, { grant: "g_1", shown_digest: shown, draft: "drf_1", shown_draft: "v3" })).body).toMatchObject({ ask: { step: "connect" } });
    // No draft on the card: the key alone.
    expect((await w.call("POST", ASK, { grant: "g_1", shown_digest: shown, can: ["key_and_cards"] })).body).toMatchObject({ ask: { step: "connect" } });
    // A grant that signs in at the vendor (no key): its tools come after the sign-in, so the two steps.
    s.grants = [grant()];
    expect((await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant().request), draft: "drf_1", shown_draft: "v3", can: ["key_and_cards"] })).body)
      .toMatchObject({ ask: { step: "connect" } });
  });

  it("never answers approved for an issued Card when the key didn't go in, and notes the connection once it did", async () => {
    const { s, w, r } = await keyWorld(true);
    const id = (r.body.ask as { id: string }).id;
    s.drafts.drf_1 = { status: "confirmed", version: "v3", agent_id: "refunds-x" };
    const r1 = await w.call("POST", `${ASK}/${id}`, { result: "confirmed" }, SIGNED);
    expect(r1.body.answer).toMatch(/^failed: /);
    expect(r1.body.answer).not.toBe("approved");
    expect(await w.coach.get(connectedKey("t_acme", "g_1"))).toBeUndefined();
    // Asked again with the key in: approved, and the connection noted for the done mark on Coach's next card.
    const again = await keyWorld(true);
    again.s.grants = [keyGrant({ status: "connected" })];
    again.s.drafts.drf_1 = { status: "confirmed", version: "v3", agent_id: "refunds-x" };
    const id2 = (again.r.body.ask as { id: string }).id;
    expect((await again.w.call("POST", `${ASK}/${id2}`, { result: "confirmed" }, SIGNED)).body).toMatchObject({ answer: "approved" });
    expect(await again.w.coach.get(connectedKey("t_acme", "g_1"))).toEqual({ vendor: "Stripe", read_only: false });
  });

  it("keeps the two steps while the flag is off: the services don't take kiff.requests.v1 yet", async () => {
    const { r } = await keyWorld(false);
    expect(r.body).toMatchObject({ outcome: "sign", ask: { step: "connect" } });
  });

  it("checks the draft as the cards step does: a version the card didn't show is read again", async () => {
    process.env.TAFS_KEY_AND_CARDS = "on";
    const s = services();
    s.grants = [keyGrant()];
    s.drafts.drf_1 = { status: "draft", version: "v4", agent_id: "refunds-x", cards: ONE_CARD };
    const w = await world(s, { draft: "drf_1" });
    const r = await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(keyGrant().request), draft: "drf_1", shown_draft: "v3", can: ["key_and_cards"] });
    expect(r.body).toEqual({ outcome: "read_again", note: WORDS.outOfDate });
  });

  it("answers approved only when the key went in and the Card is issued, and says which half didn't", async () => {
    const { s, w, r } = await keyWorld(true);
    const id = (r.body.ask as { id: string }).id;
    // The key went in, the Card didn't: connected, and the Card still waits.
    s.grants = [keyGrant({ status: "connected" })];
    const half = await w.call("POST", `${ASK}/${id}`, { result: "failed", why: "the confirm wasn't answered" }, SIGNED);
    expect(half.body.answer).toMatch(/^failed: Stripe is connected; /);
    // Coach hears it, so its next card asks for the Card alone (the connection is done).
    expect(w.told()).toMatchObject({ answer: expect.stringMatching(/^failed: Stripe is connected; /) });
  });

  it("answers approved for both at once, and failed when the key never went in", async () => {
    const both = await keyWorld(true);
    both.s.grants = [keyGrant({ status: "connected" })];
    both.s.drafts.drf_1 = { status: "confirmed", version: "v3", agent_id: "refunds-x" };
    const id = (both.r.body.ask as { id: string }).id;
    expect((await both.w.call("POST", `${ASK}/${id}`, { result: "confirmed" }, SIGNED)).body).toMatchObject({ answer: "approved" });
    const none = await keyWorld(true);
    const nid = (none.r.body.ask as { id: string }).id;
    const r = await none.w.call("POST", `${ASK}/${nid}`, { result: "failed", why: "the key was refused" }, SIGNED);
    expect(r.body.answer).toMatch(/^failed: /);
    expect(r.body.answer).not.toMatch(/is connected/);
  });
});

// P0, 14 Nov 2026 (Win-back offers): a Card over two apps, signed once, approved only the card's own app's tools;
// the other app's tools stayed waiting while the setup heard "approved". Whatever the fix, one signature never
// covers a Card whose other app's tools it doesn't approve, and "approved" waits for every app the Card covers.
describe("a Card over two apps approves every app's tools, or says which waits (P0, 14 Nov)", () => {
  const SIGNED = { "kiff-device-signature": "t=2026-10-16T12:00:30Z,v1=sig" };
  afterEach(() => { delete process.env.TAFS_TOOLS_AND_CARDS; });
  const gmail = (over: Record<string, unknown> = {}) =>
    grant({ id: "g_1", vendor: "Gmail", signed_in: true, declared: true, ...over }, { server: "gmail.example", tools: [{ tool: "send", name: "send_draft_m1" }] });
  const stripe = (over: Record<string, unknown> = {}) =>
    grant({ id: "g_2", vendor: "Stripe", connected: [{ tool: "list_customers", name: "list_customers_k3f9" }], ...over },
      { tools: [{ tool: "list_customers", name: "list_customers_k3f9" }, { tool: "create_coupon", name: "create_coupon_k3f9" }] });
  const both = { grants: [{ action: "send_draft_m1" }, { action: "list_customers_k3f9" }, { action: "create_coupon_k3f9" }], aggregates: [] };

  async function twoApps(stripeNow = stripe()) {
    process.env.TAFS_TOOLS_AND_CARDS = "on";
    const s = services();
    s.grants = [gmail(), stripeNow];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: [both] };
    const w = await world(s, { draft: "drf_1" });
    const r = await w.call("POST", ASK, { grant: "g_1", shown_digest: gmail().digest, draft: "drf_1", shown_draft: "v3", can: ["tools_and_cards", "signs"] });
    return { s, w, r };
  }

  it("never signs the Card with only one app's tools while the other app's wait", async () => {
    const { r } = await twoApps();
    const ask = r.body.ask as { step?: string; planque?: unknown } | undefined;
    // One signature over the Card carries the other app's approve too, or the Card isn't in this signature at all.
    const signsCard = r.body.outcome === "sign" && (ask?.step === "tools_and_cards" || ask?.step === "cards");
    expect(signsCard && !JSON.stringify(ask?.planque ?? "").includes("g_2")).toBe(false);
  });

  it("says Stripe waits, and is never approved while it does", async () => {
    const { s, w, r } = await twoApps();
    const ask = r.body.ask as { id?: string } | undefined;
    if (r.body.outcome !== "sign" || !ask?.id) {
      // Not signed now: what it says names the app to approve first.
      expect(`${r.body.answer} ${r.body.note}`).toMatch(/Stripe/);
      return;
    }
    s.grants = [gmail({ status: "connected" }), stripe()];
    s.drafts.drf_1 = { status: "confirmed", version: "v3", agent_id: "refunds-x", cards: [both] };
    const rep = await w.call("POST", `${ASK}/${ask.id}`, { result: "confirmed" }, SIGNED);
    expect(rep.body.answer).not.toBe("approved");
    expect(String(rep.body.answer)).toMatch(/Stripe/);
  });

  it("with Stripe connected first, takes Gmail's tools and the Card in one signature, approved once both hold", async () => {
    const { s, w, r } = await twoApps(stripe({ status: "connected", connected: undefined }));
    expect(r.body).toMatchObject({ outcome: "sign", ask: { step: "tools_and_cards" } });
    s.grants = [gmail({ status: "connected" }), stripe({ status: "connected", connected: undefined })];
    s.drafts.drf_1 = { status: "confirmed", version: "v3", agent_id: "refunds-x", cards: [both] };
    expect((await w.call("POST", `${ASK}/${(r.body.ask as { id: string }).id}`, { result: "confirmed" }, SIGNED)).body.answer).toBe("approved");
  });

  // Defence in depth: the Card signed with Gmail's tools while Stripe was connected, but Stripe is waiting again by
  // the time the report comes (tools asked again): never "approved", and the answer names Stripe.
  it("is never approved while another app the Card covers waits, whenever that happens", async () => {
    const { s, w, r } = await twoApps(stripe({ status: "connected", connected: undefined }));
    expect(r.body).toMatchObject({ outcome: "sign", ask: { step: "tools_and_cards" } });
    s.grants = [gmail({ status: "connected" }), stripe()];
    s.drafts.drf_1 = { status: "confirmed", version: "v3", agent_id: "refunds-x", cards: [both] };
    const rep = await w.call("POST", `${ASK}/${(r.body.ask as { id: string }).id}`, { result: "confirmed" }, SIGNED);
    expect(rep.body.answer).not.toBe("approved");
    expect(String(rep.body.answer)).toMatch(/Stripe's tools \(grant g_2\) still wait/);
  });

  // Both apps waiting (Win-back: Gmail a held sign-in, Stripe added tools): each card moves forward, never sending the
  // person to the other. The first tapped is its own connect; the last carries the Card.
  it("Stripe's card, asked with the same draft while Gmail waits too, signs Stripe's tools rather than sending back to Gmail", async () => {
    process.env.TAFS_TOOLS_AND_CARDS = "on";
    const s = services();
    s.grants = [gmail(), stripe()];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: [both] };
    const w = await world(s, { draft: "drf_1", grant: "g_2" });
    const r2 = await w.call("POST", ASK, { grant: "g_2", shown_digest: stripe().digest, draft: "drf_1", shown_draft: "v3", can: ["tools_and_cards", "signs"] });
    expect(r2.body.outcome).toBe("sign");
    expect(JSON.stringify((r2.body.ask as { planque?: unknown }).planque ?? "")).toContain("g_2");
  });

  it("with both waiting, the first card is its own connect and the last carries the Card, approved once both hold", async () => {
    const { s, w, r } = await twoApps();
    // Gmail first: its tools alone, never the Card while Stripe waits.
    expect(r.body).toMatchObject({ outcome: "sign", ask: { step: "connect" } });
    expect(JSON.stringify((r.body.ask as { planque?: unknown }).planque)).toContain("g_1");
    s.grants = [gmail({ status: "connected" }), stripe()];
    const rep = await w.call("POST", `${ASK}/${(r.body.ask as { id: string }).id}`, { result: "connected" }, SIGNED);
    expect(rep.body.answer).not.toBe("approved");
    // Then Stripe's card: its tools with the Card, in one signature.
    const w2 = await world(s, { draft: "drf_1", grant: "g_2" });
    const r2 = await w2.call("POST", ASK, { grant: "g_2", shown_digest: stripe().digest, draft: "drf_1", shown_draft: "v3", can: ["tools_and_cards", "signs"] });
    expect(r2.body).toMatchObject({ outcome: "sign", ask: { step: "tools_and_cards" } });
    s.grants = [gmail({ status: "connected" }), stripe({ status: "connected", connected: undefined })];
    s.drafts.drf_1 = { status: "confirmed", version: "v3", agent_id: "refunds-x", cards: [both] };
    expect((await w2.call("POST", `${ASK}/${(r2.body.ask as { id: string }).id}`, { result: "confirmed" }, SIGNED)).body.answer).toBe("approved");
  });
});

// Eval 43681227913: the e2e confirmed the draft in KIFF itself, so none of Approve's checks ran on it. The check route runs
// the same checks for such a caller, and only checks: nothing signed, confirmed, kept, or told to Coach.
describe("the check route: Approve's checks on a draft, with nothing signed (POST /v1/connect/grants/{grant}/check)", () => {
  const CHECK = "/v1/connect/grants/g_1/check";
  const tools = [{ tool: "list_charges", name: "list_charges_noyy", title: "List charges", read_only: true },
    { tool: "create_refund", name: "refund_charge", title: "Refund a charge" }];
  const card = (actions: string[]) => [{ id: "c1", subject: "refunds-x", grants: actions.map((action) => ({ action })), on_exceed: "owner", aggregates: [] }];

  it("answers the refusal Approve would, with its reason code, and ok with the version once the draft passes", async () => {
    const s = services();
    s.grants = [grant({ status: "connected" }, { tools })];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: card(["refund_charge", "list_charges"]) };
    const w = await world(s);
    const logged: string[] = [];
    const spy = vi.spyOn(console, "log").mockImplementation((...a: unknown[]) => { logged.push(a.join(" ")); });
    try {
      const r = await w.call("POST", CHECK, { draft: "drf_1", shown_draft: "v3" });
      expect(r.status).toBe(200);
      expect(r.body).toEqual({ outcome: "answered", code: "vendor_name", redraft: true, checked_on_read: true, rules_unchecked: true, note: WORDS.registeredName("list_charges", "list_charges_noyy"),
        answer: expect.stringMatching(/^failed: the Card covers "list_charges", but this taf's connection gives that tool as "list_charges_noyy"/) });
      // The draft over the registered name passes: ok, with the version it was checked at.
      s.drafts.drf_1.cards = card(["refund_charge", "list_charges_noyy"]);
      expect((await w.call("POST", CHECK, { draft: "drf_1", shown_draft: "v3" })).body).toEqual({ outcome: "ok", version: "v3", checked_on_read: true, rules_unchecked: true });
      // The log has the reason code only: never the draft, its Cards or the tools.
      expect(logged.filter((l) => l.startsWith("approval check:"))).toEqual(["approval check: vendor_name", "approval check: ok"]);
      expect(logged.join("\n")).not.toMatch(/drf_1|list_charges|refund_charge/);
    } finally {
      spy.mockRestore();
    }
    // Nothing was signed or asked of KIFF's signature check, nothing kept, and Coach heard nothing.
    expect(s.checks).toEqual([]);
    expect(w.told()).toBeUndefined();
    // The grant was read with the caller's own phone proof.
    expect(s.proofs.every((p) => p === PROOF)).toBe(true);
  });

  it("answers each of Approve's answers: a draft another version than read, a read without Cards, a draft with none, a grant not this account's", async () => {
    const s = services();
    s.grants = [grant({ status: "connected" }, { tools })];
    s.drafts.drf_1 = { status: "draft", version: "v4", agent_id: "refunds-x", cards: card(["refund_charge", "list_charges_noyy"]) };
    const w = await world(s);
    const check = async (body: Record<string, unknown>, path = CHECK, headers: Record<string, string> = {}) => w.call("POST", path, body, headers);
    expect((await check({ draft: "drf_1", shown_draft: "v3" })).body).toMatchObject({ outcome: "read_again", code: "out_of_date" });
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x" };
    expect((await check({ draft: "drf_1", shown_draft: "v3" })).body).toEqual({ outcome: "read_again", code: "cards_unread", note: WORDS.cardsUnread, checked_on_read: true, rules_unchecked: true });
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: null as unknown as unknown[] };
    expect((await check({ draft: "drf_1", shown_draft: "v3" })).body).toMatchObject({ outcome: "answered", code: "no_card", note: WORDS.noCard });
    // A draft for another taf than the grant's, and a grant planque doesn't list for this account.
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "someone-else", cards: card(["refund_charge", "list_charges_noyy"]) };
    const otherTaf = (await check({ draft: "drf_1", shown_draft: "v3" })).body;
    expect(otherTaf).toMatchObject({ outcome: "answered", code: "other_taf" });
    // Not one a new draft mends: no redraft mark, so a caller leaves it to the tap.
    expect(otherTaf.redraft).toBeUndefined();
    expect((await check({ draft: "drf_1", shown_draft: "v3" }, "/v1/connect/grants/g_9/check")).body).toMatchObject({ outcome: "answered", code: "no_grant" });
    // KIFF has no such draft for this person.
    expect((await check({ draft: "drf_9", shown_draft: "v3" })).body).toMatchObject({ outcome: "answered", code: "no_draft" });
  });

  // An e2e harness kept its own copy of which cards the chat's read checks, and it differed from tafs cloud's in two steps,
  // so a refusal the owner would meet at the tap didn't show in the Eval. The route says it, by the chat read's own rule.
  it("never checks the agreed rules, even where they're required, and says so on every answer (rules_unchecked)", async () => {
    const s = services();
    s.grants = [grant({ status: "connected" }, { tools })];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: card(["refund_charge", "list_charges_noyy"]) };
    const w = await world(s);
    process.env.TAFS_RULES_REQUIRED = "on";
    try {
      // Approve would refuse this draft (no rules agreed in a conversation); the check route has none to read, so it says ok
      // and that the rules weren't checked: a caller never reads its ok as "the rules hold".
      expect((await w.call("POST", CHECK, { draft: "drf_1", shown_draft: "v3" })).body).toEqual({ outcome: "ok", version: "v3", checked_on_read: true, rules_unchecked: true });
      s.drafts.drf_1.version = "v4";
      expect((await w.call("POST", CHECK, { draft: "drf_1", shown_draft: "v3" })).body).toMatchObject({ outcome: "read_again", rules_unchecked: true });
    } finally {
      delete process.env.TAFS_RULES_REQUIRED;
    }
  });

  describe("says whether the chat's read checks the card (checked_on_read), by the chat read's rule", () => {
    afterEach(() => { delete process.env.TAFS_TOOLS_AND_CARDS; delete process.env.TAFS_KEY_AND_CARDS; });
    const g = (over: Record<string, unknown>, request: Record<string, unknown> = {}) => grant(over, { tools, ...request }) as unknown as Parameters<typeof checkedOnReadFor>[0];
    const all = ["tools_and_cards", "key_and_cards", "signs"];

    it("for each step: a Card alone, the tools or key with their Card, and never a connection alone", () => {
      process.env.TAFS_TOOLS_AND_CARDS = "on";
      process.env.TAFS_KEY_AND_CARDS = "on";
      // A connected grant's Card alone (cards): checked.
      expect(checkedOnReadFor(g({ status: "connected" }), "drf_1", [])).toBe(true);
      // A first connection (connect): never, even with a draft and every step the phone can sign.
      expect(checkedOnReadFor(g({ status: "pending" }), "drf_1", all)).toBe(false);
      // A pending grant signed in elsewhere but not declared yet: connect, not checked (where a copy took any signed-in grant).
      expect(checkedOnReadFor(g({ status: "pending", signed_in: true }), "drf_1", all)).toBe(false);
      // Tools added to a connected grant, or a held, declared sign-in, with their Card (tools_and_cards): checked.
      expect(checkedOnReadFor(g({ status: "pending", connected: [{ tool: "list_charges" }] }), "drf_1", all)).toBe(true);
      expect(checkedOnReadFor(g({ status: "pending", signed_in: true, declared: true }), "drf_1", all)).toBe(true);
      // A key grant with its Card (key_and_cards), in any approvable status, not only pending (where a copy took pending only).
      for (const status of ["pending", "approved", "needs_reconnect"]) {
        expect(checkedOnReadFor(g({ status }, { kind: "api_key" }), "drf_1", all), status).toBe(true);
      }
      // No draft, no grant: nothing to check.
      expect(checkedOnReadFor(g({ status: "connected" }), undefined, all)).toBe(false);
      expect(checkedOnReadFor(undefined, "drf_1", all)).toBe(false);
    });

    it("by what the phone can sign and the deploy's flags, as the chat's read does", () => {
      const added = g({ status: "pending", connected: [{ tool: "list_charges" }] });
      const key = g({ status: "pending" }, { kind: "api_key" });
      // Flags off: both combined steps sign the connection alone.
      expect(checkedOnReadFor(added, "drf_1", all)).toBe(false);
      expect(checkedOnReadFor(key, "drf_1", all)).toBe(false);
      process.env.TAFS_TOOLS_AND_CARDS = "on";
      process.env.TAFS_KEY_AND_CARDS = "on";
      // A phone that can't sign the combined step: the connection alone.
      expect(checkedOnReadFor(added, "drf_1", ["key_and_cards"])).toBe(false);
      expect(checkedOnReadFor(key, "drf_1", ["tools_and_cards"])).toBe(false);
      // A held sign-in's first tools need the phone that draws the card by its mark (`signs`).
      expect(checkedOnReadFor(g({ status: "pending", signed_in: true, declared: true }), "drf_1", ["tools_and_cards"])).toBe(false);
    });

    it("on the route, with the caller's `can`, on an ok and on a refusal alike", async () => {
      process.env.TAFS_TOOLS_AND_CARDS = "on";
      const s = services();
      s.grants = [grant({ status: "pending", connected: [{ tool: "list_charges_noyy" }] }, { tools })];
      s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: card(["refund_charge", "list_charges_noyy"]) };
      const w = await world(s);
      expect((await w.call("POST", CHECK, { draft: "drf_1", shown_draft: "v3" })).body).toMatchObject({ checked_on_read: false });
      expect((await w.call("POST", CHECK, { draft: "drf_1", shown_draft: "v3", can: ["tools_and_cards"] })).body).toMatchObject({ checked_on_read: true });
      expect((await w.call("POST", CHECK, { draft: "drf_1", shown_draft: "v3", can: "tools_and_cards,signs" })).body).toMatchObject({ checked_on_read: true });
      // A grant planque doesn't list: refused, and not checked on the read.
      expect((await w.call("POST", "/v1/connect/grants/g_9/check", { draft: "drf_1", shown_draft: "v3", can: ["tools_and_cards"] })).body)
        .toMatchObject({ code: "no_grant", checked_on_read: false });
      expect(s.checks).toEqual([]);
    });
  });

  it("refuses a request it can't check: no phone proof, no session, a body without the draft or the version read", async () => {
    const s = services();
    s.grants = [grant({ status: "connected" }, { tools })];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: card(["refund_charge", "list_charges_noyy"]) };
    const w = await world(s);
    expect((await w.call("POST", CHECK, { draft: "drf_1", shown_draft: "v3" }, { "planque-device-signature": "" })).status).toBe(503);
    expect((await w.call("POST", CHECK, { draft: "drf_1", shown_draft: "v3" }, {}, undefined, "nobody")).status).toBe(401);
    // Only someone who may manage the account's Cards, as for the secure connection sheet.
    expect((await w.call("POST", CHECK, { draft: "drf_1", shown_draft: "v3" }, {}, undefined, "cy")).status).toBe(403);
    for (const body of [{ draft: "drf_1" }, { draft: "../x", shown_draft: "v3" }, { draft: "drf_1", shown_draft: "v".repeat(300) }]) {
      expect((await w.call("POST", CHECK, body)).status).toBe(400);
    }
    // A card with no draft is a check of its own (the waiting draft, Eval 20b): nothing to refuse here, with the proof.
    expect((await w.call("POST", CHECK, { shown_draft: "v3" })).body).toEqual({ outcome: "ok", checked_on_read: false, rules_unchecked: true });
    expect((await w.call("POST", CHECK, { draft: "" }, { "planque-device-signature": "" })).status).toBe(503);
    expect((await w.call("POST", "/v1/connect/grants/g%2F1/check", { draft: "drf_1", shown_draft: "v3" })).status).toBe(400);
    expect(s.checks).toEqual([]);
  });
});

// Eval 43690861487's Refunds: the owner approved a Card that Approve then refused (a renamed tool's vendor name), and
// approved the redraft: two Card approvals for one app. The same check runs as the phone reads the chat, before the card
// is drawn: a refusal a redraft mends goes to Coach then, and the card is never drawn with Approve.
describe("a Card draft is checked as the phone reads the chat, before its card is drawn", () => {
  const CHAT = `/v1/tafs/${COACH}/conversation`;
  const tools = [{ tool: "list_charges", name: "list_charges_noyy", title: "List charges", read_only: true },
    { tool: "create_refund", name: "refund_charge", title: "Refund a charge" }];
  const card = (actions: string[]) => [{ id: "c1", subject: "refunds-x", grants: actions.map((action) => ({ action })), on_exceed: "owner", aggregates: [] }];
  const a1 = (r: { body: Record<string, unknown> }) =>
    (r.body.components as Array<{ component: { id?: string; signs?: unknown }; answer?: { state: string; words: string } }>).find((c) => c.component.id === "a1");
  async function refused() {
    const s = services();
    s.grants = [grant({ status: "connected" }, { tools })];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: card(["refund_charge", "list_charges"]) };
    return { s, w: await world(s, { draft: "drf_1" }) };
  }

  it("tells Coach Approve's refusal once, draws the card answered with no Approve, and logs the code only", async () => {
    const { s, w } = await refused();
    const logged: string[] = [];
    const spy = vi.spyOn(console, "log").mockImplementation((...a: unknown[]) => { logged.push(a.join(" ")); });
    let first, again;
    try {
      first = await w.call("GET", CHAT, undefined);
      again = await w.call("GET", CHAT, undefined);
    } finally {
      spy.mockRestore();
    }
    expect(first.status).toBe(200);
    // Coach heard the refusal as the card's answer, with the line that keeps the rest of the draft as agreed.
    expect(String(w.told()?.answer)).toMatch(/^failed: the Card covers "list_charges", but this taf's connection gives that tool as "list_charges_noyy".* Change only what this names; every other line stays exactly as agreed\.$/);
    // Eval 43705726781: "failed: …" read as an approval still waiting. Told on the read, it says the card is closed.
    expect(String(w.told()?.answer)).toContain(`${REFUSAL_CLOSED} ${DRAW_AGAIN} ${KEEP_THE_REST}`);
    expect(String(w.told()?.answer)).not.toContain("approved either");
    // The card is answered on this read already: no signs, so no Approve.
    const c = a1(first);
    expect(c?.answer?.state).toBe("answered");
    expect(c?.component.signs).toBeUndefined();
    // A second read tells Coach nothing more and checks nothing again.
    expect(a1(again)?.answer?.state).toBe("answered");
    expect(logged.filter((l) => l.startsWith("approval precheck:"))).toEqual(["approval precheck: vendor_name"]);
    expect(logged.join("\n")).not.toMatch(/drf_1|list_charges|refund_charge/);
    // Nothing was signed or asked of KIFF's signature check; the grants were read with the phone's own proof.
    expect(s.checks).toEqual([]);
    expect(s.proofs.every((p) => p === PROOF)).toBe(true);
    // The tap on the card it answered finds it waits no more.
    expect((await w.call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" })).status).toBe(409);
  });

  // 22 Nov, the emulator: a Card refused for rules not yet agreed read "Approve · it didn't go through", with no reason,
  // once Coach's answer was in the chat: kiff/agents records only the answer written for Coach, and the precheck's note
  // was drawn only while the card waited.
  it("keeps the owner's reason on every read once Coach's answer is in the chat, never the answer written for Coach", async () => {
    const { w } = await refused();
    const first = a1(await w.call("GET", CHAT, undefined))?.answer?.words;
    expect(first).toMatch(/^Approve · it didn't go through: \S/);
    // The answer is recorded now: this read draws it from the chat, not from the precheck, and says the same.
    const again = a1(await w.call("GET", CHAT, undefined))?.answer?.words;
    expect(again).toBe(first);
    expect(again).not.toMatch(/Draft it again|Change only what this names/);
    // A phone with no proof reads the same line: it's the chat's, not the check's.
    expect(a1(await w.call("GET", CHAT, undefined, { "planque-device-signature": "" }))?.answer?.words).toBe(first);
    // Another account's card by the same id isn't given this one's reason.
    expect(await w.coach.get("t_other#approval-note#" + w.stored.session + "#a1")).toBeUndefined();
  });

  it("keeps Approve's own refusal's reason too, when the card was drawn before the check and the tap refused it", async () => {
    const { w } = await refused();
    // Read with no proof: nothing checked, the card is drawn as before, and the tap checks it.
    expect(a1(await w.call("GET", CHAT, undefined, { "planque-device-signature": "" }))?.answer).toBeUndefined();
    const tap = await w.call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" });
    expect(tap.body).toMatchObject({ outcome: "answered" });
    expect(String(tap.body.note)).not.toBe("");
    const line = a1(await w.call("GET", CHAT, undefined))?.answer?.words;
    expect(line).toBe(`Approve · it didn't go through: ${String(tap.body.note)}`);
  });

  it("a draft that passes is drawn as before, its Card to sign", async () => {
    const { s, w } = await refused();
    s.drafts.drf_1.cards = card(["refund_charge", "list_charges_noyy"]);
    const c = a1(await w.call("GET", CHAT, undefined));
    expect(c?.answer).toBeUndefined();
    expect(c?.component.signs).toEqual({ card: true, takes: "fingerprint" });
    expect(w.told()).toBeUndefined();
  });

  it("another phone's read, once Coach was told and before its answer is in the chat, draws the card answered and tells nothing", async () => {
    const { w } = await refused();
    const kept = new Date(NOW.getTime() + 60_000);
    await w.coach.once("t_acme#draft-precheck#drf_1#v3", kept);
    await w.coach.put("t_acme#draft-precheck-told#drf_1#v3", { answer: "failed: the Card covers \"list_charges\"", at: NOW.toISOString() }, kept);
    const c = a1(await w.call("GET", CHAT, undefined));
    expect(c?.answer?.state).toBe("answered");
    expect(c?.component.signs).toBeUndefined();
    expect(w.told()).toBeUndefined();
    // Claimed and not yet told (the other phone's check in flight): drawn as before.
    const { w: w2 } = await refused();
    await w2.coach.once("t_acme#draft-precheck#drf_1#v3", kept);
    expect(a1(await w2.call("GET", CHAT, undefined))?.answer).toBeUndefined();
    expect(w2.told()).toBeUndefined();
  });

  it("leaves to the tap what a redraft doesn't mend, a draft read at another version, and a read that couldn't check", async () => {
    // A draft for another taf than the grant's: Approve's answer at the tap, as before.
    const other = await refused();
    other.s.drafts.drf_1.agent_id = "someone-else";
    expect(a1(await other.w.call("GET", CHAT, undefined))?.answer).toBeUndefined();
    expect(other.w.told()).toBeUndefined();
    // KIFF's read without the Cards: not read whole, so the next read checks again, and then tells Coach.
    const unread = await refused();
    const cards = unread.s.drafts.drf_1.cards;
    delete unread.s.drafts.drf_1.cards;
    expect(a1(await unread.w.call("GET", CHAT, undefined))?.answer).toBeUndefined();
    expect(unread.w.told()).toBeUndefined();
    unread.s.drafts.drf_1.cards = cards;
    expect(a1(await unread.w.call("GET", CHAT, undefined))?.answer?.state).toBe("answered");
    expect(unread.w.told()?.answer).toMatch(/^failed: /);
  });

  it("tells Coach on the next read when kiff/agents couldn't take the answer", async () => {
    const { w } = await refused();
    const answerCard = w.agents.api.answerCard;
    w.agents.api.answerCard = async () => { throw new Error("kiff/agents is down"); };
    const down = await w.call("GET", CHAT, undefined);
    expect(down.status).toBe(200);
    expect(a1(down)?.answer).toBeUndefined();
    w.agents.api.answerCard = answerCard;
    expect(a1(await w.call("GET", CHAT, undefined))?.answer?.state).toBe("answered");
    expect(w.told()?.answer).toMatch(/^failed: /);
  });

  it("checks nothing for a reader whose role can't manage Cards, or a phone that sent no proof", async () => {
    const { w } = await refused();
    expect(a1(await w.call("GET", CHAT, undefined, { "planque-device-signature": "" }))?.answer).toBeUndefined();
    expect(w.told()).toBeUndefined();
    const viewOnly = await w.call("GET", CHAT, undefined, {}, undefined, "ana-ro");
    expect(viewOnly.status).toBe(200);
    expect(a1(viewOnly)?.answer).toBeUndefined();
    expect(w.told()).toBeUndefined();
    // The same draft, read by a manager afterwards: checked then.
    expect(a1(await w.call("GET", CHAT, undefined))?.answer?.state).toBe("answered");
  });

  it("checks each draft version once, across phones: a redraft at a new version is checked anew", async () => {
    const { s, w } = await refused();
    await w.call("GET", CHAT, undefined);
    expect(w.told()?.answer).toMatch(/^failed: /);
    // What Coach was told is kept for the version, so another phone draws the card answered too.
    // The owner's note is kept with it, so the card's line shows owner words, never the answer written for Coach.
    expect(await w.coach.get("t_acme#draft-precheck-told#drf_1#v3")).toEqual({ answer: w.told()?.answer, at: NOW.toISOString(), note: expect.any(String) });
    // The claim is kept per draft and version: the same version, read again, is drawn answered without a check.
    expect(await w.coach.once("t_acme#draft-precheck#drf_1#v3", new Date(NOW.getTime() + 60_000))).toBe(false);
    expect(await w.coach.once("t_acme#draft-precheck#drf_1#v4", new Date(NOW.getTime() + 60_000))).toBe(true);
    expect(s.checks).toEqual([]);
  });
});

// The Sheets proof (16 Nov): Coach's card said "change nothing" above a consent screen asking to edit and delete, since
// planque couldn't narrow Google Sheets' sign-in (its ceiling has no read-only scope) and nothing in tafs cloud read that.
describe("a card whose sign-in planque couldn't narrow says so, and Coach hears it once", () => {
  const CHAT = `/v1/tafs/${COACH}/conversation`;
  const comp = (r: { body: Record<string, unknown> }) =>
    (r.body.components as Array<{ component: { id?: string; wider?: { words: string } } }>).find((c) => c.component.id === "a1")?.component;

  it("marks a waiting card wider from planque's usual_access, in the reader's language, and tells Coach nothing", async () => {
    const s = services();
    // Where planque's grantView carries it: on the connector's request, not at the grant's top level.
    s.grants = [grant({ status: "pending", vendor: "Google Sheets" }, { connector: { usual_access: true, provider: "composio" } })];
    const w = await world(s, {});
    const first = await w.call("GET", CHAT, undefined);
    expect(comp(first)?.wider).toEqual({ words: "Composio connects your Google Sheets account. Google Sheets may ask for broader access than this job needs, but your taf can only use the tools allowed on its Card. tafs enforces those limits and blocks every other tool call." });
    // Every app that can approve a card draws this line (contracts/README.md): it reads the same answer.
    contract("coach-wider", "GET /v1/tafs/{id}/conversation read with the device's planque proof: an approval card whose connection planque couldn't narrow (usual_access), marked `wider: {words}`, the line every app that can approve the card draws over its buttons.", first.body);
    const fr = await w.call("GET", CHAT, undefined, { "accept-language": "fr" });
    expect(comp(fr)?.wider?.words).toMatch(/^Composio connecte votre compte Google Sheets\. Google Sheets peut demander un accès plus large/);
    // The card says it, from planque's data: Coach gets no line about the sign-in's access (connect-an-app@29).
    const notes = (await w.coach.get("t_acme#coach-notes#u_ana")) as Array<{ text: string }> | undefined;
    expect((notes ?? []).some((n) => n.text.startsWith("[connection"))).toBe(false);
  });

  it("leaves a card whose sign-in is narrowed as it was, and tells Coach nothing, a flag at the grant's top level included", async () => {
    const s = services();
    s.grants = [grant({ status: "pending", usual_access: true }, { connector: { usual_access: false } })];
    const w = await world(s, {});
    expect(comp(await w.call("GET", CHAT, undefined))?.wider).toBeUndefined();
    const notes = (await w.coach.get("t_acme#coach-notes#u_ana")) as Array<{ text: string }> | undefined;
    expect((notes ?? []).some((n) => n.text.startsWith("[connection"))).toBe(false);
  });
});

// Eval 13's Refunds: the owner approved the tools with the Card in one tap, and the Card was then refused (lifetime_sum).
// The read check covers the steps that sign the Card with the tools or the key, as it does a Card alone.
describe("the chat's read checks a Card signed with its tools or key too, before the card is drawn", () => {
  const CHAT = `/v1/tafs/${COACH}/conversation`;
  const TOOLS = { tools: [{ tool: "refund", name: "refund_k3f9" }] };
  const API = { kind: "api_key", api: { url: "https://example.com/desk/openapi.json", digest: "a".repeat(64), base: "https://desk.example.com/v1", auth: { in: "header", name: "X-Desk-Key" } }, ...TOOLS };
  const cards = (extra: string[]) => [{ grants: [{ action: "refund_k3f9" }, { action: "list_charges_k3f9" }, ...extra.map((action) => ({ action }))], aggregates: [] }];
  const a1 = (r: { body: Record<string, unknown> }) =>
    (r.body.components as Array<{ component: { id?: string; signs?: unknown }; answer?: { state: string } }>).find((c) => c.component.id === "a1");
  afterEach(() => { delete process.env.TAFS_TOOLS_AND_CARDS; delete process.env.TAFS_KEY_AND_CARDS; });

  for (const [step, flag, made] of [
    ["tools_and_cards", "TAFS_TOOLS_AND_CARDS", () => grant({ status: "pending", connected: [{ tool: "list_charges", name: "list_charges_k3f9" }] }, TOOLS)],
    ["key_and_cards", "TAFS_KEY_AND_CARDS", () => grant({}, { ...API, tools: [{ tool: "refund", name: "refund_k3f9" }, { tool: "list_charges", name: "list_charges_k3f9" }] })],
  ] as const) {
    it(`${step}: tells Coach a refusal a redraft mends once, and draws the card answered with no Approve`, async () => {
      process.env[flag] = "on";
      const s = services();
      s.grants = [made()];
      s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: cards(["send_email_everyone"]) };
      const w = await world(s, { draft: "drf_1" });
      const logged: string[] = [];
      const spy = vi.spyOn(console, "log").mockImplementation((...a: unknown[]) => { logged.push(a.join(" ")); });
      let first, again;
      try {
        first = await w.call("GET", CHAT, undefined, {}, `can=${step}`);
        again = await w.call("GET", CHAT, undefined, {}, `can=${step}`);
      } finally {
        spy.mockRestore();
      }
      expect(String(w.told()?.answer)).toMatch(/^failed: the Card covers "send_email_everyone".* Change only what this names; every other line stays exactly as agreed\.$/);
      // The tools or the key it would have carried weren't approved either: the new approval carries them.
      const carried = step === "key_and_cards" ? "Its key wasn't approved either: the new approval carries it with the Card." : "Its tools weren't approved either: the new approval carries them with the Card.";
      expect(String(w.told()?.answer)).toContain(`${REFUSAL_CLOSED} ${carried} ${DRAW_AGAIN} ${KEEP_THE_REST}`);
      expect(a1(first)?.answer?.state).toBe("answered");
      expect(a1(first)?.component.signs).toBeUndefined();
      expect(a1(again)?.answer?.state).toBe("answered");
      expect(logged.filter((l) => l.startsWith("approval precheck:"))).toEqual(["approval precheck: stray"]);
      expect(s.checks).toEqual([]);
    });

    it(`${step}: a draft that passes is drawn as before, the Card signed with it, and a phone that can't sign both checks nothing`, async () => {
      process.env[flag] = "on";
      const s = services();
      s.grants = [made()];
      s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: cards([]) };
      const w = await world(s, { draft: "drf_1" });
      const passed = a1(await w.call("GET", CHAT, undefined, {}, `can=${step}`));
      expect(passed?.answer).toBeUndefined();
      expect(passed?.component.signs).toEqual({ card: true, takes: "fingerprint" });
      expect(w.told()).toBeUndefined();
      // A refused draft read by a phone that signs the connection alone: the Card isn't on this tap, so nothing is told.
      s.drafts.drf_1 = { status: "draft", version: "v4", agent_id: "refunds-x", cards: cards(["send_email_everyone"]) };
      const alone = a1(await w.call("GET", CHAT, undefined, {}));
      expect(alone?.answer).toBeUndefined();
      expect(alone?.component.signs).toMatchObject({ card: false });
      expect(w.told()).toBeUndefined();
    });
  }
});

describe("the Cards follow the rules the owner agreed, as data (rules.ts)", () => {
  const CHAT = `/v1/tafs/${COACH}/conversation`;
  const tools = [{ tool: "refund_charge", name: "refund_charge_k3", title: "Refund a charge" },
    { tool: "list_charges", name: "list_charges_k3", title: "List charges", read_only: true }];
  const cards = (refund: string) => [
    { id: "c1", subject: "refunds-x", on_exceed: refund, grants: [{ action: "refund_charge_k3" }], aggregates: [] },
    { id: "c2", subject: "refunds-x", on_exceed: "refuse", grants: [{ action: "list_charges_k3" }], aggregates: [] }];
  const RULES = [{ tool: "refund_charge", over: "refuse" }, { tool: "list_charges", over: "refuse" }];
  async function setUp(refund: string, theTools: unknown[] = tools) {
    const s = services();
    s.grants = [grant({ status: "connected" }, { tools: theTools })];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: cards(refund) };
    const w = await world(s, { draft: "drf_1" });
    /** Coach shows a rules notice, and the confirm that agrees to it; `agree` records the person's tap. */
    // The notice's words say each figure the tests' rules give: €5 a refund, €50 a day.
    const rules = (id: string, list: unknown, agree = true, text = "Refunds over €5 each ask you, and are refused past €50 a day.") => {
      w.agents.show(w.stored, { type: "notice", id, tone: "info", text, rules: list });
      w.agents.show(w.stored, { type: "confirm", id: `ok-${id}`, text: "Agree?", agrees: id, options: [{ value: "agree", label: "Agree" }, { value: "change", label: "Change something" }] });
      if (!agree) return;
      const list2 = w.agents.events.get(w.stored.session)!;
      list2.push({ session: w.stored.session, run: w.stored.id, seq: list2.length + 1, time: NOW.toISOString(), kind: "answered", body: { id: `ok-${id}`, type: "confirm", answer: "agree", words: "Agree" } });
    };
    const tap = async () => (await w.call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" })).body;
    return { s, w, rules, tap };
  }
  afterEach(() => { delete process.env.TAFS_RULES_REQUIRED; });

  it("a Card whose on_exceed is the agreed rule's is signed; one that isn't is refused, and Coach hears the fix", async () => {
    const ok = await setUp("refuse");
    ok.rules("rules-1", RULES);
    expect(await ok.tap()).toMatchObject({ outcome: "sign" });
    const off = await setUp("owner");
    off.rules("rules-1", RULES);
    const r = await off.tap();
    expect(r).toMatchObject({ outcome: "answered", note: WORDS.rulesOnExceed("Refund a charge") });
    expect(String(r.answer)).toContain(`failed: the rules the owner agreed say "Refund a charge" refuses past its limit, and the draft's Card for it has on_exceed "owner". Draft it again with on_exceed "refuse" on that Card.`);
    expect(String(r.answer)).toContain(`${REFUSAL_CLOSED}`);
    expect(String(r.answer)).toContain(DRAW_AGAIN);
    expect(off.s.checks).toEqual([]);
  });

  it("the chat's read refuses it before the card is drawn, so the owner never approves it", async () => {
    const { w, rules } = await setUp("owner");
    rules("rules-1", RULES);
    const read = await w.call("GET", CHAT, undefined);
    const a1 = (read.body.components as Array<{ component: { id?: string; signs?: unknown }; answer?: { state: string } }>).find((c) => c.component.id === "a1");
    expect(a1?.answer?.state).toBe("answered");
    expect(a1?.component.signs).toBeUndefined();
    expect(String(w.told()?.answer)).toMatch(/^failed: the rules the owner agreed say "Refund a charge" refuses past its limit/);
  });

  // 22 Nov, the emulator: the Card's approval was drawn with Approve while its rules notice waited for Agree (KIFF's read of
  // the draft didn't come back in time, so nothing was checked on the read), and the tap was refused, with no reason shown.
  it("never offers a Card while the chat's rules notice isn't agreed, even when the draft's read doesn't come back", async () => {
    const { s, w, rules } = await setUp("refuse");
    rules("rules-1", RULES, false);
    const drafts = s.drafts.drf_1;
    delete s.drafts.drf_1;
    const read = await w.call("GET", CHAT, undefined);
    const card = (r: { body: Record<string, unknown> }) =>
      (r.body.components as Array<{ component: { id?: string; signs?: unknown }; answer?: { state: string; words: string } }>).find((c) => c.component.id === "a1");
    expect(card(read)?.answer?.state).toBe("answered");
    expect(card(read)?.component.signs).toBeUndefined();
    // The person reads why, in their words; Coach hears Approve's own refusal, and shows the rules' confirm now.
    expect(card(read)?.answer?.words).toBe(`Approve · it didn't go through: ${WORDS.rulesUnagreed}`);
    expect(String(w.told()?.answer)).toMatch(/^failed: the rules notice "rules-1" isn't agreed yet/);
    expect(String(w.told()?.answer)).toContain(`${AGREE_FIRST} ${SHOW_NOW}`);
    // Once, across reads: the next read draws it answered and tells Coach nothing more, with the draft back too.
    s.drafts.drf_1 = drafts;
    const told = w.told();
    const again = await w.call("GET", CHAT, undefined);
    expect(card(again)?.answer?.words).toBe(`Approve · it didn't go through: ${WORDS.rulesUnagreed}`);
    expect(w.told()).toEqual(told);
    expect(s.checks).toEqual([]);
  });

  it("offers the Card once the rules are agreed", async () => {
    const { w, rules } = await setUp("refuse");
    rules("rules-1", RULES);
    const read = await w.call("GET", CHAT, undefined);
    const a1 = (read.body.components as Array<{ component: { id?: string; signs?: unknown }; answer?: unknown }>).find((c) => c.component.id === "a1");
    expect(a1?.answer).toBeUndefined();
    expect(a1?.component.signs).toMatchObject({ card: true });
    expect(w.told()).toBeUndefined();
  });

  it("a write the agreed rules say nothing of isn't signed", async () => {
    const { rules, tap } = await setUp("refuse");
    rules("rules-1", [{ tool: "list_charges", over: "refuse" }]);
    expect(await tap()).toMatchObject({ outcome: "answered", note: WORDS.rulesMissing("Refund a charge") });
  });

  // A line the owner agreed as kept by the connection is held to the grant's pins at Approve, before anything is signed:
  // the owner never signs a rule nothing holds (kiff/agents' show writes "(the connection keeps it)" from the line's kept).
  describe("a line agreed as kept by the connection", () => {
    const LINES = [{ text: "Refunds only to the original card", kept: "connection", tools: ["refund_charge"] }];
    async function withPins(pinRefund: boolean, extra?: ReturnType<typeof grant>) {
      const s = services();
      const pinned = tools.map((t) => (t.tool === "refund_charge" && pinRefund ? { ...t, fixed_arguments: { "refund.reason": "requested_by_customer" } } : t));
      s.grants = [grant({ status: "connected" }, { tools: pinned }), ...(extra ? [extra] : [])];
      s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: cards("refuse") };
      const w = await world(s, { draft: "drf_1" });
      return { s, w };
    }
    function agree(w: Awaited<ReturnType<typeof world>>, lines: unknown) {
      w.agents.show(w.stored, { type: "notice", id: "rules-1", tone: "info", text: "Refunds only to the original card (the connection keeps it)", rules: RULES, lang: "en", lines });
      w.agents.show(w.stored, { type: "confirm", id: "ok-rules-1", text: "Agree?", agrees: "rules-1", options: [{ value: "agree", label: "Agree" }, { value: "change", label: "Change something" }] });
      const list = w.agents.events.get(w.stored.session)!;
      list.push({ session: w.stored.session, run: w.stored.id, seq: list.length + 1, time: NOW.toISOString(), kind: "answered", body: { id: "ok-rules-1", type: "confirm", answer: "agree", words: "Agree" } });
    }
    const tap = async (w: Awaited<ReturnType<typeof world>>) => (await w.call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" })).body;

    it("refuses a tool the grant doesn't pin, naming it, with no signing request and no grant", async () => {
      const { s, w } = await withPins(false);
      agree(w, LINES);
      const r = await tap(w);
      expect(r).toMatchObject({ outcome: "answered", note: WORDS.rulesUnpinned("Refund a charge") });
      expect(String(r.answer)).toContain(`failed: the rules the owner agreed say the connection keeps "Refunds only to the original card" for "refund_charge", but the connection doesn't pin "refund_charge".`);
      expect(s.checks).toEqual([]);
      expect(r.signs).toBeUndefined();
    });

    it("approves when every tool the line names is pinned", async () => {
      const { w } = await withPins(true);
      agree(w, LINES);
      expect(await tap(w)).toMatchObject({ outcome: "sign" });
    });

    it("still refuses when the pin is on another app's tool of the same name", async () => {
      const other = grant({ status: "connected", id: "g_2" }, { agent: "other-taf", tools: [{ tool: "refund_charge", name: "refund_charge_z9", title: "Refund a charge (other app)", fixed_arguments: { amount: 1 } }] });
      const { w } = await withPins(false, other);
      agree(w, LINES);
      expect(await tap(w)).toMatchObject({ outcome: "answered", note: WORDS.rulesUnpinned("Refund a charge") });
      // Within one taf's grants too: every entry for the tool is pinned, or the line isn't kept.
      const g = (id: string, fixed?: Record<string, unknown>) => ({ id, request: { tools: [{ tool: "refund_charge", name: `refund_charge_${id}`, title: "Refund a charge", ...(fixed ? { fixed_arguments: fixed } : {}) }] } }) as unknown as ApprovalGrant;
      expect(unpinnedConnection([g("a", { amount: 1 }), g("b")], LINES)).toEqual({ line: LINES[0]!.text, tool: "refund_charge", title: "Refund a charge" });
      expect(unpinnedConnection([g("a", { amount: 1 }), g("b", { amount: 1 })], LINES)).toBeUndefined();
      expect(unpinnedConnection([g("a", {})], LINES)?.tool).toBe("refund_charge");
      expect(unpinnedConnection([], [{ text: "No tool named", tools: [] }])).toEqual({ line: "No tool named", tool: "", title: "No tool named" });
    });

    it("leaves a notice with no connection line as it was", async () => {
      const { w } = await withPins(false);
      agree(w, [{ text: "Skips refunds it already made", kept: "instructions" }]);
      expect(await tap(w)).toMatchObject({ outcome: "sign" });
      const plain = await withPins(false);
      agree(plain.w, undefined);
      expect(await tap(plain.w)).toMatchObject({ outcome: "sign" });
    });
  });

  // kiff-qa on #1100: the conversation that can't be read is never trusted; it's read again, nothing signed.
  it("a conversation that can't be read isn't trusted: the card reads again, and nothing is signed", async () => {
    // How many conversation reads a tap makes, the rules' own read last; then the same tap with only that read failing.
    const count = await setUp("refuse");
    count.rules("rules-1", RULES);
    const counted = count.w.agents.api.session.bind(count.w.agents.api);
    let n = 0;
    count.w.agents.api.session = async (...a: Parameters<typeof counted>) => { n++; return counted(...a); };
    expect(await count.tap()).toMatchObject({ outcome: "sign" });
    const { s, w, rules, tap } = await setUp("refuse");
    rules("rules-1", RULES);
    const real = w.agents.api.session.bind(w.agents.api);
    let reads = 0;
    w.agents.api.session = async (...a: Parameters<typeof real>) => {
      if (++reads === n) throw new Error("kiff/agents is down");
      return real(...a);
    };
    expect(await tap()).toMatchObject({ outcome: "read_again", note: WORDS.cardsUnread });
    expect(s.checks).toEqual([]);
  });

  // kiff-qa on #1100: the rules hold for every tool the taf holds, the same taf's other connections included.
  it("another connection's tool of the same taf follows the agreed rules too", async () => {
    const s = services();
    const mail = [{ tool: "send_reply", name: "send_reply_m7", title: "Send a reply" }];
    s.grants = [grant({ status: "connected" }, { tools }), grant({ id: "g_2", status: "connected", vendor: "Mail" }, { server: "mcp.mail.example", tools: mail })];
    const mailCard = (onExceed: string) => ({ id: "c3", subject: "refunds-x", on_exceed: onExceed, grants: [{ action: "send_reply_m7" }], aggregates: [] });
    const both = [...RULES, { tool: "send_reply", over: "refuse" }];
    for (const [onExceed, rulesList, want] of [["refuse", both, "sign"], ["owner", both, WORDS.rulesOnExceed("Send a reply")], ["refuse", RULES, WORDS.rulesMissing("Send a reply")]] as const) {
      s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: [...cards("refuse"), mailCard(onExceed)] };
      const w = await world(s, { draft: "drf_1" });
      w.agents.show(w.stored, { type: "notice", id: "rules-1", tone: "info", text: "The rules.", rules: rulesList });
      w.agents.show(w.stored, { type: "confirm", id: "ok-rules-1", text: "Agree?", agrees: "rules-1", options: [{ value: "agree", label: "Agree" }, { value: "change", label: "Change something" }] });
      const list = w.agents.events.get(w.stored.session)!;
      list.push({ session: w.stored.session, run: w.stored.id, seq: list.length + 1, time: NOW.toISOString(), kind: "answered", body: { id: "ok-rules-1", type: "confirm", answer: "agree", words: "Agree" } });
      const r = (await w.call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" })).body;
      if (want === "sign") expect(r).toMatchObject({ outcome: "sign" });
      else expect(r).toMatchObject({ outcome: "answered", note: want });
    }
  });

  it("rules shown and not agreed yet, or newer than the agreement, block the draft until agreed", async () => {
    const shown = await setUp("refuse");
    shown.rules("rules-1", RULES, false);
    const r = await shown.tap();
    expect(r).toMatchObject({ outcome: "answered", note: WORDS.rulesUnagreed });
    expect(String(r.answer)).toContain(AGREE_FIRST);
    expect(String(r.answer)).not.toContain(DRAW_AGAIN);
    // Nothing waits for the owner after this refusal: Coach shows the rules' confirm in the same turn, never ending on
    // "waiting for you to approve" with nothing to tap (the Typeform proof, 16 Nov).
    expect(String(r.answer)).toContain(`${AGREE_FIRST} ${SHOW_NOW}`);
    expect(String(r.answer)).toMatch(/^failed: /);
    const newer = await setUp("refuse");
    newer.rules("rules-1", RULES);
    newer.rules("rules-2", [{ tool: "refund_charge", over: "ask" }, { tool: "list_charges", over: "refuse" }], false);
    expect((await newer.tap()).note).toBe(WORDS.rulesUnagreed);
  });

  it("with no rules shown, a draft is signed as before, unless the rules are required (TAFS_RULES_REQUIRED=on)", async () => {
    const before = await setUp("owner");
    const logged: string[] = [];
    const spy = vi.spyOn(console, "log").mockImplementation((...a: unknown[]) => { logged.push(a.join(" ")); });
    try {
      expect(await before.tap()).toMatchObject({ outcome: "sign" });
    } finally {
      spy.mockRestore();
    }
    expect(logged).toContain("approval rules: none agreed in the conversation");
    process.env.TAFS_RULES_REQUIRED = "on";
    const required = await setUp("refuse");
    const r = await required.tap();
    expect(r).toMatchObject({ outcome: "answered", note: WORDS.rulesRequired });
    expect(String(r.answer)).toContain(AGREE_FIRST);
    expect(String(r.answer)).toContain(SHOW_NOW);
  });

  it("the owner's notes are in each language", () => {
    for (const lang of ["es", "fr", "de", "it", "nl", "pl", "pt"] as const) {
      const w = approvalWords(lang);
      expect(w.rulesOnExceed("X")).toContain("X");
      expect(w.rulesOnExceed("X")).not.toBe(WORDS.rulesOnExceed("X"));
      expect(w.rulesUnagreed).not.toBe(WORDS.rulesUnagreed);
      expect(w.rulesRequired).not.toBe(WORDS.rulesRequired);
      expect(w.rulesMissing("X")).not.toBe(WORDS.rulesMissing("X"));
      expect(w.rulesFigures("X")).toContain("X");
      expect(w.rulesFigures("X")).not.toBe(WORDS.rulesFigures("X"));
      expect(w.rulesUnsaid).not.toBe(WORDS.rulesUnsaid);
    }
    expect(REDRAFT_CODES.has("rules_on_exceed") && REDRAFT_CODES.has("rules_missing") && REDRAFT_CODES.has("rules_unpinned") && REDRAFT_CODES.has("rules_figures") && REDRAFT_CODES.has("rules_unagreed_figure") && REDRAFT_CODES.has("rules_unagreed") && REDRAFT_CODES.has("rules_unsaid")).toBe(true);
  });

  describe("the figures the owner agreed", () => {
    const EACH = { quantity: "sum(amount)", window: "call", limit: 500 };
    const DAY = { quantity: "sum(amount)", window: "calendar_day", limit: 5000 };
    const FIGURED = [{ tool: "refund_charge", over: "refuse", limits: [EACH, DAY] }, { tool: "list_charges", over: "refuse" }];
    /** The refund Card with a cap on each refund and the day's total, and what else it carries. */
    function setFigures(s: ReturnType<typeof services>, max: number, aggregates: unknown[]) {
      s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: [
        { id: "c1", subject: "refunds-x", on_exceed: "refuse", grants: [{ action: "refund_charge_k3", per_action_max: [{ quantity: "sum(amount)", max }] }], aggregates },
        { id: "c2", subject: "refunds-x", on_exceed: "refuse", grants: [{ action: "list_charges_k3" }], aggregates: [] }] };
    }
    const dayTotal = (limit: number) => ({ quantity: "sum(amount)", window: "calendar_day", limit });

    it("the agreed figures on the Card are signed; a Card at 50000 a refund against an agreed 500 is refused, and Coach hears the fix", async () => {
      const ok = await setUp("refuse");
      setFigures(ok.s, 500, [dayTotal(5000)]);
      ok.rules("rules-1", FIGURED);
      expect(await ok.tap()).toMatchObject({ outcome: "sign" });
      const off = await setUp("refuse");
      setFigures(off.s, 50000, [dayTotal(5000)]);
      off.rules("rules-1", FIGURED);
      const r = await off.tap();
      expect(r).toMatchObject({ outcome: "answered", note: WORDS.rulesFigures("Refund a charge") });
      expect(String(r.answer)).toContain(`failed: the rules the owner agreed give "Refund a charge" 500 as each call on sum(amount), and a Card covering it has 50000. Draft it again with per_action_max {quantity: "sum(amount)", max: 500} on its grant, and no other figure for it on any Card.`);
      expect(String(r.answer)).toContain(DRAW_AGAIN);
      expect(off.s.checks).toEqual([]);
    });

    it("a stricter day's total is refused too, and the chat's read refuses it before the card is drawn", async () => {
      const { s, w, rules } = await setUp("refuse");
      setFigures(s, 500, [dayTotal(2000)]);
      rules("rules-1", FIGURED);
      await w.call("GET", CHAT, undefined);
      expect(String(w.told()?.answer)).toMatch(/^failed: the rules the owner agreed give "Refund a charge" 5000 as a day's total on sum\(amount\), and a Card covering it has 2000\. Draft it again with an aggregate \{quantity: "sum\(amount\)", window: "calendar_day", limit: 5000\} with no per/);
    });

    // Eval 43927756622 (Setup, run 1): refused once for a day's total on both Cards, Coach redrafted with an hour's €50 held
    // on the per-refund Card, beside the agreed €5 a refund held and €50 a day refused, and signed it. Never signed now,
    // and Coach hears the one figure to drop and to change nothing else.
    it("a limit the owner never agreed, in any window, is refused, and Coach hears to drop only that one", async () => {
      const split = [{ tool: "refund_charge", over: "refuse", limits: [{ ...EACH, over: "ask" }, DAY] }, { tool: "list_charges", over: "refuse" }];
      const cards = (held: unknown[]) => [
        { id: "c1", subject: "refunds-x", on_exceed: "owner", grants: [{ action: "refund_charge_k3", per_action_max: [{ quantity: "sum(amount)", max: 500 }] }], aggregates: held },
        { id: "c1b", subject: "refunds-x", on_exceed: "refuse", grants: [{ action: "refund_charge_k3" }], aggregates: [dayTotal(5000)] },
        { id: "c2", subject: "refunds-x", on_exceed: "refuse", grants: [{ action: "list_charges_k3" }], aggregates: [] }];
      const hour = await setUp("refuse");
      hour.s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: cards([{ quantity: "sum(amount)", window: "rolling_1h", limit: 5000 }]) };
      hour.rules("rules-1", split);
      const r = await hour.tap();
      expect(r).toMatchObject({ outcome: "answered", note: WORDS.rulesFigures("Refund a charge") });
      expect(String(r.answer)).toMatch(/^failed: the draft gives "Refund a charge" a limit the owner never agreed: 5000 on sum\(amount\), per rolling_1h\. /);
      expect(String(r.answer)).toContain("Draft it again without that one, and change nothing else: no new limit, Card or tool in its place.");
      expect(String(r.answer)).toContain(`${REFUSAL_CLOSED}`);
      expect(String(r.answer)).toContain(DRAW_AGAIN);
      expect(hour.s.checks).toEqual([]);
      // The same Cards with only what was agreed: signed.
      const agreed = await setUp("refuse");
      agreed.s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: cards([]) };
      agreed.rules("rules-1", split);
      expect(await agreed.tap()).toMatchObject({ outcome: "sign" });
    });

    // Eval 16's refunds: ask past €5 a refund, refuse past €50 a day, as data, and each answer on its own Card.
    it("ask past one figure and refuse past another: signed on two Cards, refused on one", async () => {
      const split = [{ tool: "refund_charge", over: "refuse", limits: [{ ...EACH, over: "ask" }, DAY] }, { tool: "list_charges", over: "refuse" }];
      const two = await setUp("refuse");
      two.s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: [
        { id: "c1", subject: "refunds-x", on_exceed: "owner", grants: [{ action: "refund_charge_k3", per_action_max: [{ quantity: "sum(amount)", max: 500 }] }], aggregates: [] },
        { id: "c1b", subject: "refunds-x", on_exceed: "refuse", grants: [{ action: "refund_charge_k3" }], aggregates: [dayTotal(5000)] },
        { id: "c2", subject: "refunds-x", on_exceed: "refuse", grants: [{ action: "list_charges_k3" }], aggregates: [] }] };
      two.rules("rules-1", split);
      expect(await two.tap()).toMatchObject({ outcome: "sign" });
      const one = await setUp("refuse");
      setFigures(one.s, 500, [dayTotal(5000)]);
      one.rules("rules-1", split);
      const r = await one.tap();
      expect(r).toMatchObject({ outcome: "answered", note: WORDS.rulesOnExceed("Refund a charge") });
      expect(String(r.answer)).toContain(`failed: the rules the owner agreed ask past one of "Refund a charge"'s limits and refuse past another, and the draft puts both on one Card, which has one on_exceed. Draft it again with each on its own Card`);
    });

    it("a figure the rules don't name is refused", async () => {
      // A total of another argument the rules never set: refused (a count beside the amounts is count_on_amount's).
      const extra = await setUp("refuse");
      setFigures(extra.s, 500, [dayTotal(5000), { quantity: "sum(fee)", window: "calendar_day", limit: 100 }]);
      extra.rules("rules-1", FIGURED);
      const r = await extra.tap();
      expect(r).toMatchObject({ outcome: "answered", note: WORDS.rulesFigures("Refund a charge") });
      expect(String(r.answer)).toMatch(/^failed: the draft gives "Refund a charge" a limit the owner never agreed: 100 on sum\(fee\), per calendar_day\. /);
    });

    // Eval 44765665210 (connect, invoices-3): the notice said "a draft invoice for each one", the rules and the Card held
    // 10 a day and 1 per order, and the draft was signed. The owner agrees to what the notice says: a figure its words
    // don't say is refused, the Cards as drafted, and Coach shows the rules again with each limit before drafting again.
    it("a figure the notice's words don't say is refused (rules_unsaid), and Coach shows the rules again first", async () => {
      const { s, rules, tap } = await setUp("refuse");
      setFigures(s, 500, [dayTotal(5000)]);
      rules("rules-1", FIGURED, true, "Refunds are refused past €50 a day.");
      const logged: string[] = [];
      const spy = vi.spyOn(console, "log").mockImplementation((...a: unknown[]) => { logged.push(a.join(" ")); });
      let r: Record<string, unknown>;
      try {
        r = await tap();
      } finally {
        spy.mockRestore();
      }
      expect(r).toMatchObject({ outcome: "answered", note: WORDS.rulesUnsaid });
      expect(String(r.answer)).toMatch(/^failed: the rules notice "rules-1" the owner agreed doesn't say every limit its rules give, so the owner never read them: refund_charge sum\(amount\) call 500\. Show the rules again as a new notice/);
      expect(String(r.answer)).not.toContain("calendar_day 5000");
      expect(String(r.answer)).toContain("changing nothing in the draft");
      expect(String(r.answer)).toContain(`${AGREE_FIRST} ${SHOW_NOW}`);
      expect(String(r.answer)).not.toContain(DRAW_AGAIN);
      expect(s.checks).toEqual([]);
      expect(logged).toContain("approval rules: figures the notice's words don't say: refund_charge sum(amount) call 500");
      // The same draft under a notice that says both figures: signed.
      const said = await setUp("refuse");
      setFigures(said.s, 500, [dayTotal(5000)]);
      said.rules("rules-1", FIGURED, true, "Refunds up to 5,00 € chacun et 50 € par jour.");
      expect(await said.tap()).toMatchObject({ outcome: "sign" });
    });

    // qa-kiff's condition 2: a limit the notice says that no Card holds is refused already, by the figure the rules give it.
    it("a limit the notice says, €50 each, on a Card without it: refused (rules_figures)", async () => {
      const { s, rules, tap } = await setUp("refuse");
      s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: [
        { id: "c1", subject: "refunds-x", on_exceed: "refuse", grants: [{ action: "refund_charge_k3" }], aggregates: [dayTotal(50000)] },
        { id: "c2", subject: "refunds-x", on_exceed: "refuse", grants: [{ action: "list_charges_k3" }], aggregates: [] }] };
      rules("rules-1", [{ tool: "refund_charge", over: "refuse", limits: [{ ...EACH, limit: 5000 }, { ...DAY, limit: 50000 }] }, { tool: "list_charges", over: "refuse" }], true, "Refunds up to €50 each and €500 a day.");
      const r = await tap();
      expect(r).toMatchObject({ outcome: "answered", note: WORDS.rulesFigures("Refund a charge") });
      expect(String(r.answer)).toContain(`failed: the rules the owner agreed give "Refund a charge" 5000 as each call on sum(amount)`);
      expect(s.checks).toEqual([]);
    });
  });

  // invoices-3 replayed in each language: create_invoice at 10 a day and 1 per order. A notice that says neither is
  // refused; one that says 10 in digits and 1 in digits, or in words where the word for one is never an article (en, nl,
  // pl), is signed.
  describe("the figures said in the notice's words, in each language (rules_unsaid)", () => {
    const INVOICE_TOOLS = [{ tool: "create_invoice", name: "create_invoice_k3", title: "Create a draft invoice", resource_argument: "order_id" },
      { tool: "list_orders", name: "list_orders_k3", title: "List orders", read_only: true }];
    const INVOICES = [{ tool: "create_invoice", over: "refuse", limits: [{ quantity: "count", window: "calendar_day", limit: 10 }, { quantity: "count", window: "lifetime", per: "order_id", limit: 1 }] }, { tool: "list_orders", over: "refuse" }];
    function setInvoices(s: ReturnType<typeof services>) {
      s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: [
        { id: "c1", subject: "refunds-x", on_exceed: "refuse", grants: [{ action: "create_invoice_k3" }], aggregates: [{ quantity: "count", window: "calendar_day", limit: 10 }, { quantity: "count", window: "lifetime", per: "order_id", limit: 1 }] },
        { id: "c2", subject: "refunds-x", on_exceed: "refuse", grants: [{ action: "list_orders_k3" }], aggregates: [] }] };
    }
    const SAYS: Record<string, { neither: string; both: string }> = {
      en: { neither: "Every hour, Invoices lists your fulfilled orders and makes a draft invoice for each one.", both: "Every hour, Invoices makes one draft invoice per fulfilled order, at most 10 a day." },
      fr: { neither: "Chaque heure, Invoices liste vos commandes livrées et crée un brouillon de facture pour chacune.", both: "Chaque heure, Invoices crée 1 facture brouillon par commande livrée, 10 par jour au plus." },
      de: { neither: "Jede Stunde listet Invoices deine erfüllten Bestellungen und erstellt für jede einen Rechnungsentwurf.", both: "Jede Stunde erstellt Invoices 1 Rechnungsentwurf pro erfüllter Bestellung, höchstens 10 am Tag." },
      es: { neither: "Cada hora, Invoices lista tus pedidos completados y crea un borrador de factura para ellos.", both: "Cada hora, Invoices crea 1 factura borrador por pedido completado, como máximo 10 al día." },
      it: { neither: "Ogni ora, Invoices elenca i tuoi ordini evasi e ne prepara le bozze delle fatture.", both: "Ogni ora, Invoices crea 1 bozza di fattura per ogni ordine evaso, al massimo 10 al giorno." },
      nl: { neither: "Elk uur zet Invoices je afgeronde bestellingen op een rij en maakt er een conceptfactuur van.", both: "Elk uur maakt Invoices één conceptfactuur per afgeronde bestelling, hooguit 10 per dag." },
      pl: { neither: "Co godzinę Invoices sprawdza zrealizowane zamówienia i tworzy dla nich szkice faktur.", both: "Co godzinę Invoices tworzy jedną fakturę roboczą na każde zrealizowane zamówienie, najwyżej 10 dziennie." },
      pt: { neither: "A cada hora, o Invoices lista as suas encomendas concluídas e cria um rascunho de fatura para elas.", both: "A cada hora, o Invoices cria 1 fatura rascunho por encomenda concluída, no máximo 10 por dia." },
    };
    for (const [lang, says] of Object.entries(SAYS)) {
      it(`${lang}: neither figure said is refused; both said is signed`, async () => {
        const off = await setUp("refuse", INVOICE_TOOLS);
        setInvoices(off.s);
        off.rules("rules-1", INVOICES, true, says.neither);
        const r = await off.tap();
        expect(r).toMatchObject({ outcome: "answered", note: WORDS.rulesUnsaid });
        expect(String(r.answer)).toContain(": create_invoice count calendar_day 10; create_invoice count lifetime 1 per order_id. ");
        expect(off.s.checks).toEqual([]);
        const ok = await setUp("refuse", INVOICE_TOOLS);
        setInvoices(ok.s);
        ok.rules("rules-1", INVOICES, true, says.both);
        expect(await ok.tap()).toMatchObject({ outcome: "sign" });
      });
    }
  });
});

// The owner's phone, 17 Nov: two approval cards drawn in one second, a Card over both apps on the first app's card and
// the other app still waiting. The chat's read marked the first card as signing the Card while its tap asked for the
// connection alone; the phone refuses a step that isn't its mark and reads again, so five taps did nothing.
describe("two approval cards in one read: each card's mark is what its tap asks (other_app_first, 17 Nov)", () => {
  const CHAT = `/v1/tafs/${COACH}/conversation`;
  const CAN = "can=key_and_cards,tools_and_cards";
  const base = (name: string, tool: string, title: string) => ({ kind: "api_key", server: "desk.example.com",
    api: { url: `https://desk.example.com/${name}/openapi.json`, digest: "a".repeat(64), base: "https://desk.example.com/v1", auth: { in: "header", name: "X-Desk-Key" } },
    tools: [{ tool, name: `${tool}_k3`, title }] });
  const mailbox = () => grant({ id: "g_1", vendor: "desk.example.com", title: "desk.example.com" }, base("mail", "send_mail", "Send an email"));
  const books = () => grant({ id: "g_2", vendor: "desk.example.com", title: "desk.example.com" }, base("books", "list_invoices", "List invoices"));
  const both = [{ id: "c1", subject: "refunds-x", grants: [{ action: "send_mail_k3" }, { action: "list_invoices_k3" }], aggregates: [] }];
  const card = (r: { body: Record<string, unknown> }, id: string) =>
    (r.body.components as Array<{ component: { id?: string; signs?: { card?: boolean } }; answer?: { state: string } }>).find((c) => c.component.id === id);
  afterEach(() => { delete process.env.TAFS_KEY_AND_CARDS; });

  async function twoCards() {
    process.env.TAFS_KEY_AND_CARDS = "on";
    const s = services();
    s.grants = [mailbox(), books()];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: both };
    const w = await world(s, { grant: "g_1", draft: "drf_1" });
    w.agents.ask(w.stored, { type: "approval", id: "a2", grant: "g_2" });
    return { s, w };
  }

  it("marks the Card's card as connecting its app alone while the other app waits, as its tap asks, read after read", async () => {
    const { w } = await twoCards();
    for (let i = 0; i < 3; i++) {
      const r = await w.call("GET", CHAT, undefined, {}, CAN);
      expect(card(r, "a1")?.answer).toBeUndefined();
      expect(card(r, "a1")?.component.signs?.card).toBe(false);
      const tap = await w.call("POST", ASK, { grant: "g_1", shown_digest: mailbox().digest, draft: "drf_1", shown_draft: "v3", can: ["key_and_cards", "tools_and_cards"] });
      expect(tap.body).toMatchObject({ outcome: "sign", ask: { step: "connect" } });
    }
  });

  it("still keeps the waiting app's note, and answers its draftless card on the first read, with no Approve", async () => {
    const { w } = await twoCards();
    const r = await w.call("GET", CHAT, undefined, {}, CAN);
    expect(await w.coach.get(waitingDraftKey("t_acme", "g_2"))).toMatchObject({ draft: "drf_1" });
    expect(card(r, "a2")?.answer?.state).toBe("answered");
    expect(card(r, "a2")?.component.signs).toBeUndefined();
    // Its tap finds it waits no more: the books' key is never approved apart from its Card.
    expect((await w.call("POST", `/v1/tafs/${COACH}/components/a2/approval`, { grant: "g_2", shown_digest: books().digest })).status).toBe(409);
  });

  it("with the draft unread in time, still never marks the Card while another app's approval waits (tafs#1355's follow-up)", async () => {
    const { s, w } = await twoCards();
    s.draftDown = 503;
    const r = await w.call("GET", CHAT, undefined, {}, CAN);
    expect(card(r, "a1")?.component.signs?.card).toBe(false);
    // Once the draft reads again, the mark is the same, and the tap asks what the mark says.
    delete s.draftDown;
    const again = await w.call("GET", CHAT, undefined, {}, CAN);
    expect(card(again, "a1")?.component.signs?.card).toBe(false);
    const tap = await w.call("POST", ASK, { grant: "g_1", shown_digest: mailbox().digest, draft: "drf_1", shown_draft: "v3", can: ["key_and_cards", "tools_and_cards"] });
    expect(tap.body).toMatchObject({ outcome: "sign", ask: { step: "connect" } });
  });

  it("with the draft unread in time and no other app waiting, marks the combined step as before", async () => {
    process.env.TAFS_KEY_AND_CARDS = "on";
    const s = services();
    s.grants = [mailbox(), { ...books(), status: "connected" }];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: both };
    s.draftDown = 503;
    const w = await world(s, { grant: "g_1", draft: "drf_1" });
    const r = await w.call("GET", CHAT, undefined, {}, CAN);
    expect(card(r, "a1")?.component.signs?.card).toBe(true);
  });

  it("marks the combined step as before once no other app waits", async () => {
    process.env.TAFS_KEY_AND_CARDS = "on";
    const s = services();
    s.grants = [mailbox(), { ...books(), status: "connected" }];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", cards: both };
    const w = await world(s, { grant: "g_1", draft: "drf_1" });
    const r = await w.call("GET", CHAT, undefined, {}, CAN);
    expect(card(r, "a1")?.component.signs?.card).toBe(true);
    const tap = await w.call("POST", ASK, { grant: "g_1", shown_digest: mailbox().digest, draft: "drf_1", shown_draft: "v3", can: ["key_and_cards", "tools_and_cards"] });
    expect(tap.body).toMatchObject({ outcome: "sign", ask: { step: "key_and_cards" } });
  });
});
