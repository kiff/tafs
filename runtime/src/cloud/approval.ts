import { noteSendingOutside } from "./sends-outside.js";
/**
 * An approval card's outcome is tafs cloud's to decide (kiff/tafs#413). The
 * phone says the person tapped Approve; tafs cloud reads planque's grant and
 * KIFF's draft, as that phone, and says what needs signing, if anything:
 *
 * - `answered`: nothing to sign, and Coach heard it from tafs cloud: `already
 *   approved: <why>` when the connection and its Card were approved earlier,
 *   with their own signature; `failed: <why>` otherwise. The card shows the note.
 * - `read_again`: the card is out of date (its terms or its draft changed since
 *   it was shown) or KIFF's words for the connection aren't on the phone yet.
 *   Nothing is told to Coach: the owner reads it again and approves.
 * - `sign`: one message for the device's key, under biometrics, and the call it
 *   goes into, exactly as the phone must make it: planque's approve body, or
 *   KIFF's draft and version. The phone signs only that, makes that call, and
 *   reports what came of it, signed as a setup call (KIFF-Device-Signature).
 *
 * The report answers Coach `approved` only once KIFF says this phone signed
 * it, and planque or KIFF says the connection or the Card went through: tafs
 * cloud never vouches for an answer, and the apps never build one. Every word
 * the apps show for an outcome comes from here.
 */
import { createHash } from "node:crypto";
import type { CoachStore } from "./coach.js";
import { sha256 } from "./jcs.js";
import { readsOnly, takesKey } from "./sheet.js";
import { actionsInForce, draftInfo, type Fetch, issued, KIFF_API, KIFFError } from "./kiff.js";
import { vendorName } from "./steps.js";
import { type Lang, wordsIn } from "./words/index.js";

/** A grant as planque sends it, in what the decision reads. */
export interface ApprovalGrant {
  id: string;
  status: string;
  digest: string;
  vendor?: string;
  /** The name of the taf it was made for, as planque keeps it: named before the taf exists, during its setup. */
  agent_name?: string;
  reason?: string;
  /** The app's sign-in was set up on the owner's pick, not by an operator (kiff/planque#87). */
  on_demand?: boolean;
  request: { agent?: string; server?: string; profile?: unknown; tools?: Array<{ tool?: string; name?: string; title?: string; read_only?: boolean; setup_reads?: boolean }> } & Record<string, unknown>;
  /** The tools it had before the addition that waits now: a connected grant whose new tools wait for their approval. */
  connected?: unknown[];
  /** planque holds a confirmed sign-in for it (a reused one, or one made earlier): approving its tools needs no sign-in. */
  signed_in?: boolean;
  /** Its tools are declared in KIFF while they wait for approval, so a Card can be drafted and confirmed over them. */
  declared?: boolean;
}

/**
 * The Card's draft, read and checked for signing (the cards step's checks, and `key_and_cards`'): KIFF has it,
 * it's still a draft at the version the card showed, an app set up on demand lets none of its writes run outright,
 * and every tool the connection gives the taf is on a Card. Otherwise what answers instead.
 */
async function cardsReady(ask: ApprovalAsk, draftID: string, grant: ApprovalGrant, vendor: string, ctx: DecideContext, others: ApprovalGrant[] = []): Promise<Decision | { draft: NonNullable<Awaited<ReturnType<typeof draftInfo>>> & { version: string } }> {
  const W = approvalWords(ctx.lang);
  const draft = await draftInfo(ctx.token, draftID, ctx.fetch, ctx.kiffBase ?? KIFF_API, ctx.now);
  if (!draft) return { outcome: "answered", answer: `failed: KIFF has no Card draft ${draftID} on this account`, note: `KIFF has no Card draft for this. Ask Coach for a new one.` };
  // Its Card confirmed already: a real approval, signed earlier (another phone, or a tap before this one).
  if (issued(draft.status)) return { outcome: "answered", answer: `already approved: ${vendor} is connected and its Card is confirmed`, note: W.alreadyApproved };
  if (draft.status !== "draft") return { outcome: "answered", answer: `failed: the Card draft is ${draft.status}`, note: `The Card draft is ${draft.status}. Ask Coach for a new one.` };
  if (!draft.version || draft.version !== ask.shown_draft) return { outcome: "read_again", note: W.outOfDate };
  // An app set up on demand asks for every change (kiff/planque#87): a draft that lets one of its
  // writes run outright isn't signed, and Coach hears why, so the default holds here, not only in its words.
  // The check needs the Cards as KIFF shows them: a draft read without them isn't signed on trust.
  if (grant.on_demand === true && !draft.cards) return { outcome: "read_again", note: W.cardsUnread };
  const letsRun = onDemandLetsRun(grant, draft.cards ?? []);
  if (letsRun) {
    const why = W.onDemandLetsRun(vendor, letsRun);
    return { outcome: "answered", answer: `failed: ${vendor} was set up on demand, so every write and money tool of it must ask the owner each time (0 a day, held); the draft lets "${letsRun}" run without asking. Draft it again with that tool held.`, note: why };
  }
  // Every tool the taf was given goes on a Card, the reads too: a draft that leaves one uncovered,
  // with no Card in force covering it either, isn't signed, and Coach hears which. A draft read
  // without its Cards, or Cards in force that can't be read, isn't signed on trust.
  if (draft.cards) {
    let inForce: Set<string>;
    try {
      inForce = await actionsInForce(ctx.token, draft.agent, ctx.fetch, ctx.kiffBase ?? KIFF_API);
    } catch {
      return { outcome: "read_again", note: W.cardsUnread };
    }
    // And the other way (qa-kiff's negative 1 on #919): every action the draft's Cards allow is one of this connection's
    // tools, one of the same taf's other connections', or allowed already. A Card signed under "Connect Stripe" never
    // also lets the taf act on something no connection of its gives it here.
    const stray = strayAction(grant, others, draft.cards, inForce);
    if (stray) {
      return { outcome: "answered", answer: `failed: the Card covers "${stray}", which no connection of this taf gives it, so it isn't signed. Draft it again over the taf's connected tools only.`, note: W.stray(stray) };
    }
    const left = uncoveredTool(grant, draft.cards, inForce);
    if (left) {
      return { outcome: "answered", answer: `failed: no Card covers "${left}", which the taf's connection gives it, so KIFF would refuse every call of it (no_mandate_issued). Draft it again with "${left}" on a Card (a read gets a call count only, generous enough never to get in the way).`, note: W.uncovered(left) };
    }
    // A limit for each item drafted as one lifetime count on the whole tool stops the taf after its first item
    // (Eval 43402441612: "one invoice per order, ever" signed as 1 create_invoice in the Card's life). Never signed.
    const capped = lifetimeCapOnWrite(grant, draft.cards);
    if (capped) {
      const per = capped.resource ? `per: "${capped.resource}" (its resource_argument)` : "per its resource_argument";
      return { outcome: "answered", answer: `failed: the draft counts "${capped.tool}" ${capped.limit} in its whole life, across every item, so the taf would stop after its first. A limit for each item is a total with ${per}: count, lifetime, limit ${capped.limit}, per that argument, beside the day's total. With no resource_argument, it goes in the taf's instructions, and the rules say KIFF can't count it. Draft it again.`, note: W.lifetimeCap(capped.tool) };
    }
  }
  return { draft: draft as NonNullable<typeof draft> & { version: string } };
}

/**
 * A write tool of the connection that a Card counts over its whole life with no `per`: one count total on the tool
 * for every item, which stops it after the first (count, lifetime, no per). A sum over a lifetime is a budget, and a
 * count per item is the limit for each item: both are kept. The first such tool, with its resource argument if the
 * request names one, so Coach knows what to count per.
 */
export function lifetimeCapOnWrite(grant: ApprovalGrant, cards: unknown[]): { tool: string; limit: number; resource?: string } | undefined {
  const fold = (v: unknown) => (typeof v === "string" ? v.trim().toLowerCase() : "");
  const writes = (grant.request.tools ?? []).filter((t) => !t.read_only && !t.setup_reads);
  for (const t of writes) {
    const name = fold(t.name || t.tool);
    if (!name) continue;
    for (const raw of cards) {
      const card = raw as { grants?: unknown; aggregates?: unknown };
      const actions = Array.isArray(card.grants) ? card.grants.map((g) => fold((g as { action?: unknown }).action)) : [];
      if (!actions.includes(name) || !Array.isArray(card.aggregates)) continue;
      for (const a of card.aggregates as Array<{ quantity?: unknown; window?: unknown; per?: unknown; limit?: unknown }>) {
        if (fold(a.quantity) === "count" && fold(a.window) === "lifetime" && !fold(a.per)) {
          const resource = (t as { resource_argument?: unknown }).resource_argument;
          return { tool: t.title || t.name || t.tool || name, limit: typeof a.limit === "number" ? a.limit : 0,
            ...(typeof resource === "string" && resource.trim() ? { resource: resource.trim() } : {}) };
        }
      }
    }
  }
  return undefined;
}

/**
 * An app set up on demand asks the owner for every change: a Card drafted over its tools lets
 * no write or money tool run without asking. KIFF's draft shows each Card's actions, what
 * happens past its limits (`on_exceed`) and its totals; a write tool of the connection is held
 * for the owner only when every Card covering it holds past 0 calls. The first tool the draft
 * lets run outright is named, so Coach redrafts; a tool no Card covers can't run at all.
 */
export function onDemandLetsRun(grant: ApprovalGrant, cards: unknown[]): string | undefined {
  if (grant.on_demand !== true) return undefined;
  const writes = (grant.request.tools ?? []).filter((t) => !t.read_only && !t.setup_reads);
  // KIFF matches a Card's action to a tool ignoring case, and trims a quantity: so does this.
  const fold = (v: unknown) => (typeof v === "string" ? v.trim().toLowerCase() : "");
  for (const t of writes) {
    const name = fold(t.name || t.tool);
    if (!name) continue;
    for (const raw of cards) {
      const card = raw as { on_exceed?: unknown; grants?: unknown; aggregates?: unknown };
      const actions = Array.isArray(card.grants) ? card.grants.map((g) => fold((g as { action?: unknown }).action)) : [];
      if (!actions.includes(name)) continue;
      const asks = card.on_exceed === "owner" && Array.isArray(card.aggregates)
        && card.aggregates.some((a) => fold((a as { quantity?: unknown }).quantity) === "count" && (a as { limit?: unknown }).limit === 0);
      if (!asks) return t.title || t.name || t.tool || name;
    }
  }
  return undefined;
}

/**
 * The first tool of the grant no Card covers, once the draft is confirmed: neither the draft's Cards
 * nor the agent's Cards in force (inForce, as actionsInForce reads them) name it. KIFF refuses every
 * call no Card covers, so the taf couldn't use it, and would ask its owner for a Card it shouldn't
 * need (Eval 42781271564). Setup's own reads (setup_reads) are on its reader Card, never the taf's, and a
 * tool listed with no KIFF name is left out.
 */
/**
 * The first action a draft's Cards allow that neither this grant nor another grant of the same taf gives it (its tools,
 * or what it had connected before an addition, by their names in KIFF), and that no Card in force allows already.
 */
export function strayAction(grant: ApprovalGrant, others: ApprovalGrant[], cards: unknown[], inForce: Set<string>): string | undefined {
  const fold = (v: unknown) => (typeof v === "string" ? v.trim().toLowerCase() : "");
  const given = new Set(inForce);
  for (const g of [grant, ...others]) {
    for (const t of [...(g.request.tools ?? []), ...((g.connected ?? []) as Array<{ name?: unknown }>)]) {
      const name = fold((t as { name?: unknown }).name);
      if (name) given.add(name);
    }
  }
  for (const raw of cards) {
    const card = raw as { grants?: unknown };
    if (!Array.isArray(card.grants)) continue;
    for (const g of card.grants) {
      const action = fold((g as { action?: unknown }).action);
      if (action && !given.has(action)) return (g as { action: string }).action;
    }
  }
  return undefined;
}

export function uncoveredTool(grant: ApprovalGrant, cards: unknown[], inForce: Set<string>): string | undefined {
  const fold = (v: unknown) => (typeof v === "string" ? v.trim().toLowerCase() : "");
  const covered = new Set(inForce);
  for (const raw of cards) {
    const card = raw as { grants?: unknown };
    if (Array.isArray(card.grants)) for (const g of card.grants) covered.add(fold((g as { action?: unknown }).action));
  }
  for (const t of grant.request.tools ?? []) {
    if (t.setup_reads) continue;
    // By its name in KIFF, which is what a Card covers. A tool with none is left out, never compared by the
    // vendor's own name (tool): planque names every tool when it's requested.
    const name = fold(t.name);
    if (name && !covered.has(name)) return t.title || t.name || name;
  }
  return undefined;
}

/** What the phone sends with Approve: the card's grant and what it rendered. */
export interface ApprovalAsk {
  grant: string;
  shown_digest?: string;
  draft?: string;
  shown_draft?: string;
  /** KIFF's words for the connection's profile, as the phone read and checked them. */
  words?: { profile_sha256: string; words_sha256: string };
  /**
   * The steps this phone can sign beyond connect and cards (`key_and_cards`): tafs cloud never answers an app a
   * step it didn't say it can act on, so an app from before gets the two steps, the flag on or off.
   */
  can?: string[];
}

/** One message to sign, and the call it goes into. */
export interface Signing {
  id: string;
  /**
   * `key_and_cards` (docs/secure-connection.md): the key to the connection server and the Card's confirm to KIFF
   * under one fingerprint. The phone builds both requests itself, with one `signed_at`, and signs `kiff.requests.v1`
   * over their two messages' hashes; `message` is then empty, and `scheme` says so.
   */
  step: "connect" | "cards" | "key_and_cards" | "tools_and_cards";
  /** For `key_and_cards`: what the phone signs, built by it from the two requests it makes. */
  scheme?: "kiff.requests.v1";
  /** The exact bytes the device's key signs, under biometrics. */
  message: string;
  /** What the biometric prompt says. */
  reason: string;
  /**
   * Which key the call takes, a hint for the app (kiff/tafs#494): "window", the device key inside an open
   * approval window does (connecting an app; a draft KIFF says widens nothing), so a tap; "critical", the
   * approval key behind the fingerprint, every time. The service the call goes to decides for itself, and
   * the report's check here refuses a key short of it; the hint only saves the app a round trip.
   */
  key: "window" | "critical";
  /** The connect step: planque's approve, its body as the phone sends it. */
  planque?: { grant: string; body: { digest: string; kiff_profile?: { profile_sha256: string; words_sha256: string } } };
  /** The cards step: KIFF's draft to confirm, at this version. */
  kiff?: { draft: string; version: string };
}

export type Decision =
  | { outcome: "answered"; answer: string; note: string }
  | { outcome: "read_again"; note: string }
  | { outcome: "sign"; ask: Signing };

/** What a pending ask keeps, so the report is checked against what was asked. */
interface Pending {
  step: "connect" | "cards" | "key_and_cards" | "tools_and_cards";
  grant: string;
  vendor: string;
  draft?: string;
  version?: string;
  made: string;
  /** The key the ask takes (Signing.key); an ask from before the window reads as "window". */
  key?: "window" | "critical";
}

/** An ask waits this long for the phone's report. */
export const ASK_KEPT_MS = 20 * 60_000;

/** The words the apps show, all from here: tafs cloud's catalog, in the approving request's language (words/*.ts). */
/**
 * The end of a connect step's answer when it worked by design and the taf's Card is next (approval.ts): Coach shows the
 * approval again for the Card alone, and the person reads tafs cloud's "Gmail is connected. Next, its Card." instead of a failure.
 */
export const CARD_NEXT = "its Card is next: show the approval again for its Card alone";

export function approvalWords(lang: Lang = "en") {
  const w = wordsIn(lang);
  return {
    outOfDate: w.approval_out_of_date,
    wordsUnread: w.approval_words_unread,
    alreadyApproved: w.approval_already,
    nothingToApprove: w.approval_nothing_new,
    digestMismatch: w.approval_digest_mismatch,
    noGrant: w.approval_no_grant,
    removedTafsConnection: w.approval_removed_tafs_connection,
    otherTafsConnection: w.approval_other_tafs_connection,
    cardsNotIssued: w.approval_cards_not_issued,
    connectedCardNext: w.approval_connected_card_next,
    draftIs: (status: string) => w.approval_draft_is(w.approval_status(status)),
    noDraft: w.approval_no_draft,
    cardsUnread: w.approval_cards_unread,
    uncovered: w.approval_uncovered,
    stray: w.approval_stray,
    lifetimeCap: w.approval_lifetime_cap,
    onDemandLetsRun: w.approval_on_demand,
    criticalKey: w.approval_critical_key,
    driveKey: w.approval_drive_key,
    windowClosed: w.approval_window_closed,
    signInClosed: w.approval_sign_in_closed,
    noCode: w.approval_no_code,
    // What came back, in the owner's words, never planque's status: already signed in (its actions come next), or nothing opened.
    // A connection that takes no approval now, in the owner's words, never planque's status: signed in already, or past approving.
    notApprovable: (vendor: string, status: string) => (status === "listed" || status === "signed_in" ? w.approval_listed_already(vendor) : w.approval_not_approvable(vendor)),
    cameBack: (vendor: string, status: string) => (status === "signed_in" || status === "listed" ? w.approval_came_back_signed_in(vendor) : w.approval_came_back(vendor)),
    confirmFirst: w.approval_confirm_first,
    notConnected: (vendor: string) => w.approval_not_connected(vendor),
    failed: (why?: string) => w.approval_failed(why ?? w.approval_failed_default),
    reasonConnect: (vendor: string, taf?: string) => (taf ? w.approval_reason_connect(vendor, taf) : w.approval_reason_connect_unnamed(vendor)),
    reasonCard: (taf?: string) => (taf ? w.approval_reason_card(taf) : w.approval_reason_card_unnamed),
  };
}

/** The words in English: what a test or a caller without a request reads. */
export const WORDS = approvalWords("en");

export class ApprovalError extends Error {
  /** `code`, when set, is what the app acts on beside the words: critical_key_required, window_required. */
  constructor(readonly status: number, message: string, readonly code?: string) {
    super(message);
  }
}

const ID = /^[A-Za-z0-9_.:-]{1,128}$/;
const HEX64 = /^[0-9a-f]{64}$/;

const keyOf = (tenant: string, taf: string, component: string, ask: string) => `${tenant}#approval-ask#${taf}#${component}#${ask}`;

/** Where tafs cloud keeps that a grant is connected, for a later card that names it (docs/secure-connection.md). */
export const connectedKey = (tenant: string, grant: string) => `${tenant}#connected#${grant}`;

/** How long a connected grant's note is kept: a setup's later cards come within it. */
export const CONNECTED_KEPT_MS = 30 * 24 * 3600_000;

/** What a later card says of a connection already made: its vendor, and whether every tool is a read. */
export interface Connected { vendor: string; read_only: boolean }

/**
 * Notes that the connection server says this grant is connected, so a later card that names it says only that,
 * and never repeats the connection's text. Never fails what it rides on: a note not kept is a card as before.
 */
async function noteConnected(store: CoachStore, tenant: string, grant: ApprovalGrant, now: Date): Promise<void> {
  // Connected only: one signed in whose tools wait (`listed`), or one asking for more tools again (`pending`), still asks for
  // something, so a card for it shows what it asks, never "connected". Such a grant's note goes.
  if (grant.status !== "connected") {
    await store.remove(connectedKey(tenant, grant.id)).catch(() => undefined);
    return;
  }
  const note: Connected = { vendor: vendorOf(grant), read_only: readsOnly(grant) };
  await store.put(connectedKey(tenant, grant.id), note, new Date(now.getTime() + CONNECTED_KEPT_MS)).catch(() => undefined);
}

/** The grants planque shows this phone, read with the proof the phone signed and tafs cloud forwards unchanged. */
export async function readGrants(base: string, tenant: string, proof: string, net: Fetch): Promise<ApprovalGrant[]> {
  if (!proof || proof.length > 4096) throw new ApprovalError(503, "Connect this phone to read its connected apps");
  let res: Response;
  try {
    res = await net(`${base.replace(/\/$/, "")}/v1/accounts/${encodeURIComponent(tenant)}/grants`, { headers: { accept: "application/json", "Planque-Device-Signature": proof } });
  } catch {
    throw new ApprovalError(502, "Connected apps could not be read; try again");
  }
  if (!res.ok) throw new ApprovalError(502, `Connected apps could not be read (planque answered ${res.status}); try again`);
  const out = (await res.json().catch(() => ({}))) as { grants?: unknown };
  if (!Array.isArray(out.grants)) throw new ApprovalError(502, "Connected apps returned an unreadable list");
  return out.grants.filter((g): g is ApprovalGrant => !!g && typeof g === "object" && typeof (g as ApprovalGrant).id === "string" && typeof (g as ApprovalGrant).status === "string" && !!(g as ApprovalGrant).request);
}

/** The vendor as the owner knows it: planque's name for it, else its server. */
function vendorOf(g: ApprovalGrant): string {
  return g.vendor || g.request.server || "the vendor";
}

/**
 * The taf's name for the phone's signing prompt: tafs cloud's taf by that agent first; then, for a taf still
 * being set up and not listed yet, the names it was given (planque's `agent_name`, a draft's label). Never the agent id.
 */
async function nameOf(ctx: DecideContext, agent: string | undefined, ...given: Array<string | undefined>): Promise<string | undefined> {
  const name = await ctx.tafName?.(agent).catch(() => undefined);
  return [name, ...given].map((n) => (typeof n === "string" ? n.trim() : "")).find((n) => n !== "") || undefined;
}

/**
 * The app as the owner knows it ("Stripe" for mcp.stripe.com), for the phone's signing prompt: its server's name, else
 * the grant's vendor. A managed app's server is the sign-in partner's, never the app: its name is the grant's vendor
 * ("Gmail"), as planque keeps it, so the partner's name never reaches the prompt.
 */
function appNameOf(g: ApprovalGrant): string {
  if (g.request.kind === "connector") return typeof g.vendor === "string" ? g.vendor.trim() : "";
  const server = typeof g.request.server === "string" ? g.request.server.trim() : "";
  return vendorName(server) || (server && !server.includes("://") ? vendorName(`https://${server}/`) : "") || g.vendor || "";
}

/** A grant that can be approved and signed in: new, or one its vendor signed out. */
const APPROVABLE = new Set(["pending", "approved", "signed_in", "needs_reconnect"]);

/** Reads what the phone sends with Approve, refusing what isn't an ask. */
export function askOf(body: Record<string, unknown>): ApprovalAsk {
  const grant = typeof body.grant === "string" ? body.grant.trim() : "";
  if (!ID.test(grant)) throw new ApprovalError(400, "send the card's grant");
  const str = (v: unknown) => (typeof v === "string" && v.length <= 256 ? v : undefined);
  const words = body.words && typeof body.words === "object" ? (body.words as Record<string, unknown>) : undefined;
  const ask: ApprovalAsk = { grant };
  if (str(body.shown_digest)) ask.shown_digest = str(body.shown_digest);
  if (str(body.draft)) ask.draft = str(body.draft);
  if (str(body.shown_draft)) ask.shown_draft = str(body.shown_draft);
  if (words && HEX64.test(String(words.profile_sha256)) && HEX64.test(String(words.words_sha256))) {
    ask.words = { profile_sha256: String(words.profile_sha256), words_sha256: String(words.words_sha256) };
  }
  if (Array.isArray(body.can)) ask.can = body.can.filter((x): x is string => typeof x === "string" && x.length <= 32).slice(0, 8);
  return ask;
}

export interface DecideContext {
  /**
   * Whether a key grant with its Card's draft is asked as one signature (`key_and_cards`): on only once KIFF and the
   * connection server both take `kiff.requests.v1`, as tafs cloud's `TAFS_KEY_AND_CARDS=on` says. Off, the key and
   * the Card are signed apart, as before.
   */
  keyAndCards?: boolean;
  /**
   * Whether a connected grant's added tools with the Card's draft over them are asked as one signature
   * (`tools_and_cards`): on once planque takes the paired approve and declares an addition's tools to KIFF while they wait.
   */
  toolsAndCards?: boolean;
  tenant: string;
  taf: string;
  component: string;
  token: string;
  proof: string;
  store: CoachStore;
  planqueBase: string;
  kiffBase?: string;
  fetch: Fetch;
  now: Date;
  newID: () => string;
  /** The approving request's language (its Accept-Language): the notes and the reasons in an answer are in it. */
  lang?: Lang;
  /** A taf's name by its KIFF agent, as its owner reads it: what the phone's signing prompt names, never the agent id. */
  tafName?: (agent?: string) => Promise<string | undefined>;
  /**
   * Whether an agent is a removed taf's (connections.ts `removedAgent`): archived in KIFF, or the other form of an
   * archived agent's name, and no live taf runs as it. Unset, or unable to read, it says no: nothing is refused on a
   * read that failed.
   */
  removedAgent?: (agent: string, askedOn?: string) => Promise<boolean>;
}

/**
 * An approval that would sign over the wrong agent is refused before anything is signed: a grant asked for a taf
 * that was removed (its connection still held), or a Card drafted for another agent than the grant's. 14 Nov: one
 * fingerprint signed a removed taf's Gmail connection with a new taf's Card, and the new taf ran as the removed one.
 * Coach hears what to do instead: connect the app for this taf, reusing the sign-in on a new request.
 */
async function agentRefusal(ask: ApprovalAsk, grant: ApprovalGrant, vendor: string, ctx: DecideContext): Promise<Decision | undefined> {
  const W = approvalWords(ctx.lang);
  const agent = typeof grant.request.agent === "string" ? grant.request.agent : "";
  const askedOn = typeof (grant.request as { created_at?: unknown }).created_at === "string" ? (grant.request as { created_at: string }).created_at : undefined;
  if (agent && ctx.removedAgent && await ctx.removedAgent(agent, askedOn).catch(() => false)) {
    return { outcome: "answered", note: W.removedTafsConnection(vendor),
      answer: `failed: this ${vendor} connection was asked for a taf that was removed, so it can't be approved for another; request ${vendor} again for this taf (reuse its sign-in with a new request), never this grant` };
  }
  if (ask.draft && agent) {
    const draft = await draftInfo(ctx.token, ask.draft, ctx.fetch, ctx.kiffBase ?? KIFF_API, ctx.now).catch(() => undefined);
    if (draft?.agent && draft.agent !== agent) {
      return { outcome: "answered", note: W.otherTafsConnection(vendor),
        answer: `failed: the Card draft is for another taf than this ${vendor} connection's; show an approval with this taf's own grant and its draft` };
    }
  }
  return undefined;
}

/** The steps a phone signs for an approval card. */
export type SignStep = "connect" | "cards" | "key_and_cards" | "tools_and_cards";

/**
 * The step an approval card asks the phone to sign, from the grant as planque says it and what the card and the
 * phone bring: one rule, for `decide` and for the mark the card carries before Approve (`signs`), so the apps draw
 * the Card as signed only on a fingerprint that signs it. Undefined when nothing is signed (answered without one).
 */
export function stepFor(grant: ApprovalGrant, ask: { draft?: string; can?: string[] }, flags: { toolsAndCards?: boolean; keyAndCards?: boolean }): SignStep | undefined {
  if (grant.status === "connected") return ask.draft ? "cards" : undefined;
  if (grant.status === "listed" && !ask.draft) return undefined;
  if (!APPROVABLE.has(grant.status)) return undefined;
  // Tools that wait on a sign-in planque already holds, with the Card's draft over them: one fingerprint
  // (docs/secure-connection.md). A connected grant's added tools, or a held sign-in's first tools (one reused for
  // another taf), declared in KIFF meanwhile: the approve connects at once, with no sign-in between the two halves.
  const added = Array.isArray(grant.connected) && grant.connected.length > 0;
  // Only for a phone that draws the card by its mark (`can: signs`): one that draws the Card only for a connected
  // grant would sign it unseen here, and builds already in people's hands don't update with this rule.
  const heldFirst = grant.signed_in === true && grant.declared === true && ask.can?.includes("signs") === true;
  if (flags.toolsAndCards && ask.can?.includes("tools_and_cards") && !takesKey(grant) && grant.status === "pending" && (added || heldFirst) && ask.draft) return "tools_and_cards";
  // A key grant whose Card is drafted already: the key and the Card under one fingerprint.
  if (flags.keyAndCards && ask.can?.includes("key_and_cards") && takesKey(grant) && ask.draft) return "key_and_cards";
  return "connect";
}

/**
 * What an approval card's fingerprint will sign, for the apps to draw before Approve: the Card, or the connection alone.
 * The connection alone says the Card comes next (`words`) when the card carries the Card's draft, or when it's an app's
 * first connection, before its tools: a Card always follows that one (its draft can only be made over the tools the
 * sign-in brings), so the owner knows a second approval is coming. Never on a sign-in again (its Card is in force) or
 * on tools added to a connected app with no draft.
 */
export function signsOf(step: SignStep, draft: string | undefined, lang: Lang = "en", grant?: Pick<ApprovalGrant, "status" | "connected">): { card: boolean; words?: string } {
  const card = step !== "connect";
  const first = !!grant && grant.status !== "needs_reconnect" && !(Array.isArray(grant.connected) && grant.connected.length > 0);
  return { card, ...(!card && (draft || first) ? { words: wordsIn(lang).approval_card_next } : {}) };
}

/**
 * Decides an approval: what the card needs signed, if anything. Reads planque's
 * grant as the phone, and KIFF's draft with the person's session.
 */
export async function decide(ask: ApprovalAsk, ctx: DecideContext): Promise<Decision> {
  const W = approvalWords(ctx.lang);
  const grants = await readGrants(ctx.planqueBase, ctx.tenant, ctx.proof, ctx.fetch);
  const grant = grants.find((g) => g.id === ask.grant);
  if (!grant) return { outcome: "answered", answer: `failed: ${W.noGrant}`, note: W.noGrant };
  const vendor = vendorOf(grant);
  const refused = await agentRefusal(ask, grant, vendor, ctx);
  if (refused) return refused;
  // The same taf's other connections: a Card may cover their tools too (two apps, one Card).
  const sameTaf = grants.filter((g) => g.id !== grant.id && g.request.agent && g.request.agent === grant.request.agent && !["failed", "disconnected", "revoked"].includes(g.status));
  // A grant asking for something again (tools added once connected) loses its "connected" note, so its card shows what it asks.
  if (grant.status !== "connected") await noteConnected(ctx.store, ctx.tenant, grant, ctx.now);
  if (grant.status === "connected") {
    await noteConnected(ctx.store, ctx.tenant, grant, ctx.now);
    // The second approval: the vendor is connected, and the taf's Cards over its tools wait as a
    // draft. A connection already connected with no Cards left signs nothing, so it isn't approved.
    // The card showed exactly what is connected (its digest): an approval signed earlier whose
    // report was lost. Approve again answers what holds, already approved, as the report would have.
    if (!ask.draft && ask.shown_digest && ask.shown_digest === grant.digest) return { outcome: "answered", answer: `already approved: ${vendor} is connected`, note: W.alreadyApproved };
    if (!ask.draft) return { outcome: "answered", answer: `failed: ${vendor} is already connected; nothing new to approve`, note: W.nothingToApprove(vendor) };
    const checked = await cardsReady(ask, ask.draft, grant, vendor, ctx, sameTaf);
    if ("outcome" in checked) return checked;
    const { draft } = checked;
    const id = ctx.newID();
    // KIFF says which key confirms the draft (kiff/tafs#494); until it does, the fingerprint.
    const key = draft.confirmKey ?? "critical";
    const pending: Pending = { step: "cards", grant: grant.id, vendor, draft: ask.draft, version: draft.version, made: ctx.now.toISOString(), key };
    await ctx.store.put(keyOf(ctx.tenant, ctx.taf, ctx.component, id), pending, new Date(ctx.now.getTime() + ASK_KEPT_MS));
    await noteSending(grant, ctx);
    return { outcome: "sign", ask: { id, step: "cards", message: `tafs.cards.v1|${ctx.tenant}|${ask.draft}|${draft.version}`, reason: W.reasonCard(await nameOf(ctx, draft.agent, draft.label, grant.agent_name)), key, kiff: { draft: ask.draft, version: draft.version } } };
  }
  // Signed in already, its tools listed and waiting for their own approval: an earlier approval
  // went through even if its report didn't reach here (the phone lost it, or it was refused), so
  // Approve again answers what holds, as the report would have: approved, never failed.
  if (grant.status === "listed" && !ask.draft) return { outcome: "answered", answer: `already approved: ${vendor} is signed in; its tools wait for approval`, note: W.alreadyApproved };
  if (!APPROVABLE.has(grant.status)) {
    // Coach hears planque's status and reason, to act on; the person reads plain words (14 Nov: "This connection is listed.").
    const why = `This connection is ${grant.status.replace(/_/g, " ")}${grant.reason ? `: ${grant.reason}` : "."}`;
    return { outcome: "answered", answer: `failed: ${why}`, note: W.notApprovable(vendor, grant.status) };
  }
  // The digest planque signs is the request's: the phone shows Approve only when it matches, and so does tafs cloud.
  let digest = "";
  try { digest = sha256(grant.request); } catch { /* an unreadable request never matches */ }
  if (digest !== grant.digest) return { outcome: "answered", answer: `failed: ${W.digestMismatch}`, note: W.digestMismatch };
  if (grant.digest !== ask.shown_digest) return { outcome: "read_again", note: W.outOfDate };
  // A connection with a profile is approved only with KIFF's words for exactly that profile, as the owner read them.
  let kiffProfile: { profile_sha256: string; words_sha256: string } | undefined;
  if (grant.request.profile !== undefined) {
    if (!ask.words || ask.words.profile_sha256 !== sha256(grant.request.profile)) return { outcome: "read_again", note: W.wordsUnread };
    kiffProfile = { profile_sha256: ask.words.profile_sha256, words_sha256: ask.words.words_sha256 };
  }
  // A connected grant's added tools, with the Card's draft over them: the addition's approve and the Card under one
  // fingerprint (docs/secure-connection.md), as a key and its Card are, once planque takes the paired approve.
  const step = stepFor(grant, ask, ctx);
  if (step === "tools_and_cards" && ask.draft) {
    const checked = await cardsReady(ask, ask.draft, grant, vendor, ctx, sameTaf);
    if ("outcome" in checked) return checked;
    const id = ctx.newID();
    const pending: Pending = { step: "tools_and_cards", grant: grant.id, vendor, draft: ask.draft, version: checked.draft.version, made: ctx.now.toISOString(), key: "critical" };
    await ctx.store.put(keyOf(ctx.tenant, ctx.taf, ctx.component, id), pending, new Date(ctx.now.getTime() + ASK_KEPT_MS));
    await noteSending(grant, ctx);
    return {
      outcome: "sign",
      ask: {
        id, step: "tools_and_cards", scheme: "kiff.requests.v1", message: "",
        reason: W.reasonConnect(appNameOf(grant) || vendor, await nameOf(ctx, grant.request.agent, grant.agent_name)), key: "critical",
        planque: { grant: grant.id, body: { digest: grant.digest, ...(kiffProfile ? { kiff_profile: kiffProfile } : {}) } },
        kiff: { draft: ask.draft, version: checked.draft.version },
      },
    };
  }
  // A key grant whose Card is drafted already: the key and the Card under one fingerprint (docs/secure-connection.md),
  // once both services take kiff.requests.v1. The phone builds both requests and signs their two hashes.
  if (step === "key_and_cards" && ask.draft) {
    const checked = await cardsReady(ask, ask.draft, grant, vendor, ctx, sameTaf);
    if ("outcome" in checked) return checked;
    const id = ctx.newID();
    const pending: Pending = { step: "key_and_cards", grant: grant.id, vendor, draft: ask.draft, version: checked.draft.version, made: ctx.now.toISOString(), key: "critical" };
    await ctx.store.put(keyOf(ctx.tenant, ctx.taf, ctx.component, id), pending, new Date(ctx.now.getTime() + ASK_KEPT_MS));
    await noteSending(grant, ctx);
    return {
      outcome: "sign",
      ask: {
        id, step: "key_and_cards", scheme: "kiff.requests.v1", message: "",
        reason: W.reasonConnect(appNameOf(grant) || vendor, await nameOf(ctx, grant.request.agent, grant.agent_name)), key: "critical",
        planque: { grant: grant.id, body: { digest: grant.digest, ...(kiffProfile ? { kiff_profile: kiffProfile } : {}) } },
        kiff: { draft: ask.draft, version: checked.draft.version },
      },
    };
  }
  const id = ctx.newID();
  // Connecting an app is routine (kiff/tafs#494): a tap inside an approval window. A card that also carries a
  // draft keeps it here, unsigned: the connection alone is signed, and the report never answers it approved
  // while that draft isn't issued, so Coach asks for its Card rather than hand over a taf no Card covers.
  const pending: Pending = { step: "connect", grant: grant.id, vendor, made: ctx.now.toISOString(), key: "window", ...(ask.draft ? { draft: ask.draft, ...(ask.shown_draft ? { version: ask.shown_draft } : {}) } : {}) };
  await ctx.store.put(keyOf(ctx.tenant, ctx.taf, ctx.component, id), pending, new Date(ctx.now.getTime() + ASK_KEPT_MS));
  await noteSending(grant, ctx);
  return {
    outcome: "sign",
    ask: {
      id, step: "connect", message: `tafs.connect.v1|${ctx.tenant}|${grant.id}|${grant.digest}`, reason: W.reasonConnect(appNameOf(grant) || vendor, await nameOf(ctx, grant.request.agent, grant.agent_name)), key: "window",
      planque: { grant: grant.id, body: { digest: grant.digest, ...(kiffProfile ? { kiff_profile: kiffProfile } : {}) } },
    },
  };
}

/**
 * The grant's tools that send outside the company, as its profile says, noted for its taf's agent as the owner is
 * about to sign (gap 14): Coach's result is checked against them. Read as the phone, so only here, and each Approve
 * of the grant replaces its entry, so a later connection's sending tool counts.
 */
async function noteSending(grant: ApprovalGrant, ctx: DecideContext): Promise<void> {
  if (typeof grant.request.agent !== "string" || !grant.request.agent) return;
  await noteSendingOutside(ctx.store, ctx.tenant, grant.request.agent, grant.id, grant.request.profile, ctx.now);
}

/** What the phone reports came of the call it made with the signature. */
export interface Report {
  result: "connected" | "listed" | "confirmed" | "sign_in_closed" | "no_code" | "not_connected" | "failed";
  /** planque's status, for `not_connected`. */
  status?: string;
  /** The error, in the phone's words, for `failed`. */
  why?: string;
}

const RESULTS = new Set(["connected", "listed", "confirmed", "sign_in_closed", "no_code", "not_connected", "failed"]);

export function reportOf(body: Record<string, unknown>): Report {
  const result = typeof body.result === "string" ? body.result : "";
  if (!RESULTS.has(result)) throw new ApprovalError(400, "send what came of the call: connected, listed, confirmed, sign_in_closed, no_code, not_connected or failed");
  const r: Report = { result: result as Report["result"] };
  if (typeof body.status === "string" && body.status.length <= 64) r.status = body.status;
  if (typeof body.why === "string" && body.why.trim()) r.why = body.why.trim().slice(0, 500);
  return r;
}

export interface ReportContext extends DecideContext {
  /** The request as the phone signed it: its method, path and raw body, and its KIFF-Device-Signature. */
  signed: { method: string; uri: string; body: string; signature: string };
}

/**
 * Asks KIFF whether this phone signed the report, with the phone's own session
 * (`POST /v1/me/device-signature`): tafs cloud holds no device key and checks
 * no signature itself. Each signed report is taken once. KIFF also says which
 * key signed and, for a signature made inside an approval window, whether that
 * window is open (kiff/tafs#494). The report itself is the device key's, as it
 * always was: the fingerprint went on the call it reports (KIFF takes a draft's
 * confirm only from the approval key, planque its approvals as its kind says),
 * and `settle` reads what holds there. What's refused here is a report signed
 * inside a window for a critical ask, since that phone took the window path for
 * a critical call, and a window that has ended.
 */
export async function checkSigned(ctx: Pick<ReportContext, "signed" | "fetch" | "kiffBase" | "token" | "lang"> & Partial<ReportContext>, key: "window" | "critical" | "approval" = "window"): Promise<void> {
  const W = approvalWords(ctx.lang);
  const { signed } = ctx;
  if (!signed.signature) throw new ApprovalError(403, "this answer isn't signed by your phone");
  let res: Response;
  try {
    res = await ctx.fetch(`${ctx.kiffBase ?? KIFF_API}/v1/me/device-signature`, {
      method: "POST",
      headers: { authorization: `Bearer ${ctx.token}`, accept: "application/json", "content-type": "application/json" },
      body: JSON.stringify({ method: signed.method, uri: signed.uri, body_sha256: createHash("sha256").update(signed.body, "utf8").digest("hex"), signature: signed.signature }),
    });
  } catch {
    throw new ApprovalError(502, "KIFF didn't answer; try again");
  }
  if (res.status === 403) throw new ApprovalError(403, "this phone didn't sign that answer");
  if (res.status === 409) throw new ApprovalError(409, "this signed answer was already taken");
  if (res.status === 401) throw new KIFFError(401, "KIFF did not accept this sign-in");
  if (!res.ok) throw new ApprovalError(502, `KIFF answered ${res.status} for the signature`);
  // KIFF's answer also carries a one-minute device grant (`grant`, `grant_until`): the writes a
  // service like the gateway then makes for the phone. tafs cloud makes none and must not use
  // it, so only `signed` is read; the grant is dropped here and never kept or passed on.
  const out = (await res.json().catch(() => ({}))) as { signed?: unknown; key?: unknown; window?: { open?: unknown } };
  if (out.signed !== true) throw new ApprovalError(403, "this phone didn't sign that answer");
  // Only the approval key, behind the fingerprint: a Coach drive's start (kiff/tafs#544).
  if (key === "approval" && (out.key !== "critical" || out.window)) throw new ApprovalError(403, W.driveKey, "critical_key_required");
  if (!out.window) return; // signed outside a window: as every report before (the device key's, v1)
  if (key === "critical") throw new ApprovalError(403, W.criticalKey, "critical_key_required");
  if (out.key === "device" && out.window.open !== true) throw new ApprovalError(403, W.windowClosed, "window_required");
}

/** The outcome of a reported approval: Coach's answer and the card's note. */
export interface Outcome {
  answer: "approved" | `failed: ${string}`;
  note?: string;
}

/**
 * Settles a reported approval: KIFF checks the phone signed the report, then
 * planque or KIFF says what came of the call. `approved` only when the service
 * holds it; else `failed: <why>`, in tafs cloud's words.
 */
export async function settle(askID: string, report: Report, ctx: ReportContext): Promise<Outcome> {
  const W = approvalWords(ctx.lang);
  if (!ID.test(askID)) throw new ApprovalError(404, "no approval waits for that answer");
  const key = keyOf(ctx.tenant, ctx.taf, ctx.component, askID);
  const pending = (await ctx.store.get(key)) as Pending | undefined;
  if (!pending || !pending.step) throw new ApprovalError(404, "no approval waits for that answer");
  await checkSigned(ctx, pending.key ?? "window");
  // Taken once, atomically, before Coach hears anything: two signed reports of one ask that
  // arrive together settle it once; the other finds nothing waiting. A report KIFF refused
  // above spends nothing, so the phone can report again.
  if (!(await ctx.store.once(`${key}#taken`, new Date(ctx.now.getTime() + ASK_KEPT_MS)))) throw new ApprovalError(404, "no approval waits for that answer");
  await ctx.store.remove(key);
  if (pending.step === "key_and_cards" || pending.step === "tools_and_cards") {
    // One signature, two requests: approved only when the connection server says connected and KIFF says the
    // Card is issued. A key that went in with a Card that didn't says so, and the card's next tap asks the Card alone.
    const [grants, draft] = await Promise.all([
      readGrants(ctx.planqueBase, ctx.tenant, ctx.proof, ctx.fetch),
      pending.draft ? draftInfo(ctx.token, pending.draft, ctx.fetch, ctx.kiffBase ?? KIFF_API, ctx.now) : Promise.resolve(undefined),
    ]);
    const grant = grants.find((g) => g.id === pending.grant);
    // A key grant has no sign-in to list its tools after: it's connected, or the key didn't go in.
    const connected = grant?.status === "connected";
    if (connected) await noteConnected(ctx.store, ctx.tenant, grant!, ctx.now);
    if (connected && draft && issued(draft.status)) return { answer: "approved" };
    if (!connected) {
      const why = report.result === "failed" && report.why ? W.failed(report.why) : W.notConnected(pending.vendor);
      return { answer: `failed: ${why}`, note: why };
    }
    const why = report.result === "failed" && report.why ? report.why : draft ? W.draftIs(draft.status) : W.noDraft;
    return { answer: `failed: ${pending.vendor} is connected; ${W.cardsNotIssued(why)}`, note: W.cardsNotIssued(why) };
  }
  if (pending.step === "cards") {
    const draft = pending.draft ? await draftInfo(ctx.token, pending.draft, ctx.fetch, ctx.kiffBase ?? KIFF_API, ctx.now) : undefined;
    if (draft && issued(draft.status)) return { answer: "approved" };
    const why = report.result === "failed" && report.why ? report.why : draft ? W.draftIs(draft.status) : W.noDraft;
    return { answer: `failed: ${W.cardsNotIssued(why)}`, note: W.cardsNotIssued(why) };
  }
  let grant = (await readGrants(ctx.planqueBase, ctx.tenant, ctx.proof, ctx.fetch)).find((g) => g.id === pending.grant);
  // signed_in: planque holds the sign-in's token, unused, until the approving phone confirms it with planque's
  // one-use code; unconfirmed, planque revokes it. So it's "not yet", never approved and never failed: read once
  // more (the phone may have just confirmed), and if it's still waiting, the ask waits again for the phone's
  // report after its confirm, and Coach hears nothing yet (go-to-market job 1, 13 Nov).
  if (grant?.status === "signed_in") {
    grant = (await readGrants(ctx.planqueBase, ctx.tenant, ctx.proof, ctx.fetch)).find((g) => g.id === pending.grant);
    if (grant?.status === "signed_in") {
      await ctx.store.put(key, pending, new Date(ctx.now.getTime() + ASK_KEPT_MS));
      await ctx.store.remove(`${key}#taken`);
      throw new ApprovalError(409, W.confirmFirst, "sign_in_not_confirmed");
    }
  }
  if (grant && (grant.status === "connected" || grant.status === "listed")) {
    await noteConnected(ctx.store, ctx.tenant, grant, ctx.now);
    // The card carried a draft that this signature didn't confirm (the combined step wasn't offered): approved
    // only once KIFF says it's issued. Otherwise the connection is said done and the Card not, as key_and_cards
    // says its half, and Coach shows the approval again, which asks the Card alone.
    if (pending.draft) {
      const draft = await draftInfo(ctx.token, pending.draft, ctx.fetch, ctx.kiffBase ?? KIFF_API, ctx.now);
      // Issued at the version the card showed, when it showed one: another version isn't the Card the owner read.
      const other = !!draft && !!pending.version && !!draft.version && draft.version !== pending.version;
      // By design (connect now, the Card next, as the card said): the connection is connected and its Card is still the
      // draft the card showed. That never reads as a failure: Coach hears to show the Card alone, and the person reads
      // "Gmail is connected. Next, its Card." (CARD_NEXT, approvalAnswerWords).
      if (grant.status === "connected" && draft && draft.status === "draft" && !other) {
        return { answer: `failed: ${pending.vendor} is connected; ${CARD_NEXT}`, note: W.connectedCardNext(pending.vendor) };
      }
      if (!draft || !issued(draft.status) || other) {
        const why = !draft ? W.noDraft : other ? W.outOfDate : W.draftIs(draft.status);
        const done = grant.status === "connected" ? "is connected" : "is signed in";
        return { answer: `failed: ${pending.vendor} ${done}; ${W.cardsNotIssued(why)}; show the approval again for its Card alone`, note: W.cardsNotIssued(why) };
      }
    }
    return { answer: "approved" };
  }
  const vendor = pending.vendor;
  let why: string;
  switch (report.result) {
    case "sign_in_closed": why = W.signInClosed; break;
    case "no_code": why = W.noCode(vendor); break;
    // planque's approve came back with no sign-in link and not connected: its status, as it answered.
    case "not_connected": why = W.cameBack(vendor, report.status ?? grant?.status ?? "unknown"); break;
    case "failed": why = W.failed(report.why); break;
    default: why = W.notConnected(vendor);
  }
  return { answer: `failed: ${why}`, note: why };
}
