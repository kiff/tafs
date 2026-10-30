import { describe, expect, it } from "vitest";
import { stepWords, Steps, vendorName } from "../src/cloud/steps.js";

describe("what Coach is doing, in a few words (kiff/tafs#214)", () => {
  it("names a vendor from its server's host, and none from a cloud's own URL", () => {
    expect(vendorName("https://mcp.stripe.com/v1")).toBe("Stripe");
    expect(vendorName("https://mcp.linear.app/sse")).toBe("Linear");
    expect(vendorName("https://abc123.lambda-url.eu-west-1.on.aws/mcp")).toBe("");
    expect(vendorName("https://10.0.0.1/mcp")).toBe("");
    expect(vendorName("not a url")).toBe("");
    expect(vendorName(undefined)).toBe("");
  });

  it("says each step, and the vendor once a request's grant is known", () => {
    const s = new Steps();
    expect(s.call("planque", { action: "request", server: "https://mcp.stripe.com" }, "c1")).toBe("Preparing the connection to Stripe…");
    s.result("c1", "grant grt_Stripe01 pending");
    expect(s.call("planque", { action: "status", grant: "grt_Stripe01" })).toBe("Checking your Stripe connection…");
    expect(s.call("planque", { action: "read", grant: "grt_Stripe01" })).toBe("Reading from your Stripe account…");
    expect(s.call("planque", { action: "status", grant: "grt_Unknown1" })).toBe("Checking your connection…");
    expect(s.call("planque", { action: "request", server: "https://mcp.stripe.com", tools: [{}] })).toBe("Preparing Stripe's tools for your approval…");
    expect(s.call("planque", { action: "grants" })).toBe("Looking up your connections…");
    expect(s.call("kiff_draft", { action: "submit" })).toBe("Drafting the Card…");
    expect(s.call("kiff_card", { agent: "a" })).toBe("Reading the taf's Card…");
    expect(s.call("taf_task", {})).toBe("Handing the task over…");
    expect(s.call("show", {})).toBe("Showing you a card…");
    expect(s.call("create_refund", null)).toBeUndefined();
  });

  it("says a taf's tool through KIFF by KIFF's title for it, and an own tool by what it does; never a tool's name", () => {
    const titles = new Map([["list_charges_ijkp", "List your charges"], ["refund_charge", "Refund a charge"]]);
    const s = new Steps(titles);
    expect(s.call("list_charges_ijkp", { limit: 10 })).toBe("List your charges…");
    expect(s.call("refund_charge", { charge: "ch_1" })).toBe("Refund a charge…");
    expect(s.call("get_charge_ijkp", {})).toBeUndefined(); // no title: no words, never its name
    expect(s.call("memory", {})).toBe("Going through its notes…");
    expect(s.call("workspace", {})).toBe("Working in its workspace…");
    // A finished run's steps: the same words, without the "…".
    expect(stepWords("list_charges_ijkp", titles)).toBe("List your charges");
    expect(stepWords("planque")).toBe("Working on the connection");
    expect(stepWords("kiff_draft")).toBe("Drafting the Card");
    expect(stepWords("show")).toBe("Showing you a card");
    expect(stepWords("get_charge_ijkp", titles)).toBeUndefined();
  });
});
