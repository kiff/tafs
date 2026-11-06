import { createHash } from "node:crypto";
import { type Fetch, KIFF_API, KIFFError } from "./kiff.js";
import { dayIn } from "./knob.js";
import { timeIn } from "./work.js";
import { connectionRefusalWords } from "./refusal.js";
import { CARD_UNIT_NOTICE, cardUnitExceptions, collapsedCurrencyDecision, type CardUnitException } from "./card-unit.js";
import type { Held } from "./home.js";

export const ACTIVITY_FILTERS = [
  { id: "all", title: "All" }, { id: "done", title: "Done" },
  { id: "held", title: "Held" }, { id: "refused", title: "Refused" },
] as const;
export type ActivityFilter = typeof ACTIVITY_FILTERS[number]["id"];

export class ActivityError extends Error {
  constructor(readonly status: number, message: string) { super(message); }
}

/** KIFF's public activity contract. Signed evidence is passed through, never reconstructed. */
export interface ActivityCall {
  proposal_id: string; actor_id?: string | null; action_name: string; title?: string | null;
  outcome: string; created_at: string; held?: boolean;
  tool_outcome?: { status: string; message?: string | null; refused_by?: string; code?: string } | null;
  detail?: string;
  amount?: { value: string; unit?: string | null } | null;
  read_only?: boolean; entity_id?: string; on_hash?: string; on_arg?: string;
  answer?: unknown; receipt?: unknown; reasons?: string[]; made_at?: string; reason?: string;
}

export interface ActivityEntry {
  id: string; at: string; kind: "done" | "approved" | "held" | "refused" | "not_done" | "sending" | "unknown";
  title: string; detail: string; time: string; day: string; day_title: string;
  amount_text?: string; call: ActivityCall;
  /** What the owner can do about a call that wasn't done, when its outcome says (kiff/tafs#476, gap 10). */
  next?: NextStep;
}

/**
 * The owner's next step for a call that wasn't done (kiff/tafs#476, gap 10): the app draws `words` as a button
 * for `reconnect` (the connection whose tools include `tool`) and `ask_coach` (Coach's chat with `message` written
 * in), and as a line for `try_later`; a kind it doesn't know, as a line.
 */
export interface NextStep {
  kind: "reconnect" | "ask_coach" | "try_later";
  words: string;
  /** The call's tool as KIFF names it: the connection to reconnect is the one whose tools include it. */
  tool: string;
  taf?: string;
  /** ask_coach: Coach's first message, for the owner to send. */
  message?: string;
}

/** The HTTP status a refusal's words name, as planque and the connectors write it ("it answered 403"), the form KIFF's gateway reads. */
export function answeredStatus(message: string | null | undefined): number | undefined {
  const m = /\banswered ([1-5]\d\d)\b/i.exec(message ?? "");
  return m ? Number(m[1]) : undefined;
}

/** KIFF's message for a call the tool refused when it says why, never its fallback for none. */
function saidWhy(message: string | null | undefined): string | undefined {
  const said = (message ?? "").replace(/\s+/g, " ").trim();
  return said && said !== "The tool returned an error." ? said : undefined;
}

/**
 * The next step for a call that wasn't done, by what KIFF recorded: the vendor's status in its words, or the
 * connection's code. A call that was done, held, refused by its Card, or whose answer was lost has none.
 */
export function nextStep(c: ActivityCall, taf?: string): NextStep | undefined {
  const o = c.tool_outcome;
  if (c.outcome !== "allowed" || !o) return;
  const title = c.title || c.action_name;
  const base = { tool: c.action_name, ...(taf ? { taf } : {}) };
  const said = saidWhy(o.message);
  const reconnect: NextStep = { kind: "reconnect", words: "Connect it again", ...base };
  const later: NextStep = { kind: "try_later", words: "Try again later: the app was busy or down", ...base };
  const ask: NextStep = { kind: "ask_coach", words: "Ask Coach to change the taf", ...base,
    message: `${taf ? `Change ${taf}: ` : ""}${title} failed.${said ? ` ${said.replace(/[.…]?$/, ".")}` : ""}` };
  if (o.status === "not_sent") return later;
  if (o.status !== "refused_by_tool") return;
  if (o.refused_by === "connection") {
    switch (o.code) {
      case "operation_changed": return reconnect;
      case "not_granted": case "definition_invalid": case "definition_unread": case "bad_arguments": case "permit_invalid": return ask;
      case "halt_unknown": case "record_unavailable": case "permit_expired": case "profile_not_applied": return later;
      default: return;
    }
  }
  return byStatus(answeredStatus(o.message), reconnect, ask, later);
}

/** The step a vendor's HTTP status calls for, when it calls for one. */
function byStatus(status: number | undefined, reconnect: NextStep, ask: NextStep, later: NextStep): NextStep | undefined {
  if (status === 401 || status === 403) return reconnect;
  if (status === 400 || status === 404 || status === 405 || status === 410 || status === 422) return ask;
  if (status === 429 || (status !== undefined && status >= 500)) return later;
  return;
}

/**
 * The next step for a failed call kiff/agents kept on its record line (Called: the tool and its result's
 * words), as Coach reads it: by the vendor's status in those words, as for an Activity row.
 */
export function nextForCalled(tool: string, said: string | undefined, taf?: string): NextStep | undefined {
  const base = { tool, ...(taf ? { taf } : {}) };
  return byStatus(answeredStatus(said), { kind: "reconnect", words: "Connect it again", ...base },
    { kind: "ask_coach", words: "Ask Coach to change the taf", ...base, message: `${taf ? `Change ${taf}: ` : ""}${tool} failed.${said ? ` ${said.replace(/[.…]?$/, ".")}` : ""}` },
    { kind: "try_later", words: "Try again later: the app was busy or down", ...base });
}

export interface ActivityPage {
  entries: ActivityEntry[]; groups: Array<{ id: string; title: string; entries: ActivityEntry[] }>;
  filters: typeof ACTIVITY_FILTERS; filter: ActivityFilter; next_cursor?: string; revision: string; end_words?: string;
  card_unit_exceptions: CardUnitException[];
  card_unit_exceptions_notice?: string;
}

interface Cursor { scope: string; offset: number; revision: string; day?: string }
const hash = (v: unknown) => createHash("sha256").update(JSON.stringify(v)).digest("hex");
export const representationTag = (v: unknown) => `"${hash(v)}"`;

function cursorOf(raw: string | null, scope: string): Cursor | undefined {
  if (!raw) return;
  if (raw.length > 2048) throw new ActivityError(400, "invalid activity cursor");
  try {
    const c = JSON.parse(Buffer.from(raw, "base64url").toString()) as Cursor;
    if (c.scope !== scope || !Number.isSafeInteger(c.offset) || c.offset < 0 || c.offset > 2147483647 || typeof c.revision !== "string") throw new Error();
    return c;
  } catch { throw new ActivityError(400, "the cursor belongs to another activity view; refresh this view"); }
}

function kindOf(c: ActivityCall): ActivityEntry["kind"] {
  if (c.outcome === "owner_required" || c.outcome === "approval_required") return "held";
  if (c.outcome !== "allowed") return "refused";
  switch (c.tool_outcome?.status) {
    case "done": return c.held ? "approved" : "done";
    case "refused_by_tool": case "not_sent": return "not_done";
    case "sending": return "sending";
    default: return "unknown";
  }
}

export function activityMatches(c: ActivityCall, filter: ActivityFilter): boolean {
  const kind = kindOf(c);
  return filter === "all" || (filter === "done" ? kind === "done" || kind === "approved"
    : filter === "held" ? kind === "held"
      : kind === "refused" || kind === "not_done");
}

export function amountWords(amount: ActivityCall["amount"]): string | undefined {
  if (!amount) return;
  const value = Number(amount.value);
  if (!amount.unit || !Number.isFinite(value) || Math.abs(value) > Number.MAX_SAFE_INTEGER / 100) return `${amount.value}${amount.unit ? ` ${amount.unit}` : ""}`;
  try {
    if (!Intl.supportedValuesOf("currency").includes(amount.unit)) return `${amount.value} ${amount.unit}`;
    return new Intl.NumberFormat("en-GB", { style: "currency", currency: amount.unit }).format(value);
  } catch { return `${amount.value} ${amount.unit}`; }
}

export function activityEntry(c: ActivityCall, zone: string, now: Date, taf?: string): ActivityEntry {
  const kind = kindOf(c);
  const connection = connectionRefusalWords(c.tool_outcome);
  // The app's own words for why, as KIFF recorded them, quoted as the app's: never read for whether
  // anything was done, which the outcome says (kiff/tafs#476, gap 10).
  const said = saidWhy(c.tool_outcome?.message);
  const details = { done: "Done, within its Card", approved: "Approved, then done", held: "Waiting for you",
    refused: c.reason ? `Not sent · ${c.reason}` : "Not sent", not_done: connection
      ? c.detail || connection : c.tool_outcome?.status === "refused_by_tool" ? (said ? `The app refused it: "${said}"` : "The app refused it and gave no reason") : "Nothing reached the app",
    sending: "Sent; waiting for the app's answer", unknown: "The app's answer wasn't recorded; check its records" };
  const raw = hash(c.proposal_id).slice(0, 32);
  const id = `${raw.slice(0, 8)}-${raw.slice(8, 12)}-5${raw.slice(13, 16)}-8${raw.slice(17, 20)}-${raw.slice(20)}`;
  const day = dayIn(c.created_at, zone);
  const dayTitle = day === dayIn(now.toISOString(), zone) ? "Today" : new Intl.DateTimeFormat("en-GB", { timeZone: zone, weekday: "long", day: "numeric", month: "short" }).format(new Date(c.created_at));
  const next = kind === "not_done" ? nextStep(c, taf) : undefined;
  return { id, at: c.created_at, kind, title: c.title || c.action_name, detail: details[kind], time: timeIn(c.created_at, zone, now), day, day_title: dayTitle,
    ...(amountWords(c.amount) ? { amount_text: amountWords(c.amount) } : {}), call: c, ...(next ? { next } : {}) };
}

/** Bounded source scanning: a cursor advances even through a page with no matching outcomes. */
export async function activityPage(input: { token: string; tenant: string; subject: string; taf: string; tafName?: string; agent: string; zone: string; now: Date; query: URLSearchParams; unitExceptions?: Held[]; unitExceptionsNotice?: string }, net: Fetch = fetch, base = KIFF_API): Promise<ActivityPage> {
  const filter = input.query.get("filter") ?? "all";
  if (!ACTIVITY_FILTERS.some((f) => f.id === filter)) throw new ActivityError(400, "filter is all, done, held or refused");
  const take = Number(input.query.get("limit") ?? 20);
  if (!Number.isInteger(take) || take < 1 || take > 50) throw new ActivityError(400, "limit is 1 to 50");
  const tools = (input.query.get("tools") ?? "").split(",").filter(Boolean);
  if (tools.length > 100 || tools.some((t) => !/^[A-Za-z0-9_.-]{1,128}$/.test(t))) throw new ActivityError(400, "invalid app actions");
  const from = input.query.get("from");
  if (from && !Number.isFinite(Date.parse(from))) throw new ActivityError(400, "from is an ISO date");
  const scope = hash([input.tenant, input.subject, input.taf, filter, tools, from]);
  const cursor = cursorOf(input.query.get("cursor"), scope);
  let unitExceptions = (input.unitExceptions ?? []).filter((e) => e.subject === input.agent && e.kind === "card_unit" && e.status === "held");
  let exceptionRows: CardUnitException[] = [], notice = input.unitExceptionsNotice;
  try { exceptionRows = cardUnitExceptions({ id: input.taf, name: input.tafName ?? "This taf", kiffAgent: input.agent }, unitExceptions, [], input.zone, input.now); }
  catch { unitExceptions = []; notice = CARD_UNIT_NOTICE; }
  const read = async (offset: number, limit: number) => {
    const q = new URLSearchParams({ actor: input.agent, limit: String(limit), offset: String(offset) });
    const res = await net(`${base}/v1/me/activity?${q}`, { headers: { authorization: `Bearer ${input.token}`, accept: "application/json" } });
    if (!res.ok) throw new KIFFError(res.status === 401 || res.status === 403 ? res.status : 502, "Activity could not be read; try again");
    return await res.json() as { calls: ActivityCall[]; shown: number; counts?: unknown };
  };
  let source = await read(0, cursor ? 1 : 100);
  if (!Array.isArray(source.calls) || !Number.isSafeInteger(source.shown) || source.shown < 0) throw new ActivityError(502, "Activity returned an unreadable page");
  const revision = hash([source.shown, source.counts, source.calls[0] ?? null]);
  if (cursor && cursor.revision !== revision) throw new ActivityError(409, "Activity changed; refresh to see the newest entries");
  let offset = cursor?.offset ?? 0;
  const entries: ActivityEntry[] = [];
  let more = offset < source.shown;
  for (let scan = 0; scan < 10 && more && entries.length < take; scan++) {
    if (cursor || scan > 0) source = await read(offset, 100);
    if (!Array.isArray(source.calls)) throw new ActivityError(502, "Activity returned an unreadable page");
    if (!source.calls.length) { more = false; break; }
    for (const call of source.calls) {
      offset++;
      if (call.actor_id === input.agent && (!tools.length || tools.includes(call.action_name)) && (!from || Date.parse(call.created_at) >= Date.parse(from)) && activityMatches(call, filter as ActivityFilter)
        && !collapsedCurrencyDecision(call, unitExceptions)) entries.push(activityEntry(call, input.zone, input.now, input.tafName));
      if (entries.length === take) break;
    }
    more = offset < source.shown && !(from && source.calls.some((c) => Date.parse(c.created_at) < Date.parse(from)) && entries.length < take);
  }
  const groups: ActivityPage["groups"] = [];
  for (const entry of entries) {
    let group = groups.find((g) => g.id === entry.day);
    if (!group) { group = { id: entry.day, title: entry.day === cursor?.day ? "" : entry.day_title, entries: [] }; groups.push(group); }
    group.entries.push(entry);
  }
  return { entries, groups, filters: ACTIVITY_FILTERS, filter: filter as ActivityFilter, revision, card_unit_exceptions: exceptionRows,
    ...(notice ? { card_unit_exceptions_notice: notice } : {}),
    ...(more ? { next_cursor: Buffer.from(JSON.stringify({ scope, offset, revision, day: entries.at(-1)?.day ?? cursor?.day })).toString("base64url") } : { end_words: "Nothing older in this view" }) };
}
