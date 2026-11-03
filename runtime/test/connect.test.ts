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
      { entry: "notion@1", vendor: "Notion", title: "Notion", icon: "https://x/n.png", how: "oauth", state: "connected", grant: "g1" },
      { entry: "holded@1", vendor: "Holded", title: "Holded", how: "key", state: "available" },
    ] });
  });

  it("lists a managed provider's apps a page at a time, its entries as planque names them", async () => {
    const seen: { url?: string; init?: RequestInit }[] = [];
    const got = await listApps(fake(200, { entries: [
      { entry: "connector:composio:_1password", vendor: "_1password", title: "1Password", how: "oauth", state: "available" },
      { entry: "connector:composio:gmail", vendor: "gmail", title: "Gmail", how: "oauth", state: "connected", grant: "g9" },
    ], by_key: true, provider: "composio", next_cursor: "Y3Vyc29yOjE=", skipped: 2 }, seen), BASE, "t1", "sess", "", { provider: "composio", cursor: "Y3Vyc29yOjA=" });
    expect(seen[0]!.url).toBe("https://planque.test/v1/accounts/t1/catalog?provider=composio&cursor=Y3Vyc29yOjA%3D");
    expect(got.apps.map((a) => a.entry)).toEqual(["connector:composio:_1password", "connector:composio:gmail"]);
    expect(got).toMatchObject({ provider: "composio", next_cursor: "Y3Vyc29yOjE=", skipped: 2 });
    // The last page has no next cursor; a bad provider or cursor is never passed on.
    const last = await listApps(fake(200, { entries: [], by_key: true, provider: "composio", next_cursor: "" }, seen), BASE, "t1", "s", "", { provider: "../x", cursor: "a b" });
    expect(seen[1]!.url).toBe("https://planque.test/v1/accounts/t1/catalog");
    expect(last).toEqual({ apps: [], by_key: true, provider: "composio" });
    // Starting one sends its whole id, encoded.
    await startApp(fake(200, { grant: { id: "g" } }, seen), BASE, "t1", "s", "connector:composio:_1password", "a");
    expect(seen[2]!.url).toBe("https://planque.test/v1/accounts/t1/catalog/connector%3Acomposio%3A_1password/grants");
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
