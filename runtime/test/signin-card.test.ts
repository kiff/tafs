import { describe, expect, it } from "vitest";
import { withLine } from "../src/cloud/handler.js";
import { appNamed, brokerNamed, Steps } from "../src/cloud/steps.js";

describe("a sign-in card's buttons", () => {
  const card = { type: "sign_in", id: "si", grant: "grt_abcdef", service: "Stripe", title: "Połącz Stripe", text: "Zaloguj się w Stripe, aby taf mógł zwracać płatności.", yes: "Zaloguj się", no: "Nie teraz", waits: "owner" };

  it("are the app's chrome, in the app's language, whatever language Coach wrote the card in", () => {
    expect(withLine(card, "fr")).toMatchObject({ yes: "Se connecter", no: "Pas maintenant" });
    expect(withLine(card, "en")).toMatchObject({ yes: "Sign in", no: "Not now" });
  });

  it("leave Coach's own words on the card as written", () => {
    expect(withLine(card, "fr")).toMatchObject({ title: "Połącz Stripe", text: card.text, service: "Stripe", grant: "grt_abcdef" });
  });

  // Go-to-market job 1 (owner's phone, 13 Nov): the card read "Sign in at backend.composio.dev", "Opens backend.composio.dev".
  // The person connects an app, never the connection service in between: the card names the app, never a host.
  it("name the app planque named in the run, never the connection service's host", () => {
    const composio = { ...card, service: "backend.composio.dev", grant: "grt_sheets1" };
    const named = withLine(composio, "en", undefined, (g) => (g === "grt_sheets1" ? "Google Sheets" : undefined));
    expect(named).toMatchObject({ service: "Google Sheets" });
    // With no app's name from the run, no service at all: the apps say "the vendor", never the host.
    const bare = withLine(composio, "en") as Record<string, unknown>;
    expect("service" in bare).toBe(false);
    for (const host of ["backend.composio.dev", "app.composio.dev", "composio.dev", "connect.composio.io", "Composio"]) {
      const out = JSON.stringify(withLine({ ...card, service: host }, "en"));
      expect(out.toLowerCase(), host).not.toContain("composio");
    }
    // A vendor's own host says the vendor's name, never the host.
    expect(withLine({ ...card, service: "mcp.stripe.com" }, "en")).toMatchObject({ service: "Stripe" });
    // A name planque gave that is a connection service's is never used either.
    expect(JSON.stringify(withLine(composio, "en", undefined, () => "Composio")).toLowerCase()).not.toContain("composio");
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

  it("is never a host or a connection service's name, and nothing from a call that isn't a connection call", () => {
    expect(appNamed(JSON.stringify({ id: "grt_sheets1", vendor: "backend.composio.dev" }))).toBeUndefined();
    expect(appNamed(JSON.stringify({ id: "grt_sheets1", vendor: "Composio" }))).toBeUndefined();
    expect(appNamed(JSON.stringify({ grants: [{ id: "grt_sheets1", vendor: "Google Sheets" }] }))).toBeUndefined();
    expect(appNamed("not json")).toBeUndefined();
    const steps = new Steps();
    steps.call("taf_results", {}, "c3");
    steps.result("c3", JSON.stringify({ id: "grt_sheets1", vendor: "Google Sheets" }));
    expect(steps.appOf("grt_sheets1")).toBeUndefined();
    expect(brokerNamed("api.composio.io")).toBe(true);
    expect(brokerNamed("Google Sheets")).toBe(false);
  });
});
