import { describe, expect, it } from "vitest";
import { activityPage, activityEntry, activityMatches, representationTag, type ActivityCall } from "../src/cloud/activity.js";
import { handle, type Deps, type HTTPEvent } from "../src/cloud/handler.js";

const now = new Date("2026-11-01T12:00:00Z");
const call = (n: number, status = "done"): ActivityCall => ({ proposal_id: `proposal-${n}`, actor_id: "agent-example", action_name: "read_case", title: "Read a case", outcome: "allowed", created_at: new Date(now.getTime() - n * 1000).toISOString(), tool_outcome: { status } });
const input = (query = "", subject = "person-example") => ({ token: "synthetic-session", tenant: "account-example", subject, taf: "taf-example", agent: "agent-example", zone: "Europe/Paris", now, query: new URLSearchParams(query) });
function source(calls: ActivityCall[]) {
  const requests: URL[] = [];
  return { requests, net: (async (url: string | URL | Request, init?: RequestInit) => {
    const u = new URL(String(url)); requests.push(u);
    expect(u.searchParams.get("actor")).toBe("agent-example");
    expect((init?.headers as Record<string, string>).authorization).toBe("Bearer synthetic-session");
    const offset = Number(u.searchParams.get("offset")), limit = Number(u.searchParams.get("limit"));
    return new Response(JSON.stringify({ calls: calls.slice(offset, offset + limit), shown: calls.length }));
  }) as typeof fetch };
}

describe("Activity pages from the source", () => {
  it("returns twenty newest entries and a cursor, without dropping the next entry", async () => {
    const s = source(Array.from({ length: 45 }, (_, i) => call(i)));
    const one = await activityPage(input(), s.net);
    expect(one.entries).toHaveLength(20);
    const two = await activityPage(input(`cursor=${one.next_cursor}`), s.net);
    expect(two.entries[0]?.call.proposal_id).toBe("proposal-20");
    expect(two.groups[0]?.title).toBe("", "the next page continues the day heading");
    const three = await activityPage(input(`cursor=${two.next_cursor}`), s.net);
    expect(three.entries).toHaveLength(5); expect(three.next_cursor).toBeUndefined();
    expect(new Set([...one.entries, ...two.entries, ...three.entries].map((x) => x.id)).size).toBe(45);
  });
  it("filters before paging, even when a hundred nonmatching outcomes come first", async () => {
    const s = source([...Array.from({ length: 110 }, (_, i) => call(i, "sending")), ...Array.from({ length: 30 }, (_, i) => call(110 + i))]);
    const one = await activityPage(input("filter=done"), s.net);
    expect(one.entries).toHaveLength(20); expect(one.entries[0]?.call.proposal_id).toBe("proposal-110");
    const two = await activityPage(input(`filter=done&cursor=${one.next_cursor}`), s.net);
    expect(two.entries).toHaveLength(10);
  });
  it("invalidates a changed snapshot instead of repeating or skipping entries", async () => {
    const calls = Array.from({ length: 30 }, (_, i) => call(i)); const s = source(calls);
    const first = await activityPage(input(), s.net); calls.unshift(call(-1));
    await expect(activityPage(input(`cursor=${first.next_cursor}`), s.net)).rejects.toMatchObject({ status: 409 });
  });
  it("binds a cursor to the account, person, taf and filter", async () => {
    const s = source(Array.from({ length: 30 }, (_, i) => call(i))); const first = await activityPage(input(), s.net);
    await expect(activityPage(input(`cursor=${first.next_cursor}`, "another-person"), s.net)).rejects.toMatchObject({ status: 400 });
    await expect(activityPage(input(`filter=done&cursor=${first.next_cursor}`), s.net)).rejects.toMatchObject({ status: 400 });
    await expect(activityPage(input("limit=1000000"), s.net)).rejects.toMatchObject({ status: 400 });
  });
  it("does not call an allowed-but-unknown action done, and never shows another agent's row", async () => {
    expect(activityMatches(call(0, "unknown"), "done")).toBe(false);
    expect(activityMatches(call(0, "refused_by_tool"), "refused")).toBe(true);
    const s = source([{ ...call(0), actor_id: "other-agent" }, call(1)]);
    expect((await activityPage(input(), s.net)).entries).toHaveLength(1);
  });
  it("writes dates, titles, amounts and stable ids for both screens", () => {
    const c = { ...call(0), amount: { value: "12.30", unit: "EUR" } };
    expect(activityEntry(c, "Europe/Paris", now)).toMatchObject({ time: "13:00", day_title: "Today", amount_text: "€12.30", title: "Read a case" });
    expect(representationTag(activityEntry(c, "Europe/Paris", now))).toBe(representationTag(activityEntry(c, "Europe/Paris", now)));
  });
});

describe("Activity's HTTP contract", () => {
  it("returns 304 without a body and checks account and agent access before reading calls", async () => {
    const id = "taf-01234567890123456789";
    let tenant = "account-example", allowed = true, reads = 0;
    const deps = {
      now: () => now,
      tafs: { get: async (account: string) => account === "account-example" ? { id, name: "Example", job: "refunds", kiffAgent: "agent-example" } : undefined },
      fetch: (async (url: string | URL | Request) => {
        const u = new URL(String(url));
        if (u.pathname === "/v1/me") return new Response(JSON.stringify({ tenant_id: tenant, subject: "person-example", api_key: false, capabilities: ["read"], time_zone: "UTC" }));
        if (u.pathname === "/v1/me/access") return new Response(JSON.stringify({ agents: allowed ? { "agent-example": { use: true } } : {} }));
        if (u.pathname === "/v1/me/activity") { reads++; return new Response(JSON.stringify({ calls: [call(0)], shown: 1 })); }
        throw new Error("unexpected request");
      }) as typeof fetch,
    } as unknown as Deps;
    const event: HTTPEvent = { rawPath: `/v1/tafs/${id}/activity`, headers: { authorization: "Bearer synthetic-session" }, requestContext: { http: { method: "GET" } } };
    const first = await handle(event, deps); expect(first.statusCode).toBe(200); expect(first.headers.etag).toBeTruthy();
    const unchanged = await handle({ ...event, headers: { ...event.headers, "if-none-match": first.headers.etag } }, deps);
    expect(unchanged.statusCode).toBe(304); expect(unchanged.body).toBe("");
    const before = reads; allowed = false;
    expect((await handle(event, deps)).statusCode).toBe(404); expect(reads).toBe(before);
    tenant = "other-account";
    expect((await handle(event, deps)).statusCode).toBe(404); expect(reads).toBe(before);
  });
});
