import type { CoachStore } from "./coach.js";
import { type HeadlineTaf, headlineOf } from "./home.js";
import { CATEGORIES, categoryTitle } from "./recipes.js";
import { LANGUAGES, type Lang } from "./words/index.js";

/**
 * Labels (docs/labels.md): a name and a colour on a taf, set by Coach, for the apps to filter one flat list. A label
 * holds no Card, permission, budget or context, and removing one changes nothing about its tafs.
 */
export interface Label {
  id: string;
  /** As Coach or the person named it; a suggested label (with a key) is shown in the person's language instead. */
  name: string;
  /** One of LABEL_COLORS. */
  color: string;
  /** A suggested label: one of Explore's categories. */
  key?: string;
}

/** The account's labels, in the order the apps show them, and the version each write moves on by one. */
export interface LabelSet { labels: Label[]; version: number }

export const LABEL_LIMITS = { account: 12, taf: 3, name: 24 } as const;

/** The colour tokens a label takes: Explore's categories' own, which the apps draw for light and dark. */
export const LABEL_COLORS: readonly string[] = CATEGORIES.map((c) => c.id);

/** A label change refused, in words Coach can act on. */
export class LabelRefusal extends Error {}

const KEPT_MS = 100 * 365 * 24 * 3600_000;
/** A version is claimed this long: a write that stops between its claim and its save holds the next one no longer. */
const CLAIM_MS = 60_000;

export const labelsKey = (tenant: string) => `${tenant}#labels`;

export async function readLabels(store: CoachStore, tenant: string): Promise<LabelSet> {
  const kept = await store.get(labelsKey(tenant)) as Partial<LabelSet> | undefined;
  return { labels: Array.isArray(kept?.labels) ? kept.labels : [], version: typeof kept?.version === "number" ? kept.version : 0 };
}

/** Saves `labels` as the version after `read`'s, once: false, and nothing saved, when another write took it first. */
export async function saveLabels(store: CoachStore, tenant: string, read: LabelSet, labels: Label[], now: Date): Promise<boolean> {
  const version = read.version + 1;
  if (!(await store.once(`${labelsKey(tenant)}#v${version}`, new Date(now.getTime() + CLAIM_MS)))) return false;
  await store.put(labelsKey(tenant), { labels, version }, new Date(now.getTime() + KEPT_MS));
  return true;
}

const fold = (s: string) => s.normalize("NFKC").trim().toLocaleLowerCase();

/** The category a name means, in any of the 8 languages ("Finances", "Geld" and "Money" are one): its key. */
export function keyOf(name: string): string | undefined {
  const n = fold(name);
  return CATEGORIES.find((c) => n === c.id || LANGUAGES.some((l) => fold(categoryTitle(c.id, l)) === n))?.id;
}

/** A label's name as the person reads it: a suggested label's in their language, else as named. */
export function labelName(label: Label, lang: Lang): string {
  return label.key ? categoryTitle(label.key, lang) : label.name;
}

function checkName(name: string): string {
  const n = name.normalize("NFKC").replace(/\s+/g, " ").trim();
  // eslint-disable-next-line no-control-regex
  if (!n || [...n].length > LABEL_LIMITS.name || /[\u0000-\u001f\u007f]/.test(n)) throw new LabelRefusal(`a label's name is 1 to ${LABEL_LIMITS.name} characters on one line`);
  return n;
}

/** The colour fewest labels have, the first of those in LABEL_COLORS' order. */
function freeColor(labels: Label[]): string {
  const uses = (c: string) => labels.filter((l) => l.color === c).length;
  return [...LABEL_COLORS].sort((a, b) => uses(a) - uses(b))[0]!;
}

/** The label `given` names: by id, by name regardless of case, or by the category it means. */
export function findLabel(labels: Label[], given: string): Label | undefined {
  const key = keyOf(given);
  return labels.find((l) => l.id === given) ?? labels.find((l) => fold(l.name) === fold(given) || (key !== undefined && l.key === key));
}

/** Labels no taf carries any more: dropped, so none ever shows empty. */
export function dropEmpty(labels: Label[], tafs: Array<{ labels?: string[] }>): Label[] {
  const used = new Set(tafs.flatMap((t) => t.labels ?? []));
  return labels.filter((l) => used.has(l.id));
}

/**
 * A taf's labels set to `wanted` (ids or names; a new name makes the label): the account's labels after it, with the
 * empty ones dropped, and the taf's ids. Refused past 3 on the taf or 12 on the account.
 */
export function withTafLabels(set: LabelSet, tafs: Array<{ id: string; labels?: string[] }>, tafID: string, wanted: unknown, newID: () => string): { labels: Label[]; ids: string[] } {
  if (!Array.isArray(wanted)) throw new LabelRefusal("labels is the taf's whole list of labels, each an id or a name ([] for none)");
  if (wanted.length > LABEL_LIMITS.taf) throw new LabelRefusal(`a taf has at most ${LABEL_LIMITS.taf} labels`);
  const labels = [...set.labels];
  const ids: string[] = [];
  for (const w of wanted) {
    const given = typeof w === "string" ? w.trim() : "";
    if (!given) throw new LabelRefusal("each label is an id or a name");
    let label = findLabel(labels, given);
    if (!label) {
      const name = checkName(given);
      const key = keyOf(name);
      label = { id: newID(), name, color: key ?? freeColor(labels), ...(key ? { key } : {}) };
      labels.push(label);
    }
    if (!ids.includes(label.id)) ids.push(label.id);
  }
  const kept = dropEmpty(labels, [...tafs.filter((t) => t.id !== tafID), { labels: ids }]);
  if (kept.length > LABEL_LIMITS.account) throw new LabelRefusal(`an account has at most ${LABEL_LIMITS.account} labels: put the taf under one it has, or remove one first`);
  return { labels: kept, ids };
}

/** A label renamed or recoloured: refused for a name another label has. A name that means a category takes its key. */
export function withLabelChanged(set: LabelSet, label: Label, change: { name?: unknown; color?: unknown }): Label[] {
  const next: Label = { ...label };
  if (change.name !== undefined) {
    const name = checkName(typeof change.name === "string" ? change.name : "");
    if (set.labels.some((l) => l.id !== label.id && fold(l.name) === fold(name))) throw new LabelRefusal(`another label is named ${name}`);
    const key = keyOf(name);
    const twin = key ? set.labels.find((l) => l.id !== label.id && l.key === key) : undefined;
    if (twin) throw new LabelRefusal(`${name} is the same category as the label ${twin.name}: use that label`);
    next.name = name;
    if (key) next.key = key; else delete next.key;
  }
  if (change.color !== undefined) {
    if (typeof change.color !== "string" || !LABEL_COLORS.includes(change.color)) throw new LabelRefusal(`color is one of ${LABEL_COLORS.join(", ")}`);
    next.color = change.color;
  }
  return set.labels.map((l) => (l.id === label.id ? next : l));
}

/**
 * The labels Home shows, from the tafs it lists: each with how many of them it's on, how many of those wait for the
 * person, and the headline filtered to it ("2 tafs working · Money"). A label on none of them isn't shown.
 */
export function labelsView(set: LabelSet, listed: Array<HeadlineTaf & { id: string; waiting: number }>, labelsOf: Map<string, string[]>, lang: Lang) {
  return set.labels.flatMap((l) => {
    const on = listed.filter((t) => labelsOf.get(t.id)?.includes(l.id));
    if (!on.length) return [];
    const name = labelName(l, lang);
    return [{ id: l.id, name, color: l.color, count: on.length, waiting: on.filter((t) => t.waiting > 0).length,
      headline: { words: `${headlineOf(on, lang).words.replace(/[.。]$/, "")} · ${name}` } }];
  });
}
