import { describe, expect, it } from "vitest";
import { attachWhys, failedStep, foldRetried, stepWords, Steps, vendorName, whyOf } from "../src/cloud/steps.js";

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

describe("why a step failed, in the owner's words (kiff/tafs#476, gap 9)", () => {
  it("keeps the first line's words and drops a service's prefix, ids, codes and hashes", () => {
    expect(whyOf("planque answered 403: Google Sheets refused the write: the sheet was renamed.\nstack…")).toBe("Google Sheets refused the write: the sheet was renamed.");
    expect(whyOf("KIFF answered 409: the grant grt_Abc123xyz is revoked (code grant_revoked)")).toBe("the grant it is revoked");
    expect(whyOf("Stripe refused ch_1AbCdEfGhIjKlMnOp: the charge is already refunded (grant grt_Abc123xyz, code already_refunded).")).toBe("Stripe refused it: the charge is already refunded.");
    expect(whyOf("ord_secret not found")).toBe("not found");
    expect(whyOf("  \n\nThe customer has no email on file.")).toBe("The customer has no email on file.");
    expect(whyOf("0123456789abcdef0123 ch_1AbCdEfGhIjKlMnOp")).toBeUndefined();
    expect(whyOf("ch_1AbCdEfGhIjKlMnOp")).toBeUndefined();
    expect(whyOf("")).toBeUndefined();
    expect(whyOf(undefined)).toBeUndefined();
    expect(whyOf({ error: "x" })).toBeUndefined();
    const long = whyOf(`The provider said no because ${"the limit is reached and ".repeat(20)}`)!;
    expect(long.length).toBeLessThanOrEqual(160);
    expect(long.endsWith("…")).toBe(true);
    expect(long).not.toMatch(/\s…$/);
  });

  it("leaves nothing that could be a secret or a person's own in the line (qa-kiff's probe): a table", () => {
    const cases: Array<[string, string | undefined]> = [
      ["Stripe refused the key sk_live_0123456789abcdefXYZ: no such account", "Stripe refused the key it: no such account"],
      ["Unauthorized: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.abc123DEF456 was rejected", "Unauthorized: it was rejected"],
      ["Basic dXNlcjpwYXNzd29yZA== isn't accepted here", "it isn't accepted here"],
      ["The sheet at https://docs.example.com/sheets/1AbC?token=s3cr3tT0k3n was not found", "The sheet at it was not found"],
      ["Slack answered 401 for xoxb-123456789012-abcdefghijkl", "Slack answered 401 for it"],
      ["AWS refused the key id AKIAIOSFODNN7EXAMPLE", "AWS refused the key id it"],
      ["No order 123e4567-e89b-12d3-a456-426614174000 on this account", "No order it on this account"],
      ["Operation op-7f3a9c2e-4b1d-4e8f-9a2b-1c3d5e7f9a1b failed upstream", "Operation it failed upstream"],
      ["The card 4242 4242 4242 4242 was declined", "The card it was declined"],
      ["The customer ana.lopez+shop@example.co.uk has no open invoice", "The customer it has no open invoice"],
      ["planque answered 502: the vendor behind planque timed out", "the vendor behind timed out"],
      ["The charge ch_1AbCdEfGhIjKlMnOp is already refunded", "The charge it is already refunded"],
      ["Rate limited: try again in 30 seconds", "Rate limited: try again in 30 seconds"],
      ["sk_live_0123456789abcdefXYZ", undefined],
    ];
    for (const [text, want] of cases) expect(whyOf(text), text).toBe(want);
  });

  it("marks a failed step, with its reason only when there is one", () => {
    expect(failedStep("The sheet was renamed.")).toEqual({ failed: true, why: "The sheet was renamed." });
    expect(failedStep("grt_Abc123xyz")).toEqual({ failed: true });
  });

  it("gives the thread's failed steps their reasons, each to the first failed step of its tool, in order", () => {
    const whys = [{ call: "c1", tool: "get_order", why: "Not found." }, { call: "c3", tool: "get_order", why: "Timed out." }];
    const steps = [{ tool: "get_order", error: true as const }, { tool: "get_order" }, { tool: "get_order", error: true as const }, { tool: "create_refund", error: true as const }];
    expect(attachWhys(steps, whys)).toEqual([
      { tool: "get_order", error: true, failed: true, why: "Not found." },
      { tool: "get_order" },
      { tool: "get_order", error: true, failed: true, why: "Timed out." },
      { tool: "create_refund", error: true, failed: true },
    ]);
    expect(whys).toEqual([]); // consumed: a run's parts share one list
  });

  it("folds a failed call into a later call of the same tool: Coach's retry is what the owner sees", () => {
    const steps = [
      { tool: "version_install", error: true as const },
      { tool: "get_order" },
      { tool: "version_install" },
      { tool: "create_refund", error: true as const },
    ];
    // The retried install is done; the refund's failure stands, since nothing retried it.
    expect(foldRetried(steps)).toEqual([{ tool: "get_order" }, { tool: "version_install" }, { tool: "create_refund", error: true }]);
    // A retry that failed again: one failure, the last.
    expect(foldRetried([{ tool: "a", error: true as const }, { tool: "a", error: true as const }])).toEqual([{ tool: "a", error: true }]);
    // Nothing to fold.
    expect(foldRetried([{ tool: "a" }, { tool: "b", error: true as const }])).toEqual([{ tool: "a" }, { tool: "b", error: true }]);
  });
});
