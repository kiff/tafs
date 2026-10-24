import { describe, expect, it } from "vitest";
import { Steps, vendorName } from "../src/cloud/steps.js";

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
    expect(s.call("show", {})).toBeUndefined();
    expect(s.call("create_refund", null)).toBeUndefined();
  });
});
