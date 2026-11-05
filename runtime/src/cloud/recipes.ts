/**
 * Recipes (docs/coach.md, "Recipes"; recipes/README.md): the jobs a new person opens Coach to,
 * each with the words to say to Coach. tafs cloud serves them filtered to those whose every
 * connection has a vendor the account's catalog lists; the apps only draw them and fill Coach's
 * message box with the words.
 */
import { RECIPES } from "../recipes.js";
import type { CatalogApp } from "./connect.js";

/** A recipe's category, in the order the apps show them. */
export const CATEGORIES: ReadonlyArray<{ id: string; title: string }> = [
  { id: "money", title: "Money" },
  { id: "customers", title: "Customers" },
  { id: "operations", title: "Operations" },
  { id: "errands", title: "Errands" },
];

/** One recipe as the apps draw it. */
export interface Recipe {
  id: string;
  title: string;
  category: string;
  /** What the taf does, in one line. */
  line: string;
  /** The words to say to Coach: what a tap fills the message box with. */
  say: string;
  /** Each connection the taf needs, as the vendors that would do for it. */
  connections: string[][];
  /** What its Card allows, what waits for the person, and what is refused outright, in words. */
  card: { allows: string[]; holds: string[]; never: string[] };
}

/** What the apps get: the recipes by category, and whether the catalog filtered them. */
export interface RecipesView {
  categories: Array<{ id: string; title: string }>;
  recipes: Recipe[];
  /** False when the catalog couldn't be read: every recipe is listed, and Coach says what it can't connect. */
  filtered: boolean;
  /**
   * Whether a new Coach conversation shows them (the owner's decision, kiff/tafs#476): only while the
   * account has no live taf, and while its "Show suggestions" setting is on. False, the list is empty
   * and `suggestions.words` say why; the apps draw only what comes.
   */
  shown: boolean;
  suggestions: Suggestions;
}

/** The account's "Show suggestions" setting, with the words for it, all from here. */
export interface Suggestions {
  /** The setting: on by default; a manager turns it off for good. */
  show: boolean;
  /** Why the list is as it is: shown (`on`), turned off (`off`), or the account has a taf (`live`). */
  reason: "on" | "off" | "live";
  /** The same, in words the apps show. */
  words: string;
}

export const SUGGESTION_WORDS = {
  on: "Suggestions show in a new conversation with Coach until the account's first taf is live.",
  off: "Suggestions are off for this account.",
  live: "The account has a live taf, so a new conversation with Coach opens blank. Suggestions can be turned off for good.",
};

/** An empty list, with why (shown false). */
export function noRecipes(suggestions: Suggestions): RecipesView {
  return { categories: [], recipes: [], filtered: false, shown: false, suggestions };
}

/**
 * The vendor a catalog entry names, as a recipe names it: the slug after `connector:<provider>:`,
 * or before `@` in a reviewed entry; and its `vendor`, lowered. Dots, spaces, dashes and
 * underscores fold to nothing (`google-calendar` and `Google Calendar` match `googlecalendar`).
 */
export function vendorOf(app: Pick<CatalogApp, "entry" | "vendor">): string[] {
  const m = /^connector:[^:]+:(.+)$/.exec(app.entry);
  return [...new Set([fold(m ? m[1]! : app.entry.replace(/@\d+$/, "")), fold(app.vendor)])];
}

function fold(name: string): string {
  return name.toLowerCase().replace(/^_+/, "").replace(/[\s._-]+/g, "");
}

/** The recipes each of whose connections has a vendor among apps, by category; every recipe when apps is undefined. */
export function recipesFor(apps: Pick<CatalogApp, "entry" | "vendor">[] | undefined): RecipesView {
  const have = new Set(apps?.flatMap(vendorOf) ?? []);
  const listed = RECIPES
    .filter((r) => !apps || r.connections.every((group) => group.some((v) => have.has(fold(v)))))
    .map((r): Recipe => ({ id: r.id, title: r.title, category: r.category, line: r.line, say: r.say,
      connections: r.connections.map((g) => [...g]), card: { allows: [...r.card.allows], holds: [...r.card.holds], never: [...r.card.never] } }));
  const order = new Map(CATEGORIES.map((c, i) => [c.id, i]));
  listed.sort((a, b) => (order.get(a.category) ?? 9) - (order.get(b.category) ?? 9));
  return {
    categories: CATEGORIES.filter((c) => listed.some((r) => r.category === c.id)),
    recipes: listed,
    filtered: apps !== undefined,
    shown: true,
    suggestions: { show: true, reason: "on", words: SUGGESTION_WORDS.on },
  };
}
