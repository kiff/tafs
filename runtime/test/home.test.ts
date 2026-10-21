import { describe, expect, it } from "vitest";
import { type Mandate, summary } from "../src/cloud/home.js";

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
