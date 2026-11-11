/**
 * The Record across a person's tafs: search it, one case, and its receipts for an accountant. Everything is read from
 * KIFF's activity with the person's session (`GET /v1/me/activity`, which KIFF scopes to what they may see) and its
 * receipts (`GET /v1/receipts/{trace_id}`), passed through as KIFF issued them: tafs cloud keeps no copy and builds
 * nothing of its own. A call's case is what it's about (`about`, the taf's business reference, "Order 1042"); KIFF
 * keeps no customer's name, so the Record is searched by order, invoice, amount or action, never by customer.
 */
import { createHash } from "node:crypto";
import { ActivityError, activityEntry, type ActivityCall, type ActivityEntry } from "./activity.js";
import { type Fetch, KIFF_API, KIFFError } from "./kiff.js";
import { dayIn } from "./knob.js";
import { type Lang, wordsIn } from "./words/index.js";

/** A taf as the Record names its calls: the KIFF agent that makes them, and the name people know it by. */
export interface RecordTaf { id: string; name: string; kiffAgent: string }

export interface RecordEntry extends ActivityEntry {
  taf: { id: string; name: string };
  /** What the call is about, in the taf's words, as KIFF kept it: the case it belongs to. */
  about?: string;
}

export interface RecordPage {
  q: string; hint: string; entries: RecordEntry[];
  groups: Array<{ id: string; title: string; entries: RecordEntry[] }>;
  next_cursor?: string; end_words?: string; none_words?: string;
}

/** KIFF's activity a page at a time, with how many calls it shows in all. */
type Page = { calls: ActivityCall[]; shown: number };
const PAGE = 100;
/** Pages read for one answer: a search, a case or an export never reads more than this many calls of KIFF's. */
export const MAX_SCAN = 20;
export const MAX_Q = 100;
/** The longest date range one export covers, and the most receipts it carries. */
export const EXPORT_MAX_DAYS = 92;
export const EXPORT_MAX_RECEIPTS = 500;

const hash = (v: unknown) => createHash("sha256").update(JSON.stringify(v)).digest("hex");

async function read(token: string, offset: number, net: Fetch, base: string): Promise<Page> {
  const q = new URLSearchParams({ limit: String(PAGE), offset: String(offset) });
  const res = await net(`${base}/v1/me/activity?${q}`, { headers: { authorization: `Bearer ${token}`, accept: "application/json" } });
  if (!res.ok) throw new KIFFError(res.status === 401 || res.status === 403 ? res.status : 502, "The Record could not be read; try again");
  const page = await res.json() as Partial<Page>;
  if (!Array.isArray(page.calls) || !Number.isSafeInteger(page.shown) || (page.shown ?? -1) < 0) throw new ActivityError(502, "The Record returned an unreadable page");
  return page as Page;
}

/** One way to write a case's reference, so "Order #1042" and "order 1042" are the same case. */
export function caseKey(about: string): string {
  return about.normalize("NFKC").toLowerCase().replace(/[#№]/g, "").replace(/\s+/g, " ").trim();
}

/**
 * Whether a call matches what was searched: its case's reference or its action's name holding the words, or its
 * amount being the number asked ("150" is 150.00 and 150.50, "150.50" only that). Case and "#" don't count.
 */
export function recordMatches(c: ActivityCall, q: string): boolean {
  const k = caseKey(q);
  if (!k) return true;
  if (typeof c.about === "string" && caseKey(c.about).includes(k)) return true;
  if (caseKey(c.title || "").includes(k) || caseKey(c.action_name || "").includes(k)) return true;
  const n = /^[^\d-]{0,3}(-?\d{1,15})(?:[.,](\d{1,6}))?\s*[^\d]{0,3}$/.exec(q.trim());
  const value = Number(c.amount?.value);
  if (!n || !Number.isFinite(value)) return false;
  return n[2] === undefined ? Math.trunc(value) === Number(n[1]) : value === Number(`${n[1]}.${n[2]}`);
}

/** A date as the person asked for it (YYYY-MM-DD), or undefined. */
function dateOf(raw: string | null): string | undefined {
  return raw && /^\d{4}-\d{2}-\d{2}$/.test(raw) && Number.isFinite(Date.parse(`${raw}T00:00:00Z`)) ? raw : undefined;
}

function entryOf(c: ActivityCall, taf: RecordTaf, zone: string, now: Date, lang: Lang): RecordEntry {
  return { ...activityEntry(c, zone, now, taf.name, lang), taf: { id: taf.id, name: taf.name }, ...(typeof c.about === "string" && c.about ? { about: c.about } : {}) };
}

/**
 * A page of the Record across the person's tafs, newest first, grouped by day: `q` searched (recordMatches), `from`
 * and `to` the days it covers in the account's zone. Calls of an agent that isn't one of these tafs aren't shown.
 */
export async function recordPage(input: { token: string; scope: string; tafs: RecordTaf[]; zone: string; now: Date; query: URLSearchParams; lang?: Lang },
  net: Fetch = fetch, base = KIFF_API): Promise<RecordPage> {
  const lang = input.lang ?? "en";
  const w = wordsIn(lang);
  const q = (input.query.get("q") ?? "").trim();
  if (q.length > MAX_Q) throw new ActivityError(400, `a search is at most ${MAX_Q} characters`);
  const from = input.query.get("from"), to = input.query.get("to");
  if ((from && !dateOf(from)) || (to && !dateOf(to))) throw new ActivityError(400, "from and to are dates, such as 2026-11-05");
  const take = Number(input.query.get("limit") ?? 20);
  if (!Number.isInteger(take) || take < 1 || take > 50) throw new ActivityError(400, "limit is 1 to 50");
  const byAgent = new Map(input.tafs.map((t) => [t.kiffAgent, t]));
  const scope = hash([input.scope, q, from, to]);
  const raw = input.query.get("cursor");
  let offset = 0, revision: string | undefined;
  if (raw) {
    try {
      const c = JSON.parse(Buffer.from(raw.slice(0, 2048), "base64url").toString()) as { scope: string; offset: number; revision: string };
      if (c.scope !== scope || !Number.isSafeInteger(c.offset) || c.offset < 0 || typeof c.revision !== "string") throw new Error();
      offset = c.offset; revision = c.revision;
    } catch { throw new ActivityError(400, "the cursor belongs to another search; search again"); }
  }
  const entries: RecordEntry[] = [];
  let more = true, older = false;
  for (let scan = 0; scan < MAX_SCAN && more && entries.length < take; scan++) {
    const page = await read(input.token, offset, net, base);
    // How many calls KIFF shows in all: a call made since moves every offset, so a cursor from before is refused.
    const head = String(page.shown);
    if (scan === 0 && revision && revision !== head) throw new ActivityError(409, "The Record changed; search again to see the newest");
    revision ??= head;
    if (!page.calls.length) { more = false; break; }
    for (const call of page.calls) {
      offset++;
      const day = dayIn(call.created_at, input.zone);
      if (from && day < from) { older = true; break; }
      const taf = byAgent.get(call.actor_id ?? "");
      if (taf && (!to || day <= to) && recordMatches(call, q)) entries.push(entryOf(call, taf, input.zone, input.now, lang));
      if (entries.length === take) break;
    }
    more = !older && offset < page.shown;
  }
  const groups: RecordPage["groups"] = [];
  for (const entry of entries) {
    let group = groups.find((g) => g.id === entry.day);
    if (!group) { group = { id: entry.day, title: entry.day_title, entries: [] }; groups.push(group); }
    group.entries.push(entry);
  }
  return { q, hint: w.record_hint, entries, groups,
    ...(more ? { next_cursor: Buffer.from(JSON.stringify({ scope, offset, revision })).toString("base64url") } : { end_words: w.act_end }),
    ...(!entries.length && !more && q ? { none_words: w.record_none(q) } : {}) };
}

/**
 * Every call the Record reads (at most MAX_SCAN pages), newest first, while `keep` says to read on. `cut` says it
 * stopped at MAX_SCAN while the next call is still one `keep` wants: what it read isn't all there is.
 */
async function scan(token: string, net: Fetch, base: string, keep: (c: ActivityCall) => boolean): Promise<{ calls: ActivityCall[]; cut: boolean }> {
  const out: ActivityCall[] = [];
  let offset = 0;
  for (let n = 0; n < MAX_SCAN; n++) {
    const page = await read(token, offset, net, base);
    for (const c of page.calls) {
      if (!keep(c)) return { calls: out, cut: false };
      out.push(c);
    }
    offset += page.calls.length;
    if (!page.calls.length || offset >= page.shown) return { calls: out, cut: false };
  }
  // At the cap with calls left: cut only if the next one is still one `keep` wants. A range of exactly the cap,
  // with older calls after it, is whole.
  const next = (await read(token, offset, net, base)).calls[0];
  return { calls: out, cut: next !== undefined && keep(next) };
}

export interface CaseView {
  about: string; steps: RecordEntry[];
  /** The step that didn't go through, when one didn't: its id, and what the owner can do (its `next`). */
  failed?: { id: string; words: string; next?: ActivityEntry["next"] };
}

/**
 * One case: every call of the person's tafs about the same thing (caseKey), oldest first, each with its receipt and
 * how it went. When one didn't go through, `failed` names the latest such step and its own next step, which is the
 * only retry there is: through the taf, under its Card, never a re-send from here. It reads the newest
 * MAX_SCAN × PAGE calls: a case older than those isn't found.
 */
export async function caseView(input: { token: string; about: string; tafs: RecordTaf[]; zone: string; now: Date; lang?: Lang },
  net: Fetch = fetch, base = KIFF_API): Promise<CaseView | undefined> {
  const lang = input.lang ?? "en";
  const key = caseKey(input.about);
  if (!key || input.about.length > 120) return;
  const byAgent = new Map(input.tafs.map((t) => [t.kiffAgent, t]));
  const calls = (await scan(input.token, net, base, () => true)).calls
    .filter((c) => typeof c.about === "string" && caseKey(c.about) === key && byAgent.has(c.actor_id ?? ""));
  if (!calls.length) return;
  const steps = calls.reverse().map((c) => entryOf(c, byAgent.get(c.actor_id ?? "")!, input.zone, input.now, lang));
  const bad = [...steps].reverse().find((s) => s.kind === "not_done" || s.kind === "refused");
  return { about: steps.at(-1)?.about ?? input.about, steps,
    ...(bad ? { failed: { id: bad.id, words: wordsIn(lang).record_case_failed(bad.title, bad.detail), ...(bad.next ? { next: bad.next } : {}) } } : {}) };
}

/** A CSV cell: quoted, and one a spreadsheet would read as a formula starts with an apostrophe. */
function cell(v: string): string {
  // A plain number, a negative amount among them, stays a number a spreadsheet sums; anything else it would run is text.
  const safe = /^-?\d+(\.\d+)?$/.test(v) ? v : /^[=+\-@\t\r]/.test(v) ? `'${v}` : v;
  return `"${safe.replaceAll('"', '""')}"`;
}

export interface ReceiptsExport {
  from: string; to: string; time_zone: string;
  /** KIFF's receipts, exactly as KIFF issued them. */
  receipts: unknown[];
  /** One row a call: day, taf, action, about, amount, unit, outcome, receipt. */
  csv: string;
  /** Calls in the range whose receipt KIFF couldn't give just now: listed in the CSV, without it. */
  missing: number;
}

/**
 * The receipts of every call of the person's tafs on the days `from` to `to` (at most EXPORT_MAX_DAYS, in the
 * account's zone), for an accountant: KIFF's signed receipts passed through, and a CSV that lists each call.
 */
export async function receiptsExport(input: { token: string; from: string | null; to: string | null; tafs: RecordTaf[]; zone: string },
  net: Fetch = fetch, base = KIFF_API): Promise<ReceiptsExport> {
  const from = dateOf(input.from), to = dateOf(input.to);
  if (!from || !to) throw new ActivityError(400, "from and to are dates, such as 2026-11-05");
  if (to < from) throw new ActivityError(400, "to is on or after from");
  if ((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000 >= EXPORT_MAX_DAYS) throw new ActivityError(400, `an export covers at most ${EXPORT_MAX_DAYS} days`);
  const byAgent = new Map(input.tafs.map((t) => [t.kiffAgent, t]));
  const found = await scan(input.token, net, base, (c) => dayIn(c.created_at, input.zone) >= from);
  // An export is whole or refused: one cut short at MAX_SCAN would read as complete to an accountant.
  if (found.cut) throw new ActivityError(400, `more than ${MAX_SCAN * PAGE} calls on those days; export fewer days at a time`);
  const calls = found.calls.filter((c) => dayIn(c.created_at, input.zone) <= to && byAgent.has(c.actor_id ?? ""));
  const traced = calls.filter((c) => typeof (c.receipt as { trace_id?: unknown } | null)?.trace_id === "string");
  if (traced.length > EXPORT_MAX_RECEIPTS) throw new ActivityError(400, `more than ${EXPORT_MAX_RECEIPTS} receipts on those days; export fewer days at a time`);
  const receipts = new Map<string, unknown>();
  for (let i = 0; i < traced.length; i += 8) {
    await Promise.all(traced.slice(i, i + 8).map(async (c) => {
      const id = (c.receipt as { trace_id: string }).trace_id;
      const res = await net(`${base}/v1/receipts/${encodeURIComponent(id)}`, { headers: { authorization: `Bearer ${input.token}`, accept: "application/json" } }).catch(() => undefined);
      if (res?.ok) receipts.set(c.proposal_id, await res.json().catch(() => undefined));
      else if (res && (res.status === 401 || res.status === 403)) throw new KIFFError(res.status, "The receipts could not be read");
    }));
  }
  const rows = [["day", "taf", "action", "about", "amount", "unit", "outcome", "receipt"].map(cell).join(",")];
  for (const c of [...calls].reverse()) {
    const r = receipts.get(c.proposal_id) as { trace_id?: string } | undefined;
    rows.push([dayIn(c.created_at, input.zone), byAgent.get(c.actor_id ?? "")!.name, c.title || c.action_name, typeof c.about === "string" ? c.about : "",
      c.amount?.value ?? "", c.amount?.unit ?? "", c.outcome, r?.trace_id ?? ""].map((v) => cell(String(v))).join(","));
  }
  return { from, to, time_zone: input.zone, receipts: [...receipts.values()].filter((r) => r !== undefined), csv: `${rows.join("\r\n")}\r\n`,
    missing: traced.length - [...receipts.values()].filter((r) => r !== undefined).length };
}
