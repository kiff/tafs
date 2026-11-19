/**
 * Your brief (docs/missions.md, design v2.1 §7): what replaces Needs you, twice a day, plus an alert at once for what's
 * truly urgent. This module is the builder's logic, with no reads of its own: the route gives it the decisions (held
 * calls, steps, connections, proposals), the cases done, the labels and the person's time zone, and it answers the
 * shape the apps draw (`GET /v1/brief`).
 *
 * What's urgent is decided here, by tafs cloud's rules, never by a model: a deadline before the next brief, money going
 * out today, a halt, a blocked connection, or "ask me at once". A recommendation is shown, never acted on: the brief
 * only lists, and every answer goes through the place it already goes (a held call's signed answer, a step, a
 * connection, Coach's signed change sheet).
 */

/** The brief's times in the account's time zone (decision 1: 08:00 and 18:00, changeable in Account). */
export const BRIEF_SLOTS = ["08:00", "18:00"] as const;

/** At most this many decisions are listed; the rest are counted. */
export const BRIEF_DECISIONS = 5;

/** A decision as the route gathers it, before the brief decides what's urgent and what can wait. */
export interface BriefDecisionIn {
  /** Stable across briefs, so "Later" can name it: `${ref.kind}:${ref.id}`. */
  id: string;
  ref: { kind: "hold" | "step" | "connection" | "check" | "proposal"; id: string; taf: string };
  /** The taf it's for, with its labels (the brief's filter). */
  taf: { id: string; name: string; labels?: string[] };
  question: string;
  answers: Array<{ words: string; answer: string; recommended?: boolean }>;
  /** Why the recommended answer is recommended; without it, the answers show as equals. */
  why?: string;
  /** What happens while the person hasn't answered. */
  meanwhile: string;
  /** When it stops waiting (a held call's expiry, a step's end), ISO. */
  deadline?: string;
  /** When it was raised, ISO: the oldest first, after the urgent ones. */
  since?: string;
  /** Money goes out today if it's answered yes (a payment or refund due today). */
  money_today?: boolean;
  /** A halt waits on it. */
  halt?: boolean;
  /** A connection the taf needs is blocked (signed out, refused). */
  blocked?: boolean;
  /** The person asked to hear of this at once. */
  ask_at_once?: boolean;
  /** False when it can't be put off (the card's line then says why): `laterWhy`. */
  can_wait?: boolean;
  laterWhy?: string;
}

/** A decision as the apps draw it (§7.5, 1). */
export interface BriefDecision {
  id: string;
  question: string;
  answers: Array<{ words: string; answer: string; recommended: boolean }>;
  why?: string;
  meanwhile: string;
  deadline?: string;
  urgent: boolean;
  later?: { words: string };
  /** Why it can't be put off, in place of Later. */
  now_why?: string;
  ref: { kind: string; id: string; taf: string };
}

/** A case done or in hand (§7, "what finishes each case"), as the route reads it from the record and the taf's reads. */
export interface BriefCaseIn {
  taf: { id: string; name: string; labels?: string[] };
  mission?: { id: string; name: string };
  words: string;
  state: "done" | "waiting" | "closed" | "unread";
  next_check?: string;
  value?: string;
  log?: string;
}

/** The words the brief's own lines use, in the person's language (from words/*.ts). */
export interface BriefWords {
  decisions: string;
  done: string;
  rest: string;
  later: string;
  more: (n: number) => string;
  restLine: (n: number) => string;
  /** The line for Coach's chats that wait on the person ("17 Coach chats wait for your answer."). */
  coachLine?: (n: number) => string;
  /** The day's credits line, when the balance could be read. */
  credits?: string;
}

/** The brief's two times around `now`: the last one passed (`at`) and the next (`next_at`), as instants. */
export function briefTimes(now: Date, timeZone: string, slots: readonly string[] = BRIEF_SLOTS): { at: Date; next_at: Date } {
  if (!slots.length || slots.some((s) => !/^([01]\d|2[0-3]):[0-5]\d$/.test(s))) throw new Error("a brief needs at least one time, as HH:MM");
  const zone = validZone(timeZone);
  const day = localDate(now, zone);
  const candidates: Date[] = [];
  for (const offset of [-1, 0, 1]) {
    const d = addDays(day, offset);
    for (const s of slots) candidates.push(instantAt(d, s, zone));
  }
  candidates.sort((a, b) => a.getTime() - b.getTime());
  const past = candidates.filter((c) => c.getTime() <= now.getTime());
  const future = candidates.filter((c) => c.getTime() > now.getTime());
  return { at: past[past.length - 1]!, next_at: future[0]! };
}

/** tafs cloud's urgency rules (§7): never the model's. A deadline that can't be read counts as before the next brief. */
export function isUrgent(d: BriefDecisionIn, nextAt: Date): boolean {
  const at = d.deadline ? Date.parse(d.deadline) : NaN;
  const before = !!d.deadline && (Number.isNaN(at) || at < nextAt.getTime());
  return before || !!d.money_today || !!d.halt || !!d.blocked || !!d.ask_at_once;
}

/** The decisions put off with Later: each until the brief it moved to. */
export type LaterSet = ReadonlyMap<string, string>;

/**
 * The brief's decisions: the urgent ones first, then by deadline, then the oldest. One put off with Later stays out
 * until its brief comes, unless it has turned urgent. At most one answer is recommended, and none without a why.
 */
export function briefDecisions(all: readonly BriefDecisionIn[], now: Date, nextAt: Date, later: LaterSet, w: Pick<BriefWords, "later">): BriefDecision[] {
  return all
    .filter((d) => {
      const until = later.get(d.id);
      return !until || Date.parse(until) <= now.getTime() || isUrgent(d, nextAt);
    })
    .map((d) => ({ d, urgent: isUrgent(d, nextAt) }))
    .sort((a, b) => Number(b.urgent) - Number(a.urgent) || cmp(a.d.deadline, b.d.deadline) || cmp(a.d.since, b.d.since))
    .map(({ d, urgent }) => {
      let recommended = d.why ? d.answers.findIndex((a) => a.recommended) : -1;
      if (recommended >= 0 && d.answers.filter((a) => a.recommended).length > 1) recommended = -1;
      const canWait = !urgent && d.can_wait !== false;
      return {
        id: d.id,
        question: d.question,
        answers: d.answers.map((a, i) => ({ words: a.words, answer: a.answer, recommended: i === recommended })),
        ...(d.why && recommended >= 0 ? { why: d.why } : {}),
        meanwhile: d.meanwhile,
        ...(d.deadline ? { deadline: d.deadline } : {}),
        urgent,
        ...(canWait ? { later: { words: w.later } } : d.laterWhy ? { now_why: d.laterWhy } : {}),
        ref: { kind: d.ref.kind, id: d.ref.id, taf: d.ref.taf },
      };
    });
}

/** The brief as the apps draw it (§7.5, 5), for one label or all. */
export interface Brief {
  at: string;
  next_at: string;
  decisions: BriefDecision[];
  more?: { count: number; words: string };
  done: Array<{ mission?: { id: string; name: string }; cases: Array<{ words: string; state: string; next_check?: string }>; value?: string; log?: string }>;
  rest: { count: number; words: string };
  /**
   * Coach's chats that wait on this person (coachwaiting.ts), counted as Needs you counts them: so every number the person
   * sees leads to its items. Only when there are some, and only in the whole brief; it opens Home's Coach waiting list.
   */
  coach_waiting?: { count: number; words: string };
  credits?: { words: string };
  labels: Array<{ id: string; name: string; color: string; count: number; waiting: number }>;
  words: { decisions: string; done: string; rest: string };
}

/**
 * Builds the brief. `label` narrows decisions and cases to its tafs; each label's `count` and `waiting` are of the whole
 * account, before the label filter, so the other labels' dots still say where to look. A decision put off with Later
 * still counts as waiting: it's put off, not answered. Every urgent decision is shown; only the others are capped.
 */
export function buildBrief(input: {
  now: Date;
  timeZone: string;
  decisions: readonly BriefDecisionIn[];
  cases: readonly BriefCaseIn[];
  later: LaterSet;
  labels: ReadonlyArray<{ id: string; name: string; color: string }>;
  tafs: ReadonlyArray<{ id: string; labels?: string[] }>;
  rest: number;
  /** How many of Coach's chats wait on this person: Needs you's `coach` part. */
  coachWaiting?: number;
  label?: string;
  slots?: readonly string[];
  words: BriefWords;
}): Brief {
  const { at, next_at } = briefTimes(input.now, input.timeZone, input.slots);
  const inLabel = (t: { labels?: string[] }) => !input.label || (t.labels ?? []).includes(input.label);
  const all = briefDecisions(input.decisions.filter((d) => inLabel(d.taf)), input.now, next_at, input.later, input.words);
  const urgent = all.filter((d) => d.urgent);
  const shown = [...urgent, ...all.filter((d) => !d.urgent).slice(0, Math.max(0, BRIEF_DECISIONS - urgent.length))];
  const more = all.length - shown.length;
  const groups = new Map<string, Brief["done"][number]>();
  for (const c of input.cases.filter((x) => inLabel(x.taf))) {
    const key = c.mission?.id ?? `taf:${c.taf.id}`;
    const g = groups.get(key) ?? { ...(c.mission ? { mission: c.mission } : {}), cases: [], ...(c.log ? { log: c.log } : {}) };
    g.cases.push({ words: c.words, state: c.state, ...(c.next_check ? { next_check: c.next_check } : {}) });
    if (c.value && !g.value) g.value = c.value;
    groups.set(key, g);
  }
  const waitingByTaf = new Map<string, number>();
  for (const d of input.decisions) waitingByTaf.set(d.taf.id, (waitingByTaf.get(d.taf.id) ?? 0) + 1);
  const labels = input.labels.map((l) => {
    const tafs = input.tafs.filter((t) => (t.labels ?? []).includes(l.id));
    return { ...l, count: tafs.length, waiting: tafs.reduce((n, t) => n + (waitingByTaf.get(t.id) ?? 0), 0) };
  }).filter((l) => l.count > 0);
  return {
    at: at.toISOString(),
    next_at: next_at.toISOString(),
    decisions: shown,
    ...(more > 0 ? { more: { count: more, words: input.words.more(more) } } : {}),
    done: [...groups.values()],
    rest: { count: input.rest, words: input.words.restLine(input.rest) },
    ...(!input.label && input.coachWaiting && input.coachWaiting > 0 && input.words.coachLine
      ? { coach_waiting: { count: input.coachWaiting, words: input.words.coachLine(input.coachWaiting) } } : {}),
    ...(input.words.credits ? { credits: { words: input.words.credits } } : {}),
    labels,
    words: { decisions: input.words.decisions, done: input.words.done, rest: input.words.rest },
  };
}

/** The words a decision made from tafs cloud's own reads is said in. */
export interface DecisionWords {
  approve: string;
  refuse: string;
  holdQuestion: (taf: string) => string;
  holdMeanwhile: string;
  stepMeanwhile: string;
  connectionQuestion: (vendor: string) => string;
  signInAgain: string;
  connectionMeanwhile: (taf: string, vendor: string) => string;
  openTaf: string;
  checkMeanwhile: (taf: string) => string;
}

/**
 * A call KIFF holds for the person's approval, as a decision (§7.5, 2: ref kind hold). Its answers are Approve and Refuse,
 * as equals: KIFF gives no why, and nothing here makes one up. A held call with no id can't be opened, so it's left out,
 * and so is a Card-unit exception, which isn't an approval.
 */
export function holdDecision(h: { id?: string; kind?: string; title?: string; created_at?: string }, taf: BriefDecisionIn["taf"], w: DecisionWords): BriefDecisionIn | undefined {
  if (!h.id || (h.kind ?? "hold") !== "hold") return undefined;
  return {
    id: `hold:${h.id}`, ref: { kind: "hold", id: h.id, taf: taf.id }, taf,
    question: h.title?.trim() || w.holdQuestion(taf.name),
    answers: [{ words: w.approve, answer: "approve" }, { words: w.refuse, answer: "refuse" }],
    meanwhile: w.holdMeanwhile,
    ...(h.created_at ? { since: h.created_at } : {}),
  };
}

/**
 * A step a taf handed its owner, as a decision (§7.5, 2: ref kind step): its own words and buttons, with KIFF's expiry
 * as its deadline, so one that ends before the next brief is urgent.
 */
export function stepDecision(s: { handoff: string; what: string; since: string; expires: string; words: { done: string; skip: string } }, taf: BriefDecisionIn["taf"], w: DecisionWords): BriefDecisionIn {
  return {
    id: `step:${s.handoff}`, ref: { kind: "step", id: s.handoff, taf: taf.id }, taf,
    question: s.what,
    answers: [{ words: s.words.done, answer: "done" }, { words: s.words.skip, answer: "skip" }],
    meanwhile: w.stepMeanwhile,
    deadline: s.expires, since: s.since,
  };
}

/**
 * A taf's connection that needs its owner to sign in again, as a decision (§7.5, 2: ref kind connection). It blocks the
 * taf's work with that app, so it's urgent (tafs cloud's rule: a blocked connection). One per taf and app; any other
 * status isn't a decision.
 */
export function connectionDecision(c: { vendor: string; status: string }, taf: BriefDecisionIn["taf"], w: DecisionWords): BriefDecisionIn | undefined {
  if (c.status !== "needs_reconnect" || !c.vendor) return undefined;
  return {
    id: `connection:${taf.id}:${c.vendor}`, ref: { kind: "connection", id: c.vendor, taf: taf.id }, taf,
    question: w.connectionQuestion(c.vendor),
    answers: [{ words: w.signInAgain, answer: "sign_in" }],
    meanwhile: w.connectionMeanwhile(taf.name, c.vendor),
    blocked: true,
  };
}

/**
 * A taf whose scheduled check couldn't read its app at its last firings in a row, as a decision (ref kind check): it finds
 * no new work until a check reads again, so it's blocked, and urgent, as a blocked connection is. `line` is its health's
 * line (health.ts healthLine: how many times, since when, and the app by its connection's name). Its one answer opens the
 * taf, where the owner sees why and fixes it (its connection, or its check with Coach): nothing is answered from the brief.
 * One per taf, raised at the first of those failures; it leaves the brief at the first check that reads again.
 */
export function checkDecision(taf: BriefDecisionIn["taf"], line: string, since: string | undefined, w: DecisionWords): BriefDecisionIn {
  return {
    id: `check:${taf.id}`, ref: { kind: "check", id: taf.id, taf: taf.id }, taf,
    question: line,
    answers: [{ words: w.openTaf, answer: "open" }],
    meanwhile: w.checkMeanwhile(taf.name),
    ...(since ? { since } : {}),
    blocked: true,
  };
}

/**
 * Where the decisions put off with Later are kept: one record per person, {decision id: until}. It's their brief: one
 * person's Later never moves a decision out of anyone else's, so a member who can't answer a held call can't hide it
 * from the person who can.
 */
export const briefLaterKey = (tenant: string, person: string) => `${tenant}#brief-later#${person}`;

/** The decisions still put off at `now`, from what was kept: anything else (a past time, a value that isn't one) is dropped. */
export function laterOf(kept: unknown, now: Date): Map<string, string> {
  const out = new Map<string, string>();
  if (!kept || typeof kept !== "object" || Array.isArray(kept)) return out;
  for (const [id, until] of Object.entries(kept as Record<string, unknown>)) {
    if (typeof until === "string" && Date.parse(until) > now.getTime()) out.set(id, until);
  }
  return out;
}

/** Later (decision 2): the decision moves to the next brief. Refused for one that's urgent or can't wait. */
export function putOff(d: BriefDecisionIn, now: Date, timeZone: string, slots: readonly string[] = BRIEF_SLOTS): { until: string } | { refused: string } {
  const { next_at } = briefTimes(now, timeZone, slots);
  if (isUrgent(d, next_at)) return { refused: "urgent" };
  if (d.can_wait === false) return { refused: "cant_wait" };
  return { until: next_at.toISOString() };
}

function cmp(a?: string, b?: string): number {
  if (a === b) return 0;
  if (!a) return 1;
  if (!b) return -1;
  return a < b ? -1 : 1;
}

function validZone(tz: string): string {
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: tz });
    return tz;
  } catch {
    return "UTC";
  }
}

/** The calendar date at `now` in `zone`. */
function localDate(now: Date, zone: string): { y: number; m: number; d: number } {
  const p = parts(now, zone);
  return { y: p.year, m: p.month, d: p.day };
}

function addDays(day: { y: number; m: number; d: number }, n: number) {
  const t = new Date(Date.UTC(day.y, day.m - 1, day.d + n));
  return { y: t.getUTCFullYear(), m: t.getUTCMonth() + 1, d: t.getUTCDate() };
}

/** The instant a wall-clock time ("08:00") falls on, that day, in `zone`. */
function instantAt(day: { y: number; m: number; d: number }, hhmm: string, zone: string): Date {
  const [h, mi] = hhmm.split(":").map(Number) as [number, number];
  const guess = Date.UTC(day.y, day.m - 1, day.d, h, mi);
  // Two passes of the zone's offset settle a time around a DST change.
  let t = guess;
  for (let i = 0; i < 2; i++) {
    const p = parts(new Date(t), zone);
    const asUTC = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute);
    t += guess - asUTC;
  }
  return new Date(t);
}

function parts(at: Date, zone: string): { year: number; month: number; day: number; hour: number; minute: number } {
  const f = new Intl.DateTimeFormat("en-US", { timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
  const o: Record<string, number> = {};
  for (const p of f.formatToParts(at)) if (p.type !== "literal") o[p.type] = Number(p.value);
  return { year: o.year!, month: o.month!, day: o.day!, hour: o.hour!, minute: o.minute! };
}

/** Where the zone of the account's last brief read is kept, for the brief's push (the sweep has no person's session). */
export const briefZoneKey = (tenant: string) => `${tenant}#brief-zone`;
/** How long after a brief's time its push may still go: the sweep runs every 5 minutes. */
export const BRIEF_PUSH_WINDOW_MS = 10 * 60_000;

/** The brief whose time has just come at `now` in `timeZone` (within `windowMs`), else undefined. */
export function briefDue(now: Date, timeZone: string, windowMs = BRIEF_PUSH_WINDOW_MS, slots: readonly string[] = BRIEF_SLOTS): { at: Date; next_at: Date; hm: string } | undefined {
  const t = briefTimes(now, timeZone, slots);
  if (now.getTime() - t.at.getTime() >= windowMs) return undefined;
  const p = parts(t.at, validZone(timeZone));
  return { ...t, hm: `${String(p.hour).padStart(2, "0")}:${String(p.minute).padStart(2, "0")}` };
}
