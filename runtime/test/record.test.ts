import { describe, expect, it } from "vitest";
import type { ActivityCall } from "../src/cloud/activity.js";
import { caseKey, caseView, EXPORT_MAX_RECEIPTS, MAX_SCAN, receiptsExport, recordMatches, recordPage, type RecordTaf } from "../src/cloud/record.js";

const now = new Date("2026-11-11T12:00:00Z");
const refunds: RecordTaf = { id: "taf-refunds", name: "Refunds", kiffAgent: "agent-refunds" };
const invoices: RecordTaf = { id: "taf-invoices", name: "Invoices", kiffAgent: "agent-invoices" };
const tafs = [refunds, invoices];

/** A call `hours` before now, newest first when listed by hours. */
const call = (n: number, hours: number, extra: Partial<ActivityCall> = {}): ActivityCall => ({ proposal_id: `p-${n}`, actor_id: "agent-refunds",
  action_name: "create_refund", title: "Refund a payment", outcome: "allowed", created_at: new Date(now.getTime() - hours * 3_600_000).toISOString(),
  tool_outcome: { status: "done" }, receipt: { trace_id: `tr-${n}`, status: "anchored" }, ...extra });

/** KIFF: the activity in pages, newest first, and each receipt by its trace id. */
function kiff(calls: ActivityCall[], missing: string[] = []) {
  const asked: string[] = [];
  const net = (async (url: string | URL | Request, init?: RequestInit) => {
    const u = new URL(String(url)); asked.push(u.pathname);
    expect((init?.headers as Record<string, string>).authorization).toBe("Bearer session");
    if (u.pathname === "/v1/me/activity") {
      // The Record is the person's whole activity: never narrowed to one agent here.
      expect(u.searchParams.get("actor")).toBeNull();
      const offset = Number(u.searchParams.get("offset")), limit = Number(u.searchParams.get("limit"));
      return new Response(JSON.stringify({ calls: calls.slice(offset, offset + limit), shown: calls.length }));
    }
    const id = decodeURIComponent(u.pathname.replace("/v1/receipts/", ""));
    if (missing.includes(id)) return new Response("{}", { status: 503 });
    return new Response(JSON.stringify({ trace_id: id, signature: `sig-${id}`, causal_chain: [{ sequence: 1 }] }));
  }) as typeof fetch;
  return { net, asked };
}

const page = (calls: ActivityCall[], query: string) =>
  recordPage({ token: "session", scope: "account#person", tafs, zone: "Europe/Paris", now, query: new URLSearchParams(query) }, kiff(calls).net, "https://kiff.test");

describe("the Record across a person's tafs", () => {
  const calls = [
    call(1, 1, { about: "Order #1042", amount: { value: "150.00", unit: "EUR" } }),
    call(2, 2, { actor_id: "agent-invoices", action_name: "send_invoice", title: "Send an invoice", about: "INV-77", amount: { value: "980.50", unit: "EUR" } }),
    call(3, 3, { about: "Order 1043", amount: { value: "150.50", unit: "EUR" } }),
    call(4, 4, { actor_id: "agent-elsewhere", about: "Order 1042" }),
    call(5, 30, { about: "order 1042", action_name: "read_charge", title: "Read a charge" }),
  ];

  it("finds a case by its order or invoice, whatever its case or #, across tafs, and only the person's tafs", async () => {
    const found = await page(calls, "q=order%201042");
    expect(found.entries.map((e) => e.call.proposal_id)).toEqual(["p-1", "p-5"]);
    expect(found.entries[0]).toMatchObject({ about: "Order #1042", taf: { id: "taf-refunds", name: "Refunds" }, amount_text: "€150.00" });
    expect((await page(calls, "q=inv-77")).entries.map((e) => e.taf.name)).toEqual(["Invoices"]);
    expect(found.hint).toBe("Search by order, invoice, amount or action");
  });

  it("finds an amount: a whole number is every amount of it, a decimal only that one", async () => {
    expect((await page(calls, "q=150")).entries.map((e) => e.call.proposal_id)).toEqual(["p-1", "p-3"]);
    expect((await page(calls, "q=150,50")).entries.map((e) => e.call.proposal_id)).toEqual(["p-3"]);
    expect((await page(calls, "q=€980.50")).entries.map((e) => e.call.proposal_id)).toEqual(["p-2"]);
    expect(recordMatches(calls[0]!, "Order 1042 150")).toBe(false);
  });

  it("finds an action by its name, keeps to the days asked, and says when nothing matches", async () => {
    expect((await page(calls, "q=charge")).entries.map((e) => e.call.proposal_id)).toEqual(["p-5"]);
    expect((await page(calls, "from=2026-11-11")).entries.map((e) => e.call.proposal_id)).toEqual(["p-1", "p-2", "p-3"]);
    expect((await page(calls, "to=2026-11-10")).entries.map((e) => e.call.proposal_id)).toEqual(["p-5"]);
    const none = await page(calls, "q=Order%209999");
    expect(none.entries).toEqual([]);
    expect(none.none_words).toBe('Nothing in the Record matches "Order 9999"');
  });

  it("pages with a cursor tied to its search, and refuses a cursor from another", async () => {
    const many = Array.from({ length: 30 }, (_, i) => call(i, i, { about: `Order ${2000 + i}` }));
    const first = await page(many, "q=order&limit=20");
    expect(first.entries).toHaveLength(20);
    const next = await page(many, `q=order&limit=20&cursor=${first.next_cursor}`);
    expect(next.entries.map((e) => e.call.proposal_id)).toEqual(many.slice(20).map((c) => c.proposal_id));
    expect(next.end_words).toBeTruthy();
    await expect(page(many, `q=other&cursor=${first.next_cursor}`)).rejects.toThrow("another search");
    // A call made since moves every offset: the old cursor is refused, never a page that skips or repeats.
    await expect(page([call(99, 0, { about: "Order 1999" }), ...many], `q=order&limit=20&cursor=${first.next_cursor}`)).rejects.toThrow("search again");
    await expect(page(many, `q=${"x".repeat(101)}`)).rejects.toThrow("at most 100");
  });

  it("writes a reference one way, so the same case is found however it's typed", () => {
    expect(caseKey("Order #1042")).toBe(caseKey(" order   1042 "));
    expect(caseKey("ＩＮＶ-77")).toBe("inv-77");
  });
});

describe("one case", () => {
  it("is every call of the person's tafs about it, oldest first, with its receipts, and names the step that didn't go through", async () => {
    const calls = [
      call(4, 1, { about: "Order 1042", action_name: "send_reply", title: "Reply to the customer", outcome: "allowed", tool_outcome: { status: "refused_by_tool", message: "it answered 422: mailbox full" } }),
      call(3, 2, { about: "Order #1042", amount: { value: "150.00", unit: "EUR" } }),
      call(2, 3, { about: "Order 1042", action_name: "read_charge", title: "Read a charge" }),
      call(9, 3, { about: "Order 1042", actor_id: "agent-elsewhere" }),
      call(1, 4, { about: "order 1042", action_name: "read_ticket", title: "Read a ticket" }),
      call(8, 5, { about: "Order 1043" }),
    ];
    const view = await caseView({ token: "session", about: "Order 1042", tafs, zone: "Europe/Paris", now }, kiff(calls).net, "https://kiff.test");
    expect(view?.steps.map((s) => s.title)).toEqual(["Read a ticket", "Read a charge", "Refund a payment", "Reply to the customer"]);
    expect(view?.steps[2]?.call.receipt).toEqual({ trace_id: "tr-3", status: "anchored" });
    expect(view?.failed).toMatchObject({ id: view?.steps[3]?.id, words: 'Reply to the customer didn\'t go through: The app refused it: "it answered 422: mailbox full"' });
    // The retry is the step's own next step, through the taf: never a re-send from the Record.
    expect(view?.failed?.next?.kind).toBe("ask_coach");
    expect(view?.failed?.next?.taf).toBe("Refunds");
  });

  it("is none for a reference no call of theirs is about", async () => {
    const calls = [call(1, 1, { about: "Order 1042", actor_id: "agent-elsewhere" })];
    expect(await caseView({ token: "session", about: "Order 1042", tafs, zone: "UTC", now }, kiff(calls).net, "https://kiff.test")).toBeUndefined();
    expect(await caseView({ token: "session", about: " ", tafs, zone: "UTC", now }, kiff(calls).net, "https://kiff.test")).toBeUndefined();
  });
});

describe("the receipts export", () => {
  const exportOf = (calls: ActivityCall[], from: string, to: string, missing: string[] = []) => {
    const k = kiff(calls, missing);
    return { k, out: receiptsExport({ token: "session", from, to, tafs, zone: "Europe/Paris" }, k.net, "https://kiff.test") };
  };

  it("passes KIFF's receipts through for the days asked, with a CSV of every call, oldest first", async () => {
    const calls = [
      call(3, 1, { about: "=HYPERLINK(\"x\")", amount: { value: "150.00", unit: "EUR" } }),
      call(2, 30, { about: "Order 1042", amount: { value: "80.00", unit: "EUR" }, receipt: null }),
      call(1, 40, { about: "Order 1041" }),
      call(4, 41, { actor_id: "agent-elsewhere" }),
      call(0, 24 * 40),
    ];
    const { k, out } = exportOf(calls, "2026-11-09", "2026-11-11");
    const got = await out;
    expect(got.receipts).toEqual([{ trace_id: "tr-3", signature: "sig-tr-3", causal_chain: [{ sequence: 1 }] }, { trace_id: "tr-1", signature: "sig-tr-1", causal_chain: [{ sequence: 1 }] }]);
    const rows = got.csv.trim().split("\r\n");
    expect(rows[0]).toBe('"day","taf","action","about","amount","unit","outcome","receipt"');
    expect(rows.slice(1)).toEqual([
      '"2026-11-09","Refunds","Refund a payment","Order 1041","","","allowed","tr-1"',
      '"2026-11-10","Refunds","Refund a payment","Order 1042","80.00","EUR","allowed",""',
      // A cell a spreadsheet would run as a formula is written as text.
      '"2026-11-11","Refunds","Refund a payment","\'=HYPERLINK(""x"")","150.00","EUR","allowed","tr-3"',
    ]);
    expect(got.missing).toBe(0);
    // It stopped reading at the first day before the range.
    expect(k.asked.filter((p) => p === "/v1/me/activity")).toHaveLength(1);
  });

  it("lists a call whose receipt KIFF couldn't give, without it, and refuses a range too long or too full", async () => {
    const got = await exportOf([call(1, 1), call(2, 2)], "2026-11-11", "2026-11-11", ["tr-2"]).out;
    expect(got.receipts).toHaveLength(1);
    expect(got.missing).toBe(1);
    await expect(exportOf([], "2026-08-01", "2026-11-11").out).rejects.toThrow("at most 92 days");
    await expect(exportOf([], "2026-11-11", "2026-11-10").out).rejects.toThrow("on or after");
    const full = Array.from({ length: EXPORT_MAX_RECEIPTS + 1 }, (_, i) => call(i, 1));
    await expect(exportOf(full, "2026-11-11", "2026-11-11").out).rejects.toThrow("export fewer days");
  });

  it("covers 92 days at most, both days counted", async () => {
    // 1 January to 2 April 2026 is 92 days; to 3 April, 93.
    await expect(exportOf([], "2026-01-01", "2026-04-02").out).resolves.toMatchObject({ from: "2026-01-01", to: "2026-04-02" });
    await expect(exportOf([], "2026-01-01", "2026-04-03").out).rejects.toThrow("at most 92 days");
  });

  it("is whole or refused: more calls in the range than it reads is refused, never cut short", async () => {
    const all = MAX_SCAN * 100;
    const many = (n: number) => Array.from({ length: n }, (_, i) => call(i, 1, { receipt: null }));
    // Every call it may read, and no more: whole.
    const whole = await exportOf(many(all), "2026-11-11", "2026-11-11").out;
    expect(whole.csv.trim().split("\r\n")).toHaveLength(all + 1);
    // One more in the range than it reads: refused, never a CSV that looks complete.
    await expect(exportOf(many(all + 1), "2026-11-11", "2026-11-11").out).rejects.toThrow(`more than ${all} calls on those days; export fewer days at a time`);
    // As many before the range don't count: it stops at the first day before it.
    const before = Array.from({ length: all }, (_, i) => call(5000 + i, 48, { receipt: null }));
    await expect(exportOf([...many(10), ...before], "2026-11-11", "2026-11-11").out).resolves.toMatchObject({ missing: 0 });
    // Exactly as many as it reads in the range, with older calls after them: whole, not refused.
    const exact = await exportOf([...many(all), ...before.slice(0, 5)], "2026-11-11", "2026-11-11").out;
    expect(exact.csv.trim().split("\r\n")).toHaveLength(all + 1);
  });

  it("keeps a negative amount a number, and still writes a formula as text", async () => {
    const got = await exportOf([call(1, 1, { amount: { value: "-5.00", unit: "EUR" }, about: "-1+1" })], "2026-11-11", "2026-11-11").out;
    expect(got.csv.trim().split("\r\n")[1]).toBe('"2026-11-11","Refunds","Refund a payment","\'-1+1","-5.00","EUR","allowed","tr-1"');
  });
});
