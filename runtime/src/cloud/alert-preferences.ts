/** Per-phone choices, applied before an alert leaves tafs cloud. */
export const ALERT_KINDS = [
  { id: "needs_you", title: "A taf needs you", words: "For a call waiting for your answer" },
  { id: "halted", title: "Every taf halted", words: "When a taf or the account is halted" },
  { id: "answered", title: "A taf answered someone", words: "Silent, for the tafs whose results include this phone" },
  { id: "not_sent", title: "A reply wasn't sent", words: "Silent, when a reply needs another try" },
] as const;

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

export function alertChoices(kinds: Partial<AlertKinds> | undefined) {
  return ALERT_KINDS.map((kind) => ({ ...kind, enabled: kinds?.[kind.id] !== false }));
}
