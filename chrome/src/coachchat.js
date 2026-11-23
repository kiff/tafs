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
