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
  it("reads a figure at the currency's ISO exponent, never at Intl's display precision", () => {
    // Intl shows IDR and COP with no decimals; ISO and Stripe count them in hundredths.
    const plain = (s?: string) => s?.replace(/\u00a0/g, " ");
    expect(plain(limitLine({ quantity: "sum(amount)", window: "call", limit: 500000 }, "ask", { title: "Refunds", currency: "IDR" }, "en"))).toBe("Refunds: up to IDR 5,000.00 each time; past that, it asks you");
    expect(plain(limitLine({ quantity: "sum(amount)", window: "call", limit: 5000 }, "ask", { title: "Refunds", currency: "KWD" }, "en"))).toBe("Refunds: up to KWD 5.000 each time; past that, it asks you");
    // A currency Stripe treats as a special case, or one in no list, gets no line rather than a guessed scale.
    expect(limitLine({ quantity: "sum(amount)", window: "call", limit: 500000 }, "ask", { title: "Refunds", currency: "HUF" }, "en")).toBeUndefined();
    expect(limitLine({ quantity: "sum(amount)", window: "call", limit: 5000 }, "ask", { title: "Refunds", currency: "XYZ" }, "en")).toBeUndefined();
  });
  it("says a count, a per-item figure and a limit of 0", () => {
    expect(limitLine({ quantity: "count", window: "calendar_day", limit: 1000 }, "ask", { title: "replies to customers" }, "en"))
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
    expect(limitLine({ quantity: "count", window: "calendar_day", limit: 10 }, "ask", { title: "Refunds" }, "ko")).toBeUndefined();
  });
  it("says the lines in the eight languages and pt-BR, in each one's number format", () => {
    const plain = (s?: string) => s?.replace(/[\u00a0\u202f]/g, " ");
    const sum = { quantity: "sum(amount)", window: "call", limit: 5000 } as const;
    const perDay = { quantity: "count", window: "calendar_day", limit: 1000, per: "order_id" } as const;
    const zero = { quantity: "count", window: "calendar_day", limit: 0 } as const;
    const cases: [Parameters<typeof limitLine>[3], string, string, string, string, string][] = [
      ["de", "Rückerstattungen", "Bestellung", "Rückerstattungen: bis zu 50,00 € jedes Mal; für mehr fragt er dich",
        "Rückerstattungen: bis zu 1.000 pro Tag pro Bestellung; mehr wird abgelehnt", "Rückerstattungen: fragt dich jedes Mal zuerst"],
      ["fr", "remboursements", "commande", "Remboursements : jusqu'à 50,00 € à chaque fois; au-delà, il vous demande",
        "Remboursements : jusqu'à 1 000 par jour par commande; au-delà, c'est refusé", "Remboursements : toujours soumis à votre accord"],
      ["es", "Reembolsos", "pedido", "Reembolsos: hasta 50,00 € cada vez; por encima, te lo pregunta",
        "Reembolsos: hasta 1000 al día por pedido; por encima, se rechaza", "Reembolsos: siempre te lo pregunta antes"],
      ["it", "Rimborsi", "ordine", "Rimborsi: fino a 50,00 € ogni volta; oltre, chiede a te",
        "Rimborsi: fino a 1000 al giorno per ordine; oltre, viene rifiutato", "Rimborsi: chiede sempre prima a te"],
      ["pt", "Reembolsos", "encomenda", "Reembolsos: até 50,00 € de cada vez; acima disso, pergunta-lhe",
        "Reembolsos: até 1000 por dia por encomenda; acima disso, é recusado", "Reembolsos: pergunta-lhe sempre antes"],
      ["pt-BR", "Reembolsos", "pedido", "Reembolsos: até € 50,00 de cada vez; acima disso, pergunta a você",
        "Reembolsos: até 1.000 por dia por pedido; acima disso, é recusado", "Reembolsos: sempre pergunta a você antes"],
      ["nl", "Terugbetalingen", "bestelling", "Terugbetalingen: tot € 50,00 per keer; daarboven vraagt het je",
        "Terugbetalingen: tot 1.000 per dag per bestelling; daarboven wordt het geweigerd", "Terugbetalingen: vraagt het je altijd eerst"],
      ["pl", "Zwroty", "zamówienie", "Zwroty: do 50,00 € za każdym razem; powyżej tej granicy pyta Ciebie",
        "Zwroty: do 1000 dziennie (osobno dla każdej pozycji: zamówienie); powyżej tej granicy jest odrzucane", "Zwroty: zawsze najpierw pyta Ciebie"],
    ];
    for (const [lang, title, perLabel, money, count, none] of cases) {
      expect(plain(limitLine(sum, "ask", { title, currency: "EUR" }, lang))).toBe(money);
      expect(plain(limitLine(perDay, "refuse", { title, perLabel }, lang))).toBe(count);
      expect(plain(limitLine(zero, "ask", { title }, lang))).toBe(none);
    }
  });
});
