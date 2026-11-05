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
import { draftInfo, type Fetch, issued, KIFF_API, KIFFError } from "./kiff.js";

/** A grant as planque sends it, in what the decision reads. */
export interface ApprovalGrant {
  id: string;
  status: string;
  digest: string;
  vendor?: string;
  reason?: string;
  /** The app's sign-in was set up on the owner's pick, not by an operator (kiff/planque#87). */
  on_demand?: boolean;
  request: { agent?: string; server?: string; profile?: unknown; tools?: Array<{ tool?: string; name?: string; title?: string; read_only?: boolean; setup_reads?: boolean }> } & Record<string, unknown>;
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
  for (const t of writes) {
    const name = t.name || t.tool;
    if (!name) continue;
    for (const raw of cards) {
      const card = raw as { on_exceed?: unknown; grants?: unknown; aggregates?: unknown };
      const actions = Array.isArray(card.grants) ? card.grants.map((g) => (g as { action?: unknown }).action) : [];
      if (!actions.includes(name)) continue;
      const asks = card.on_exceed === "owner" && Array.isArray(card.aggregates)
        && card.aggregates.some((a) => (a as { quantity?: unknown; limit?: unknown }).quantity === "count" && (a as { limit?: unknown }).limit === 0);
      if (!asks) return t.title || name;
    }
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
}

/** One message to sign, and the call it goes into. */
export interface Signing {
  id: string;
  step: "connect" | "cards";
  /** The exact bytes the device's key signs, under biometrics. */
  message: string;
  /** What the biometric prompt says. */
  reason: string;
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
  step: "connect" | "cards";
  grant: string;
  vendor: string;
  draft?: string;
  version?: string;
  made: string;
}

/** An ask waits this long for the phone's report. */
export const ASK_KEPT_MS = 20 * 60_000;

/** The words the apps show, all from here. */
export const WORDS = {
  outOfDate: "What you're asked to approve changed since it was shown. Read it again, then approve.",
  wordsUnread: "KIFF's words for this connection aren't read yet. Read it again, then approve.",
  alreadyApproved: "Already approved",
  nothingToApprove: (vendor: string) => `${vendor} is already connected. There's nothing new to approve, so nothing was approved.`,
  digestMismatch: "This request doesn't match what it asks for. Nothing was approved.",
  noGrant: "There's no connection with that id.",
  cardsNotIssued: (why: string) => `KIFF didn't issue the taf's Cards: ${why}`,
  onDemandLetsRun: (vendor: string, tool: string) => `${vendor} was set up today, so every change must ask you first; this Card would let "${tool}" run without asking. Nothing was approved. Coach drafts it again.`,
  signInClosed: "The sign-in was closed. Nothing was connected.",
  noCode: (vendor: string) => `The sign-in at ${vendor} came back without its confirmation code. Nothing was connected.`,
  cameBack: (status: string) => `The connection came back ${status.replace(/_/g, " ")} with no sign-in link. Nothing was connected.`,
  notConnected: (vendor: string, status: string) => `The sign-in at ${vendor} didn't connect: the connection is ${status.replace(/_/g, " ")}. Nothing was connected. Try signing in again.`,
  failed: (why: string) => `Not connected: ${why}`,
};

export class ApprovalError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

const ID = /^[A-Za-z0-9_.:-]{1,128}$/;
const HEX64 = /^[0-9a-f]{64}$/;

const keyOf = (tenant: string, taf: string, component: string, ask: string) => `${tenant}#approval-ask#${taf}#${component}#${ask}`;

/** The grants planque shows this phone, read with the proof the phone signed and tafs cloud forwards unchanged. */
export async function readGrants(base: string, tenant: string, proof: string, net: Fetch): Promise<ApprovalGrant[]> {
  if (!proof || proof.length > 4096) throw new ApprovalError(503, "Connect this phone to read its connected apps");
  let res: Response;
  try {
    res = await net(`${base.replace(/\/$/, "")}/v1/accounts/${encodeURIComponent(tenant)}/grants`, { headers: { accept: "application/json", "Planque-Device-Signature": proof } });
  } catch {
    throw new ApprovalError(502, "Connected apps could not be read; try again");
  }
  if (!res.ok) throw new ApprovalError(502, "Connected apps could not be read; try again");
  const out = (await res.json().catch(() => ({}))) as { grants?: unknown };
  if (!Array.isArray(out.grants)) throw new ApprovalError(502, "Connected apps returned an unreadable list");
  return out.grants.filter((g): g is ApprovalGrant => !!g && typeof g === "object" && typeof (g as ApprovalGrant).id === "string" && typeof (g as ApprovalGrant).status === "string" && !!(g as ApprovalGrant).request);
}

/** The vendor as the owner knows it: planque's name for it, else its server. */
function vendorOf(g: ApprovalGrant): string {
  return g.vendor || g.request.server || "the vendor";
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
  return ask;
}

export interface DecideContext {
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
}

/**
 * Decides an approval: what the card needs signed, if anything. Reads planque's
 * grant as the phone, and KIFF's draft with the person's session.
 */
export async function decide(ask: ApprovalAsk, ctx: DecideContext): Promise<Decision> {
  const grants = await readGrants(ctx.planqueBase, ctx.tenant, ctx.proof, ctx.fetch);
  const grant = grants.find((g) => g.id === ask.grant);
  if (!grant) return { outcome: "answered", answer: `failed: ${WORDS.noGrant}`, note: WORDS.noGrant };
  const vendor = vendorOf(grant);
  if (grant.status === "connected") {
    // The second approval: the vendor is connected, and the taf's Cards over its tools wait as a
    // draft. A connection already connected with no Cards left signs nothing, so it isn't approved.
    if (!ask.draft) return { outcome: "answered", answer: `failed: ${vendor} is already connected; nothing new to approve`, note: WORDS.nothingToApprove(vendor) };
    const draft = await draftInfo(ctx.token, ask.draft, ctx.fetch, ctx.kiffBase ?? KIFF_API, ctx.now);
    if (!draft) return { outcome: "answered", answer: `failed: KIFF has no Card draft ${ask.draft} on this account`, note: `KIFF has no Card draft for this. Ask Coach for a new one.` };
    // Its Card confirmed already: a real approval, signed earlier (another phone, or a tap before this one).
    if (issued(draft.status)) return { outcome: "answered", answer: `already approved: ${vendor} is connected and its Card is confirmed`, note: WORDS.alreadyApproved };
    if (draft.status !== "draft") return { outcome: "answered", answer: `failed: the Card draft is ${draft.status}`, note: `The Card draft is ${draft.status}. Ask Coach for a new one.` };
    if (!draft.version || draft.version !== ask.shown_draft) return { outcome: "read_again", note: WORDS.outOfDate };
    // An app set up on demand asks for every change (kiff/planque#87): a draft that lets one of its
    // writes run outright isn't signed, and Coach hears why, so the default holds here, not only in its words.
    const letsRun = onDemandLetsRun(grant, draft.cards ?? []);
    if (letsRun) {
      const why = WORDS.onDemandLetsRun(vendor, letsRun);
      return { outcome: "answered", answer: `failed: ${vendor} was set up on demand, so every write and money tool of it must ask the owner each time (0 a day, held); the draft lets "${letsRun}" run without asking. Draft it again with that tool held.`, note: why };
    }
    const id = ctx.newID();
    const pending: Pending = { step: "cards", grant: grant.id, vendor, draft: ask.draft, version: draft.version, made: ctx.now.toISOString() };
    await ctx.store.put(keyOf(ctx.tenant, ctx.taf, ctx.component, id), pending, new Date(ctx.now.getTime() + ASK_KEPT_MS));
    return { outcome: "sign", ask: { id, step: "cards", message: `tafs.cards.v1|${ctx.tenant}|${ask.draft}|${draft.version}`, reason: `Give ${draft.agent || "the taf"} its Card`, kiff: { draft: ask.draft, version: draft.version } } };
  }
  if (!APPROVABLE.has(grant.status)) {
    const why = `This connection is ${grant.status.replace(/_/g, " ")}${grant.reason ? `: ${grant.reason}` : "."}`;
    return { outcome: "answered", answer: `failed: ${why}`, note: why };
  }
  // The digest planque signs is the request's: the phone shows Approve only when it matches, and so does tafs cloud.
  let digest = "";
  try { digest = sha256(grant.request); } catch { /* an unreadable request never matches */ }
  if (digest !== grant.digest) return { outcome: "answered", answer: `failed: ${WORDS.digestMismatch}`, note: WORDS.digestMismatch };
  if (grant.digest !== ask.shown_digest) return { outcome: "read_again", note: WORDS.outOfDate };
  // A connection with a profile is approved only with KIFF's words for exactly that profile, as the owner read them.
  let kiffProfile: { profile_sha256: string; words_sha256: string } | undefined;
  if (grant.request.profile !== undefined) {
    if (!ask.words || ask.words.profile_sha256 !== sha256(grant.request.profile)) return { outcome: "read_again", note: WORDS.wordsUnread };
    kiffProfile = { profile_sha256: ask.words.profile_sha256, words_sha256: ask.words.words_sha256 };
  }
  const id = ctx.newID();
  const pending: Pending = { step: "connect", grant: grant.id, vendor, made: ctx.now.toISOString() };
  await ctx.store.put(keyOf(ctx.tenant, ctx.taf, ctx.component, id), pending, new Date(ctx.now.getTime() + ASK_KEPT_MS));
  return {
    outcome: "sign",
    ask: {
      id, step: "connect", message: `tafs.connect.v1|${ctx.tenant}|${grant.id}|${grant.digest}`, reason: `Connect ${vendor} to ${grant.request.agent ?? "the taf"}`,
      planque: { grant: grant.id, body: { digest: grant.digest, ...(kiffProfile ? { kiff_profile: kiffProfile } : {}) } },
    },
  };
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
 * no signature itself. Each signed report is taken once.
 */
export async function checkSigned(ctx: ReportContext): Promise<void> {
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
  const out = (await res.json().catch(() => ({}))) as { signed?: unknown };
  if (out.signed !== true) throw new ApprovalError(403, "this phone didn't sign that answer");
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
  if (!ID.test(askID)) throw new ApprovalError(404, "no approval waits for that answer");
  const key = keyOf(ctx.tenant, ctx.taf, ctx.component, askID);
  const pending = (await ctx.store.get(key)) as Pending | undefined;
  if (!pending || !pending.step) throw new ApprovalError(404, "no approval waits for that answer");
  await checkSigned(ctx);
  // Taken once, atomically, before Coach hears anything: two signed reports of one ask that
  // arrive together settle it once; the other finds nothing waiting. A report KIFF refused
  // above spends nothing, so the phone can report again.
  if (!(await ctx.store.once(`${key}#taken`, new Date(ctx.now.getTime() + ASK_KEPT_MS)))) throw new ApprovalError(404, "no approval waits for that answer");
  await ctx.store.remove(key);
  if (pending.step === "cards") {
    const draft = pending.draft ? await draftInfo(ctx.token, pending.draft, ctx.fetch, ctx.kiffBase ?? KIFF_API, ctx.now) : undefined;
    if (draft && issued(draft.status)) return { answer: "approved" };
    const why = report.result === "failed" && report.why ? report.why : draft ? `the draft is ${draft.status}` : "KIFF has no such draft";
    return { answer: `failed: ${WORDS.cardsNotIssued(why)}`, note: WORDS.cardsNotIssued(why) };
  }
  const grants = await readGrants(ctx.planqueBase, ctx.tenant, ctx.proof, ctx.fetch);
  const grant = grants.find((g) => g.id === pending.grant);
  if (grant && (grant.status === "connected" || grant.status === "listed")) return { answer: "approved" };
  const vendor = pending.vendor;
  let why: string;
  switch (report.result) {
    case "sign_in_closed": why = WORDS.signInClosed; break;
    case "no_code": why = WORDS.noCode(vendor); break;
    // planque's approve came back with no sign-in link and not connected: its status, as it answered.
    case "not_connected": why = WORDS.cameBack(report.status ?? grant?.status ?? "unknown"); break;
    case "failed": why = WORDS.failed(report.why ?? "it didn't go through"); break;
    default: why = WORDS.notConnected(vendor, grant?.status ?? "unknown");
  }
  return { answer: `failed: ${why}`, note: why };
}
