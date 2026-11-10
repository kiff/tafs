/**
 * Recipes (docs/coach.md, "Recipes"; recipes/README.md): the jobs a new person opens Coach to,
 * each with the words to say to Coach. tafs cloud serves them filtered to those whose every
 * connection has a vendor the account's catalog lists, in the person's language; the apps only
 * draw them and fill Coach's message box with the words.
 */
import { RECIPES } from "../recipes.js";
import type { CatalogApp } from "./connect.js";
import { type Lang, wordsIn } from "./words/index.js";

const CATEGORY_IDS = ["money", "customers", "operations", "errands"] as const;

/** The recipes' categories in a language, in the order the apps show them. */
export function categoriesIn(lang: Lang): Array<{ id: string; title: string }> {
  const w = wordsIn(lang);
  const title = { money: w.recipes_category_money, customers: w.recipes_category_customers, operations: w.recipes_category_operations, errands: w.recipes_category_errands };
  return CATEGORY_IDS.map((id) => ({ id, title: title[id] }));
}

/** The categories in English. */
export const CATEGORIES: ReadonlyArray<{ id: string; title: string }> = categoriesIn("en");

/** One recipe as the apps draw it, in the person's language. */
export interface Recipe {
  id: string;
  title: string;
  category: string;
  /** The problem it ends, as Home leads with it. */
  headline: string;
  /** What that problem costs today, in one line, with no figures. */
  today: string;
  /** What the taf does, in one line. */
  line: string;
  /** The words to say to Coach: what a tap fills the message box with. */
  say: string;
  /** Each connection the taf needs, as the vendors that would do for it. */
  connections: string[][];
  /** What its Card allows, what waits for the person, and what is refused outright, in words. */
  card: { allows: string[]; holds: string[]; never: string[] };
}

/** What the apps get: the recipes by category, whether the catalog filtered them, and whether Home leads with them. */
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
  /** Whether Home leads with the recipes, and the line above them or, when it doesn't, Home's empty line. */
  home: Home;
  /** The recipe sheet's words: its three zones and its button. */
  labels: Labels;
}

/**
 * Home's part: shown only while the list is (no taf on the account, suggestions on), to a person who
 * manages the account's tafs, with at least one recipe listed. The apps draw `words` above the list when
 * shown, and as Home's empty line when not.
 */
export interface Home {
  shown: boolean;
  words: string;
}

export interface Labels {
  allows: string;
  holds: string;
  never: string;
  /** The button that opens Coach with the recipe's words. */
  hand: string;
}

/** The sheet's words in a language. */
export function labelsIn(lang: Lang): Labels {
  const w = wordsIn(lang);
  return { allows: w.recipes_label_allows, holds: w.recipes_label_holds, never: w.recipes_label_never, hand: w.recipes_label_hand };
}

/** Home's empty line: a person who manages the tafs connects an app to set one up; anyone else sees what's shared with them. */
function emptyLine(lang: Lang, manages: boolean): string {
  const w = wordsIn(lang);
  return manages ? w.recipes_home_empty_coach : w.recipes_home_empty_shared;
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

/** The suggestions setting's words in a language, for each reason. */
export function suggestionWords(lang: Lang): Record<Suggestions["reason"], string> {
  const w = wordsIn(lang);
  return { on: w.suggestions_on, off: w.suggestions_off, live: w.suggestions_live };
}

/** An empty list, with why (shown false), and Home's empty line. */
export function noRecipes(suggestions: Suggestions, lang: Lang = "en", manages = true): RecipesView {
  return { categories: [], recipes: [], filtered: false, shown: false, suggestions,
    home: { shown: false, words: emptyLine(lang, manages) }, labels: labelsIn(lang) };
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

/**
 * The recipes each of whose connections has a vendor among apps, by category, in lang; every recipe when
 * apps is undefined. `manages`: the person manages the account's tafs (`cards.manage`), so Home may lead
 * with the recipes.
 */
export function recipesFor(apps: Pick<CatalogApp, "entry" | "vendor">[] | undefined, lang: Lang = "en", manages = true): RecipesView {
  const have = new Set(apps?.flatMap(vendorOf) ?? []);
  const listed = RECIPES
    .filter((r) => !apps || r.connections.every((group) => group.some((v) => have.has(fold(v)))))
    .map((r): Recipe => {
      const w = lang === "en" ? r : r.languages[lang];
      return { id: r.id, title: w.title, category: r.category, headline: w.headline, today: w.today, line: w.line, say: w.say,
        connections: r.connections.map((g) => [...g]), card: { allows: [...w.card.allows], holds: [...w.card.holds], never: [...w.card.never] } };
    });
  const order = new Map<string, number>(CATEGORY_IDS.map((c, i) => [c, i]));
  // RECIPES is in the apps' order already (category, then each recipe's `order`); a stable sort keeps it within a category.
  listed.sort((a, b) => (order.get(a.category) ?? 9) - (order.get(b.category) ?? 9));
  // Home leads with the recipes only when there's one to lead with: a catalog that connects none of them
  // leaves Home its empty line.
  const home = manages && listed.length > 0;
  return {
    categories: categoriesIn(lang).filter((c) => listed.some((r) => r.category === c.id)),
    recipes: listed,
    filtered: apps !== undefined,
    shown: true,
    suggestions: { show: true, reason: "on", words: suggestionWords(lang).on },
    home: { shown: home, words: home ? wordsIn(lang).recipes_home_lead : emptyLine(lang, manages) },
    labels: labelsIn(lang),
  };
}
