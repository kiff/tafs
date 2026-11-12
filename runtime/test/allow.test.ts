import { describe, expect, it } from "vitest";
import { allowOffer, type Breach, type Change, changesMatch, changeWords, kindFor, roundOffer, roundUp, wouldWords } from "../src/cloud/allow.js";
import { LANGUAGES, wordsIn } from "../src/cloud/words/index.js";

const EACH: Breach = { mandate_id: "m_refunds", outcome: "per_action_exceeded", quantity: "sum(amount)", unit: "EUR", limit: 15000, draw: 32000 };
const TOTAL: Breach = { mandate_id: "m_refunds", outcome: "limit_reached", quantity: "sum(amount)", unit: "EUR", limit: 50000, used: 42000, draw: 18000, window: "calendar_day" };
const COUNT: Breach = { mandate_id: "m_mail", outcome: "limit_reached", quantity: "count", limit: 0, used: 0, draw: 1, window: "calendar_day" };
const VALUE: Breach = { mandate_id: "m_scope", outcome: "resource_not_on_card", resource: "customer", value: "cus_42" };

const each = (after = 32000, rounded = false): Change => ({ mandate_id: "m_refunds", kind: "per_action", before: 15000, after, unit: "EUR", rounded });
const total = (after = 60000, rounded = false): Change => ({ mandate_id: "m_refunds", kind: "total", before: 50000, after, unit: "EUR", window: "calendar_day", rounded });
const count: Change = { mandate_id: "m_mail", kind: "count", before: 0, after: 1, window: "calendar_day", rounded: false };
const value: Change = { mandate_id: "m_scope", kind: "resource", parameter: "customer", value: "cus_42", rounded: false };

describe("Allow this from now on: the narrowest change (docs/allow-from-now-on.md)", () => {
  it("maps each breach to the one kind of change the contract's table allows", () => {
    expect([EACH, TOTAL, COUNT, VALUE].map(kindFor)).toEqual(["per_action", "total", "count", "resource"]);
    expect(kindFor({ outcome: "denied" })).toBeUndefined();
    expect(kindFor({ outcome: "per_action_exceeded", quantity: "count" })).toBeUndefined();
  });

  it("rounds up one step at most: 50 below 1,000, 100 below 10,000, then 1,000, in major units", () => {
    expect(roundUp(32000, "EUR")).toBe(35000);
    expect(roundUp(124000, "EUR")).toBe(130000);
    expect(roundUp(1234500, "EUR")).toBe(1300000);
    expect(roundUp(35000, "EUR")).toBe(35000);
    expect(roundUp(320, "JPY")).toBe(350);
    expect(roundUp(32001, "EUR")).toBe(35000);
  });

  it("accepts exactly the narrowest changes: each, a total for good, a count, a value", () => {
    expect(changesMatch([EACH], [each()], false)).toBe(true);
    expect(changesMatch([TOTAL], [total()], false)).toBe(true);
    expect(changesMatch([COUNT], [count], false)).toBe(true);
    expect(changesMatch([VALUE], [value], false)).toBe(true);
    // A call outside two limits needs both, in one draft, in any order.
    expect(changesMatch([EACH, COUNT], [count, each()], false)).toBe(true);
  });

  it("refuses a change that widens past the held call, or touches anything else", () => {
    expect(changesMatch([EACH], [each(32100)], false)).toBe(false); // past the call's figure
    expect(changesMatch([EACH], [each(31900)], false)).toBe(false); // short of it
    expect(changesMatch([EACH], [{ ...each(), mandate_id: "m_other" }], false)).toBe(false); // another Card
    expect(changesMatch([EACH], [{ ...each(), kind: "total" }], false)).toBe(false); // another kind
    expect(changesMatch([EACH], [{ ...each(), unit: "USD" }], false)).toBe(false); // another unit
    expect(changesMatch([EACH], [{ ...each(), before: 10000 }], false)).toBe(false); // not the limit in force
    expect(changesMatch([TOTAL], [{ ...total(), window: "rolling_7d" }], false)).toBe(false); // another window
    expect(changesMatch([EACH], [each(), count], false)).toBe(false); // one more change than breaches
    expect(changesMatch([EACH, COUNT], [each()], false)).toBe(false); // one breach left open
    expect(changesMatch([EACH], [], false)).toBe(false);
    expect(changesMatch([], [], false)).toBe(false); // an empty change is no offer
    expect(changesMatch([VALUE], [{ ...value, value: "cus_43" }], false)).toBe(false); // another value
    expect(changesMatch([VALUE], [{ ...value, parameter: "account" }], false)).toBe(false); // another scope
    expect(changesMatch([VALUE], [{ ...value, after: 1 }], false)).toBe(false); // a value never carries a figure
    expect(changesMatch([COUNT], [{ ...count, after: 2 }], false)).toBe(false);
  });

  it("rounds only on {round: true}, and only one step: a count and a value never", () => {
    expect(changesMatch([EACH], [each(35000, true)], true)).toBe(true);
    expect(changesMatch([EACH], [each(35000, true)], false)).toBe(false); // rounded unasked
    expect(changesMatch([EACH], [each(40000, true)], true)).toBe(false); // two steps
    expect(changesMatch([EACH], [each(32000)], true)).toBe(false); // asked to round, and it didn't
    expect(changesMatch([TOTAL], [total(60000)], true)).toBe(true); // €600 is round already: exact, and not marked rounded
    expect(changesMatch([TOTAL], [total(60000, true)], true)).toBe(false);
    expect(changesMatch([COUNT], [count], true)).toBe(true);
    expect(changesMatch([COUNT], [{ ...count, rounded: true }], true)).toBe(false);
    expect(changesMatch([VALUE], [{ ...value, rounded: true }], true)).toBe(false);
  });

  it("offers rounding when it changes the figure: the per-call one first, else a total's", () => {
    expect(roundOffer([EACH], "en")).toEqual({ after: 35000, words: "€350" });
    expect(roundOffer([COUNT, EACH], "en")?.after).toBe(35000);
    expect(roundOffer([TOTAL], "en")).toBeUndefined(); // €600 is round
    expect(roundOffer([{ ...TOTAL, used: 42500 }], "de")?.after).toBe(65000);
    expect(roundOffer([COUNT, VALUE], "en")).toBeUndefined();
  });

  it("words a change as the Card says it today, and the same with the new figure", () => {
    expect(changeWords(each(), EACH, "Refund a payment", "en")).toEqual({ words_before: "Refund a payment: up to €150 each", words_after: "Refund a payment: up to €320 each" });
    expect(changeWords(each(35000, true), EACH, "Refund a payment", "en").words_after).toBe("Refund a payment: up to €350 each (rounded up from €320)");
    expect(changeWords(total(), TOTAL, "Refund a payment", "en")).toEqual({ words_before: "Refund a payment: €500 in total a day", words_after: "Refund a payment: €600 in total a day, from now on" });
    expect(changeWords(count, COUNT, "Send an email", "en")).toEqual({ words_before: "Send an email: each call asks you first", words_after: "Send an email: 1 call a day on its own, from now on" });
    expect(changeWords(value, VALUE, "Refund a payment", "en")).toEqual({ words_before: "Refund a payment: not for cus_42", words_after: "Refund a payment: also for cus_42" });
    expect(changeWords({ ...total(), window: "lifetime" }, TOTAL, "Pay", "en").words_before).toBe("Pay: €500 in total");
    expect(changeWords(each(), EACH, "Rückerstattung", "de").words_after.replace(/\s/g, " ")).toBe("Rückerstattung: bis 320 € pro Aufruf");
    expect(changeWords({ ...each(), after: 32050 }, EACH, "Refund", "en").words_after).toBe("Refund: up to €320.50 each");
  });

  it("says what the change would have done, or that nothing else would have gone out", () => {
    expect(wouldWords({ would_allow: { calls: 4, by_quantity: [{ quantity: "amount", unit: "EUR", total: 118000 }] } }, "en"))
      .toEqual({ calls: 4, words: "With this Card, 4 more calls would have gone out on their own (€1,180)" });
    expect(wouldWords({ would_allow: { calls: 1, by_quantity: [{ quantity: "count", total: 1 }] } }, "en").words).toBe("With this Card, 1 more call would have gone out on its own");
    expect(wouldWords({ would_allow: { calls: 0 } }, "en")).toEqual({ calls: 0, words: "Nothing else in the last 30 days would have gone out on its own" });
    expect(wouldWords({}, "fr").words).toBe(wordsIn("fr").allow_would_none);
  });

  it("has every line in the 8 languages", () => {
    for (const lang of LANGUAGES) {
      const w = wordsIn(lang);
      expect(Object.keys(w.allow_window).sort()).toEqual(["calendar_day", "lifetime", "rolling_1h", "rolling_24h", "rolling_7d"]);
      for (const n of [0, 1, 2, 5, 22]) expect(wouldWords({ would_allow: { calls: n } }, lang).words).not.toMatch(/undefined|NaN/);
      expect(changeWords(count, COUNT, "x", lang).words_after).toContain("1");
    }
  });
});

/** KIFF as allowOffer reads it: the hold, allow-draft and the replay, each answer replaceable. */
function kiffWith(answers: { hold?: object; draft?: object; replay?: object | number }) {
  const asked: Array<{ method: string; path: string; body?: unknown }> = [];
  const doFetch = (async (url: string, init?: RequestInit) => {
    const path = String(url).replace("https://kiff.test", "");
    asked.push({ method: init?.method ?? "GET", path, ...(init?.body ? { body: JSON.parse(String(init.body)) } : {}) });
    const send = (x: object | number | undefined) => typeof x === "number" ? new Response("{}", { status: x }) : new Response(JSON.stringify(x ?? {}), { status: x ? 200 : 404 });
    if (path.endsWith("/allow-draft")) return send(answers.draft);
    if (path.endsWith("/replay")) return send(answers.replay);
    return send(answers.hold);
  }) as typeof fetch;
  return { asked, deps: { token: "ana", agent: "rae", lang: "en" as const, fetch: doFetch, base: "https://kiff.test" } };
}

const HOLD = { id: "exc_1", subject: "rae", kind: "hold", status: "held", title: "Refund a payment", breaches: [EACH] };
const DRAFT = { draft: "drf_1", version: "v1", confirm_key: "critical", changes: [each()], round: { after: 35000 } };

describe("allowOffer", () => {
  it("asks KIFF for the hold, the draft and the replay, and offers the checked change in words", async () => {
    const k = kiffWith({ hold: HOLD, draft: DRAFT, replay: { would_allow: { calls: 4, by_quantity: [{ quantity: "amount", unit: "EUR", total: 118000 }] } } });
    const out = await allowOffer("exc_1", false, k.deps);
    expect(out).toEqual({
      offer: {
        changes: [{ ...each(), words_before: "Refund a payment: up to €150 each", words_after: "Refund a payment: up to €320 each" }],
        round: { after: 35000, words: "€350" },
        would: { calls: 4, words: "With this Card, 4 more calls would have gone out on their own (€1,180)" },
        draft: "drf_1", confirm_key: "critical", send_held: true,
      },
    });
    expect(k.asked).toEqual([
      { method: "GET", path: "/v1/me/card-exceptions/exc_1" },
      { method: "POST", path: "/v1/me/card-exceptions/exc_1/allow-draft", body: {} },
      { method: "POST", path: "/v1/me/agent-drafts/drf_1/replay", body: { days: 30 } },
    ]);
  });

  it("asks for the rounded figure only on round, and carries no second rounding then", async () => {
    const k = kiffWith({ hold: HOLD, draft: { ...DRAFT, changes: [each(35000, true)], round: undefined }, replay: { would_allow: { calls: 0 } } });
    const out = await allowOffer("exc_1", true, k.deps);
    expect(k.asked[1]!.body).toEqual({ round: true });
    expect("offer" in out && out.offer.round).toBeUndefined();
    expect("offer" in out && out.offer.changes[0]!.words_after).toBe("Refund a payment: up to €350 each (rounded up from €320)");
  });

  it("offers nothing, and asks for no draft, for another taf's call, a unit mismatch or a breach no Card change answers", async () => {
    for (const [hold, why] of [
      [{ ...HOLD, subject: "max" }, "not_yours"],
      [{ ...HOLD, subject: undefined }, "not_yours"],
      [{ ...HOLD, kind: "card_unit" }, "unit"],
      [{ ...HOLD, breaches: [{ ...EACH, outcome: "denied" }] }, "no_change"],
      [{ ...HOLD, breaches: [] }, "no_change"],
    ] as const) {
      const k = kiffWith({ hold, draft: DRAFT });
      const out = await allowOffer("exc_1", false, k.deps);
      expect(out).toMatchObject({ none: why });
      expect("words" in out && out.words.length > 0).toBe(true);
      expect(k.asked.map((a) => a.path)).toEqual(["/v1/me/card-exceptions/exc_1"]);
    }
  });

  it("passes KIFF's own none on, and asks no replay", async () => {
    for (const why of ["never", "unit", "no_change"]) {
      const k = kiffWith({ hold: HOLD, draft: { none: why } });
      expect(await allowOffer("exc_1", false, k.deps)).toMatchObject({ none: why });
      expect(k.asked).toHaveLength(2);
    }
    const odd = kiffWith({ hold: HOLD, draft: { none: "something_new" } });
    expect(await allowOffer("exc_1", false, odd.deps)).toMatchObject({ none: "no_change" });
  });

  it("doesn't offer a draft whose change its own check refuses, nor one whose rounding disagrees", async () => {
    for (const draft of [
      { ...DRAFT, changes: [each(40000)] },
      { ...DRAFT, changes: [each(), count] },
      { ...DRAFT, changes: [{ ...each(), mandate_id: "m_other" }] },
      { ...DRAFT, round: { after: 40000 } },
      { ...DRAFT, draft: undefined },
      { ...DRAFT, confirm_key: undefined },
      { ...DRAFT, changes: undefined },
    ]) {
      const k = kiffWith({ hold: HOLD, draft });
      expect(await allowOffer("exc_1", false, k.deps)).toMatchObject({ none: "no_change" });
      expect(k.asked.some((a) => a.path.endsWith("/replay"))).toBe(false);
    }
  });

  it("still offers the change when the replay fails, without what it would have done, and says so", async () => {
    const said: string[] = [];
    const k = kiffWith({ hold: HOLD, draft: DRAFT, replay: 500 });
    const out = await allowOffer("exc_1", false, { ...k.deps, log: (l) => said.push(l) });
    expect("offer" in out && out.offer.would).toBeUndefined();
    expect("offer" in out && out.offer.draft).toBe("drf_1");
    expect(said[0]).toContain("drf_1");
  });

  it("answers 409 for a call that no longer waits, and 404 for one KIFF doesn't know", async () => {
    await expect(allowOffer("exc_1", false, kiffWith({ hold: { ...HOLD, status: "approved" } }).deps)).rejects.toMatchObject({ status: 409 });
    await expect(allowOffer("exc_9", false, kiffWith({}).deps)).rejects.toMatchObject({ status: 404 });
  });
});
