import { type Lang, wordsIn } from "./words/index.js";

/** Per-phone choices, applied before an alert leaves tafs cloud. Their words are in the phone's language (words/). */
export const ALERT_KINDS = [{ id: "needs_you" }, { id: "halted" }, { id: "answered" }, { id: "not_sent" }] as const;

export type AlertKind = typeof ALERT_KINDS[number]["id"];
export type AlertKinds = Record<AlertKind, boolean>;
export const defaultKinds = (): AlertKinds => ({ needs_you: true, halted: true, answered: true, not_sent: true });

/** Older registrations keep all kinds enabled. Unknown fields are refused. */
export function parseKinds(value: unknown): AlertKinds | undefined {
  if (value === undefined) return undefined;
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("kinds is an object of alert choices");
  const kinds = defaultKinds();
  for (const [id, on] of Object.entries(value)) {
    if (!Object.hasOwn(kinds, id) || typeof on !== "boolean") throw new Error("each alert kind is true or false");
    kinds[id as AlertKind] = on;
  }
  return kinds;
}

/** Each kind with its title and line in `lang`, and whether this phone has it on. */
export function alertChoices(kinds: Partial<AlertKinds> | undefined, lang: Lang = "en") {
  const w = wordsIn(lang);
  return ALERT_KINDS.map((kind) => ({ id: kind.id, title: w[`alert_${kind.id}_title`], words: w[`alert_${kind.id}_words`], enabled: kinds?.[kind.id] !== false }));
}
