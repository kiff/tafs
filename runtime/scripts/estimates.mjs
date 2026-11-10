#!/usr/bin/env node
// Reads an Eval report's results (kiff/agents' `eval -out results.json`: one entry per run, each with its
// `case` and the run's AI cost, `cost_micro_usd`) into src/cloud/estimates.json, the "in our tests" figures a
// new taf's estimate gives for a recipe's job (src/cloud/estimate.ts): credits per run at the median and the
// 90th percentile, how many runs, and the Eval's date. Run after an Eval:
//   node scripts/estimates.mjs results.json [--date 2026-11-10] [--recipe case=recipe-id ...]
// The results name a case, not a recipe: CASE_RECIPES says which recipe sets up the job each case runs, and
// --recipe adds or overrides one. A case no recipe is named for is left out, and said so. Recipes the
// report doesn't cover keep the figures they had. Nothing here is made up: no report, no figures.
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

/** The Eval's cases (kiff/agents' eval/cases) and the recipe whose job each runs. */
export const CASE_RECIPES = { refunds: "refunds-within-a-limit", chargebacks: "chargebacks-with-evidence" };

/** Credits for an AI cost in micro US dollars, as KIFF's meter counts them: the same rule as estimate.ts creditsOf. */
export function creditsOf(costMicroUSD) {
  if (!Number.isFinite(costMicroUSD) || costMicroUSD <= 0) return 0;
  const n = Math.ceil(costMicroUSD) * 100 * 100 * 4;
  const d = 1_000_000 * 108;
  return Math.floor(n / d) + (n % d > 0 ? 1 : 0);
}

const percentile = (xs, p) => {
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.max(0, Math.ceil(p * s.length) - 1)];
};

/**
 * The table from a report's results: by recipe, each run's credits at the median and the 90th percentile.
 * A run with no cost recorded is left out. Answers the table and the cases no recipe is named for.
 */
export function tableFrom(results, recipes, date) {
  if (!Array.isArray(results)) throw new Error("an Eval report's results are a JSON array, one entry per run");
  const by = new Map();
  const unnamed = new Set();
  for (const r of results) {
    if (!r || typeof r.case !== "string") continue;
    const recipe = recipes[r.case];
    if (!recipe) { unnamed.add(r.case); continue; }
    const c = creditsOf(Number(r.cost_micro_usd));
    if (c <= 0) continue;
    by.set(recipe, [...(by.get(recipe) ?? []), c]);
  }
  const table = {};
  for (const [recipe, credits] of [...by.entries()].sort(([a], [b]) => a.localeCompare(b))) {
    table[recipe] = { p50: percentile(credits, 0.5), p90: percentile(credits, 0.9), runs: credits.length, from: date };
  }
  return { table, unnamed: [...unnamed].sort() };
}

/** The arguments: the report's path, --date, and each --recipe case=recipe-id. */
export function argsOf(argv) {
  const out = { file: "", date: new Date().toISOString().slice(0, 10), recipes: { ...CASE_RECIPES } };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--date") out.date = argv[++i] ?? "";
    else if (a === "--recipe") {
      const [c, r] = String(argv[++i] ?? "").split("=");
      if (!c || !r) throw new Error("--recipe takes case=recipe-id");
      out.recipes[c] = r;
    } else if (!out.file) out.file = a;
    else throw new Error(`one report at a time: ${a}`);
  }
  if (!out.file) throw new Error("usage: node scripts/estimates.mjs results.json [--date YYYY-MM-DD] [--recipe case=recipe-id]");
  if (!/^\d{4}-\d{2}-\d{2}$/.test(out.date)) throw new Error("--date is the Eval's date, YYYY-MM-DD");
  return out;
}

const here = dirname(fileURLToPath(import.meta.url));
export const TABLE = join(here, "..", "src", "cloud", "estimates.json");

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const args = argsOf(process.argv.slice(2));
  const { table, unnamed } = tableFrom(JSON.parse(readFileSync(args.file, "utf8")), args.recipes, args.date);
  const kept = JSON.parse(readFileSync(TABLE, "utf8"));
  const next = Object.fromEntries(Object.entries({ ...kept, ...table }).sort(([a], [b]) => a.localeCompare(b)));
  writeFileSync(TABLE, JSON.stringify(next, null, 2) + "\n");
  console.log(`${Object.keys(table).length} recipes from ${args.file} → src/cloud/estimates.json${unnamed.length ? `; no recipe named for: ${unnamed.join(", ")}` : ""}`);
}
