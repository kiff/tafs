// A step handed to the person (docs/chrome-panel.md, "A step handed to the person"): the taf needs one thing done in the page, by the
// person, and says so; the panel shows "Your turn" with the taf's words, and the person's Done or Not now goes back to planque as its
// own signed message (`tafs-chrome/hand/v1`), kept until planque says it has it. The extension never types, clicks or fills for the person.

import { LABEL, sign } from "./crypto.js";

/** How long a hand-over stays open: planque's own limit, so the two end together. */
export const HAND_MAX_MS = 30 * 60_000;
/** The taf's words: at most this many characters, plain text. */
export const WORDS_MAX = 280;
// Control characters (a newline included) and the marks that reverse or isolate text direction: refused, never stripped, so
// the taf's words can't be drawn as something other than what they say.
const CONTROL = /\p{Cc}/u;
const DIRECTION = /[؜‎‏‪-‮⁦-⁩]/u;

/** Whether `words` is what a hand-over may say: a string of 1 to 280 characters, no control or direction characters. */
export function validWords(words) {
  if (typeof words !== "string") return false;
  const n = [...words].length;
  return n >= 1 && n <= WORDS_MAX && words.trim().length > 0 && !CONTROL.test(words) && !DIRECTION.test(words);
}

/**
 * A read for the taf after the person typed into the page: the same read without **what is in the fields**. Every field's
 * `value` (a text, the checked state, a select's chosen option) is left out, so the taf learns the field is there and nothing of
 * what was typed. A page that echoes what was typed into its own text is read as text, as any read is.
 */
export function withoutValues(page) {
  if (!page || typeof page !== "object") return null;
  const { fields, ...rest } = page;
  return { ...rest, fields: (Array.isArray(fields) ? fields : []).map(({ value, ...field }) => field) }; // eslint-disable-line no-unused-vars
}

/** The message that gives planque the person's answer, signed by this Chrome's key over `{hand, outcome, page?}`. */
export async function handMessage(privateKey, { hand, outcome, page = null }) {
  const body = { hand, outcome, ...(page ? { page } : {}) };
  return { type: "hand_result", ...body, sig: await sign(privateKey, LABEL.hand, body) };
}

/**
 * The answers waiting to reach planque: kept until planque says it has one (`hand_kept`), or that it never will (`unknown_hand`,
 * `expired`). `store` is a small key-value area that survives the worker stopping.
 */
export class HandAnswers {
  constructor(store) {
    this.store = store;
  }

  async all() {
    return (await this.store.get()) || [];
  }

  async add(answer) {
    const kept = (await this.all()).filter((a) => a.hand !== answer.hand);
    await this.store.set([...kept, answer]);
  }

  async drop(hand) {
    await this.store.set((await this.all()).filter((a) => a.hand !== hand));
  }

  /** planque's answer to one: done with it, or (too large) once more without the page. */
  async heard(m) {
    if (!m || typeof m.hand !== "string") return;
    if (m.type === "hand_kept") return this.drop(m.hand);
    if (m.type !== "hand_refused") return;
    if (m.code === "too_large") {
      const a = (await this.all()).find((x) => x.hand === m.hand);
      if (a && a.page) return this.add({ ...a, page: null });
    }
    // planque couldn't keep it just now (a store failure): it stays, and goes again on the next welcome, until it is kept or too old.
    if (m.code === "not_kept") return;
    return this.drop(m.hand); // unknown_hand, expired, unsigned: sending it again changes nothing
  }
}
