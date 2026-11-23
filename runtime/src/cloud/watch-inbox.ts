/**
 * The watch's Inbox, read and dismissed, kept in step across the person's phones and watches (docs/watch-app.md,
 * docs/watch-wear.md, step iv). The Inbox's lines are kept on the watch; what tafs cloud keeps is only which of them the
 * person has read and which they dismissed, by the line's id: a KIFF held call's id, or a watch request's (`wa_…`).
 * Never a line's words, its taf or an amount.
 *
 * Each write is appended, never read and written back, so two marks sent at once (a Dismiss and then Clear read, through
 * the phone) both stay. A read folds the marks in order, newest last, and keeps the INBOX_IDS_MAX ids marked last.
 */

import type { CoachStore } from "./coach.js";

/** The ids kept per person: the ones marked last. */
export const INBOX_IDS_MAX = 500;
/** The most ids one write marks. */
export const INBOX_MARK_MAX = 100;
/** How long the marks are kept after the person's last one. */
export const INBOX_KEPT_MS = 30 * 24 * 3600_000;
/** Past this many writes kept, a write folds them into one. */
export const INBOX_FOLD_AT = 200;

const ID = /^[A-Za-z0-9_-]{1,100}$/;

export const inboxKey = (tenant: string, person: string) => `${tenant}#watch-inbox#${person}`;

/** One write: the ids it reads and the ids it dismisses. A dismissed line is read too. */
export interface InboxMarks {
  read: string[];
  dismissed: string[];
}

/** The marks as the route answers them: each id once, in the order they were last marked. */
export interface InboxState {
  read: string[];
  dismissed: string[];
}

/** A write's body, checked: `{read?: [id], dismissed?: [id]}`. A string says what's wrong. */
export function inboxMarks(body: unknown): InboxMarks | string {
  if (typeof body !== "object" || body === null || Array.isArray(body)) return "send {read?: [id], dismissed?: [id]}";
  const b = body as Record<string, unknown>;
  for (const k of Object.keys(b)) if (k !== "read" && k !== "dismissed") return `${k} isn't part of an Inbox mark`;
  const ids = (v: unknown, name: string): string[] | string => {
    if (v === undefined) return [];
    if (!Array.isArray(v)) return `${name} is a list of a line's ids`;
    for (const id of v) if (typeof id !== "string" || !ID.test(id)) return `${name} holds only a line's ids`;
    return [...new Set(v as string[])];
  };
  const read = ids(b.read, "read");
  if (typeof read === "string") return read;
  const dismissed = ids(b.dismissed, "dismissed");
  if (typeof dismissed === "string") return dismissed;
  if (read.length + dismissed.length === 0) return "mark at least one line";
  if (read.length + dismissed.length > INBOX_MARK_MAX) return `mark at most ${INBOX_MARK_MAX} lines at once`;
  return { read, dismissed };
}

/** The marks folded in order: the last INBOX_IDS_MAX ids marked, each read or dismissed. */
export function foldMarks(writes: readonly unknown[]): InboxState {
  // A Map keeps the order ids were set in, so an id marked again moves to the end.
  const seen = new Map<string, "read" | "dismissed">();
  const mark = (id: string, how: "read" | "dismissed") => {
    // A dismissed line stays dismissed: a read after it never brings it back.
    const was = seen.get(id);
    seen.delete(id);
    seen.set(id, was === "dismissed" ? "dismissed" : how);
  };
  for (const w of writes) {
    const m = w as Partial<InboxMarks> | null;
    for (const id of Array.isArray(m?.read) ? m.read : []) if (typeof id === "string" && ID.test(id)) mark(id, "read");
    for (const id of Array.isArray(m?.dismissed) ? m.dismissed : []) if (typeof id === "string" && ID.test(id)) mark(id, "dismissed");
  }
  const kept = [...seen].slice(-INBOX_IDS_MAX);
  return {
    read: kept.filter(([, how]) => how === "read").map(([id]) => id),
    dismissed: kept.filter(([, how]) => how === "dismissed").map(([id]) => id),
  };
}

/** The person's marks. */
export async function inboxState(store: CoachStore, tenant: string, person: string): Promise<InboxState> {
  const writes = await store.get(inboxKey(tenant, person));
  return foldMarks(Array.isArray(writes) ? writes : []);
}

/**
 * Adds a write, and answers the marks with it. Past INBOX_FOLD_AT writes they're folded into one, so the list stays
 * small; a write landing during that fold may be lost, which only shows a line unread again.
 */
export async function markInbox(store: CoachStore, tenant: string, person: string, marks: InboxMarks, now: Date): Promise<InboxState> {
  const key = inboxKey(tenant, person);
  const until = new Date(now.getTime() + INBOX_KEPT_MS);
  await store.append(key, marks, until);
  const writes = await store.get(key);
  const list = Array.isArray(writes) ? writes : [];
  const state = foldMarks(list);
  if (list.length > INBOX_FOLD_AT) {
    await store.remove(key);
    await store.append(key, state, until);
  }
  return state;
}
