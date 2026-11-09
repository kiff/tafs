/** English: the source of every line (docs/languages.md). Another language has these keys, each in its own words. */
export const en = {
  // A taf's work (work.ts): a request's line, the list's summary, and the firings it skipped.
  work_working: "Working on it",
  work_waiting_approval: "Waiting for your approval",
  work_waiting_answer: "Waiting for an answer",
  work_waiting_event: (event: string) => `Waiting for the app's ${event} event`,
  work_done_at: (time: string) => `Done at ${time}`,
  work_stopped: "Stopped",
  work_failed: "It failed",
  work_failed_because: (why: string) => `It failed: ${why}`,
  work_needs_you: "It needs you to look at it.",
  work_retry_at: (time: string, of: string) => `It stopped while it worked. Trying again at ${time}${of}.`,
  work_retry_soon: (of: string) => `It stopped while it worked. Trying again soon${of}.`,
  work_try_of: (n: number, of: number) => `, ${n} of ${of}`,
  work_replied_at: (time: string) => `Replied at ${time}`,
  work_reply_not_sent: (why: string) => `Reply not sent: ${why}`,
  work_provider_refused: "the provider didn't take it",
  work_skipped_busy: (time: string) => `Skipped the ${time} run: the last one was still working`,
  work_skipped_late: (time: string) => `Skipped the ${time} run: it came too late to run`,
  work_halted: "Halted · resume it from Halt",
  work_needs_you_count: (n: number, _form: Intl.LDMLPluralRule) => (n === 1 ? "1 request needs you" : `${n} requests need you`),
  work_paused: "Paused",
  work_from_chat: "Works from its chat",
  work_next_run: (time: string) => `Next run ${time}`,
  work_last_done: (time: string) => `Last done at ${time}`,
  work_nothing_yet: "Nothing yet",
  // A run kiff/agents stopped with a code, in tafs cloud's own words (work.ts runFailure).
  code_ai_unavailable: "Our AI provider isn't serving us right now, so this stopped and used none of your credits. It's on our side, not yours: try again a little later.",
};

export type Words = typeof en;
