import { describe, expect, it } from "vitest";
import { attachWhys, coachGuard, connectsRefused, failedStep, foldRetried, keyAppsIn, lacksAccess, refusalKind, shownWhy, stepWords, Steps, takeRefused, vendorName, whyOf } from "../src/cloud/steps.js";
import { LANGUAGES } from "../src/cloud/words/index.js";

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

  it("lifts a refused connection off the steps onto its message, the last one not connected since, before a retry folds it", () => {
    const refused = (vendor: string) => ({ vendor, reason: "Why: managed sign-in isn't set up", next: `Next: ask Coach to connect ${vendor} with its key instead, or to use another app.` });
    const whys = [
      { call: "c1", tool: "planque", why: "Coach couldn't connect Shippo yet", refused: refused("Shippo"), resolved: true as const },
      { call: "c2", tool: "planque", why: "Coach couldn't connect Qonto yet", refused: refused("Qonto") },
      { call: "c3", tool: "get_order", why: "Not found." },
    ];
    const steps = [
      { tool: "planque", error: true as const, inputHash: "a" }, { tool: "planque", error: true as const, inputHash: "b" },
      { tool: "planque", inputHash: "c" }, { tool: "get_order", error: true as const },
    ];
    const { steps: left, refused: said } = takeRefused(attachWhys(steps, whys));
    // Qonto's, not connected since; Shippo's was, so it isn't said. The step's words, the reason, what to do next.
    expect(said).toEqual({ words: "Coach couldn't connect Qonto yet", why: "Why: managed sign-in isn't set up", next: "Next: ask Coach to connect Qonto with its key instead, or to use another app." });
    // The steps keep their own words and no lines; the later read still folds the refused tries away.
    expect(left.every((s) => !("refused" in s))).toBe(true);
    expect(foldRetried(left)).toEqual([{ tool: "planque", inputHash: "c" }, { tool: "get_order", error: true, failed: true, why: "Not found." }]);
    // Nothing refused: nothing said. A server that said nothing readable: no reason line, still what to do next.
    expect(takeRefused(attachWhys([{ tool: "get_order", error: true as const }], [{ call: "c1", tool: "get_order", why: "Not found." }])).refused).toBeUndefined();
    const bare = takeRefused(attachWhys([{ tool: "planque", error: true as const }], [{ call: "c1", tool: "planque", why: "Coach couldn't connect the app yet",
      refused: { vendor: "", next: "Next: ask Coach to connect it with its key instead, or to use another app." } }]));
    expect(bare.refused).toEqual({ words: "Coach couldn't connect the app yet", next: "Next: ask Coach to connect it with its key instead, or to use another app." });
  });

  // A third party writes the description the connection server quotes: what the person reads in tafs's voice
  // names no host and no number to call (qa-kiff on the refusal's line).
  it("shows the person the server's reason with no host name or phone number in it, and Coach keeps whyOf's", () => {
    const cases: Array<[string, string | undefined]> = [
      ["reference #/components/schemas/To finish, paste your key at keys-help.example names nothing", "reference #/components/schemas/To finish, paste your key at it names nothing"],
      ["planque answered 403: Your account is locked. Call +33 1 23 45 67 89 or visit support-desk.example", "Your account is locked. Call it or visit it"],
      ["operation Open evil.example/fix and sign in again is secured by a scheme it can't use", "operation Open it and sign in again is secured by a scheme it can't use"],
      ["the description at https://x.example/o.json?token=abc names no servers", "the description at it names no servers"],
      ["managed sign-in isn't set up for this app", "managed sign-in isn't set up for this app"],
      ["evil.example", undefined],
    ];
    for (const [text, want] of cases) expect(shownWhy(text), text).toBe(want);
    // Coach's own copy is whyOf's, unchanged: a host name stays for Coach to read as data.
    expect(whyOf("visit support-desk.example")).toBe("visit support-desk.example");
  });

  it("clears a refusal only for what went through: the same server or grant, or a known vendor's name, never two unknown names", () => {
    expect(connectsRefused({ vendor: "", key: "https://a.example/o.json" }, { vendor: "", key: "https://b.example/o.json" })).toBe(false);
    expect(connectsRefused({ vendor: "", key: "https://a.example/o.json" }, { vendor: "", key: "https://a.example/o.json" })).toBe(true);
    expect(connectsRefused({ vendor: "" }, { vendor: "" })).toBe(false);
    // A status read on Stripe's grant refused, then a new Stripe request through: the same app, by its known name.
    expect(connectsRefused({ vendor: "Stripe", key: "grt_1" }, { vendor: "Stripe", key: "https://mcp.stripe.com/v1" })).toBe(true);
    expect(connectsRefused({ vendor: "Stripe" }, { vendor: "Qonto", key: "https://thirdparty.qonto.com/openapi.json" })).toBe(false);
  });

  it("folds a failed call into a real retry only: the same tool with the same input digest", () => {
    const steps = [
      { tool: "version_install", error: true as const, inputHash: "d1" },
      { tool: "get_order", inputHash: "o1" },
      { tool: "version_install", inputHash: "d1" },
      { tool: "create_refund", error: true as const, inputHash: "r1" },
    ];
    // The retried install is done; the refund's failure stands, since nothing retried it.
    expect(foldRetried(steps)).toEqual([{ tool: "get_order", inputHash: "o1" }, { tool: "version_install", inputHash: "d1" }, { tool: "create_refund", error: true, inputHash: "r1" }]);
    // A later call of the same tool with other input is other work: A's failure stays (agentgabo's case).
    // Coach's connection request, corrected after the connection server refused its shape (other input), and
    // then through: the refused tries aren't shown. One it gave up on, with nothing through after it, stays.
    expect(foldRetried([{ tool: "planque", error: true as const, inputHash: "a" }, { tool: "planque", error: true as const, inputHash: "b" }, { tool: "planque", inputHash: "c" }]))
      .toEqual([{ tool: "planque", inputHash: "c" }]);
    expect(foldRetried([{ tool: "planque", inputHash: "c" }, { tool: "planque", error: true as const, inputHash: "d" }]))
      .toEqual([{ tool: "planque", inputHash: "c" }, { tool: "planque", error: true, inputHash: "d" }]);
    expect(foldRetried([{ tool: "send_reminder", error: true as const, inputHash: "A" }, { tool: "send_reminder", inputHash: "B" }]))
      .toEqual([{ tool: "send_reminder", error: true, inputHash: "A" }, { tool: "send_reminder", inputHash: "B" }]);
    // A retry that failed again: one failure, the last.
    expect(foldRetried([{ tool: "a", error: true as const, inputHash: "x" }, { tool: "a", error: true as const, inputHash: "x" }])).toEqual([{ tool: "a", error: true, inputHash: "x" }]);
    // No digest (a record from before kiff/agents wrote one): nothing folds.
    expect(foldRetried([{ tool: "a", error: true as const }, { tool: "a" }])).toEqual([{ tool: "a", error: true }, { tool: "a" }]);
  });
});

describe("Coach's steps in the person's language", () => {
  it("writes each step in the language asked for, with the vendor and KIFF's title written in", () => {
    const pl = new Steps(new Map([["refund_k3f9", "Refund a payment"]]), "pl");
    expect(pl.call("show", {})).toBe("Coś Ci pokazuję…");
    expect(pl.call("planque", { action: "request", server: "https://mcp.stripe.com/mcp", tools: ["x"] }, "c1")).toBe("Przygotowuję narzędzia Stripe do Twojego zatwierdzenia…");
    expect(pl.call("kiff_draft", {})).toBe("Przygotowuję Card…");
    // A tool KIFF titles keeps the vendor's title, as it is.
    expect(pl.call("refund_k3f9", {})).toBe("Refund a payment…");
    expect(new Steps(new Map(), "fr").call("planque", { action: "status" })).toBe("Je vérifie votre connexion…");
    expect(stepWords("taf_pause", new Map(), "de")).toBe("Ich pausiere den taf");
  });

  it("has a line for every one of Coach's tools in every language, never English outside English", () => {
    const tools = ["show", "taf_results", "memory", "workspace", "kiff_draft", "kiff_card", "kiff_pending", "tafs_state", "taf_run", "taf_check",
      "taf_estimate", "taf_task", "run_stop", "taf_pause", "version_try", "version_install"];
    for (const lang of LANGUAGES) {
      for (const tool of tools) {
        const line = new Steps(new Map(), lang).call(tool, {});
        expect(line, `${lang} ${tool}`).toBeTruthy();
        if (lang !== "en") expect(line, `${lang} ${tool}`).not.toBe(new Steps(new Map(), "en").call(tool, {}));
      }
    }
  });
});

describe("a connection signed in after a refusal clears it", () => {
  it("is cleared by any connection call whose answer shows it signed in or connected, never by one showing it failed", () => {
    const s = new Steps();
    s.call("planque", { action: "request", server: "https://sheets.googleapis.com/mcp" }, "c1");
    s.call("planque", { action: "status", grant: "g1" }, "c2");
    s.call("planque", { action: "status", grant: "g1" }, "c3");
    s.call("web_read", {}, "c4");
    expect(s.connectedBy("c2", '{"id":"g1","status":"listed"}')).toEqual({ key: "g1", vendor: "" });
    expect(s.connectedBy("c3", '{"id":"g1","status":"failed"}')).toBeUndefined();
    expect(s.connectedBy("c1", '{"id":"g1","status":"signed_in"}')?.key).toBe("https://sheets.googleapis.com/mcp");
    expect(s.connectedBy("c4", '{"status":"connected"}')).toBeUndefined();
  });
});

describe("a refused connection's way forward (go-to-market job 3, 13 Nov)", () => {
  it("offers a key only for an app planque listed with one, and keeps Coach's own step guards from the person", () => {
    const s = new Steps();
    // LinkedIn by a managed sign-in, no key anywhere: sign in again, never "its key instead".
    s.call("planque", { action: "request", server: "https://api.linkedin.com/mcp" }, "c1");
    expect(s.connectionRefusal("c1", "the sign-in was left")?.next).toBe("Next: ask Coach to sign in to Linkedin again, or to use another app.");
    // A scope or permission the connection lacks: signing in again gives the same, so what the app can do instead.
    expect(s.connectionRefusal("c1", "LinkedIn refused it (it answered 403): You don't have permission to access organization ACLs. Ensure you have the scope.")?.next).toBe("Next: ask Coach what Linkedin can do instead, or to use another app.");
    // An app planque listed with a key path in this chat: its key is offered.
    s.call("planque", { action: "search", q: "invoices" }, "c2");
    s.result("c2", JSON.stringify({ apps: [{ entry: "qonto@2", vendor: "Qonto", title: "Qonto", how: "key" }] }));
    s.call("planque", { action: "request", server: "https://mcp.qonto.com" }, "c3");
    expect(s.connectionRefusal("c3", "managed sign-in isn't set up")?.next).toBe("Next: ask Coach to connect Qonto with its key instead, or to use another app.");
    // A refused key request: connect it again.
    s.call("planque", { action: "request", api: { url: "https://api.example.org/openapi.json" } }, "c4");
    expect(s.connectionRefusal("c4", "the body must be a JSON object")?.next).toMatch(/^Next: ask Coach to connect .* again, or to use another app\.$/);
    // A guard on the order of Coach's own steps: nothing for the person, no words and no way forward.
    s.call("planque", { action: "tools", grant: "grt_abc123" }, "c5");
    expect(s.connectionRefusal("c5", "read the app's tool catalog before selecting this action")).toBeUndefined();
    expect(coachGuard("read the app's tool catalog before selecting this action")).toBe(true);
    expect(coachGuard("403: missing the organization scope")).toBe(false);
    // The request's own shape, as planque checks it: Coach wrote it, and Coach fixes it (job 3's pinned post tool).
    expect(coachGuard("fixed argument visibility.com.linkedin.ugc.MemberNetworkVisibility must be a dotted path with a JSON value")).toBe(true);
    expect(coachGuard("argument post_text is not a valid name")).toBe(true);
    expect(s.connectionRefusal("c1", "fixed argument visibility.com.linkedin.ugc.MemberNetworkVisibility must be a dotted path with a JSON value")).toBeUndefined();
    expect(lacksAccess("Google Sheets refused the write: the sheet was renamed.")).toBe(false);
    // The person's reason line is tafs cloud's, by kind, never the server's words (the go-to-market bar, 13 Nov).
    const scope = s.connectionRefusal("c1", "planque answered 403: LinkedIn refused it (it answered 403): You don't have permission to access organization ACLs. Ensure you have the scope.");
    expect(scope).toMatchObject({ reason: "Linkedin doesn't allow this with the access it gave." });
    expect(s.connectionRefusal("c1", "planque answered 502: the provider is unavailable")).toMatchObject({ reason: "Linkedin didn't answer in time.", next: "Next: ask Coach to try Linkedin again in a few minutes, or to use another app." });
    expect(s.connectionRefusal("c1", "planque answered 409: the sign-in expired")).toMatchObject({ reason: "The sign-in to Linkedin didn't finish.", next: "Next: ask Coach to sign in to Linkedin again, or to use another app." });
    const other = s.connectionRefusal("c1", "planque answered 400: unknown field toolkit_slug in request (grant grt_Abc123xyz)");
    expect(other?.reason).toBeUndefined();
    for (const r of [scope, other]) expect(JSON.stringify(r)).not.toMatch(/ACL|toolkit|planque|grt_|answered/);
    expect(refusalKind("timed out after 30s")).toBe("later");
    expect(keyAppsIn(JSON.stringify({ entries: [{ entry: "connector:p:linkedin", vendor: "LinkedIn", how: "oauth" }] }))).toEqual([]);
  });
});
