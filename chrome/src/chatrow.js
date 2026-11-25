// What each row of a chat in the side panel shows, by the phones' rules (docs/chat.md): when each message was sent and a
// header where a day starts, a run's work folded into one line, Copy and Report under an answer, and Copy, Send again and
// Edit and send on the person's own message. Pure: the panel draws what these say, and decides nothing of its own.

/** The day a moment falls on, as "2026-11-25", in `timeZone` (the browser's own when none), or "" for a moment unread. */
export function dayKey(at, timeZone) {
  const d = new Date(at);
  if (!at || Number.isNaN(d.getTime())) return "";
  // en-CA writes a date as year-month-day, whatever the person's language: a key to compare, never shown.
  return new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" }).format(d);
}

/** The day before `key`, as a key: from the calendar, so a day of 23 or 25 hours is still one day. */
function dayBefore(key) {
  const [y, m, d] = key.split("-").map(Number);
  const before = new Date(Date.UTC(y, m - 1, d - 1));
  return before.toISOString().slice(0, 10);
}

/**
 * Whether message [i] of [messages] starts a day, so a header goes over it: the first one, or the first on a later day than
 * the one before it (the phones' `ChatTime.startsDay`). A message with no time never starts one.
 */
export function startsDay(messages, i, timeZone) {
  const key = dayKey(messages[i] && messages[i].at, timeZone);
  if (!key) return false;
  for (let j = i - 1; j >= 0; j--) {
    const before = dayKey(messages[j] && messages[j].at, timeZone);
    if (before) return before !== key;
  }
  return true;
}

/**
 * The time under a message, small: "14:05", or "2:05 PM" where the language reads 12 hours, in the panel's language
 * (`chrome.i18n.getUILanguage()`). A browser says nothing of the computer's 12- or 24-hour setting, so the language's own is
 * used. "" for a moment unread.
 */
export function timeLabel(at, lang, timeZone) {
  const d = new Date(at);
  if (!at || Number.isNaN(d.getTime())) return "";
  return new Intl.DateTimeFormat(lang || undefined, { timeZone, hour: "numeric", minute: "2-digit" }).format(d);
}

/**
 * The header where a day starts, as the phones say it: Today, Yesterday (the extension's words, `today` and `yesterday`),
 * else the date with its weekday in the language's own order ("Wed, Nov 18", "mer. 18 nov."), with the year when it isn't
 * this one.
 */
export function dayLabel(at, { now = Date.now(), lang, timeZone, today, yesterday } = {}) {
  const key = dayKey(at, timeZone);
  if (!key) return "";
  const todayKey = dayKey(now, timeZone);
  if (key === todayKey) return today;
  if (key === dayBefore(todayKey)) return yesterday;
  const sameYear = key.slice(0, 4) === todayKey.slice(0, 4);
  const options = { timeZone, weekday: "short", month: "short", day: "numeric", ...(sameYear ? {} : { year: "numeric" }) };
  return new Intl.DateTimeFormat(lang || undefined, options).format(new Date(at));
}

/** The plural forms the extension's words carry for a count, CLDR's: each key has all six, in every language. */
export const PLURAL_FORMS = Object.freeze(["zero", "one", "two", "few", "many", "other"]);

/** The key of [base]'s form for [n] in [lang] ("chat_work_tries_few"): the language's own plural rule, else "other". */
export function pluralKey(base, n, lang) {
  let form = "other";
  try {
    form = new Intl.PluralRules(lang || undefined).select(n);
  } catch {
    // A language Intl doesn't know: its "other" form.
  }
  return base + "_" + (PLURAL_FORMS.includes(form) ? form : "other");
}

/**
 * How long a run worked, as the phones write it: "4.2 s" under 10 s (Android's `Chat.workLine`), "12 s" under a minute, then
 * "1 min 5 s" (iOS's `WorkLog.duration`). [say] is the extension's words (`chat_duration_*`), the number in the language.
 */
export function duration(ms, lang, say) {
  const s = Math.max(0, Number(ms) || 0) / 1000;
  const num = (n, digits = 0) => new Intl.NumberFormat(lang || undefined, { minimumFractionDigits: digits, maximumFractionDigits: digits }).format(n);
  if (s < 10) return say("chat_duration_seconds", num(Math.max(0.1, Math.round(s * 10) / 10), 1));
  const whole = Math.round(s);
  if (whole < 60) return say("chat_duration_seconds", num(whole));
  return say("chat_duration_minutes", num(Math.floor(whole / 60)), num(whole % 60));
}

/**
 * A message's work as tafs cloud sends it (`work: {ms, steps: [{tool, words?, error?, failed?, why?, not_made?}]}`, docs/chat.md
 * "The work block"), kept to what the panel shows: how long, and each step's words, whether it failed and why, or that it
 * wasn't made. A step's tool is never kept: the panel never names a step by its tool. Null for none.
 */
export function workOf(raw) {
  if (!raw || typeof raw !== "object" || !Number.isFinite(raw.ms) || !Array.isArray(raw.steps)) return null;
  const text = (v, max) => (typeof v === "string" && v.trim() ? v.trim().slice(0, max) : null);
  const steps = raw.steps.filter((s) => s && typeof s === "object").slice(0, 200).map((s) => ({
    words: text(s.words, 200),
    failed: s.error === true || s.failed === true,
    why: text(s.why, 200),
    notMade: s.not_made === true,
  }));
  return { ms: Math.max(0, raw.ms), steps };
}

/**
 * The folded line of a message's work, as Android writes it (`Chat.workLine`): "Worked 4.2 s · 3 steps, 1 failed", "Worked
 * 12 s · 1 step", or "Worked 3 s" with no step. Every step counts, those without words too.
 */
export function workLine(work, lang, say) {
  const took = duration(work.ms, lang, say);
  const n = work.steps.length;
  if (!n) return say("chat_worked", took);
  const failed = work.steps.filter((s) => s.failed).length;
  const count = new Intl.NumberFormat(lang || undefined).format(n);
  if (failed) return say(pluralKey("chat_worked_steps_failed", n, lang), took, count, new Intl.NumberFormat(lang || undefined).format(failed));
  return say(pluralKey("chat_worked_steps", n, lang), took, count);
}

/**
 * The steps under the opened line (iOS's `WorkLog.rows`, over Android's `Chat.steps`): only those tafs cloud has words for,
 * in order; tries of the same step after a failed or unmade one are one row with the last try's outcome and how many tries;
 * a failed row is left out once something worked, and when nothing did the failures say why. `{words, status: "done" |
 * "failed" | "not_made", tries, why?}`. A step's words lose the "…" they have while it runs.
 */
export function workRows(work) {
  const groups = [];
  for (const s of work.steps) {
    if (!s.words) continue;
    const status = s.notMade ? "not_made" : s.failed ? "failed" : "done";
    const words = s.words.endsWith("…") ? s.words.slice(0, -1) : s.words;
    const last = groups.at(-1);
    if (last && last.words === words && last.status !== "done") {
      last.status = status;
      last.tries += 1;
      last.why = s.why;
      continue;
    }
    groups.push({ words, status, tries: 1, why: s.why });
  }
  const rows = groups.map(({ words, status, tries, why }) => ({ words, status, tries, ...(status === "failed" && why ? { why } : {}) }));
  return rows.some((r) => r.status === "done") ? rows.filter((r) => r.status !== "failed") : rows;
}

/** The reasons tafs cloud takes for a report (`reportInput`), in the order the phones offer them. */
export const REPORT_REASONS = Object.freeze(["offensive", "harmful", "wrong", "other"]);

/** The longest note tafs cloud takes with a report. */
export const REPORT_NOTE_MAX = 1000;

/**
 * Whether the panel offers Report this answer on [m] (the phones' `canReport`): an answer from Coach or a taf, with words,
 * that tafs cloud has an id for, in a chat the panel can name. tafs cloud takes a report from the panel only with the chat's
 * `session`, so with none, nothing is offered.
 */
export function canReport(m, session) {
  return !!(m && m.from === "taf" && (m.kind || "message") === "message" && typeof m.id === "string" && m.id && typeof m.text === "string" && m.text.trim() && session);
}

/**
 * The body of a report (`POST …/messages/{message}/report`): a reason tafs cloud takes, the note when there is one (trimmed,
 * at most REPORT_NOTE_MAX), and the chat's session, always. Null when it can't go: no reason, a note too long, no chat.
 */
export function reportBody(reason, note, session) {
  if (!REPORT_REASONS.includes(reason) || typeof session !== "string" || !session) return null;
  const n = String(note || "").trim();
  if (n.length > REPORT_NOTE_MAX) return null;
  return { reason, ...(n ? { note: n } : {}), session };
}

/**
 * How a report went, from the worker's answer, as the phones say it: `{state: "sent"}` (Thanks), `{state: "already"}` (tafs
 * cloud answers `new: false`), or `{state: "failed", words?, key?}`: tafs cloud's own words when it sent them, else "This
 * answer isn't on tafs cloud any more" for a 404, else "It didn't reach KIFF. Try again", the phones' own lines.
 */
export function reportOutcome(r) {
  if (r && r.report && typeof r.report === "object") return { state: r.report.new === false ? "already" : "sent" };
  const f = (r && r.failed) || {};
  if (typeof f.words === "string" && f.words.trim()) return { state: "failed", words: f.words.trim() };
  if (f.status === 404) return { state: "failed", key: "report_answer_gone" };
  return { state: "failed", key: "report_not_reached" };
}

/**
 * What the person's own message offers (the phones' message menu): Copy whenever it has words; Send again and Edit and send
 * only in a chat begun in this Chrome ([began]), where a message from here goes. A chat shown only for a run that worked
 * here is read-only to the panel (tafs cloud answers 404 to a message into it): Copy alone.
 */
export function ownerActions(m, began) {
  const words = !!(m && m.from === "owner" && (m.kind || "message") === "message" && typeof m.text === "string" && m.text.trim());
  return { copy: words, again: words && began === true, edit: words && began === true };
}

/** The most Coach chats the worker remembers as begun in this Chrome: past it, the oldest go first. */
export const BEGAN_MAX = 200;

/**
 * The Coach chats begun in this Chrome, with [session] added (newest last, each once, at most BEGAN_MAX). tafs cloud takes a
 * message from the panel only into a chat this Chrome began, so a send it took names one.
 */
export function withBegan(list, session) {
  if (typeof session !== "string" || !session) return Array.isArray(list) ? list : [];
  const kept = (Array.isArray(list) ? list : []).filter((s) => typeof s === "string" && s && s !== session);
  return [...kept, session].slice(-BEGAN_MAX);
}

/** How long a run has had no step before its turn being over ends it here: the runner's own `TURN_QUIET_MS`. */
export const TURN_QUIET_MS = 10_000;

/**
 * Whether a run's card still shows under the chat: always while it works, pauses or waits on the person; not once tafs cloud
 * said its turn was over ([over]: run id → how many steps it had then, from `turnOver`), it has had no step since, and none
 * for TURN_QUIET_MS (the runner's own rule for a turn being over, so a run that still steps, as one from the phone's message
 * may while the chat here is idle, keeps its card). Its work is then the answer's folded line.
 */
export function cardShown(run, over, now = Date.now()) {
  if (!run || run.paused || run.hand || !over || !over.has(run.id)) return true;
  const steps = run.steps || [];
  if (over.get(run.id) !== steps.length) return true;
  const last = steps.length ? Number(steps[steps.length - 1].at) || 0 : 0;
  return now - last < TURN_QUIET_MS;
}
