/**
 * Coach's cards that wait on their person (docs/api.md, Coach waiting): a Coach chat paused on a card its
 * person answers (an approval, a sign-in, questions, a confirm), unanswered and not past its time. Home counts each
 * such chat once in `needs_you` and lists it in `coach_waiting`, so Home never says "Nothing needs you." while
 * Coach waits for them. Only the person who started the chat sees it: it opens their own chat.
 *
 * A setup's result card isn't one: a taf whose setup stopped partway is Home's `setting_up`, with its own way back.
 */
import type { Run } from "./agents.js";
import type { Lang } from "./words/index.js";
import { wordsIn } from "./words/index.js";

/** The cards a person answers in Coach's chat that count as waiting on them. */
const WAITS_ON_YOU = new Set(["approval", "sign_in", "questions", "confirm"]);
/** Of those, the ones that ask for an approval, said as such. */
const APPROVES = new Set(["approval", "sign_in"]);

export interface CoachWaiting {
  /** Coach's taf and the chat to open. */
  taf: string;
  session: string;
  run: string;
  /** When the chat paused for it. */
  since: string;
  words: { line: string; open: string };
}

/**
 * The chats of `coach` paused on a card `subject` answers, oldest first, one per chat. A card past its `expires` no
 * longer waits: kiff/agents ends it.
 */
export function coachWaitingOf(paused: Run[], coach: string, subject: string, now: Date, lang: Lang = "en"): CoachWaiting[] {
  const w = wordsIn(lang);
  const out = new Map<string, CoachWaiting>();
  for (const r of paused) {
    if (r.agent !== coach || r.status !== "PAUSED") continue;
    if (r.requester?.kind !== "member" || r.requester.id !== subject) continue;
    const open = (r.requirements ?? []).filter((q) => {
      if (q.kind !== "card" || q.answered) return false;
      const type = (q.input as { type?: unknown } | undefined)?.type;
      if (typeof type !== "string" || !WAITS_ON_YOU.has(type)) return false;
      const expires = Date.parse(q.expires);
      return Number.isNaN(expires) || expires > now.getTime();
    });
    if (!open.length || out.has(r.session)) continue;
    const approves = open.some((q) => APPROVES.has((q.input as { type?: string }).type ?? ""));
    out.set(r.session, {
      taf: coach, session: r.session, run: r.id, since: r.updated || r.created,
      words: { line: approves ? w.coach_waiting_approval : w.coach_waiting_answer, open: w.coach_waiting_open },
    });
  }
  return [...out.values()].sort((a, b) => a.since.localeCompare(b.since));
}
