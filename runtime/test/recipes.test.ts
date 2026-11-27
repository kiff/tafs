import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { RECIPES } from "../src/recipes.js";
import { CATEGORIES, categoriesIn, connectsOf, connectsToday, CONNECTS_TODAY, labelsIn, recipesFor, vendorOf, welcomeVideo, withApps } from "../src/cloud/recipes.js";
import type { CatalogApp } from "../src/cloud/connect.js";
import { baseLanguage, FULL_LANGUAGES, LANGUAGES } from "../src/cloud/words/index.js";
// The generator is plain JavaScript, so the script and the test parse the files the same way.
import { LANGS, MODULE, parseRecipe, readRecipes, RECIPES_DIR, render } from "../scripts/recipes.mjs";

const FILE = `---
id: pay-a-bill
title: Pay a bill
category: money
order: 1
line: Pays one bill.
headline: Bills paid on time.
today: Bills wait until someone has the time.
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
    expect(RECIPES.length).toBeGreaterThanOrEqual(9);
    // Every category of the first batch is there, and the five starter jobs are recipes now (kiff/tafs#476).
    for (const c of CATEGORIES) expect(RECIPES.some((r) => r.category === c.id)).toBe(true);
    for (const id of ["refunds-within-a-limit", "ship-when-green", "chargebacks-with-evidence"]) {
      expect(RECIPES.map((r) => r.id)).toContain(id);
    }
  });

  it("takes its categories from the data: recipes/categories.txt orders them, and each has a title in every language", () => {
    const listed = readFileSync(`${RECIPES_DIR}/categories.txt`, "utf8").split("\n").map((l) => l.trim()).filter((l) => l && !l.startsWith("#"));
    // The apps' order is the file's, for the categories the recipes use.
    expect(CATEGORIES.map((c) => c.id)).toEqual(listed.filter((id) => RECIPES.some((r) => r.category === id)));
    for (const lang of LANGUAGES) {
      for (const c of categoriesIn(lang)) expect(c.title, `${lang} ${c.id}`).not.toBe(c.id);
    }
  });

  it("reads a recipe file: the front matter and the words to say to Coach", () => {
    expect(parseRecipe(FILE, "pay-a-bill.md")).toEqual({
      id: "pay-a-bill", title: "Pay a bill", category: "money", order: 1, line: "Pays one bill.", headline: "Bills paid on time.", today: "Bills wait until someone has the time.",
      say: "Pay my bills under 100 EUR.",
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
    bad(FILE.replace("headline: Bills paid on time.\n", ""), "headline is missing");
    bad(FILE.replace("order: 1\n", ""), "order is the recipe's place in its category");
    bad(FILE.replace("order: 1", "order: first"), "order is the recipe's place in its category");
    bad(FILE.replace("today: Bills wait until someone has the time.\n", ""), "today is missing");
  });

  const DE = `---
title: Eine Rechnung zahlen
headline: Rechnungen pünktlich bezahlt.
today: Rechnungen warten, bis jemand Zeit hat.
line: Bezahlt eine Rechnung.
card:
  allows:
    - Eine Zahlung bis 100 EUR
  holds:
    - Eine Zahlung über 100 EUR
  never:
    - Ein neuer Empfänger
---
Bezahle meine Rechnungen unter 100 EUR.
`;

  it("reads a recipe's words in another language, with the rest English's alone", () => {
    const en = parseRecipe(FILE, "pay-a-bill.md");
    expect(parseRecipe(DE, "de/pay-a-bill.md", en)).toEqual({
      title: "Eine Rechnung zahlen", headline: "Rechnungen pünktlich bezahlt.", today: "Rechnungen warten, bis jemand Zeit hat.", line: "Bezahlt eine Rechnung.",
      say: "Bezahle meine Rechnungen unter 100 EUR.",
      card: { allows: ["Eine Zahlung bis 100 EUR"], holds: ["Eine Zahlung über 100 EUR"], never: ["Ein neuer Empfänger"] },
    });
    const bad = (text: string, why: string) => expect(() => parseRecipe(text, "de/pay-a-bill.md", en)).toThrow(`de/pay-a-bill.md: ${why}`);
    // The Card says the same in every language: as many lines in each list.
    bad(DE.replace("    - Ein neuer Empfänger\n", "    - Ein neuer Empfänger\n    - Eine Auszahlung\n"), "card.never has 2 lines, and English has 1");
    bad(DE.replace("title: Eine Rechnung zahlen", "title: Eine Rechnung zahlen\nid: pay-a-bill"), "id isn't a translated recipe's field");
    bad(DE.replace("card:", "connections:\n  - stripe\ncard:"), "connections isn't a translated recipe's field");
    bad(DE.replace("today: Rechnungen warten, bis jemand Zeit hat.\n", ""), "today is missing");
  });

  it("reads what a recipe's proof run passed on, and what it reported, in every language or none", () => {
    const PROVEN = FILE.replace("  - wise\n", "  - wise\nproved: help_desk\nsample:\n  - Paid 2 bills.\n  - One waits for you.\n");
    expect(parseRecipe(PROVEN, "pay-a-bill.md")).toMatchObject({ proved: "help_desk", sample: "Paid 2 bills.\n\nOne waits for you." });
    // A list in a row stays one list; a paragraph after it starts after a blank line.
    expect(parseRecipe(FILE.replace("  - wise\n", "  - wise\nproved: help_desk\nsample:\n  - Done.\n  - - T-1: paid.\n  - - T-2: held.\n  - Nothing else.\n"), "pay-a-bill.md").sample)
      .toBe("Done.\n\n- T-1: paid.\n- T-2: held.\n\nNothing else.");
    expect(parseRecipe(FILE, "pay-a-bill.md")).not.toHaveProperty("proved");
    const bad = (text: string, why: string) => expect(() => parseRecipe(text, "pay-a-bill.md")).toThrow(`pay-a-bill.md: ${why}`);
    bad(FILE.replace("  - wise\n", "  - wise\nproved: a laptop\n"), "proved is what its proof run passed on: one of help_desk");
    bad(FILE.replace("  - wise\n", "  - wise\nsample:\n  - Paid 2 bills.\n"), "sample is what its proof run reported");
    const en = parseRecipe(PROVEN, "pay-a-bill.md");
    const DE_SAMPLE = DE.replace("card:", "sample:\n  - 2 Rechnungen bezahlt.\ncard:");
    expect(parseRecipe(DE_SAMPLE, "de/pay-a-bill.md", en)).toMatchObject({ sample: "2 Rechnungen bezahlt." });
    const badDe = (text: string, of: ReturnType<typeof parseRecipe>, why: string) => expect(() => parseRecipe(text, "de/pay-a-bill.md", of)).toThrow(`de/pay-a-bill.md: ${why}`);
    badDe(DE, en, "sample is missing: English has one");
    badDe(DE_SAMPLE, parseRecipe(FILE, "pay-a-bill.md"), "sample is given, and English has none");
    badDe(DE.replace("card:", "proved: help_desk\ncard:"), en, "proved isn't a translated recipe's field");
    // Served in the person's language: the proof line from tafs cloud's words, the sample as written.
    const ticket = recipesFor(undefined, "fr").recipes.find((r) => r.id === "acknowledge-every-ticket")!;
    expect(ticket.proved).toBe("Éprouvé sur un support de test.");
    expect(ticket.sample).toMatch(/^Chaque ticket ouvert/);
    // Every language keeps the ticket ids and labels as the taf wrote them, and its list stays one list.
    for (const lang of LANGUAGES) {
      const s = recipesFor(undefined, lang).recipes.find((r) => r.id === "acknowledge-every-ticket")!.sample!;
      for (const kept of ["T-3001", "T-3099", "order-status", "wholesale"]) expect(s, `${lang} ${kept}`).toContain(kept);
      expect(s, lang).toMatch(/\n- \*\*T-3001\*\*[^\n]*\n- \*\*T-3002\*\*/);
    }
  });

  it("reads the recipes in English in Korean, until they're written in it", () => {
    const ko = recipesFor(undefined, "ko", true, null);
    const enView = recipesFor(undefined, "en", true, null);
    expect(ko.recipes.map((r) => r.title)).toEqual(enView.recipes.map((r) => r.title));
  });

  it("has every recipe in every language the apps speak, each word translated", () => {
    // A recipe is written per base language; a regional variant (pt-BR) reads its base's (recipesFor).
    // Korean, Japanese and the languages added since read the recipes in English until they're written in them (recipesFor falls back).
    const english = new Set(["ko", "ja", "et", "lt", "lv", "cs", "sk", "sl", "hu", "ro", "bg", "hr", "tr", "id", "vi", "th", "zh-Hant"]);
    expect(["en", ...LANGS].sort()).toEqual(FULL_LANGUAGES.filter((l) => baseLanguage(l) === l && !english.has(l)).sort());
    for (const r of RECIPES) {
      for (const lang of LANGS) {
        const w = r.languages[lang as keyof typeof r.languages];
        for (const key of ["title", "headline", "today", "line", "say"] as const) {
          expect(w[key], `${lang}/${r.id}.md ${key}`).not.toBe(r[key]);
        }
      }
    }
  });

  it("says what the problem costs today in words, never a figure, in every language", () => {
    for (const r of RECIPES) {
      expect(r.today, r.id).not.toMatch(/\d/);
      for (const w of Object.values(r.languages)) expect(w.today, r.id).not.toMatch(/\d/);
    }
  });

  it("serves the recipes, categories and sheet in the person's language", () => {
    const fr = recipesFor(undefined, "fr", true, null);
    expect(fr.categories.map((c) => c.title)).toEqual(["Argent", "Clients", "Opérations", "Tâches courantes"]);
    expect(fr.recipes.find((r) => r.id === "refunds-within-a-limit")).toMatchObject({
      // One line says what the job does: no problem headline over it, and no line on what waiting costs (the app-words cut).
      title: "Remboursements dans une limite", headline: "Répond aux demandes de remboursement du support et rembourse chez le prestataire de paiement, dans les limites de la Card.", line: "", today: "",
      connections: [["gorgias", "intercom", "gmail"], ["stripe"]],
    });
    expect(fr.labels).toEqual({ allows: "Fait seul", holds: "Vous demande d'abord", never: "Ne fait jamais", hand: "Confier à Coach" });
    expect(fr.home).toEqual({ shown: true, words: "Choisissez une tâche pour votre premier taf.", own_words: { title: "Dites-la avec vos mots", line: "Coach construit le taf avec vous." } });
    expect(fr.suggestions.words).toBe("Les suggestions s'affichent dans une nouvelle conversation avec Coach tant que le compte n'a pas son premier taf.");
    // Every language has its own words for the categories and the sheet.
    for (const lang of LANGS) {
      expect(categoriesIn(lang as never).map((c) => c.title)).not.toEqual(CATEGORIES.map((c) => c.title));
      expect(labelsIn(lang as never)).not.toEqual(labelsIn("en"));
    }
    // English, the order the apps show: money, customers, operations, errands.
    // English: one line, what the job does; no line on what waiting costs.
    expect(recipesFor(undefined).recipes.find((r) => r.id === "refunds-within-a-limit")).toMatchObject({ today: "", line: "",
      headline: "Answers refund requests from the support queue and refunds in the payment provider, within the Card." });
  });

  it("keeps the apps' order: by category, then each recipe's place, with refunds leading Money", () => {
    expect(RECIPES.map((r) => r.id)).toEqual([
      "refunds-within-a-limit", "chargebacks-with-evidence", "chase-overdue-invoices", "save-a-customer-who-cancels", "failed-card-payments", "payouts-matched-to-the-bank",
      "answer-questions-about-orders", "follow-up-new-leads", "acknowledge-every-ticket", "returns-by-email", "late-parcels",
      "book-and-move-appointments", "ship-when-green",
      "file-receipts",
    ]);
    for (const c of CATEGORIES) {
      const places = RECIPES.filter((r) => r.category === c.id).map((r) => r.order);
      expect(places, c.id).toEqual(places.map((_, i) => i + 1));
    }
    // Filtered or in another language, the order holds.
    expect(recipesFor(undefined, "pl").recipes[0]!.id).toBe("refunds-within-a-limit");
    const stripeAndGmail = [{ entry: "stripe@3", vendor: "Stripe" }, { entry: "connector:composio:gmail", vendor: "Gmail" }];
    expect(recipesFor(stripeAndGmail).recipes.map((r) => r.id)).toEqual(["refunds-within-a-limit", "chase-overdue-invoices", "save-a-customer-who-cancels", "failed-card-payments"]);
  });

  it("leads Home with the recipes only for a person who manages tafs, and only with one to lead with", () => {
    expect(recipesFor(undefined).home).toEqual({ shown: true, words: "Pick a job for your first taf.", own_words: { title: "Say it in your own words", line: "Coach builds the taf with you." } });
    expect(recipesFor(undefined, "en", false).home).toEqual({ shown: false, words: "No tafs yet. Tafs shared with you show here." });
    expect(recipesFor([], "en", true).home).toEqual({ shown: false, words: "No tafs yet. Tell Coach the job a taf should do.", own_words: { title: "Say it in your own words", line: "Coach builds the taf with you." } });
    // Home is the gallery now: "Say it in your own words" leads it for a person who manages tafs, never for someone a taf is only shared with.
    expect(recipesFor(undefined, "en", false).home).not.toHaveProperty("own_words");
  });

  it("names a catalog entry's vendor as a recipe does", () => {
    expect(vendorOf({ entry: "connector:composio:gmail", vendor: "gmail" })).toEqual(["gmail"]);
    expect(vendorOf({ entry: "connector:composio:_1password", vendor: "_1password" })).toEqual(["1password"]);
    expect(vendorOf({ entry: "stripe@3", vendor: "Stripe" })).toEqual(["stripe"]);
    expect(vendorOf({ entry: "connector:composio:googlecalendar", vendor: "Google Calendar" })).toEqual(["googlecalendar"]);
  });

  it("lists every recipe when the catalog wasn't read, and none when it lists nothing", () => {
    const all = recipesFor(undefined, "en", true, null);
    expect(all.filtered).toBe(false);
    expect(all.recipes.map((r) => r.id)).toEqual(RECIPES.map((r) => r.id).sort((a, b) => {
      const order = (id: string) => CATEGORIES.findIndex((c) => c.id === RECIPES.find((r) => r.id === id)!.category);
      return order(a) - order(b);
    }));
    expect(all.categories).toEqual(CATEGORIES);
    expect(recipesFor([])).toEqual({ categories: [], recipes: [], filtered: true, shown: true, suggestions: { show: true, reason: "on", words: "Suggestions show in a new conversation with Coach until the account's first taf is live." },
      home: { shown: false, words: "No tafs yet. Tell Coach the job a taf should do.", own_words: { title: "Say it in your own words", line: "Coach builds the taf with you." } },
      labels: { allows: "Does on its own", holds: "Asks you first", never: "Never does", hand: "Hand it to Coach" } });
  });

  // Rule 13 (go-to-market scoreboard, 13 Nov): the gallery shows only jobs whose apps connect today. A job whose app
  // can't connect yet stays out, and comes back once its app is added to CONNECTS_TODAY after a check proves it.
  it("lists only the jobs whose every connection has an app that connects today", () => {
    const ids = recipesFor(undefined).recipes.map((r) => r.id);
    // Overdue invoices takes Stripe as its billing app (invoices read there, reminders by the person's own mail), so it is listed
    // with Stripe and Gmail; without a billing app that connects, it would not be.
    expect(ids).toContain("chase-overdue-invoices");
    const noStripe = new Set([...CONNECTS_TODAY].filter((v) => v !== "stripe"));
    expect(recipesFor(undefined, "en", true, noStripe).recipes.map((r) => r.id)).not.toContain("chase-overdue-invoices");
    for (const id of ids) expect(connectsToday(RECIPES.find((r) => r.id === id)!), id).toBe(true);
    expect(ids).toEqual(expect.arrayContaining(["ship-when-green", "refunds-within-a-limit", "save-a-customer-who-cancels", "acknowledge-every-ticket"]));
    // Once an app is proved, its jobs come back with no other change: Typeform was proved by a sign-in on a phone
    // (16 Nov), so new leads is listed; a job whose app isn't proved yet still isn't.
    expect(ids).toContain("follow-up-new-leads");
    const without = new Set([...CONNECTS_TODAY].filter((v) => v !== "typeform"));
    expect(recipesFor(undefined, "en", true, without).recipes.map((r) => r.id)).not.toContain("follow-up-new-leads");
    // Google Calendar and Drive were proved by sign-ins on a phone (16 Nov): appointments are listed. Sheets was
    // proved the same way (read 20 Nov, a row added 21 Nov, after the grant's scope fix went live): receipts is listed too,
    // and a job whose Sheets isn't proved isn't.
    expect(ids).toContain("book-and-move-appointments");
    expect(ids).toContain("file-receipts");
    const noSheets = new Set([...CONNECTS_TODAY].filter((v) => v !== "googlesheets"));
    expect(recipesFor(undefined, "en", true, noSheets).recipes.map((r) => r.id)).not.toContain("file-receipts");
  });

  // Three jobs for small shops that need only apps that connect today: listed now, with no proof run.
  it("lists failed card payments, payouts matched to the bank and late parcels with today's apps", () => {
    const ids = recipesFor(undefined).recipes.map((r) => r.id);
    expect(ids).toEqual(expect.arrayContaining(["failed-card-payments", "payouts-matched-to-the-bank", "late-parcels"]));
    // A read-only job holds nothing: its Card allows reads and refuses every move of money.
    const payouts = RECIPES.find((r) => r.id === "payouts-matched-to-the-bank")!;
    expect(payouts.card.holds).toEqual([]);
    for (const lang of Object.keys(payouts.languages)) expect(payouts.languages[lang as keyof typeof payouts.languages].card.holds, lang).toEqual([]);
  });

  // Returns by email: small shops on a store app handle each return by hand. It stays out until the store app is proved.
  it("lists returns by email only once its store app connects today", () => {
    expect(recipesFor(undefined).recipes.map((r) => r.id)).not.toContain("returns-by-email");
    const withShopify = new Set([...CONNECTS_TODAY, "shopify"]);
    const ids = recipesFor(undefined, "en", true, withShopify).recipes.map((r) => r.id);
    expect(ids).toEqual(expect.arrayContaining(["returns-by-email", "answer-questions-about-orders", "chargebacks-with-evidence"]));
  });

  // The store app has no read of a return's state, so the taf can't see that an item came back: no refund in this
  // job is ever its own to make. Every refund is held for the owner; only the replies with the return steps go out alone.
  it("holds every refund in returns by email, in every language", () => {
    const returns = RECIPES.find((r) => r.id === "returns-by-email")!;
    expect(returns.card.allows).toEqual(["Replies with the return steps, up to 30 a day"]);
    expect(returns.card.holds[0]).toBe("Every refund, back to the original payment, for your approval");
    for (const [lang, w] of Object.entries(returns.languages)) {
      expect(w.card.allows, lang).toHaveLength(1);
      expect(w.card.holds, lang).toHaveLength(2);
      expect(w.card.allows[0], lang).not.toMatch(/EUR/);
    }
  });

  // Rule 13 on the read: once planque's catalog says which apps a check connected (`proved`), the gallery reads it, so a
  // proof is one data change. Dark behind CONNECTS_TODAY (TAFS_PROVED_FROM_CATALOG); and a catalog that can't be read, or
  // marks nothing proved yet, keeps the list, so no read ever empties the gallery.
  it("reads the apps that connect today from the catalog's proofs, only when asked and only from a catalog that has them", () => {
    const app = (vendor: string, proved?: string): CatalogApp => ({ entry: `connector:p:${vendor}`, vendor, title: vendor, name: vendor, how: "oauth", state: "available",
      ...(proved ? { proved: { at: proved, how: "oauth" as const } } : {}) });
    const apps = [app("stripe"), app("gmail", "2026-11-13"), app("typeform", "2026-11-16"), app("pipedrive"), app("shopify"), app("gorgias"), app("github")];
    // Stripe and Pipedrive were proved by their keys, and no entry here is a key entry: they're only in the read's map.
    const read = { apps, proved: { stripe: { at: "2026-11-13", how: "key" }, pipedrive: { at: "2026-11-13", how: "key" }, gmail: { at: "2026-11-13", how: "oauth" } } };
    expect(connectsOf(read, false)).toBe(CONNECTS_TODAY);
    expect(connectsOf(undefined, true)).toBe(CONNECTS_TODAY);
    expect(connectsOf({ apps: apps.map((a) => ({ ...a, proved: undefined })) }, true)).toBe(CONNECTS_TODAY);
    const connects = connectsOf(read, true);
    expect([...connects].sort()).toEqual(["gmail", "pipedrive", "stripe", "typeform"]);
    const ids = recipesFor(apps, "en", true, connects).recipes.map((r) => r.id);
    // Proved: new leads (Typeform, Pipedrive, mail) and refunds (mail, Stripe). Not proved: the store app's jobs, and GitHub's.
    expect(ids).toEqual(expect.arrayContaining(["follow-up-new-leads", "refunds-within-a-limit"]));
    expect(ids).not.toContain("answer-questions-about-orders");
    expect(ids).not.toContain("ship-when-green");
    // A key app proved in the map alone lists its job: GitHub, as planque's reviewed list has it.
    const withGitHub = connectsOf({ ...read, proved: { ...read.proved, github: { at: "2026-11-13", how: "key" } } }, true);
    expect(recipesFor(apps, "en", true, withGitHub).recipes.map((r) => r.id)).toContain("ship-when-green");
    // A proof added in planque's data alone lists its jobs: no list here changes.
    const withShopify = connectsOf({ ...read, apps: apps.map((a) => (a.vendor === "shopify" ? { ...a, proved: { at: "2026-11-17", how: "oauth" as const } } : a)) }, true);
    expect(recipesFor(apps, "en", true, withShopify).recipes.map((r) => r.id)).toContain("answer-questions-about-orders");
    // The app drawn for a connection is one that connects by the same read: Gorgias, listed but not proved, isn't.
    expect(withApps(recipesFor(apps, "en", true, connects).recipes, apps, new Map(), undefined, connects)
      .find((r) => r.id === "refunds-within-a-limit")!.apps.map((a) => a.name)).toEqual(["gmail", "stripe"]);
  });

  // A read of the four key jobs (13 Nov): refunds drew Gorgias, which can't connect yet, ahead of Intercom.
  it("draws, for a connection, an app that connects today ahead of one that can't, unless one is connected", () => {
    const app = (entry: string, vendor: string, state: CatalogApp["state"] = "available"): CatalogApp => ({ entry, vendor, title: vendor, how: "oauth", state });
    const refunds = (catalog: CatalogApp[]) => withApps(recipesFor(catalog).recipes, catalog, new Map()).find((r) => r.id === "refunds-within-a-limit")!.apps.map((a) => a.name);
    const stripe = app("stripe@3", "Stripe");
    expect(refunds([app("connector:p:gorgias", "Gorgias"), app("connector:p:intercom", "Intercom"), stripe])).toEqual(["Intercom", "Stripe"]);
    // With only an app that can't connect yet in the catalog for a connection, the job isn't shown at all.
    expect(withApps(recipesFor([app("connector:p:gorgias", "Gorgias"), stripe]).recipes, [], new Map()).map((r) => r.id)).not.toContain("refunds-within-a-limit");
    // A connected help desk is still the one drawn, whichever it is.
    expect(refunds([app("connector:p:gorgias", "Gorgias", "connected"), app("connector:p:intercom", "Intercom"), stripe])).toEqual(["Gorgias", "Stripe"]);
  });

  it("filters by every connection, taking any vendor that would do for it, by category", () => {
    const ids = (apps: Array<{ entry: string; vendor: string }>) => recipesFor(apps, "en", true, null).recipes.map((r) => r.id);
    const stripeAndGmail = [{ entry: "stripe@3", vendor: "Stripe" }, { entry: "connector:composio:gmail", vendor: "Gmail" }];
    expect(ids(stripeAndGmail)).toEqual(["refunds-within-a-limit", "chase-overdue-invoices", "save-a-customer-who-cancels", "failed-card-payments"]);
    // Gorgias instead of Gmail does as well for Refunds; Chargebacks needs Stripe and a shop or help desk; a help desk alone does for tickets.
    expect(ids([{ entry: "stripe@3", vendor: "Stripe" }, { entry: "connector:composio:gorgias", vendor: "Gorgias" }]))
      .toEqual(["refunds-within-a-limit", "chargebacks-with-evidence", "acknowledge-every-ticket"]);
    expect(ids([{ entry: "stripe@3", vendor: "Stripe" }])).toEqual([]);
    // Only the categories with a recipe, in their order, with the title the apps show.
    const github = recipesFor([{ entry: "connector:composio:github", vendor: "GitHub" }]);
    expect(github.categories).toEqual([{ id: "operations", title: "Operations" }]);
    expect(github.recipes.map((r) => r.id)).toEqual(["ship-when-green"]);
    expect(github.recipes[0]!.say).toContain("Deploy my releases from my code host");
    // The title, line and words name the kind of app, never a brand (kiff/tafs#476, gap 2): the brands are the
    // connections' alone. Whole words, so "wise" never trips on "otherwise", and two-word brands are caught by their first.
    const brands = /\b(stripe|quickbooks|xero|moneybird|zoho|wise|revolut|gorgias|intercom|gmail|outlook|shopify|typeform|pipedrive|google|slack|github|notion|amazon|agentmail|hubspot|zendesk|microsoft|teams)\b/i;
    for (const r of RECIPES) {
      for (const w of [r, ...Object.values(r.languages)]) for (const text of [w.title, w.headline, w.today, w.line, w.say]) expect(text).not.toMatch(brands);
    }
    expect("Save it in Google Drive, otherwise wait").toMatch(brands);
    expect("otherwise wait").not.toMatch(brands);
  });
});

// The first-run welcome's video link (the owner's first-run decision, 26 Nov): the apps show "Watch the 2-minute video" only
// when tafs cloud sends a URL, so nothing goes out until the setting holds one, and only an https one.
describe("the welcome video's address", () => {
  it("sends nothing while the setting is empty, unset or not an https URL", () => {
    for (const setting of [undefined, "", "   ", "kiff.dev/tafs/video", "http://kiff.dev/tafs/video", "javascript:alert(1)", "https://user:pw@kiff.dev/v", "not a url"]) {
      expect(welcomeVideo(setting), String(setting)).toEqual({});
    }
  });
  it("sends an https URL as it is", () => {
    expect(welcomeVideo("https://kiff.dev/tafs/video")).toEqual({ video_url: "https://kiff.dev/tafs/video" });
    expect(welcomeVideo("  https://www.youtube.com/watch?v=abc123  ")).toEqual({ video_url: "https://www.youtube.com/watch?v=abc123" });
  });
});
