import { describe, expect, it } from "vitest";
import { withLine } from "../src/cloud/handler.js";
import { appNamed, Steps } from "../src/cloud/steps.js";

describe("a sign-in card's buttons", () => {
  const card = { type: "sign_in", id: "si", grant: "grt_abcdef", service: "Stripe", title: "Połącz Stripe", text: "Zaloguj się w Stripe, aby taf mógł zwracać płatności.", yes: "Zaloguj się", no: "Nie teraz", waits: "owner" };

  it("are the app's chrome, in the app's language, whatever language Coach wrote the card in", () => {
    expect(withLine(card, "fr")).toMatchObject({ yes: "Se connecter", no: "Pas maintenant" });
    expect(withLine(card, "en")).toMatchObject({ yes: "Sign in", no: "Not now" });
  });

  it("leave Coach's own words on the card as written", () => {
    expect(withLine(card, "fr")).toMatchObject({ title: "Połącz Stripe", text: card.text, service: "Stripe", grant: "grt_abcdef" });
  });

  // Go-to-market job 1 (owner's phone, 13 Nov): the card read "Sign in at" the host of the service a managed sign-in
  // goes through. The person connects an app, never the service in between: the card names the app, never a host.
  it("name the app planque named in the run, and never a host", () => {
    const managed = { ...card, service: "signin.broker.example", grant: "grt_sheets1" };
    const named = withLine(managed, "en", undefined, (g) => (g === "grt_sheets1" ? "Google Sheets" : undefined));
    expect(named).toMatchObject({ service: "Google Sheets" });
    // With no app's name from the run, any dotted service is left out: the apps say "the vendor", never the host.
    for (const host of ["signin.broker.example", "app.broker.example", "broker.example", "https://app.broker.example/x", "mcp.stripe.com"]) {
      const out = withLine({ ...card, service: host }, "en") as Record<string, unknown>;
      expect("service" in out, host).toBe(false);
      expect(JSON.stringify(out), host).not.toContain(host);
    }
    // A host planque gave as a name is never used either.
    expect("service" in (withLine(managed, "en", undefined, () => "app.broker.example") as Record<string, unknown>)).toBe(false);
  });

  it("change no other card", () => {
    const confirm = { type: "confirm", id: "c", text: "Ok?", options: [{ value: "y", label: "Tak" }], waits: "owner" };
    expect(withLine(confirm, "fr")).toEqual(confirm);
  });
});

describe("the app a sign-in card names", () => {
  it("is planque's name for the grant's app, from its answer to a connection call in the run", () => {
    const steps = new Steps();
    steps.call("planque", { action: "request", entry: "connector:composio:googlesheets", name: "outreach", taf_name: "Outreach" }, "c1");
    steps.result("c1", JSON.stringify({ id: "grt_sheets1", vendor: "Google Sheets", status: "pending" }));
    expect(steps.appOf("grt_sheets1")).toBe("Google Sheets");
    // An answer that wraps the grant (a key request's) names it too.
    steps.call("planque", { action: "status", grant: "grt_mail01" }, "c2");
    steps.result("c2", JSON.stringify({ grant: { id: "grt_mail01", vendor: "Gmail" }, needs_key: false }));
    expect(steps.appOf("grt_mail01")).toBe("Gmail");
  });

  it("is never a host, and nothing from a call that isn't a connection call", () => {
    expect(appNamed(JSON.stringify({ id: "grt_sheets1", vendor: "signin.broker.example" }))).toBeUndefined();
    expect(appNamed(JSON.stringify({ grants: [{ id: "grt_sheets1", vendor: "Google Sheets" }] }))).toBeUndefined();
    expect(appNamed("not json")).toBeUndefined();
    const steps = new Steps();
    steps.call("taf_results", {}, "c3");
    steps.result("c3", JSON.stringify({ id: "grt_sheets1", vendor: "Google Sheets" }));
    expect(steps.appOf("grt_sheets1")).toBeUndefined();
  });
});
