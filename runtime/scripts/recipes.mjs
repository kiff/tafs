#!/usr/bin/env node
// Reads recipes/*.md (one recipe each: a front matter and the words to say to Coach), and each
// recipe's words in the other seven languages from recipes/<lang>/<id>.md, into src/recipes.ts,
// which tafs cloud serves at GET /v1/recipes in the person's language. Run after changing a recipe:
//   node scripts/recipes.mjs
// The test (test/recipes.test.ts) fails when the generated module no longer matches the files.
import { existsSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
export const RECIPES_DIR = join(here, "..", "..", "recipes");
export const MODULE = join(here, "..", "src", "recipes.ts");

/** The categories, in the apps' order, from recipes/categories.txt: data, never a list here. */
export const CATEGORIES = readFileSync(join(RECIPES_DIR, "categories.txt"), "utf8").split("\n")
  .map((l) => l.trim()).filter((l) => l && !l.startsWith("#"));
for (const c of CATEGORIES) if (!/^[a-z]+(?:-[a-z]+)*$/.test(c)) throw new Error(`categories.txt: ${c} isn't a category id`);
/** The languages besides English a recipe's words are in (docs/languages.md): each has a file per recipe. */
export const LANGS = ["de", "fr", "es", "it", "pt", "nl", "pl"];
const LISTS = ["allows", "holds", "never"];
/**
 * What a recipe's proof run passed on, as `proved:` names it (English only): each key has its line in every
 * language in tafs cloud's words (`recipes_proved_<key>`). A recipe with no proof run names none.
 */
export const PROVED = ["help_desk"];
const ID = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const VENDOR = /^[a-z0-9_]+(?:-[a-z0-9_]+)*$/;

/** A recipe's line of front matter: its indent, key (for `key:` or `key: value`) or list item. */
function lineOf(raw) {
  const indent = raw.length - raw.trimStart().length;
  const text = raw.trim();
  if (text.startsWith("- ")) return { indent, item: text.slice(2).trim() };
  const m = /^([a-z]+):(?:\s+(.*))?$/.exec(text);
  if (!m) return null;
  return { indent, key: m[1], value: (m[2] ?? "").trim() };
}

/**
 * Parses one recipe file. Throws, naming the file, on anything it doesn't take. With `of` (the English
 * recipe), the file is that recipe's words in another language: its title, headline, today, line, Card
 * lists and words to say, with as many lines in each list as English has; the rest is English's alone.
 */
export function parseRecipe(text, file, of) {
  const fail = (why) => { throw new Error(`${file}: ${why}`); };
  const m = /^---\n([\s\S]*?)\n---\n([\s\S]*)$/.exec(text.replace(/\r\n/g, "\n"));
  if (!m) fail("a recipe starts with a front matter between --- lines, then the words to say to Coach");
  const say = m[2].trim();
  if (!say) fail("the words to say to Coach are missing after the front matter");
  if (/^#|^---/m.test(say)) fail("the words to say to Coach are plain sentences, with no headings");

  // The front matter: top-level keys, `connections` as a list, `card` with three lists.
  const out = { connections: [], card: {}, sample: [] };
  const lists = { connections: out.connections, sample: out.sample };
  let open = null;
  let inCard = false;
  for (const raw of m[1].split("\n")) {
    if (!raw.trim()) continue;
    const line = lineOf(raw);
    if (!line) fail(`a line I don't understand: ${raw.trim()}`);
    if (line.item !== undefined) {
      if (!open) fail(`a list item outside a list: ${raw.trim()}`);
      open.push(line.item);
      continue;
    }
    if (line.indent === 0) {
      inCard = false;
      open = null;
      if (line.key in out && !["connections", "card", "sample"].includes(line.key)) fail(`${line.key} is given twice`);
      if (line.key === "connections" || line.key === "card" || line.key === "sample") {
        if (line.value) fail(`${line.key} is a list under its own line, not a value`);
        if (line.key === "card") inCard = true;
        else open = lists[line.key];
        continue;
      }
      if (!line.value) fail(`${line.key} has no value`);
      out[line.key] = line.value;
      continue;
    }
    if (inCard && line.indent === 2 && ["allows", "holds", "never"].includes(line.key)) {
      if (line.value) fail(`card.${line.key} is a list under its own line, not a value`);
      if (line.key in out.card) fail(`card.${line.key} is given twice`);
      open = out.card[line.key] = [];
      continue;
    }
    fail(`a line I don't understand: ${raw.trim()}`);
  }

  const words = ["title", "headline", "today", "line"];
  const known = of ? [...words, "card", "sample"] : ["id", "title", "category", "order", "line", "headline", "today", "connections", "card", "proved", "sample"];
  // A translation names no connections: they're English's alone (an empty list reads as none given).
  if (of && !out.connections.length) delete out.connections;
  // The sample report, as one text: each item a paragraph, a blank line between them, except that list
  // items ("- …") in a row stay one list. Given in every language or in none.
  const sample = out.sample.length
    ? out.sample.map((l, i) => (i === 0 ? "" : l.startsWith("- ") && out.sample[i - 1].startsWith("- ") ? "\n" : "\n\n") + l).join("")
    : undefined;
  for (const key of Object.keys(out)) if (!known.includes(key)) fail(`${key} isn't a${of ? " translated" : ""} recipe's field (${known.join(", ")})`);
  for (const key of words) if (!out[key]) fail(`${key} is missing`);
  for (const key of LISTS) if (!out.card[key]) fail(`card.${key} is missing (an empty list is fine)`);
  if (of) {
    for (const key of LISTS) {
      if (out.card[key].length !== of.card[key].length) fail(`card.${key} has ${out.card[key].length} lines, and English has ${of.card[key].length}`);
    }
    if (Boolean(sample) !== Boolean(of.sample)) fail(of.sample ? "sample is missing: English has one" : "sample is given, and English has none");
    return { title: out.title, headline: out.headline, today: out.today, line: out.line, say, card: { allows: out.card.allows, holds: out.card.holds, never: out.card.never },
      ...(sample ? { sample } : {}) };
  }
  if (!out.id) fail("id is missing");
  if (!out.category) fail("category is missing");
  if (!ID.test(out.id)) fail("id is lower-case words joined by -");
  if (`${out.id}.md` !== file) fail(`id ${out.id} isn't the file's name`);
  if (!CATEGORIES.includes(out.category)) fail(`category is one of ${CATEGORIES.join(", ")}`);
  if (!/^[1-9][0-9]*$/.test(out.order ?? "")) fail("order is the recipe's place in its category: 1, 2, 3…");
  if (!out.connections.length) fail("connections lists at least one vendor");
  const connections = out.connections.map((group) => {
    const vendors = group.split("|").map((v) => v.trim());
    if (vendors.some((v) => !VENDOR.test(v))) fail(`a vendor is named as the catalog names it, lower case: ${group}`);
    if (new Set(vendors).size !== vendors.length) fail(`a vendor named twice in one connection: ${group}`);
    return vendors;
  });
  if (!out.card.allows.length) fail("card.allows says what the Card allows, at least one line");
  if (out.proved !== undefined && !PROVED.includes(out.proved)) fail(`proved is what its proof run passed on: one of ${PROVED.join(", ")}`);
  if (sample && !out.proved) fail("sample is what its proof run reported: a recipe with a sample names what it was proved on (proved)");
  return {
    id: out.id, title: out.title, category: out.category, order: Number(out.order), line: out.line, headline: out.headline, today: out.today, say, connections,
    card: { allows: out.card.allows, holds: out.card.holds, never: out.card.never },
    ...(out.proved ? { proved: out.proved } : {}), ...(sample ? { sample } : {}),
  };
}

/** Every recipe in dir, each checked, in the apps' order (by category, then `order`), with its words in every other language from dir/<lang>/. */
export function readRecipes(dir) {
  const files = readdirSync(dir).filter((f) => f.endsWith(".md") && f !== "README.md").sort();
  const recipes = files.map((f) => parseRecipe(readFileSync(join(dir, f), "utf8"), f));
  const ids = new Set();
  const places = new Set();
  for (const r of recipes) {
    if (ids.has(r.id)) throw new Error(`${r.id}: two recipes with one id`);
    ids.add(r.id);
    if (places.has(`${r.category} ${r.order}`)) throw new Error(`${r.id}.md: another ${r.category} recipe is already at order ${r.order}`);
    places.add(`${r.category} ${r.order}`);
  }
  // In the order the apps show them: by category, then each recipe's place in it.
  recipes.sort((a, b) => CATEGORIES.indexOf(a.category) - CATEGORIES.indexOf(b.category) || a.order - b.order);
  // A language has a file for every recipe and none for a recipe that isn't one, so a recipe can't ship untranslated.
  for (const lang of LANGS) {
    const there = existsSync(join(dir, lang)) ? readdirSync(join(dir, lang)).filter((f) => f.endsWith(".md")) : [];
    for (const f of there) if (!ids.has(f.slice(0, -3))) throw new Error(`${lang}/${f}: no recipe ${f.slice(0, -3)} in English`);
    for (const r of recipes) {
      if (!there.includes(`${r.id}.md`)) throw new Error(`${lang}/${r.id}.md: the recipe's words in ${lang} are missing`);
      (r.languages ??= {})[lang] = parseRecipe(readFileSync(join(dir, lang, `${r.id}.md`), "utf8"), `${lang}/${r.id}.md`, r);
    }
  }
  return recipes;
}

export function render(recipes) {
  return `// Generated by scripts/recipes.mjs from recipes/*.md and recipes/<lang>/*.md. Do not edit by hand: run the script.
/** The recipes, as recipes/README.md describes them. */
export const RECIPES = ${JSON.stringify(recipes, null, 2)} as const;
`;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const recipes = readRecipes(RECIPES_DIR);
  writeFileSync(MODULE, render(recipes));
  console.log(`${recipes.length} recipes → src/recipes.ts`);
}
