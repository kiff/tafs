import { describe, expect, it } from "vitest";
import { type Mandate, cardView, isLive, summary } from "../src/cloud/home.js";

describe("a taf's Card summary", () => {
  // The owner's Card tab read "Read Stripe API data" twice (kiff/tafs#138, item 53).
  it("tells apart two tools titled alike, and leaves the others as they are", () => {
    const reads: Mandate = {
      id: "c1", subject: "refunds-bxkwf7vi", active: true,
      grants: [
        { action: "bxkwf7vi_list_accounts", title: "List available accounts or orgs" },
        { action: "bxkwf7vi_list_charges", title: "Read Stripe API data" },
        { action: "bxkwf7vi_get_charge", title: "Read Stripe API data" },
      ],
      aggregates: [],
    };
    const refunds: Mandate = { id: "c2", subject: "refunds-bxkwf7vi", active: true, grants: [{ action: "create_refund", title: null }], aggregates: [] };
    const s = summary([reads, refunds]);
    expect(s.rules.map((r) => r.label)).toEqual([
      "List available accounts or orgs",
      "Read Stripe API data · list charges",
      "Read Stripe API data · get charge",
      "create_refund",
    ]);
    expect(s.tools).toEqual(s.rules.map((r) => r.label));
  });
});

describe("each Card on its own (kiff/tafs#211)", () => {
  const refunds: Mandate = {
    id: "live", subject: "refunds-bxkwf7vi", active: true, environment: "production",
    grants: [{ action: "create_refund", title: "Refund a payment", per_action_max: [{ quantity: "amount", max: 5000, unit: "EUR", scale: 2 }] }],
    aggregates: [{ quantity: "sum(amount)", limit: 80000, window: "1d", unit: "EUR", scale: 2 }],
    limits: [{ quantity: "sum(amount)", window: "1d", limit: 80000, used: 18000, status: "ok", unit: "EUR", scale: 2 }],
  };
  const reads: Mandate = {
    id: "reads", subject: "refunds-bxkwf7vi", active: true,
    grants: [{ action: "list_charges", title: "List charges" }, { action: "get_charge", title: "Look up a charge" }],
    aggregates: [{ quantity: "count", limit: 200, window: "1d" }],
    limits: [{ quantity: "count", window: "1d", limit: 200, used: 20, status: "ok" }],
  };
  const test: Mandate = { ...refunds, id: "test", environment: "test" };

  it("gives a Card its money total, what it has used, and its environment", () => {
    const v = cardView(refunds);
    expect(v).toMatchObject({ id: "live", title: "Refund a payment", environment: "production", live: true, active: true, calls: null });
    expect(v.total).toEqual({ limit: { minor: 80000, unit: "EUR", scale: 2 }, used: { minor: 18000, unit: "EUR", scale: 2 }, window: "day" });
    expect(v.rules.map((r) => r.label)).toEqual(["Refund a payment"]);
  });

  it("gives a Card with no money total its calls and what it has used", () => {
    const v = cardView(reads);
    expect(v.total).toBeNull();
    expect(v.calls).toEqual({ limit: 200, used: 20, window: "day" });
    expect(v.live).toBe(true);
    expect(v.title).toBe("List charges");
  });

  it("never reads use KIFF couldn't read as zero", () => {
    const unread: Mandate = { ...refunds, limits: [{ quantity: "sum(amount)", window: "1d", limit: 80000, status: "unavailable" }] };
    expect(cardView(unread).total?.used).toBeNull();
    const noLine: Mandate = { ...reads, limits: [] };
    expect(cardView(noLine).calls).toEqual({ limit: 200, used: null, window: "day" });
  });

  it("puts the live Cards first, then a test Card", () => {
    const s = summary([test, reads, refunds]);
    expect(s.cards.map((c) => [c.id, c.live])).toEqual([["reads", true], ["live", true], ["test", false]]);
  });

  it("takes the Card's own title when KIFF gives one, else its main tool's", () => {
    expect(cardView({ ...refunds, title: "  Refunds on Stripe " }).title).toBe("Refunds on Stripe");
    expect(cardView({ ...refunds, title: "" }).title).toBe("Refund a payment");
    expect(cardView({ ...refunds, title: null }).title).toBe("Refund a payment");
  });

  it("reads production, live and no environment as live, any other as not", () => {
    expect([isLive("production"), isLive("Live"), isLive(""), isLive(null), isLive("test"), isLive("staging")]).toEqual([true, true, true, true, false, false]);
  });
});
