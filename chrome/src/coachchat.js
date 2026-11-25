// Which Coach chat the side panel shows (docs/chrome.md, "Coach in the side panel"). The panel opens on a fresh conversation,
// as the phone's New conversation does, never on the account's whole old thread; the earlier ones are one tap away (Previous
// conversations). A fresh conversation has no chat yet: its first message starts one (`new_chat`), and the panel follows the
// chat tafs cloud says it went into.

/** Nothing chosen: a new conversation. */
export const FRESH = Object.freeze({ fresh: true, session: null });

/** One of the person's earlier chats, by its session. */
export function chosen(session) {
  return { fresh: false, session: String(session) };
}

/** What the panel sends with the owner's words: a new chat, or the chat on show. */
export function sendOptions(view) {
  return view.fresh ? { newChat: true } : { session: view.session };
}

/** The view after a send: a fresh conversation becomes the chat the answer names; a chosen chat stays. */
export function afterSend(view, answer) {
  if (!view.fresh) return view;
  const session = answer && typeof answer.session === "string" ? answer.session : "";
  return session ? chosen(session) : view;
}

/** A chat's title as the list shows it: tafs cloud's, else the panel's own word for one. */
export function chatTitle(chat, fallback) {
  const t = chat && typeof chat.title === "string" ? chat.title.trim() : "";
  return t || fallback;
}

/** The panel's read of a chat while it's open: often while Coach is at work or the owner just wrote, seldom otherwise. */
export const POLL_BUSY_MS = 4_000;
export const POLL_IDLE_MS = 30_000;
/** How long after the owner's message the panel keeps reading often, whether or not Coach is shown at work yet. */
export const BUSY_AFTER_SEND_MS = 60_000;

/**
 * How long until the panel reads the chat again, or null for never. A fresh conversation has no chat to read, so nothing
 * polls it; a chat read often while Coach is at work or a message was just sent, and every 30 s otherwise (to see an answer
 * from the phone). A panel nobody sees reads nothing (the caller stops it while hidden).
 */
export function pollDelay({ view, working = false, sentAt = 0, now = Date.now() }) {
  if (view.fresh) return null;
  return working || now - sentAt < BUSY_AFTER_SEND_MS ? POLL_BUSY_MS : POLL_IDLE_MS;
}

/**
 * The tafs the panel offers: each of this Chrome's live connections (planque's list, for display only) whose agent is one of
 * the person's tafs, once each, the one at work first. A connection whose taf the person can't see isn't offered.
 */
export function tafsHere(connections, tafs, workingName = null) {
  const byAgent = new Map((tafs || []).map((t) => [t.agent, t]));
  const out = [];
  for (const c of connections || []) {
    const t = byAgent.get(c.agent);
    if (!t) continue;
    const had = out.find((o) => o.id === t.id);
    // Each of the taf's connections here names a grant: a run carries its grant, so the panel knows whose run it is.
    if (had) {
      if (!had.grants.includes(c.grant)) had.grants.push(c.grant);
      continue;
    }
    out.push({ id: t.id, name: t.name || c.name, sites: c.sites, grants: [c.grant] });
  }
  if (workingName) out.sort((a, b) => (b.name === workingName) - (a.name === workingName));
  return out;
}

/**
 * Several tafs, several tabs (docs/chrome.md, "The panel follows the tab"): the taf a run is for, by the grant its steps came
 * under, among the tafs the panel offers (`tafsHere`). Null for a grant none of them holds: never a guess by the taf's name.
 */
export function tafOfRun(run, tafs) {
  if (!run || typeof run.grant !== "string" || !run.grant) return null;
  return (tafs || []).find((t) => Array.isArray(t.grants) && t.grants.includes(run.grant)) || null;
}

/**
 * The runs of taf [tafId] here whose turn is over, in tafs cloud's word: its chat, as the panel just read it, says it isn't
 * working, and it isn't waiting on the person's phone (a step held for their answer goes on in the same tab once they answer).
 * Only the taf's own runs, by their grant, never another's; the runner then ends each only if it has had no step for a while
 * (runner.endTurn). Empty while the chat says it works, or when it couldn't be read.
 */
export function turnOver(tafId, thread, runs, tafs) {
  if (!tafId || !thread || thread.waitsOnPhone) return [];
  // A turn that failed is over too, whatever the chat still says of its work (tafs cloud's `failed`, `turnFailed`).
  if (thread.working !== false && !turnFailed(thread)) return [];
  return (runs || []).filter((r) => r && !r.stopped && !r.paused && !r.hand && (tafOfRun(r, tafs) || {}).id === tafId).map((r) => r.id);
}

/** How long a message just sent stays on show at most, whatever the chat says: a pending copy never stays for good. */
export const PENDING_MAX_MS = 120_000;

/**
 * Whether a message just sent ({words, id, at}) is still to come in the chat as tafs cloud reads it: the panel shows it at once,
 * as the app does, with the working line under it, until the chat has the message by the id tafs cloud gave the send
 * (`message.id`), never by its words, so a message tafs cloud keeps in another form still clears, and the same words sent twice
 * clear each on their own. Before the send is answered there's no id yet: it stays. Past PENDING_MAX_MS it goes.
 */
export function stillPending(pending, messages, now = Date.now()) {
  if (!pending || !pending.words) return false;
  if (now - (pending.at || 0) > PENDING_MAX_MS) return false;
  if (!pending.id) return true;
  return !arrived(pending, messages);
}

/** Whether the chat has the message just sent, by the id tafs cloud gave it. */
function arrived(pending, messages) {
  return (messages || []).some((m) => m && m.id === pending.id);
}

/**
 * Whether a message just sent went past PENDING_MAX_MS without the chat ever having it: the panel then says so under the
 * chat, rather than taking its copy away with nothing said (a problem is always shown).
 */
export function lapsed(pending, messages, now = Date.now()) {
  if (!pending || !pending.words) return false;
  if (now - (pending.at || 0) <= PENDING_MAX_MS) return false;
  return !pending.id || !arrived(pending, messages);
}

/**
 * Whether the chat's latest run failed (tafs cloud's `failed`, coach.js `failedOf`): the turn is over, and nothing says the
 * taf works any more.
 */
export function turnFailed(thread) {
  return !!(thread && thread.failed);
}

/**
 * The codes of a failed turn the panel knows (kiff/agents'): a failed turn with another code still shows its words, and never
 * offers Retry, whatever it says.
 */
export const FAILED_CODES = Object.freeze(["ai_unavailable", "no_credits", "ai_consent_required", "timeout", "failed"]);

/**
 * The words Try again sends again for the chat's failed run [failed], or null for none: only when tafs cloud says so
 * (`retry: true`, set when the failed run did nothing with an effect), for a code the panel knows, and for the person's own
 * message that started that run, found in the chat by its id (`<run>.in`, as tafs cloud names a run's message); never a guess
 * at another message. They go as a new message.
 */
export function retryOf(failed, messages) {
  if (!failed || failed.retry !== true || !FAILED_CODES.includes(failed.code) || !failed.run) return null;
  const m = (messages || []).find((x) => x && x.id === failed.run + ".in" && x.from === "owner");
  return m && typeof m.text === "string" && m.text.trim() ? m.text : null;
}

/** What Try again is pressed under, once: the failed run. */
export function failedKey(failed) {
  return "run:" + failed.run;
}

const WHY_LINES = Object.freeze({ sensitive: "aside_sensitive", off: "aside_off_line", not_taf_site: "aside_not_taf_site" });

/**
 * The line under the person's message when the page in front didn't go with it (sw.js `notReading`), or null for none: no page
 * consent (with the button that opens it), a sensitive site, a site they switched off, or one off the taf's sites, each with
 * the site's name. Sent alone by their choice, or no page to read (Chrome's own pages, another window's tab): nothing.
 */
export function whyLine(why, host) {
  if (why === "consent") return { key: "page_consent_off", consent: true };
  const key = WHY_LINES[why];
  return key && host ? { key, host: String(host) } : null;
}

/**
 * Words typed for a taf stay that taf's (D2): switching the chat to another taf, by its tab, a chip or the picker, keeps
 * what was typed for the one before and brings back what was typed for the new one, or nothing. Never carried over: Send
 * goes to the taf on show, and an instruction for one taf must never reach another. Returns the drafts after and the text
 * the box now holds.
 */
export function switchDraft(drafts, from, to, typed) {
  const next = new Map(drafts || []);
  if (from) {
    if (typed) next.set(from, typed);
    else next.delete(from);
  }
  const text = (to && next.get(to)) || "";
  if (to) next.delete(to);
  return { drafts: next, text };
}

/** The live run whose tab [tabId] is: the tab it acts in first, then any tab it opened. Null for a tab of no live run. */
export function runOfTab(tabId, runs) {
  if (typeof tabId !== "number") return null;
  const live = (runs || []).filter((r) => r && !r.stopped);
  return live.find((r) => r.tab === tabId) || live.find((r) => Array.isArray(r.tabs) && r.tabs.includes(tabId)) || null;
}

/** The taf whose tab [tabId] is, or null: the panel then keeps the person's own pick. */
export function tafOfTab(tabId, runs, tafs) {
  return tafOfRun(runOfTab(tabId, runs), tafs);
}

/**
 * The panel's head: each taf at work in this Chrome, once, with the run to go to (the first live one) and whether one of its
 * runs waits on the person (a hand-over). A run whose taf the panel doesn't offer isn't listed.
 */
export function tafsAtWork(runs, tafs) {
  const out = [];
  for (const run of (runs || []).filter((r) => r && !r.stopped)) {
    const taf = tafOfRun(run, tafs);
    if (!taf) continue;
    const had = out.find((o) => o.id === taf.id);
    if (had) {
      had.waiting = had.waiting || !!run.hand;
      continue;
    }
    out.push({ id: taf.id, name: taf.name, run: run.id, waiting: !!run.hand });
  }
  return out;
}

/** An idle chat's next read, backing off from 60 s to 5 minutes, each wait up to a fifth shorter at random. */
export const IDLE_FIRST_MS = 60_000;
export const IDLE_MOST_MS = 300_000;
export function idleDelay(idleReads, jitter = Math.random()) {
  const step = Math.min(IDLE_MOST_MS, IDLE_FIRST_MS * 2 ** Math.max(0, idleReads));
  return Math.round(step * (1 - 0.2 * Math.min(1, Math.max(0, jitter))));
}

/**
 * The toolbar's badge, a signal that a taf is at work in this Chrome: "!" when a run waits on the person (a hand-over), "●"
 * while one works, nothing when none does. A mark, not a word, so it reads the same in every language.
 */
export function actionBadge(live) {
  const runs = (live || []).filter((r) => r && !r.stopped);
  if (runs.some((r) => r.hand)) return "!";
  return runs.length ? "\u25CF" : "";
}

/** The toolbar's tooltip: who is at work ("Rae is working", one name per taf, once each), or the extension's name when none is. */
export function actionTitle(live, say) {
  const names = [...new Set((live || []).filter((r) => r && !r.stopped).map((r) => r.taf || "taf"))];
  if (!names.length) return say("name") || "tafs";
  return names.map((n) => say("working", [n]) || n).join(" · ");
}
