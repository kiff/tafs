import { afterEach, describe, expect, it } from "vitest";
import type { Requirement, Run } from "../src/cloud/agents.js";
import { definition, yourStepOn } from "../src/cloud/tafdef.js";
import { requestOf, summaryOf } from "../src/cloud/work.js";
import { linkHost, vendorReach, yourStepsOf } from "../src/cloud/yourstep.js";
import { LANGUAGES, wordsIn } from "../src/cloud/words/index.js";
import { jobById } from "../src/jobs.js";

const NOW = new Date("2026-11-11T17:00:00Z");
const VENDORS = ["app.example.com", "app.stripe.com"];
const run = (o: Partial<Run>): Run => ({
  id: "r1", agent: "taf-a", session: "s", trigger: "schedule", status: "PAUSED", cost_micro_usd: 0, turns: 2,
  created: "2026-11-11T16:00:00Z", updated: "2026-11-11T16:05:00Z", ...o,
});
const step = (o: Partial<Requirement> = {}, input: Record<string, unknown> = {}): Requirement => ({
  call_id: "c1", tool: "your_step", operation: "op-1", exception: "ho_1", expires: "2026-11-18T16:05:00Z",
  input: { what: "Approve 3 supplier payments, €4,210 in all", why: "Your bank asks you to approve transfers yourself", link: "https://app.example.com/requests/r1?token=x", ...input },
  ...o,
});

describe("Your step: a step a taf handed its owner", () => {
  it("is read from the paused run: what, why, the https link with its host apart, and tafs cloud's words", () => {
    const [s] = yourStepsOf(run({ requirements: [step()] }), "en", VENDORS);
    expect(s).toMatchObject({
      handoff: "ho_1", run: "r1", what: "Approve 3 supplier payments, €4,210 in all", why: "Your bank asks you to approve transfers yourself",
      link: "https://app.example.com/requests/r1?token=x", link_host: "app.example.com", since: "2026-11-11T16:05:00.000Z", expires: "2026-11-18T16:05:00Z",
      words: { title: "Your step", done: "Done", skip: "Skip it", open: "Open in Example", line: "Your step: Approve 3 supplier payments, €4,210 in all" },
    });
  });

  it("passes only an https link to a named host: anything else is no link, only the words", () => {
    for (const bad of ["http://app.example.com/x", "javascript:alert(1)", "intent://x#Intent;end", "https://10.0.0.1/x", "https://localhost/x", "https://u:p@app.example.com/", "", 42]) {
      expect(linkHost(bad), String(bad)).toBeUndefined();
      const [s] = yourStepsOf(run({ requirements: [step({}, { link: bad })] }), "en", VENDORS);
      expect(s!.link).toBeUndefined();
      expect(s!.link_host).toBeUndefined();
      expect(s!.words.open).toBeUndefined();
    }
  });

  it("is only an open step on a paused run: answered, another tool's hold, or a run that went on has none", () => {
    expect(yourStepsOf(run({ requirements: [step({ answered: "2026-11-11T16:30:00Z" })] }))).toEqual([]);
    expect(yourStepsOf(run({ requirements: [step({ tool: "stripe_refund" })] }))).toEqual([]);
    expect(yourStepsOf(run({ status: "RUNNING", requirements: [step()] }))).toEqual([]);
  });

  it("reads as the owner's to do on the request and the summary, never as an approval", () => {
    const paused = run({ requirements: [step()] });
    expect(requestOf(paused, "UTC", NOW)).toMatchObject({ state: "waiting", line: "Your step: Approve 3 supplier payments, €4,210 in all", your_step: { handoff: "ho_1" } });
    expect(summaryOf([paused], false, "UTC", NOW)).toMatchObject({ status: "needs_you", line: "Your step: Approve 3 supplier payments, €4,210 in all", needs_you: 1 });
    // A call KIFF holds still waits for an approval, and carries no step.
    const held = run({ requirements: [step({ tool: "stripe_refund", exception: "exc_1" })] });
    expect(requestOf(held, "UTC", NOW)).toMatchObject({ state: "waiting", line: "Waiting for your approval" });
    expect(requestOf(held, "UTC", NOW).your_step).toBeUndefined();
  });

  it("has its words in every language, the vendor and the step written in", () => {
    for (const lang of LANGUAGES) {
      const w = wordsIn(lang);
      for (const k of ["your_step_title", "your_step_done", "your_step_skip"] as const) expect(w[k], `${lang} ${k}`).toBeTruthy();
      expect(w.your_step_open("example.com"), lang).toContain("example.com");
      expect(w.your_step_reminder("Approve it"), lang).toContain("Approve it");
      expect(w.your_step_line("Approve it"), lang).toContain("Approve it");
      if (lang !== "en") expect(w.your_step_skip, lang).not.toBe("Skip it");
    }
    expect(wordsIn("en").your_step_reminder("Approve it")).toBe("Your step is still waiting: Approve it");
    expect(yourStepsOf(run({ requirements: [step()] }), "fr", VENDORS)[0]!.words).toMatchObject({ title: "Votre étape", done: "Terminé", skip: "Passer", open: "Ouvrir dans Example" });
  });

  it("names the vendor on the button by its name, and dates the step from KIFF's hand-off, not the run's start", () => {
    const [s] = yourStepsOf(run({ created: "2026-11-11T09:00:00Z", requirements: [step({ expires: "2026-11-18T16:30:00Z" }, { link: "https://app.stripe.com/payments/1" })] }), "en", VENDORS);
    expect(s!.words.open).toBe("Open in Stripe");
    expect(s!.since).toBe("2026-11-11T16:30:00.000Z");
    // An expiry that can't be read: the run's start.
    expect(yourStepsOf(run({ requirements: [step({ expires: "" })] }))[0]!.since).toBe("2026-11-11T16:00:00Z");
  });
});

describe("the your_step tool on a taf's definition", () => {
  const before = process.env.TAFS_YOUR_STEP;
  afterEach(() => { if (before === undefined) delete process.env.TAFS_YOUR_STEP; else process.env.TAFS_YOUR_STEP = before; });

  it("is off until TAFS_YOUR_STEP=on, so a definition is as before until kiff/agents offers it", () => {
    delete process.env.TAFS_YOUR_STEP;
    expect(yourStepOn()).toBe(false);
    expect(definition("t_1", jobById("refunds")).your_step).toBeUndefined();
    process.env.TAFS_YOUR_STEP = "on";
    expect(definition("t_1", jobById("refunds")).your_step).toBe(true);
  });
});

describe("a step's link and the taf's own vendors", () => {
  const conns = [
    { agent: "agent-a", vendor: "Stripe", kind: "vendor_sign_in" as const, server: "https://mcp.stripe.com/mcp", status: "approved" },
    { agent: "agent-a", vendor: "Desk", kind: "key" as const, api: { url: "https://api.example.com/v2" }, status: "approved" },
    { agent: "agent-a", vendor: "Custom", kind: "provider" as const, server: "https://abc.execute-api.eu-west-1.amazonaws.com/mcp", status: "approved" },
    { agent: "agent-b", vendor: "Other", kind: "key" as const, api: { url: "https://api.other.com" }, status: "approved" },
  ];

  it("are the hosts the taf's connections reach and their vendor's listed web hosts, never a cloud function's or another taf's", () => {
    expect(vendorReach(conns, "agent-a").sort()).toEqual(["api.example.com", "dashboard.stripe.com", "mcp.stripe.com"]);
    expect(vendorReach(conns, "agent-c")).toEqual([]);
  });

  it("vouch only for the owner's own host on a platform that gives each customer one", () => {
    const reach = vendorReach([{ agent: "agent-a", vendor: "Shop", kind: "vendor_sign_in", server: "https://acme.myshopify.com/api/mcp", status: "approved" }], "agent-a");
    const of = (link: string) => yourStepsOf(run({ requirements: [step({}, { link })] }), "en", reach)[0]!;
    expect(of("https://acme.myshopify.com/admin/orders/1").link_host).toBe("acme.myshopify.com");
    expect(of("https://evil.myshopify.com/login").link).toBeUndefined();
    expect(of("https://myshopify.com/").link).toBeUndefined();
  });

  it("vouch for nothing beside the connection on a shared host or a two-part suffix", () => {
    const reach = vendorReach([
      { agent: "agent-a", vendor: "Good", kind: "provider", server: "https://good.netlify.app/mcp", status: "approved" },
      { agent: "agent-a", vendor: "Vendor", kind: "key", api: { url: "https://api.vendor.co.uk/v1" }, status: "approved" },
    ], "agent-a");
    const of = (link: string) => yourStepsOf(run({ requirements: [step({}, { link })] }), "en", reach)[0]!;
    expect(of("https://good.netlify.app/approve").link_host).toBe("good.netlify.app");
    expect(of("https://api.vendor.co.uk/x").link_host).toBe("api.vendor.co.uk");
    for (const evil of ["https://evil.netlify.app/login", "https://bank-verify.netlify.app/", "https://evil.co.uk/x", "https://vendor.co.uk/x"]) {
      expect(of(evil).link, evil).toBeUndefined();
    }
  });

  it("show the link only on one of them: a look-alike host a crafted message slipped in is words only", () => {
    const reach = vendorReach(conns, "agent-a");
    const of = (link: string) => yourStepsOf(run({ requirements: [step({}, { link })] }), "en", reach)[0]!;
    expect(of("https://dashboard.stripe.com/payments/1")).toMatchObject({ link_host: "dashboard.stripe.com", words: { open: "Open in Stripe" } });
    // The listed web host only: another host on Stripe's site isn't vouched for.
    expect(of("https://checkout.stripe.com/x").link).toBeUndefined();
    // api.example.com vouches for itself and hosts under it, not for its siblings: example.com isn't single-tenant.
    expect(of("https://eu.api.example.com/x").link_host).toBe("eu.api.example.com");
    for (const lookalike of ["https://stripe-secure.example/login", "https://stripe.com.evil.example/x", "https://app.other.com/x", "https://abc.execute-api.eu-west-1.amazonaws.com/x", "https://www.example.com/x"]) {
      const s = of(lookalike);
      expect(s.link, lookalike).toBeUndefined();
      expect(s.words.open, lookalike).toBeUndefined();
      expect(s.what).toBe("Approve 3 supplier payments, €4,210 in all");
    }
    // No vendors read (planque down, or a view that doesn't read them): no link, ever.
    expect(yourStepsOf(run({ requirements: [step()] }))[0]!.link).toBeUndefined();
  });
});
