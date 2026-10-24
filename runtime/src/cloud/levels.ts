/**
 * How loudly each notification reaches the owner (kiff/tafs#210, part 5),
 * decided here as it is sent, so several tafs don't flood them:
 *
 *   interrupt  a hold waiting for the owner, and a halt: Time Sensitive, so it
 *              comes through a Focus.
 *   quiet      a task finished or a run that ended, and a request answered: no
 *              sound or banner (passive). The badge and the chat line update.
 *   later      anything not urgent while the owner is away: nothing is pushed.
 *              It waits in Coach's chat, gathered into one "While you were
 *              away" message, for the next time they open tafs.
 */
export type Level = "interrupt" | "quiet" | "later";

/** What a notification is about. */
export type Kind = "hold" | "halt" | "run_ended" | "tasks_requested" | "request_answered";

/**
 * A notification's level. `away`: the owner hasn't used tafs for a while.
 * A member's request for tasks and its answer go to one person, not to the
 * owner's Coach, so they are never kept for later.
 */
export function levelOf(kind: Kind, away: boolean): Level {
  if (kind === "hold" || kind === "halt") return "interrupt";
  if (kind === "tasks_requested" || kind === "request_answered") return "quiet";
  return away ? "later" : "quiet";
}

/** The aps fields a level adds to an alert. Later isn't pushed; were it, it would be quiet. */
export function apsFor(level: Level): Record<string, unknown> {
  return level === "interrupt"
    ? { sound: "default", "interruption-level": "time-sensitive" }
    : { "interruption-level": "passive" };
}

/** How long after tafs was last open the owner counts as away. */
export const AWAY_MINUTES = 30;

/** Held while the account's app was open in the last AWAY_MINUTES: the app's home refresh renews it. */
export const presenceKey = (tenant: string) => `present#${tenant}`;

/** The owner is around: renewed on each refresh of the app's home. */
export function presentUntil(now: Date): Date {
  return new Date(now.getTime() + AWAY_MINUTES * 60_000);
}
