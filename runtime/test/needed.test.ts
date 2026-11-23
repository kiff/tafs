import { describe, expect, it } from "vitest";
import { appVendors, misplaced, neededBy, type NeedTaf } from "../src/cloud/needed.js";
import type { GrantView } from "../src/cloud/taf-setup.js";
import type { Mandate } from "../src/cloud/home.js";

// The owner's 105 retest: Stripe was connected under "Unanswered emails", a Gmail taf, because the phone took the
// only taf there was. Which tafs need an app is decided here, once, for every app (needed.ts).
const desk: NeedTaf = { id: "taf-desk", name: "Unanswered emails", kiffAgent: "desk", recipe: "unanswered-emails" };
const refunds: NeedTaf = { id: "taf-refunds", name: "Refunds", kiffAgent: "refunds", recipe: "refunds" };
const chargebacks: NeedTaf = { id: "taf-chargebacks", name: "Chargebacks", kiffAgent: "chargebacks" };
const recipes = [
  { id: "unanswered-emails", connections: [["gmail", "outlook"]] },
  { id: "refunds", connections: [["gorgias", "gmail"], ["stripe"]] },
];
const stripe = { entry: "stripe@3", vendor: "Stripe", title: "Stripe" };
const gmail = { entry: "connector:p:gmail", vendor: "gmail", title: "Gmail" };
const grant = (id: string, agent: string, extra: Partial<GrantView> = {}): GrantView => ({ id, status: "connected", vendor: "Stripe", request: { agent }, tools: ["refund_charge_k3"], ...extra });
const card = (subject: string, action: string, extra: Partial<Mandate> = {}) => ({ id: `m-${subject}`, subject, active: true, grants: [{ action }], ...extra }) as unknown as Mandate;

describe("which tafs need an app (needed.ts)", () => {
  it("an app's vendors are its entry's slug, its vendor and its title, folded as recipes name them", () => {
    expect([...appVendors(stripe)].sort()).toEqual(["stripe"]);
    expect([...appVendors({ entry: "connector:composio:google_calendar", vendor: "Google Calendar" })]).toEqual(["googlecalendar"]);
    expect([...appVendors({ entry: "qonto-invoicing@2", vendor: "Qonto" })].sort()).toEqual(["qonto", "qontoinvoicing"]);
  });

  // An app no taf needs is needed by none, so a connection for any taf is refused.
  it("by recipe: a taf whose recipe names the app; never a taf whose recipe doesn't (the owner's Stripe under a Gmail taf)", () => {
    expect(neededBy(stripe, [desk, refunds], recipes)).toEqual([{ taf: "taf-refunds", name: "Refunds" }]);
    expect(neededBy(gmail, [desk, refunds], recipes)).toEqual([{ taf: "taf-refunds", name: "Refunds" }, { taf: "taf-desk", name: "Unanswered emails" }]);
    expect(neededBy({ entry: "typeform@1", vendor: "Typeform" }, [desk, refunds], recipes)).toEqual([]);
  });

  // An app two tafs need is needed by each, in name order.
  it("by Card: a taf whose Card in force has a rule on one of the app's connections' actions, whichever taf asked for it", () => {
    const grants = [grant("g1", "refunds")];
    expect(neededBy(stripe, [desk, refunds, chargebacks], recipes, grants, [card("chargebacks", "refund_charge_k3")]))
      .toEqual([{ taf: "taf-chargebacks", name: "Chargebacks" }, { taf: "taf-refunds", name: "Refunds" }]);
    // A Card revoked, inactive, or on another action doesn't count.
    for (const c of [card("chargebacks", "refund_charge_k3", { active: false }), card("chargebacks", "refund_charge_k3", { revoked_at: "2026-11-15T00:00:00Z" }), card("chargebacks", "list_charges_k3")]) {
      expect(neededBy(stripe, [chargebacks], recipes, grants, [c])).toEqual([]);
    }
    // A disconnected or failed grant's actions don't count, nor a grant of another app.
    expect(neededBy(stripe, [chargebacks], recipes, [grant("g1", "refunds", { status: "disconnected" })], [card("chargebacks", "refund_charge_k3")])).toEqual([]);
    expect(neededBy(stripe, [chargebacks], recipes, [grant("g1", "refunds", { vendor: "Gmail" })], [card("chargebacks", "refund_charge_k3")])).toEqual([]);
    // A grant's catalog entry names the app too, and an unconnected one's asked tools stand for its actions (never Setup's reads).
    expect(neededBy(stripe, [chargebacks], recipes, [grant("g1", "x", { vendor: undefined, entry: "stripe@3", tools: undefined,
      request: { agent: "x", tools: [{ tool: "refund_charge", name: "refund_charge_k3" }, { tool: "list_accounts", name: "setup_k3", setup_reads: true }] } })],
    [card("chargebacks", "refund_charge_k3")])).toEqual([{ taf: "taf-chargebacks", name: "Chargebacks" }]);
    expect(neededBy(stripe, [chargebacks], recipes, [grant("g1", "x", { tools: undefined, request: { agent: "x", tools: [{ tool: "list_accounts", name: "setup_k3", setup_reads: true }] } })],
      [card("chargebacks", "setup_k3")])).toEqual([]);
  });

  // Holding a connection never counts, or the retest's Stripe under a Gmail taf could be
  // connected there again by an app that sends no proof. Without the grants and Cards, the recipes alone decide.
  it("holding a connection never makes a taf need the app: without the grants and Cards, the recipes alone decide", () => {
    expect(neededBy(stripe, [desk], recipes)).toEqual([]);
    expect(neededBy(stripe, [desk], recipes, [grant("g1", "desk")], [])).toEqual([]);
    expect(neededBy(stripe, [desk, refunds], recipes)).toEqual([{ taf: "taf-refunds", name: "Refunds" }]);
  });

  // The owner's Stripe made under "Unanswered emails" is listed, so it can be withdrawn.
  it("a live connection of the app held by a taf that doesn't need it is misplaced; one held by a taf that does isn't", () => {
    const grants = [grant("g-wrong", "desk"), grant("g-right", "refunds"), grant("g-gone", "desk", { status: "disconnected" }), grant("g-other", "someone-else")];
    const needed = neededBy(stripe, [desk, refunds], recipes, grants, []);
    expect(needed).toEqual([{ taf: "taf-refunds", name: "Refunds" }]);
    expect(misplaced(stripe, [desk, refunds], needed, grants)).toEqual([{ grant: "g-wrong", taf: "taf-desk", name: "Unanswered emails" }]);
    // Without planque's grants, nothing is called misplaced.
    expect(misplaced(stripe, [desk, refunds], needed, undefined)).toEqual([]);
  });

  it("a taf with no KIFF agent never needs an app", () => {
    expect(neededBy(stripe, [{ ...refunds, kiffAgent: "" }], recipes)).toEqual([]);
  });
});
