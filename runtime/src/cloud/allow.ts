/**
 * "Allow this from now on" on a held call (docs/allow-from-now-on.md): the offer of the narrowest change to a
 * taf's Card that would have let this call go on its own.
 *
 * KIFF builds the draft on its own model (`allow-draft`); tafs cloud never writes a Card. What tafs cloud adds is
 * its own check of KIFF's change against the hold's breaches (one change per breach, on that breach's mandate, of
 * the kind it allows, with the call's own figure or one rounding step above it), and the words. A change that
 * doesn't match isn't offered: the answer is `none` (`no_change`), and the draft is left to lapse. The phone's
 * check against the draft itself is the third.
 */
import { ask, type Fetch, KIFF_API, KIFFError } from "./kiff.js";
import { type Lang, plural, wordsIn } from "./words/index.js";

/** One Card limit the held call is outside, as `GET /v1/me/card-exceptions/{id}` carries it. */
export interface Breach {
  mandate_id?: string;
  outcome?: string;
  /** `count`, or an amount's (`sum(<param>)`, or `amount`). */
  quantity?: string;
  unit?: string;
  card_unit?: string;
  limit?: number;
  used?: number;
  draw?: number;
  window?: string;
  resource?: string;
  per?: string;
  value?: string;
}

export interface Hold {
  id?: string;
  subject?: string;
  kind?: string;
  status?: string;
  action?: string;
  title?: string;
  accounting_version?: number;
  breaches?: Breach[];
}

/** One change of KIFF's draft, as `allow-draft` answers it. */
export interface Change {
  mandate_id?: string;
  kind?: string;
  before?: number;
  after?: number;
  unit?: string;
  window?: string;
  parameter?: string;
  value?: string;
  rounded?: boolean;
}

export interface AllowDraft {
  draft?: string;
  version?: string;
  confirm_key?: string;
  changes?: Change[];
  round?: { after?: number };
  none?: string;
}

export interface Replay {
  would_allow?: { calls?: number; by_quantity?: Array<{ quantity?: string; unit?: string; total?: number }> };
}

/** Why there's no offer. */
export type None = "never" | "unit" | "no_change" | "not_yours";

export type OfferChange = Change & { words_before: string; words_after: string };

export type Offer = {
  changes: OfferChange[];
  round?: { after: number; words: string };
  would?: { calls: number; words: string };
  draft: string;
  confirm_key: string;
  send_held: true;
};

/** The three outcomes a Card change can answer; anything else (a "never", a unit mismatch) gets no offer. */
const OUTCOMES = ["per_action_exceeded", "limit_reached", "resource_not_on_card"];

/** How far back "what it would have done" looks. */
export const REPLAY_DAYS = 30;

/** Whether a breach counts calls rather than an amount. */
function counts(b: Breach): boolean {
  return b.quantity === "count";
}

/** The kind of change a breach allows, by the contract's table. */
export function kindFor(b: Breach): "per_action" | "total" | "count" | "resource" | undefined {
  if (b.outcome === "per_action_exceeded") return counts(b) ? undefined : "per_action";
  if (b.outcome === "limit_reached") return counts(b) ? "count" : "total";
  if (b.outcome === "resource_not_on_card") return "resource";
  return undefined;
}

/** The currency's minor digits (2 for EUR, 0 for JPY); 2 for a unit Intl doesn't know. */
function digits(unit: string | undefined): number {
  try {
    return new Intl.NumberFormat("en", { style: "currency", currency: unit ?? "" }).resolvedOptions().maximumFractionDigits ?? 2;
  } catch {
    return 2;
  }
}

/**
 * An amount in minor units rounded up to a figure the owner would pick: the next 50 below 1,000, the next 100
 * below 10,000, then the next 1,000, in major units (€320 → €350, €1,240 → €1,300). Never below it, and never
 * more than one step above it.
 */
export function roundUp(minor: number, unit: string | undefined): number {
  const scale = 10 ** digits(unit);
  const major = minor / scale;
  const step = major < 1000 ? 50 : major < 10000 ? 100 : 1000;
  return Math.ceil(major / step) * step * scale;
}

/** The figure a breach's change should carry: the call's own, or one rounding step above it with `round`. */
function figure(b: Breach, kind: string, round: boolean): number | undefined {
  const draw = num(b.draw);
  if (draw === undefined) return undefined;
  const own = kind === "per_action" ? draw : (num(b.used) ?? 0) + draw;
  return round && kind !== "count" ? roundUp(own, b.unit) : own;
}

function num(x: unknown): number | undefined {
  return typeof x === "number" && Number.isSafeInteger(x) && x >= 0 ? x : undefined;
}

/**
 * Whether KIFF's changes are exactly the narrowest ones for the hold's breaches: one per breach, on its mandate,
 * of its kind, in its unit and window, with the call's figure (or, rounded, one step above it), a resource adding
 * this value only. Anything more, less or wider fails.
 */
export function changesMatch(breaches: Breach[], changes: Change[], round: boolean): boolean {
  if (!breaches.length || breaches.length !== changes.length) return false;
  const left = [...changes];
  for (const b of breaches) {
    const kind = kindFor(b);
    if (!kind || !b.mandate_id) return false;
    const at = left.findIndex((c) => c.mandate_id === b.mandate_id && c.kind === kind && fits(b, kind, c, round));
    if (at < 0) return false;
    left.splice(at, 1);
  }
  return left.length === 0;
}

function fits(b: Breach, kind: string, c: Change, round: boolean): boolean {
  if (kind === "resource") {
    return !!b.value && c.value === b.value && !!c.parameter && c.rounded !== true && c.after == null && c.before == null
      && (b.resource === undefined || c.parameter === b.resource);
  }
  if (kind !== "count" && c.unit !== b.unit) return false;
  if (kind !== "per_action" && (c.window ?? "") !== (b.window ?? "")) return false;
  if (b.limit !== undefined && c.before !== b.limit) return false;
  const exact = figure(b, kind, false);
  const want = figure(b, kind, round);
  if (exact === undefined || want === undefined || c.after === undefined) return false;
  // Rounded only when rounding moved the figure: an already round figure stays exact, and says so.
  if (c.rounded === true) return round && want !== exact && c.after === want;
  return c.after === exact && (!round || want === exact) && (c.before === undefined || c.after > c.before);
}

/**
 * What rounding would give, when it changes anything: the per-call figure when there's one, else a total's. A
 * count and a resource are never rounded.
 */
export function roundOffer(breaches: Breach[], lang: Lang): { after: number; words: string } | undefined {
  const pick = breaches.find((b) => kindFor(b) === "per_action") ?? breaches.find((b) => kindFor(b) === "total");
  if (!pick) return undefined;
  const kind = kindFor(pick)!;
  const exact = figure(pick, kind, false);
  const rounded = figure(pick, kind, true);
  if (exact === undefined || rounded === undefined || rounded === exact) return undefined;
  return { after: rounded, words: money(rounded, pick.unit, lang) };
}

/** An amount in minor units in the app's money words: "€350", and "€320.50" only when there are cents. */
export function money(minor: number, unit: string | undefined, lang: Lang): string {
  const d = digits(unit);
  const major = minor / 10 ** d;
  try {
    if (!unit || !Intl.supportedValuesOf("currency").includes(unit)) return `${major} ${unit ?? ""}`.trim();
    const whole = minor % 10 ** d === 0;
    return new Intl.NumberFormat(lang === "en" ? "en-GB" : lang, { style: "currency", currency: unit, ...(whole ? { minimumFractionDigits: 0, maximumFractionDigits: 0 } : {}) }).format(major);
  } catch {
    return `${major} ${unit ?? ""}`.trim();
  }
}

/** A change's limit, before and after, in tafs cloud's words: "Refunds: up to €150 each" → "…up to €320 each". */
export function changeWords(c: Change, breach: Breach, action: string, lang: Lang): { words_before: string; words_after: string } {
  const w = wordsIn(lang);
  const window = w.allow_window[c.window ?? ""] ?? c.window ?? "";
  const rounded = (line: string) => c.rounded === true ? `${line} (${w.allow_rounded_from(money(figure(breach, c.kind ?? "", false) ?? 0, c.unit, lang))})` : line;
  switch (c.kind) {
    case "per_action":
      return { words_before: w.allow_each(action, money(c.before ?? 0, c.unit, lang)), words_after: rounded(w.allow_each(action, money(c.after ?? 0, c.unit, lang))) };
    case "total":
      return {
        words_before: w.allow_total(action, money(c.before ?? 0, c.unit, lang), window),
        words_after: rounded(w.allow_from_now_on(w.allow_total(action, money(c.after ?? 0, c.unit, lang), window))),
      };
    case "count": {
      const line = (n: number) => n === 0 ? w.allow_asks_first(action) : w.allow_count(action, n, plural(lang, n), window);
      return { words_before: line(c.before ?? 0), words_after: w.allow_from_now_on(line(c.after ?? 0)) };
    }
    default:
      return { words_before: w.allow_value_out(action, c.value ?? ""), words_after: w.allow_value_in(action, c.value ?? "") };
  }
}

/** The replay in words: "With this Card, 4 more calls would have gone out on their own (€1,180)", or that nothing would. */
export function wouldWords(r: Replay, lang: Lang): { calls: number; words: string } {
  const w = wordsIn(lang);
  const calls = num(r.would_allow?.calls) ?? 0;
  if (!calls) return { calls: 0, words: w.allow_would_none };
  const totals = (r.would_allow?.by_quantity ?? [])
    .filter((q) => q.quantity !== "count" && num(q.total) !== undefined && q.unit)
    .map((q) => money(q.total!, q.unit, lang)).join(", ");
  return { calls, words: w.allow_would(calls, plural(lang, calls), totals) };
}

export function noneWords(why: None, lang: Lang): string {
  const w = wordsIn(lang);
  return { never: w.allow_none_never, unit: w.allow_none_unit, no_change: w.allow_none_no_change, not_yours: w.allow_none_not_yours }[why];
}

/** What tafs cloud answers on the tap: the offer, or why there's none, with its words. */
export type Answer = { offer: Offer } | { none: None; words: string };

export interface AllowDeps {
  token: string;
  /** The taf's KIFF agent: a hold on any other is `not_yours`. */
  agent: string;
  lang: Lang;
  fetch?: Fetch;
  base?: string;
  /** Where a replay that fails is said, so the offer still goes without its history. */
  log?: (line: string) => void;
}

/**
 * The offer for a held call, on the tap: the hold read from KIFF, KIFF's draft (`allow-draft`), tafs cloud's own
 * check of its changes, and the replay of the last 30 days. Everything with the person's session.
 */
export async function allowOffer(exception: string, round: boolean, d: AllowDeps): Promise<Answer> {
  const doFetch = d.fetch ?? fetch;
  const base = d.base ?? KIFF_API;
  const none = (why: None): Answer => ({ none: why, words: noneWords(why, d.lang) });
  const hold = await ask<Hold>(d.token, "GET", `/v1/me/card-exceptions/${encodeURIComponent(exception)}`, undefined, doFetch, base);
  if (!hold.subject || hold.subject !== d.agent) return none("not_yours");
  if (hold.status !== undefined && hold.status !== "held") throw new KIFFError(409, "this call doesn't wait any more");
  if (hold.kind === "card_unit") return none("unit");
  const breaches = Array.isArray(hold.breaches) ? hold.breaches : [];
  if (!breaches.length || !breaches.every((b) => OUTCOMES.includes(b.outcome ?? "") && kindFor(b))) return none("no_change");
  const draft = await ask<AllowDraft>(d.token, "POST", `/v1/me/card-exceptions/${encodeURIComponent(exception)}/allow-draft`, round ? { round: true } : {}, doFetch, base);
  if (draft.none === "never" || draft.none === "unit" || draft.none === "no_change") return none(draft.none);
  if (draft.none !== undefined || !draft.draft || !draft.confirm_key || !Array.isArray(draft.changes)) return none("no_change");
  if (!changesMatch(breaches, draft.changes, round)) return none("no_change");
  const ours = round ? undefined : roundOffer(breaches, d.lang);
  // KIFF's rounding must agree with tafs cloud's, or the button would name a figure the draft won't carry.
  if (draft.round?.after !== undefined && draft.round.after !== ours?.after) return none("no_change");
  const action = hold.title || hold.action || "";
  const changes = draft.changes.map((c) => {
    const b = breaches.find((x) => x.mandate_id === c.mandate_id && kindFor(x) === c.kind)!;
    return { ...c, ...changeWords(c, b, action, d.lang) };
  });
  let would: Offer["would"];
  try {
    would = wouldWords(await ask<Replay>(d.token, "POST", `/v1/me/agent-drafts/${encodeURIComponent(draft.draft)}/replay`, { days: REPLAY_DAYS }, doFetch, base), d.lang);
  } catch (err) {
    d.log?.(`allow: the replay of ${draft.draft} failed (${err instanceof Error ? err.message : String(err)}); the offer goes without it`);
  }
  return {
    offer: {
      changes, ...(ours ? { round: ours } : {}), ...(would ? { would } : {}),
      draft: draft.draft, confirm_key: draft.confirm_key, send_held: true,
    },
  };
}
