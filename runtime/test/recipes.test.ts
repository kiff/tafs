import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { RECIPES } from "../src/recipes.js";
import { CATEGORIES, recipesFor, vendorOf } from "../src/cloud/recipes.js";
// The generator is plain JavaScript, so the script and the test parse the files the same way.
import { MODULE, parseRecipe, readRecipes, RECIPES_DIR, render } from "../scripts/recipes.mjs";

const FILE = `---
id: pay-a-bill
title: Pay a bill
category: money
line: Pays one bill.
connections:
  - quickbooks | xero
  - wise
card:
  allows:
    - A payment up to 100 EUR
  holds:
    - A payment over 100 EUR
  never:
    - A new payee
---
Pay my bills under 100 EUR.
`;

describe("recipes", () => {
  it("serves exactly what recipes/*.md says: the generated module matches the files", () => {
    const fromFiles = readRecipes(RECIPES_DIR);
    expect(readFileSync(MODULE, "utf8")).toBe(render(fromFiles));
    expect(RECIPES).toEqual(fromFiles);
    expect(RECIPES.length).toBeGreaterThanOrEqual(10);
    // Every category of the first batch is there, and the five starter jobs are recipes now (kiff/tafs#476).
    for (const c of CATEGORIES) expect(RECIPES.some((r) => r.category === c.id)).toBe(true);
    for (const id of ["refunds-within-a-limit", "pay-matching-bills", "access-for-joiners-and-leavers", "ship-when-green", "chargebacks-with-evidence"]) {
      expect(RECIPES.map((r) => r.id)).toContain(id);
    }
  });

  it("reads a recipe file: the front matter and the words to say to Coach", () => {
    expect(parseRecipe(FILE, "pay-a-bill.md")).toEqual({
      id: "pay-a-bill", title: "Pay a bill", category: "money", line: "Pays one bill.", say: "Pay my bills under 100 EUR.",
      connections: [["quickbooks", "xero"], ["wise"]],
      card: { allows: ["A payment up to 100 EUR"], holds: ["A payment over 100 EUR"], never: ["A new payee"] },
    });
  });

  it("refuses a recipe file that isn't one, naming the file and why", () => {
    const bad = (text: string, why: string) => expect(() => parseRecipe(text, "pay-a-bill.md")).toThrow(`pay-a-bill.md: ${why}`);
    bad(FILE.replace("id: pay-a-bill", "id: other"), "id other isn't the file's name");
    bad(FILE.replace("category: money", "category: fun"), "category is one of money, customers, operations, errands");
    bad(FILE.replace("  - quickbooks | xero\n  - wise\n", ""), "connections lists at least one vendor");
    bad(FILE.replace("  - quickbooks | xero", "  - Quick Books"), "a vendor is named as the catalog names it");
    bad(FILE.replace("  never:\n    - A new payee\n", ""), "card.never is missing");
    bad(FILE.replace("Pay my bills under 100 EUR.\n", ""), "the words to say to Coach are missing");
    bad(FILE.replace("line: Pays one bill.", "line: Pays one bill.\ntried: 2026-11-10 test"), "tried isn't a recipe's field");
    bad(FILE.replace("title: Pay a bill\n", ""), "title is missing");
  });

  it("names a catalog entry's vendor as a recipe does", () => {
    expect(vendorOf({ entry: "connector:composio:gmail", vendor: "gmail" })).toEqual(["gmail"]);
    expect(vendorOf({ entry: "connector:composio:_1password", vendor: "_1password" })).toEqual(["1password"]);
    expect(vendorOf({ entry: "stripe@3", vendor: "Stripe" })).toEqual(["stripe"]);
    expect(vendorOf({ entry: "connector:composio:googlecalendar", vendor: "Google Calendar" })).toEqual(["googlecalendar"]);
  });

  it("lists every recipe when the catalog wasn't read, and none when it lists nothing", () => {
    const all = recipesFor(undefined);
    expect(all.filtered).toBe(false);
    expect(all.recipes.map((r) => r.id)).toEqual(RECIPES.map((r) => r.id).sort((a, b) => {
      const order = (id: string) => CATEGORIES.findIndex((c) => c.id === RECIPES.find((r) => r.id === id)!.category);
      return order(a) - order(b);
    }));
    expect(all.categories).toEqual(CATEGORIES);
    expect(recipesFor([])).toEqual({ categories: [], recipes: [], filtered: true });
  });

  it("filters by every connection, taking any vendor that would do for it, by category", () => {
    const ids = (apps: Array<{ entry: string; vendor: string }>) => recipesFor(apps).recipes.map((r) => r.id);
    const stripeAndGmail = [{ entry: "stripe@3", vendor: "Stripe" }, { entry: "connector:composio:gmail", vendor: "Gmail" }];
    expect(ids(stripeAndGmail)).toEqual(["refunds-within-a-limit", "save-a-customer-who-cancels"]);
    // Gorgias instead of Gmail does as well for Refunds; Chargebacks needs Stripe and a shop or help desk.
    expect(ids([{ entry: "stripe@3", vendor: "Stripe" }, { entry: "connector:composio:gorgias", vendor: "Gorgias" }]))
      .toEqual(["chargebacks-with-evidence", "refunds-within-a-limit"]);
    expect(ids([{ entry: "stripe@3", vendor: "Stripe" }])).toEqual([]);
    // Only the categories with a recipe, in their order, with the title the apps show.
    const github = recipesFor([{ entry: "connector:composio:github", vendor: "GitHub" }]);
    expect(github.categories).toEqual([{ id: "operations", title: "Operations" }]);
    expect(github.recipes.map((r) => r.id)).toEqual(["ship-when-green"]);
    expect(github.recipes[0]!.say).toContain("Deploy my releases from my code host");
    // The words name the kind of app, never a brand (kiff/tafs#476, gap 2): the brands are the connections' alone.
    for (const r of RECIPES) for (const vendor of r.connections.flat()) expect(r.say.toLowerCase()).not.toContain(vendor.replace(/[_-]/g, " ").split(" ")[0]!);
  });
});
