import { createHash } from "node:crypto";
import { afterEach, describe, expect, it } from "vitest";
import { connectedKey, approvalWords, ASK_KEPT_MS, WORDS } from "../src/cloud/approval.js";
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
const BO = { tenant_id: "t_other", subject: "u_bo", api_key: false, capabilities: ["read", "cards.manage"] };
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
  drafts: Record<string, { status: string; version?: string; agent_id?: string; expires_at?: string; cards?: unknown[]; confirm_key?: string; label?: string }>;
  /** What KIFF answers the device-signature check: signed, or a status. */
  signed: true | number;
  /** Which key KIFF says signed, and the window it named (kiff/tafs#494); none, as a KIFF from before. */
  signedKey?: string;
  signedWindow?: { id: string; open: boolean };
  /** Every signature check KIFF was asked, as sent. */
  checks: Array<Record<string, unknown>>;
  proofs: string[];
  /** The account's Cards, as KIFF lists them (GET /v1/me/mandates); a status when the list fails. */
  mandates: unknown[] | number;
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
    const me = token === "ana" ? ME : token === "bo" ? BO : undefined;
    if (!me) return new Response("{}", { status: 401 });
    if (url.endsWith("/v1/me/access")) return new Response(JSON.stringify({ agents: { "setup-acme": { use: true, oversee: false, manage: true } } }), { status: 200 });
    if (url.endsWith("/v1/me")) return new Response(JSON.stringify(me), { status: 200 });
    if (url.endsWith("/v1/me/device-signature")) {
      s.checks.push(JSON.parse(String(init?.body)) as Record<string, unknown>);
      return s.signed === true ? new Response(JSON.stringify({ signed: true, grant: "dg_1", grant_until: "2026-10-16T12:01:30Z", ...(s.signedKey ? { key: s.signedKey } : {}), ...(s.signedWindow ? { window: s.signedWindow } : {}) }), { status: 200 }) : new Response(JSON.stringify({ error: "no" }), { status: s.signed });
    }
    if (url.endsWith("/v1/me/mandates")) {
      return typeof s.mandates === "number" ? new Response("{}", { status: s.mandates }) : new Response(JSON.stringify({ mandates: s.mandates }), { status: 200 });
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
  return { agents, stored, call, told, boTold, coach, at: (d: Date) => { clock = d; } };
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

  it("a connect while Coach sets the taf up, before it's listed: the prompt names it as planque keeps it, and a Card by the draft's label", async () => {
    const s = services();
    const setup = { agent: "remb-x2" };
    s.grants = [grant({ vendor: "stripe-mcp", agent_name: "Remboursements du lundi" }, setup)];
    const w = await world(s);
    const fr = await w.call("POST", ASK, { grant: "g_1", shown_digest: sha256(grant({}, setup).request) }, { "accept-language": "fr-FR" });
    expect((fr.body.ask as { reason: string }).reason).toBe("Connecter Stripe à Remboursements du lundi");
    // Its Cards, the vendor connected: the draft's label names it, and with none, planque's agent_name does.
    s.grants = [grant({ status: "connected", agent_name: "Remboursements du lundi" }, setup)];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "remb-x2", label: "Remboursements" };
    const card = await w.call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" });
    expect((card.body.ask as { reason: string }).reason).toBe("Give Remboursements its Card");
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "remb-x2" };
    const unlabelled = await w.call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" });
    expect((unlabelled.body.ask as { reason: string }).reason).toBe("Give Remboursements du lundi its Card");
    expect(JSON.stringify([fr.body, card.body, unlabelled.body])).not.toContain("remb-x2\"");
  });

  it("the Cards over a connected vendor: KIFF's draft at the version shown, one message to sign", async () => {
    const s = services();
    s.grants = [grant({ status: "connected" })];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x" };
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
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x" };
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
    // Counted for each order: signed. So is a lifetime budget on an amount, and a lifetime count on a read.
    s.drafts.drf_1.cards = [card([daily, { quantity: "count", limit: 1, window: "lifetime", per: "order" }])];
    expect(await ask()).toMatchObject({ outcome: "sign" });
    s.drafts.drf_1.cards = [card([daily, { quantity: "sum(amount)", limit: 20_000, window: "lifetime" }])];
    expect(await ask()).toMatchObject({ outcome: "sign" });
    s.drafts.drf_1.cards = [card([daily]), { id: "c2", subject: "refunds-x", grants: [{ action: "list_orders_k3" }], aggregates: [{ quantity: "count", limit: 1000, window: "lifetime" }] }];
    expect(await ask()).toMatchObject({ outcome: "sign" });
    // KIFF folds case and spaces: so does the check.
    s.drafts.drf_1.cards = [card([daily, { quantity: " Count ", limit: 1, window: "LIFETIME", per: " " }])];
    expect(await ask()).toMatchObject({ outcome: "answered", note: WORDS.lifetimeCap("Create a draft invoice") });
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
    // Approved but never signed in: planque holds nothing (signed_in waits for the phone's confirm: the test above).
    s.grants = [grant({ status: "approved" })];
    const r = await w.report({ result: "connected" });
    expect(r.body).toMatchObject({ answer: `failed: ${WORDS.notConnected("Stripe", "approved")}`, note: WORDS.notConnected("Stripe", "approved") });
    expect(w.told()).toMatchObject({ answer: `failed: ${WORDS.notConnected("Stripe", "approved")}` });
  });

  it("words each note in the approving request's language, the reason Coach hears with it, and keeps the protocol's words", async () => {
    // agentgabo on #716: "Your approval window has ended…" read English in a Polish chat.
    const PL = approvalWords("pl");
    const SIG = { "kiff-device-signature": "t=2026-10-16T12:00:30Z,v1=sig", "accept-language": "pl-PL,pl;q=0.9" };
    const cases: Array<[Record<string, unknown>, string]> = [
      [{ result: "sign_in_closed" }, PL.signInClosed],
      [{ result: "no_code" }, PL.noCode("Stripe")],
      [{ result: "not_connected", status: "needs_reconnect" }, PL.cameBack("needs_reconnect")],
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
    expect(PL.cameBack("needs_reconnect")).toBe("Połączenie wróciło bez linku do logowania (stan: czeka na ponowne zalogowanie). Nic nie zostało połączone.");
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
    expect(WORDS.notConnected("Stripe", "needs_reconnect")).toBe("The sign-in at Stripe didn't connect: the connection is needs reconnect. Nothing was connected. Try signing in again.");
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
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x" };
    const cards = await (await world(s, { draft: "drf_1" })).call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" });
    expect(cards.body).toMatchObject({ outcome: "sign", ask: { step: "cards", key: "critical" } });
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", confirm_key: "window" };
    const routine = await (await world(s, { draft: "drf_1" })).call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" });
    expect(routine.body).toMatchObject({ outcome: "sign", ask: { step: "cards", key: "window" } });
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x", confirm_key: "critical" };
    const widening = await (await world(s, { draft: "drf_1" })).call("POST", ASK, { grant: "g_1", draft: "drf_1", shown_draft: "v3" });
    expect(widening.body).toMatchObject({ outcome: "sign", ask: { step: "cards", key: "critical" } });
  });

  it("takes a critical ask's report signed by the device key outside a window, as every app does, and refuses one signed inside a window", async () => {
    const s = services();
    s.grants = [grant({ status: "connected" })];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x" };
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
    // draft confirmed is the proof (dev-android's point on the apps installed today).
    s.signedWindow = undefined;
    s.drafts.drf_1 = { status: "confirmed", version: "v3", agent_id: "refunds-x" };
    expect((await w.call("POST", `${ASK}/${id}`, { result: "confirmed" }, SIG)).body).toMatchObject({ answer: "approved" });
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

describe("one signature for a key and its Card (key_and_cards, docs/secure-connection.md)", () => {
  const API = { kind: "api_key", api: { url: "https://example.com/desk/openapi.json", digest: "a".repeat(64), base: "https://desk.example.com/v1", auth: { in: "header", name: "X-Desk-Key" } } };
  const keyGrant = (over: Record<string, unknown> = {}) => grant(over, API);
  const SIGNED = { "kiff-device-signature": "t=2026-10-16T12:00:30Z,v1=sig" };
  afterEach(() => { delete process.env.TAFS_KEY_AND_CARDS; });

  async function keyWorld(on: boolean) {
    if (on) process.env.TAFS_KEY_AND_CARDS = "on";
    const s = services();
    s.grants = [keyGrant()];
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x" };
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
    s.drafts.drf_1 = { status: "draft", version: "v3", agent_id: "refunds-x" };
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
    s.drafts.drf_1 = { status: "draft", version: "v4", agent_id: "refunds-x" };
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
