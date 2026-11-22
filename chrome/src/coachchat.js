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
