/**
 * Coach's cards that wait on their person (docs/api.md, Coach waiting): a Coach chat paused on a card its
 * person answers (an approval, a sign-in, questions, a confirm), unanswered and not past its time. Home counts each
 * such chat once in `needs_you` and lists it in `coach_waiting`, so Home never says "Nothing needs you." while
 * Coach waits for them. Only the person who started the chat sees it: it opens their own chat.
 *
 * A setup's result card isn't one: a taf whose setup stopped partway is Home's `setting_up`, with its own way back.
 *
 * The person can set one aside, or all of them: a "not now" tafs cloud answers, never an approval. Nothing is signed or
 * sent, the chat's run ends, and a connection it asked for a taf that doesn't exist yet is withdrawn (handler.ts).
 */
import type { Run } from "./agents.js";
import type { Lang } from "./words/index.js";
import { endSentence, wordsIn } from "./words/index.js";

/** The cards a person answers in Coach's chat that count as waiting on them. */
const WAITS_ON_YOU = new Set(["approval", "sign_in", "questions", "confirm"]);
/** Of those, the ones that ask for an approval, said as such. */
const APPROVES = new Set(["approval", "sign_in"]);

/** A Coach chat that has waited on its person this long ends on its own (sweepStaleCoach), as if set aside. */
export const COACH_WAITING_KEPT_MS = 48 * 3600_000;

export interface CoachWaiting {
  /** Coach's taf and the chat to open. */
  taf: string;
  session: string;
  run: string;
  /** When the chat paused for it. */
  since: string;
  /** The line names the chat, what it waits for and since when; `set_aside` ends it, as "not now". */
  words: { line: string; open: string; set_aside: string };
}

/** The cards of a run that wait on its person now, with the connection or draft each names. */
export function waitingCards(r: Run, now: Date): Array<{ id: string; type: string; grant?: string; draft?: string }> {
  return (r.requirements ?? []).flatMap((q) => {
    if (q.kind !== "card" || q.answered) return [];
    const card = (q.input ?? {}) as { id?: unknown; type?: unknown; grant?: unknown; draft?: unknown };
    if (typeof card.type !== "string" || !WAITS_ON_YOU.has(card.type)) return [];
    const expires = Date.parse(q.expires);
    if (!Number.isNaN(expires) && expires <= now.getTime()) return [];
    return [{ id: typeof card.id === "string" ? card.id : q.call_id, type: card.type,
      ...(typeof card.grant === "string" && card.grant ? { grant: card.grant } : {}),
      ...(typeof card.draft === "string" && card.draft ? { draft: card.draft } : {}) }];
  });
}

/** The person's Coach runs that wait on them, one per chat. */
export function waitingRuns(paused: Run[], coach: string, subject: string, now: Date): Run[] {
  const out = new Map<string, Run>();
  for (const r of paused) {
    if (r.agent !== coach || r.status !== "PAUSED") continue;
    if (r.requester?.kind !== "member" || r.requester.id !== subject) continue;
    if (!waitingCards(r, now).length || out.has(r.session)) continue;
    out.set(r.session, r);
  }
  return [...out.values()];
}

/**
 * The chats of `coach` paused on a card `subject` answers, oldest first, one per chat. A card past its `expires` no
 * longer waits: kiff/agents ends it. `titles` names each chat as Coach's history does, by session; a chat with no title
 * says what it waits for alone.
 */
export function coachWaitingOf(paused: Run[], coach: string, subject: string, now: Date, lang: Lang = "en", zone = "UTC",
  titles: Map<string, string> = new Map()): CoachWaiting[] {
  const w = wordsIn(lang);
  return waitingRuns(paused, coach, subject, now).map((r) => {
    const approves = waitingCards(r, now).some((c) => APPROVES.has(c.type));
    const since = r.updated || r.created;
    const title = titles.get(r.session)?.trim();
    const day = new Intl.DateTimeFormat(lang === "en" ? "en-GB" : lang, { timeZone: zone, day: "numeric", month: "short" }).format(new Date(since));
    const line = title
      ? endSentence(approves ? w.coach_waiting_named_approval(title, day) : w.coach_waiting_named_answer(title, day))
      : (approves ? w.coach_waiting_approval : w.coach_waiting_answer);
    return { taf: coach, session: r.session, run: r.id, since, words: { line, open: w.coach_waiting_open, set_aside: w.coach_waiting_set_aside } };
  }).sort((a, b) => a.since.localeCompare(b.since));
}

/** Home's "Set all aside", naming the count it ends, with the one ask: only for two or more. */
export function coachWaitingClear(rows: CoachWaiting[], lang: Lang = "en"): { words: { button: string; title: string; text: string; yes: string; no: string } } | undefined {
  if (rows.length < 2) return undefined;
  const w = wordsIn(lang);
  return { words: { button: w.coach_waiting_clear, title: w.coach_waiting_clear_title(rows.length), text: w.coach_waiting_clear_text,
    yes: w.coach_waiting_clear, no: w.coach_waiting_clear_no } };
}
