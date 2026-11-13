/**
 * Recipes (docs/coach.md, "Recipes"; recipes/README.md): the jobs a new person opens Coach to,
 * each with the words to say to Coach. tafs cloud serves them filtered to those whose every
 * connection has a vendor the account's catalog lists, in the person's language; the apps only
 * draw them and fill Coach's message box with the words.
 */
import { RECIPES } from "../recipes.js";
import type { CatalogApp } from "./connect.js";
import { type Lang, wordsIn } from "./words/index.js";

/**
 * The recipes' categories, in the order the apps show them: as the recipes name them, in recipe order
 * (recipes/README.md orders them). No list here: a recipe with a new category brings it, and its title
 * comes from the words, `recipes_category_<id>`, which a test holds in every language.
 */
const CATEGORY_IDS: readonly string[] = [...new Set(RECIPES.map((r) => r.category as string))];

/** A category's title in a language: its words, or its id when they're missing. */
export function categoryTitle(id: string, lang: Lang): string {
  const title = (wordsIn(lang) as unknown as Record<string, unknown>)[`recipes_category_${id}`];
  return typeof title === "string" && title ? title : id;
}

/** The recipes' categories in a language, in the order the apps show them. */
export function categoriesIn(lang: Lang): Array<{ id: string; title: string }> {
  return CATEGORY_IDS.map((id) => ({ id, title: categoryTitle(id, lang) }));
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
  /** What its proof run passed on, as a line ready to draw ("Proved on a test help desk."): absent for a job with none. */
  proved?: string;
  /** What it reported after that proof run, as written: absent for a job with none. */
  sample?: string;
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

/** A recipe's proof line in a language, from what its proof run passed on (`proved`), or nothing. */
function provedOf(r: (typeof RECIPES)[number], lang: Lang): { proved?: string } {
  const key = (r as { proved?: string }).proved;
  if (!key) return {};
  const line = (wordsIn(lang) as unknown as Record<string, unknown>)[`recipes_proved_${key}`];
  return typeof line === "string" && line ? { proved: line } : {};
}

/** A recipe's sample report in a language, as written, or nothing. */
function sampleOf(w: object): { sample?: string } {
  const sample = (w as { sample?: string }).sample;
  return sample ? { sample } : {};
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
    home: { shown: false, words: emptyLine(lang, manages),
      ...(manages ? { own_words: { title: wordsIn(lang).gallery_own_words, line: wordsIn(lang).gallery_own_words_line } } : {}) }, labels: labelsIn(lang) };
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
      // One line says what the job does, in the Card's terms: no problem headline over it, and no line on what waiting
      // costs today (the app-words cut, screen 2). The apps draw no empty line.
      return { id: r.id, title: w.title, category: r.category, headline: w.line, today: "", line: "", say: w.say,
        connections: r.connections.map((g) => [...g]), card: { allows: [...w.card.allows], holds: [...w.card.holds], never: [...w.card.never] },
        ...provedOf(r, lang), ...sampleOf(w) };
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
    // Home is the gallery now (the first run's item 4): "Say it in your own words" leads the list, then every job.
    home: { shown: home, words: home ? wordsIn(lang).recipes_home_lead : emptyLine(lang, manages),
      ...(manages ? { own_words: { title: wordsIn(lang).gallery_own_words, line: wordsIn(lang).gallery_own_words_line } } : {}) },
    labels: labelsIn(lang),
  };
}

/** One app a recipe needs, as the apps draw it: its name, and its logo's path on tafs cloud when there is one. */
export interface RecipeApp {
  name: string;
  /** A path on tafs cloud (`/v1/logos/{key}`), never a URL: the phones load nothing from anywhere else. */
  logo?: string;
}

/** A recipe with its apps, as the gallery and Coach's sheet draw it. */
export interface RecipeWithApps extends Recipe {
  /** One per connection: a vendor already connected, else the first the catalog lists, else the recipe's first. */
  apps: RecipeApp[];
  /** Every app it needs is connected already. */
  connected: boolean;
  /** A taf on the account was built from it: only for someone who manages the account's tafs, who can see them all. */
  running?: boolean;
}

/** The catalog by vendor, a connected entry preferred where a vendor has several. */
function byVendor(catalog: CatalogApp[] | undefined): Map<string, CatalogApp> {
  const out = new Map<string, CatalogApp>();
  for (const app of catalog ?? []) {
    for (const v of vendorOf(app)) {
      const had = out.get(v);
      if (!had || (had.state !== "connected" && app.state === "connected")) out.set(v, app);
    }
  }
  return out;
}

/** For each of a recipe's connections, the catalog entry it would use (a connected one first, else the first listed) and the name to draw. */
function pickApps(r: Recipe, vendors: Map<string, CatalogApp>): Array<{ app?: CatalogApp; name: string }> {
  return r.connections.map((group) => {
    const listed = group.map((v) => vendors.get(fold(v))).filter((a): a is CatalogApp => a !== undefined);
    const app = listed.find((a) => a.state === "connected") ?? listed[0];
    return { app, name: app?.title || group[0] || "" };
  });
}

/**
 * Each recipe with its apps. `catalog` is the account's catalog as read (undefined when it couldn't be):
 * a vendor's name and logo come from its entry. `logos` maps an entry's `icon` to its path on tafs cloud.
 * `running`, for someone who manages the tafs, is the recipes the account's tafs were built from.
 */
export function withApps(recipes: Recipe[], catalog: CatalogApp[] | undefined, logos: Map<string, string>, running?: Set<string>): RecipeWithApps[] {
  const vendors = byVendor(catalog);
  return recipes.map((r) => {
    const picked = pickApps(r, vendors);
    const apps = picked.map(({ app, name }): RecipeApp => {
      const logo = app?.icon ? logos.get(app.icon) : undefined;
      return logo ? { name, logo } : { name };
    });
    return { ...r, apps, connected: picked.length > 0 && picked.every((p) => p.app?.state === "connected"),
      ...(running ? { running: running.has(r.id) } : {}) };
  });
}

/** The icons of the catalog entries withApps draws for these recipes, to record as logos. */
export function iconsFor(recipes: Recipe[], catalog: CatalogApp[] | undefined): string[] {
  const vendors = byVendor(catalog);
  return recipes.flatMap((r) => pickApps(r, vendors).flatMap(({ app }) => (app?.icon ? [app.icon] : [])));
}

/** The strip under the tafs list: a few jobs the account doesn't run yet, in the order the apps draw them. */
export interface Strip {
  shown: boolean;
  title: string;
  /** Recipe ids, at most STRIP_MAX: every app connected first, then some, then none, each in recipe order. */
  ids: string[];
}

/** The most jobs the strip lists. */
export const STRIP_MAX = 3;

/** The gallery's own words: its title, its "all jobs" heading, and the way to say a job in one's own words. */
export interface GalleryWords {
  /** The gallery's title, and + New's words ("New taf"). */
  title: string;
  /** The heading inside the gallery, above the full list of jobs ("All jobs"). Not the row under the strip: that's `see_all`. */
  all: string;
  own_words: string;
  own_words_line: string;
  /**
   * Browse mode (looking without creating anything): the Tafs tab's entry that opens the gallery to browse. Absent
   * while Home leads with every job, where the row would open the same list again: the apps draw no row then.
   */
  explore?: string;
  /** The row under the strip that opens the same gallery to browse ("See all jobs"): the only word for that row. */
  see_all: string;
  /** A job sheet's only action while browsing: Coach opened with the job's words. Nothing is set up until it's tapped. */
  try: string;
  /** The label above a job's `sample`: what it reported after its test run. */
  sample: string;
}

/** What `GET /v1/recipes/gallery` answers: every job the catalog can connect, the strip, and the words to draw them. */
export interface GalleryView {
  categories: Array<{ id: string; title: string }>;
  recipes: RecipeWithApps[];
  /** False when the catalog couldn't be read: every recipe is listed. */
  filtered: boolean;
  strip: Strip;
  words: GalleryWords;
  labels: Labels;
}

/** Where the person and the account stand, for the gallery. */
export interface GalleryState {
  /** The person manages the account's tafs (`cards.manage`). */
  manages: boolean;
  /** The account has a live taf, Setup aside. */
  live: boolean;
  /** The account's "Show suggestions" setting is on. */
  suggestions: boolean;
  /** The recipes the account's tafs were built from; read only for someone who manages them. */
  running?: Set<string>;
}

/**
 * The gallery (+ New): every job the catalog can connect, also once a taf is live, whatever "Show
 * suggestions" says. The strip is shown only to someone who manages the tafs, once the account has a
 * live taf (before that, Home leads with every job), with suggestions on and a job left to show.
 */
export function galleryFor(catalog: CatalogApp[] | undefined, lang: Lang, state: GalleryState, logos: Map<string, string>): GalleryView {
  const base = recipesFor(catalog, lang, state.manages);
  const running = state.manages ? state.running ?? new Set<string>() : undefined;
  const recipes = withApps(base.recipes, catalog, logos, running);
  const vendors = byVendor(catalog);
  // Every app connected first, then some, then none.
  const rank = (r: RecipeWithApps) => {
    const linked = pickApps(r, vendors).filter((p) => p.app?.state === "connected").length;
    return r.connected ? 0 : linked > 0 ? 1 : 2;
  };
  const left = recipes.filter((r) => !r.running);
  const ids = left.map((r, i) => ({ r, i })).sort((a, b) => rank(a.r) - rank(b.r) || a.i - b.i).slice(0, STRIP_MAX).map(({ r }) => r.id);
  const w = wordsIn(lang);
  const shown = state.manages && state.live && state.suggestions && ids.length > 0;
  // Home leads with every job (as `/v1/recipes` has it): someone who manages the tafs, no live taf, suggestions on,
  // and a job to show. "Explore jobs" would open that same list again, so it isn't offered then.
  const homeLeads = state.manages && !state.live && state.suggestions && recipes.length > 0;
  return {
    categories: base.categories,
    recipes,
    filtered: base.filtered,
    strip: { shown, title: w.gallery_strip_title, ids: shown ? ids : [] },
    words: { title: w.gallery_title, all: w.gallery_all, own_words: w.gallery_own_words, own_words_line: w.gallery_own_words_line,
      ...(homeLeads ? {} : { explore: w.gallery_explore }), see_all: w.gallery_see_all, try: w.gallery_try, sample: w.gallery_sample },
    labels: base.labels,
  };
}
