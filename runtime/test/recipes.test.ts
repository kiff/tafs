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
    // Every category of the first batch is there, and the batch has the one that buys within a Card (kiff/tafs#475).
    for (const c of CATEGORIES) expect(RECIPES.some((r) => r.category === c.id)).toBe(true);
    expect(RECIPES.map((r) => r.id)).toContain("buy-an-item-within-a-card");
  });

  it("reads a recipe file: the front matter and the words to say to Coach", () => {
    expect(parseRecipe(FILE, "pay-a-bill.md")).toEqual({
      id: "pay-a-bill", title: "Pay a bill", category: "money", line: "Pays one bill.", say: "Pay my bills under 100 EUR.",
      connections: [["quickbooks", "xero"], ["wise"]],
      card: { allows: ["A payment up to 100 EUR"], holds: ["A payment over 100 EUR"], never: ["A new payee"] },
    });
    const tried = parseRecipe(FILE.replace("---\nPay", "tried: 2026-11-10 test\n---\nPay"), "pay-a-bill.md");
    expect(tried.tried).toEqual({ on: "2026-11-10", account: "test" });
  });

  it("refuses a recipe file that isn't one, naming the file and why", () => {
    const bad = (text: string, why: string) => expect(() => parseRecipe(text, "pay-a-bill.md")).toThrow(`pay-a-bill.md: ${why}`);
    bad(FILE.replace("id: pay-a-bill", "id: other"), "id other isn't the file's name");
    bad(FILE.replace("category: money", "category: fun"), "category is one of money, customers, operations, errands");
    bad(FILE.replace("  - quickbooks | xero\n  - wise\n", ""), "connections lists at least one vendor");
    bad(FILE.replace("  - quickbooks | xero", "  - Quick Books"), "a vendor is named as the catalog names it");
    bad(FILE.replace("  never:\n    - A new payee\n", ""), "card.never is missing");
    bad(FILE.replace("Pay my bills under 100 EUR.\n", ""), "the words to say to Coach are missing");
    bad(FILE.replace("tried", "tried").replace("---\nPay", "tried: soon\n---\nPay"), "tried is a date and the account's kind");
    bad(FILE.replace("line: Pays one bill.", "line: Pays one bill.\nstore: amazon"), "store isn't a recipe's field");
    bad(FILE.replace("title: Pay a bill\n", ""), "title is missing");
  });

  it("names a catalog entry's vendor as a recipe does", () => {
    expect(vendorOf({ entry: "connector:composio:gmail", vendor: "gmail" })).toEqual(["gmail"]);
    expect(vendorOf({ entry: "connector:composio:_1password", vendor: "_1password" })).toEqual(["1password"]);
    expect(vendorOf({ entry: "stripe@3", vendor: "Stripe" })).toEqual(["stripe"]);
    expect(vendorOf({ entry: "connector:composio:googlecalendar", vendor: "Google Calendar" })).toEqual(["googlecalendar"]);
  });

  it("lists only tried recipes, each of whose connections the catalog can make, by category", () => {
    const tried = RECIPES.filter((r) => "tried" in r).map((r) => r.id);
    const all = recipesFor(undefined);
    expect(all.filtered).toBe(false);
    expect(all.recipes.map((r) => r.id)).toEqual(tried);
    // Nothing in the catalog: nothing listed, and no category.
    expect(recipesFor([])).toEqual({ categories: [], recipes: [], filtered: true });
  });

  it("filters by every connection, taking any vendor that would do for it", () => {
    // A view over the whole file set, with every recipe tried, so the filter can be seen working.
    const view = (apps: Array<{ entry: string; vendor: string }>) => {
      const have = new Set(apps.flatMap(vendorOf));
      return RECIPES.filter((r) => r.connections.every((g) => g.some((v) => have.has(v.replace(/[_-]/g, ""))))).map((r) => r.id);
    };
    const stripeAndGmail = [{ entry: "stripe@3", vendor: "Stripe" }, { entry: "connector:composio:gmail", vendor: "Gmail" }];
    expect(view(stripeAndGmail)).toEqual(["refunds-within-a-limit", "save-a-customer-who-cancels"]);
    // Gorgias instead of Gmail does as well for Refunds; Chargebacks needs Stripe and a shop or help desk.
    expect(view([{ entry: "stripe@3", vendor: "Stripe" }, { entry: "connector:composio:gorgias", vendor: "Gorgias" }]))
      .toEqual(["chargebacks-with-evidence", "refunds-within-a-limit"]);
    expect(view([{ entry: "stripe@3", vendor: "Stripe" }])).toEqual([]);
    // Categories come in their order, with a title for the apps, only for categories with a recipe.
    const money = recipesFor(undefined);
    expect(CATEGORIES.map((c) => c.id)).toEqual(["money", "customers", "operations", "errands"]);
    for (const c of money.categories) expect(c.title).toMatch(/^[A-Z]/);
  });
});
