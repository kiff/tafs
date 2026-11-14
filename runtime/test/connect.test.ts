import { describe, expect, it } from "vitest";
import { ConnectError, listApps, startApp } from "../src/cloud/connect.js";

const BASE = "https://planque.test";

function fake(status: number, body: unknown, seen: { url?: string; init?: RequestInit }[] = []) {
  return (async (url: string | URL | Request, init?: RequestInit) => {
    seen.push({ url: String(url), init });
    return new Response(typeof body === "string" ? body : JSON.stringify(body), { status });
  }) as typeof fetch;
}

describe("Connect apps", () => {
  it("lists planque's catalog with the person's session, keeping only what the apps draw", async () => {
    const seen: { url?: string; init?: RequestInit }[] = [];
    const got = await listApps(fake(200, { entries: [
      { entry: "notion@1", vendor: "Notion", title: "Notion", how: "oauth", state: "connected", grant: "g1", icon: "https://x/n.png", token: "never" },
      { entry: "holded@1", vendor: "Holded", title: "Holded", how: "key", state: "available", icon: "http://plain/no.png" },
      { entry: "../evil", title: "Bad", how: "oauth" },
      { entry: "nohow@1", title: "No how" },
    ], by_key: true }, seen), BASE, "t 1", "sess", "  no tion ");
    expect(seen[0]!.url).toBe("https://planque.test/v1/accounts/t%201/catalog?q=no%20tion");
    expect((seen[0]!.init!.headers as Record<string, string>).authorization).toBe("Bearer sess");
    expect(got).toEqual({ by_key: true, apps: [
      { entry: "notion@1", vendor: "Notion", title: "Notion", name: "Notion", icon: "https://x/n.png", how: "oauth", state: "connected", grant: "g1" },
      { entry: "holded@1", vendor: "Holded", title: "Holded", name: "Holded", how: "key", state: "available" },
    ] });
  });

  it("puts the account's own connections first, then the apps whose name matches the search", async () => {
    const got = await listApps(fake(200, { entries: [
      { entry: "connector:composio:pylon_mcp", title: "Pylon MCP", how: "oauth" },
      { entry: "connector:composio:rootly", title: "Rootly", vendor: "Rootly", how: "oauth" },
      { entry: "connector:composio:slackbot", title: "Slackbot", how: "oauth" },
      { entry: "connector:composio:slack", title: "Slack", how: "oauth", state: "connected", grant: "g1" },
      { entry: "connector:composio:myslack", title: "Team MySlack", how: "oauth" },
      { entry: "connector:composio:stripe", title: "Stripe", how: "key", state: "needs_reconnect", grant: "g2" },
    ] }), BASE, "t1", "s", "slack");
    expect(got.apps.map((a) => a.title)).toEqual(["Stripe", "Slack", "Slackbot", "Team MySlack", "Pylon MCP", "Rootly"]);
    const plain = await listApps(fake(200, { entries: [
      { entry: "a@1", title: "0CodeKit", how: "oauth" },
      { entry: "b@1", title: "Notion", how: "oauth", state: "connected", grant: "g" },
      { entry: "c@1", title: "44API", how: "oauth" },
    ] }), BASE, "t1", "s", "");
    expect(plain.apps.map((a) => a.title)).toEqual(["Notion", "0CodeKit", "44API"]);
  });

  it("reads a reviewed entry as its app's name, the rest of its title under it", async () => {
    // As planque's catalog answers (deploy/catalog.json, vendor_catalog.go): a reviewed entry's vendor is the app's
    // name, a managed app's is its slug.
    const entries = [
      { entry: "connector:composio:gmail", vendor: "gmail", title: "Gmail", how: "oauth" },
      { entry: "connector:composio:zendesk", vendor: "zendesk", title: "Zendesk", how: "oauth" },
      { entry: "qonto-key@2", vendor: "Qonto", title: "Qonto invoicing: reads, and invoice drafts from quotes", how: "key" },
      { entry: "qonto-key@1", vendor: "Qonto", title: "Qonto invoicing, read only", how: "key", state: "connected", grant: "g1" },
      { entry: "browser", vendor: "Websites", title: "Use websites", how: "oauth" },
      { entry: "stripe", vendor: "Stripe", title: "Stripe", how: "oauth" },
    ];
    const got = await listApps(fake(200, { entries }), BASE, "t1", "s", "");
    const row = (entry: string) => got.apps.find((a) => a.entry === entry)!;
    expect(row("qonto-key@2")).toMatchObject({ name: "Qonto", subtitle: "Invoicing: reads, and invoice drafts from quotes",
      title: "Qonto invoicing: reads, and invoice drafts from quotes" });
    expect(row("qonto-key@1")).toMatchObject({ name: "Qonto", subtitle: "Invoicing, read only" });
    // Negative: a managed app reads as its title, never its slug, with no subtitle repeating it.
    expect(row("connector:composio:gmail").name).toBe("Gmail");
    expect(row("connector:composio:gmail")).not.toHaveProperty("subtitle");
    expect(row("stripe")).toEqual(expect.objectContaining({ name: "Stripe" }));
    expect(row("stripe")).not.toHaveProperty("subtitle");
    // Negative: a title that doesn't start with the vendor is the name itself, not cut.
    expect(row("browser")).toMatchObject({ name: "Use websites" });
    expect(row("browser")).not.toHaveProperty("subtitle");
    // Discover's order is as before, by planque's title: the account's own, the first apps, then the rest.
    expect(got.apps.map((a) => a.entry)).toEqual(["qonto-key@1", "connector:composio:gmail", "stripe", "qonto-key@2", "browser", "connector:composio:zendesk"]);
  });

  it("never cuts a managed app's title, nor a name that only starts with the vendor", async () => {
    const { displayName } = await import("../src/cloud/connect.js");
    expect(displayName("connector:composio:hubspot", "HubSpot", "HubSpot CRM")).toEqual({ name: "HubSpot CRM" });
    expect(displayName("qontoapp@1", "Qonto", "Qontoapp")).toEqual({ name: "Qontoapp" });
    expect(displayName("qonto@1", "Qonto", "Qonto")).toEqual({ name: "Qonto" });
  });

  it("opens Discover on the first apps people connect, in apps.md's order, then the rest by name", async () => {
    // planque answers by entry id: alphabetical, its own Stripe and Qonto after every managed app.
    const entries = [
      { entry: "browser", vendor: "Browser", title: "Use websites", how: "oauth" },
      { entry: "connector:composio:agent_mail", vendor: "agent_mail", title: "Agent Mail", how: "oauth" },
      { entry: "connector:composio:airtable", vendor: "airtable", title: "Airtable", how: "oauth" },
      { entry: "connector:composio:ashby", vendor: "ashby", title: "Ashby", how: "oauth" },
      { entry: "connector:composio:bexio", vendor: "bexio", title: "bexio", how: "oauth" },
      { entry: "connector:composio:github", vendor: "github", title: "GitHub", how: "oauth" },
      { entry: "connector:composio:gmail", vendor: "gmail", title: "Gmail", how: "oauth" },
      { entry: "connector:composio:googlesheets", vendor: "googlesheets", title: "Google Sheets", how: "oauth" },
      { entry: "connector:composio:slack", vendor: "slack", title: "Slack", how: "oauth", state: "connected", grant: "g1" },
      { entry: "connector:composio:zendesk", vendor: "zendesk", title: "Zendesk", how: "oauth" },
      { entry: "qonto", vendor: "Qonto", title: "Qonto", how: "oauth" },
      { entry: "stripe", vendor: "Stripe", title: "Stripe", how: "oauth" },
    ];
    const got = await listApps(fake(200, { entries, provider: "composio" }), BASE, "t1", "s", "");
    // The account's own first (Slack, though it's a first app too), then Gmail, Google Sheets, Stripe, GitHub, Qonto, Airtable
    // as apps.md lists them, then the rest by name, whatever its case.
    expect(got.apps.map((a) => a.title)).toEqual(["Slack", "Gmail", "Google Sheets", "Stripe", "GitHub", "Qonto", "Airtable",
      "Agent Mail", "Ashby", "bexio", "Use websites", "Zendesk"]);
    // Negative: planque's alphabetical order is gone; Agent Mail no longer opens Discover.
    expect(got.apps.map((a) => a.title).slice(1, 4)).not.toEqual(["Agent Mail", "Airtable", "Ashby"]);
    expect(got.apps.findIndex((a) => a.title === "Gmail")).toBeLessThan(got.apps.findIndex((a) => a.title === "Agent Mail"));
    // Negative: a search keeps its relevance order, never moved by the first apps nor by name.
    const searched = await listApps(fake(200, { entries: [
      { entry: "connector:composio:zmail", vendor: "zmail", title: "Zmail", how: "oauth" },
      { entry: "connector:composio:agent_mail", vendor: "agent_mail", title: "Agent Mail", how: "oauth" },
      { entry: "connector:composio:gmail", vendor: "gmail", title: "Gmail", how: "oauth" },
      { entry: "connector:composio:mailchimp", vendor: "mailchimp", title: "Mailchimp", how: "oauth" },
    ] }), BASE, "t1", "s", "mail");
    expect(searched.apps.map((a) => a.title)).toEqual(["Mailchimp", "Zmail", "Agent Mail", "Gmail"]);
  });

  it("lists a managed provider's apps a page at a time, its entries as planque names them", async () => {
    const seen: { url?: string; init?: RequestInit }[] = [];
    const got = await listApps(fake(200, { entries: [
      { entry: "connector:composio:_1password", vendor: "_1password", title: "1Password", how: "oauth", state: "available" },
      { entry: "connector:composio:gmail", vendor: "gmail", title: "Gmail", how: "oauth", state: "connected", grant: "g9" },
      { entry: "connector:composio:own", vendor: "own", title: "Own app", how: "oauth", state: "needs_setup" },
    ], by_key: true, provider: "composio", next_cursor: "Y3Vyc29yOjE=", skipped: 2, partial: true }, seen), BASE, "t1", "sess", "", { provider: "composio", cursor: "Y3Vyc29yOjA=" });
    expect(seen[0]!.url).toBe("https://planque.test/v1/accounts/t1/catalog?provider=composio&cursor=Y3Vyc29yOjA%3D");
    expect(got.apps.map((a) => a.entry)).toEqual(["connector:composio:gmail", "connector:composio:_1password", "connector:composio:own"]);
    // An app that needs setting up by an operator is said so, as planque says it; a read with pages left says so too.
    expect(got.apps[2]).toMatchObject({ state: "needs_setup", words: "Needs setting up first. Ask tafs to enable it." });
    expect(got.apps[0]).not.toHaveProperty("words");
    expect(got).toMatchObject({ provider: "composio", next_cursor: "Y3Vyc29yOjE=", skipped: 2, partial: true });
    // The last page has no next cursor; a bad provider or cursor is never passed on.
    const last = await listApps(fake(200, { entries: [], by_key: true, provider: "composio", next_cursor: "" }, seen), BASE, "t1", "s", "", { provider: "../x", cursor: "a b" });
    expect(seen[1]!.url).toBe("https://planque.test/v1/accounts/t1/catalog");
    expect(last).toEqual({ apps: [], by_key: true, provider: "composio" });
    // Starting one sends its whole id, encoded.
    await startApp(fake(200, { grant: { id: "g" } }, seen), BASE, "t1", "s", "connector:composio:_1password", "a");
    expect(seen[2]!.url).toBe("https://planque.test/v1/accounts/t1/catalog/connector%3Acomposio%3A_1password/grants");
    // The taf's name goes with it, for the words the owner signs.
    await startApp(fake(200, { grant: { id: "g" } }, seen), BASE, "t1", "s", "notion@1", "a", "Refunds\n");
    expect(JSON.parse(String(seen[3]!.init!.body))).toEqual({ agent: "a", agent_name: "Refunds" });
    // Cut to 60 and never ending in a space; C1 controls go too (planque refuses both).
    await startApp(fake(200, { grant: { id: "g" } }, seen), BASE, "t1", "s", "notion@1", "a", `${"x".repeat(59)} tail\u0085`);
    expect(JSON.parse(String(seen[4]!.init!.body)).agent_name).toBe("x".repeat(59));
  });

  it("starts a connection for the taf's KIFF agent, and gives back the grant to approve", async () => {
    const seen: { url?: string; init?: RequestInit }[] = [];
    const got = await startApp(fake(200, { grant: { id: "g2", request: { profile: {} } }, needs_key: true }, seen), BASE, "t1", "sess", "holded@1", "agent-refunds");
    expect(seen[0]!.url).toBe("https://planque.test/v1/accounts/t1/catalog/holded%401/grants");
    expect(JSON.parse(String(seen[0]!.init!.body))).toEqual({ agent: "agent-refunds" });
    expect(got).toEqual({ grant: { id: "g2", request: { profile: {} } }, needs_key: true });
  });

  it("says what went wrong in words, never planque's own", async () => {
    await expect(startApp(fake(404, "nope"), BASE, "t1", "s", "x@1", "a")).rejects.toMatchObject({ status: 404 });
    await expect(listApps(fake(403, "{}"), BASE, "t1", "s", "")).rejects.toMatchObject({ status: 403 });
    await expect(listApps(fake(500, "boom"), BASE, "t1", "s", "")).rejects.toBeInstanceOf(ConnectError);
    await expect(startApp(fake(200, {}), BASE, "t1", "s", "x@1", "a")).rejects.toMatchObject({ status: 502 });
    await expect(startApp(fake(200, {}), BASE, "t1", "s", "a/b", "a")).rejects.toMatchObject({ status: 404 });
  });
});
