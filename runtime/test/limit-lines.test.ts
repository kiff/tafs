import { describe, expect, it } from "vitest";
import { limitLine } from "../src/cloud/limit_lines.js";

describe("limit lines from the agreed figures", () => {
  const refunds = { title: "Refunds", currency: "EUR" };
  it("says a sum in the connection's currency, by its own decimals", () => {
    expect(limitLine({ quantity: "sum(amount)", window: "call", limit: 5000 }, "ask", refunds, "en")).toBe("Refunds: up to €50.00 each time; past that, it asks you");
    expect(limitLine({ quantity: "sum(amount)", window: "calendar_day", limit: 20000, over: "refuse" }, "ask", refunds, "en"))
      .toBe("Refunds: up to €200.00 a day; past that, it's refused");
    expect(limitLine({ quantity: "sum(amount)", window: "call", limit: 5000 }, "ask", { title: "Refunds", currency: "JPY" }, "en")).toBe("Refunds: up to JP¥5,000 each time; past that, it asks you");
  });
  it("says a count, a per-item figure and a limit of 0", () => {
    expect(limitLine({ quantity: "count", window: "calendar_day", limit: 1000 }, "ask", { title: "Replies to customers" }, "en"))
      .toBe("Replies to customers: up to 1,000 a day; past that, it asks you");
    expect(limitLine({ quantity: "count", window: "calendar_day", limit: 1, per: "order_id" }, "refuse", { title: "Draft invoices", perLabel: "order" }, "en"))
      .toBe("Draft invoices: up to 1 a day for each order; past that, it's refused");
    expect(limitLine({ quantity: "count", window: "calendar_day", limit: 0 }, "ask", { title: "Refunds" }, "en")).toBe("Refunds: every one asks you first");
    expect(limitLine({ quantity: "count", window: "calendar_day", limit: 0 }, "refuse", { title: "Refunds" }, "en")).toBe("Refunds: never");
  });
  it("writes no line it can't say exactly", () => {
    // No currency, or one that isn't ISO: the money figure stays with the notice's own check.
    expect(limitLine({ quantity: "sum(amount)", window: "call", limit: 5000 }, "ask", { title: "Refunds" }, "en")).toBeUndefined();
    expect(limitLine({ quantity: "sum(amount)", window: "call", limit: 5000 }, "ask", { title: "Refunds", currency: "euro" }, "en")).toBeUndefined();
    // A per-item figure with no item named, no title, and a language without its words.
    expect(limitLine({ quantity: "count", window: "calendar_day", limit: 1, per: "order_id" }, "ask", { title: "Draft invoices" }, "en")).toBeUndefined();
    expect(limitLine({ quantity: "count", window: "calendar_day", limit: 10 }, "ask", { title: " " }, "en")).toBeUndefined();
    expect(limitLine({ quantity: "count", window: "calendar_day", limit: 10 }, "ask", { title: "Refunds" }, "de")).toBeUndefined();
  });
});
