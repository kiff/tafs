import { describe, expect, it } from "vitest";
import { allowOffer, type Breach, type Change, refusal } from "../src/cloud/allow.js";

// qa-kiff's negatives for "Allow this from now on" (docs/allow-from-now-on.md, Tests): what tafs cloud must never
// offer, beside the cases test/allow.test.ts already holds. Each one names the guard it needs.

const EACH: Breach = { mandate_id: "m_refunds", outcome: "per_action_exceeded", quantity: "sum(amount)", unit: "EUR", limit: 15000, draw: 32000 };
const TOTAL: Breach = { mandate_id: "m_refunds", outcome: "limit_reached", quantity: "sum(amount)", unit: "EUR", limit: 50000, used: 42000, draw: 18000, window: "calendar_day" };
const COUNT: Breach = { mandate_id: "m_mail", outcome: "limit_reached", quantity: "count", limit: 0, used: 0, draw: 1, window: "calendar_day" };

const each = (after = 32000, rounded = false): Change => ({ mandate_id: "m_refunds", kind: "per_action", before: 15000, after, unit: "EUR", rounded });
const total = (after = 60000): Change => ({ mandate_id: "m_refunds", kind: "total", before: 50000, after, unit: "EUR", window: "calendar_day", rounded: false });

function kiffWith(answers: { hold?: object; draft?: object; replay?: object }) {
  const asked: string[] = [];
  const doFetch = (async (url: string) => {
    const path = String(url).replace("https://kiff.test", "");
    asked.push(path);
    const send = (x?: object) => new Response(JSON.stringify(x ?? {}), { status: x ? 200 : 404 });
    if (path.endsWith("/allow-draft")) return send(answers.draft);
    if (path.endsWith("/replay")) return send(answers.replay);
    return send(answers.hold);
  }) as typeof fetch;
  return { asked, deps: { token: "ana", agent: "rae", lang: "en" as const, fetch: doFetch, base: "https://kiff.test" } };
}

const HOLD = { id: "exc_1", subject: "rae", kind: "hold", status: "held", title: "Refund a payment", breaches: [EACH] };
const DRAFT = { draft: "drf_1", version: "v1", confirm_key: "critical", changes: [each()], round: { after: 35000 } };

describe("Allow this from now on: what is never offered (qa-kiff's negatives)", () => {
  it("asks KIFF for no draft when the call's figure isn't a whole, safe number of minor units", async () => {
    // A figure tafs cloud can't trust can't be the one the Card stops at: num() fails closed before allow-draft.
    for (const draw of [320.5, "32000", 2 ** 53, Number.NaN, null]) {
      const k = kiffWith({ hold: { ...HOLD, breaches: [{ ...EACH, draw }] }, draft: DRAFT });
      expect(await allowOffer("exc_1", false, k.deps)).toMatchObject({ none: "no_change" });
      expect(k.asked).toEqual(["/v1/me/card-exceptions/exc_1"]);
    }
  });

  it("refuses a rounding that goes past one step, claims a rounding that moved nothing, or rounds a count", () => {
    expect(refusal([EACH], [each(40000, true)], true)).toMatch(/no per_action change on m_refunds/); // two steps up
    expect(refusal([{ ...EACH, draw: 35000 }], [each(35000, true)], true)).toMatch(/no per_action change/); // already round
    expect(refusal([COUNT], [{ mandate_id: "m_mail", kind: "count", before: 0, after: 2, window: "calendar_day", rounded: true }], true))
      .toMatch(/no count change on m_mail/);
  });

  it("refuses two changes of one kind for a call outside two limits of one Card", () => {
    // A per-call and a daily limit on the same mandate: KIFF must answer one of each, never the wider one twice.
    expect(refusal([EACH, TOTAL], [each(), each()], false)).toMatch(/no total change on m_refunds/);
    expect(refusal([EACH, TOTAL], [total(), total()], false)).toMatch(/no per_action change on m_refunds/);
    expect(refusal([EACH, TOTAL], [each(), total()], false)).toBeUndefined();
  });

  it("refuses a total lowered or left as it is, which is no change for the held call", () => {
    expect(refusal([TOTAL], [total(50000)], false)).toMatch(/no total change/);
    expect(refusal([TOTAL], [total(59999)], false)).toMatch(/no total change/);
  });

  it("refuses a change that would lower the limit, though it is the call's own figure", () => {
    // The call fits under the limit (10000 used + 18000 drawn, limit 50000), so its own figure, 28000, is lower:
    // only `after > before` refuses it.
    const UNDER: Breach = { ...TOTAL, used: 10000 };
    expect(refusal([UNDER], [total(28000)], false)).toMatch(/no total change/);
  });

  it("answers each breach with its own change: one change never stands for two", () => {
    // Two breaches of one kind on one Card: each takes its change off the list, or the first would answer both and a
    // second, wider change would pass unread.
    expect(refusal([TOTAL, TOTAL], [total(), total(999999)], false)).toMatch(/no total change on m_refunds/);
    expect(refusal([TOTAL, TOTAL], [total(), total()], false)).toBeUndefined();
  });

  it("keeps a hold's id inside its own path: nothing it holds reaches another route", async () => {
    const k = kiffWith({ hold: HOLD, draft: DRAFT, replay: { would_allow: { calls: 0 } } });
    await allowOffer("exc_1/../../agent-drafts/drf_9", false, k.deps).catch(() => undefined);
    expect(k.asked[0]).toBe("/v1/me/card-exceptions/exc_1%2F..%2F..%2Fagent-drafts%2Fdrf_9");
    expect(k.asked.every((p) => p.startsWith("/v1/me/card-exceptions/exc_1%2F") || p === "/v1/me/agent-drafts/drf_1/replay")).toBe(true);
  });

  it("sends no replay and no offer once its own check refuses KIFF's change", async () => {
    const said: string[] = [];
    const k = kiffWith({ hold: { ...HOLD, breaches: [EACH, TOTAL] }, draft: { ...DRAFT, changes: [each(), each()], round: undefined } });
    const out = await allowOffer("exc_1", false, { ...k.deps, log: (l) => said.push(l) });
    expect(out).toMatchObject({ none: "refused" });
    expect("offer" in out).toBe(false);
    expect(k.asked.filter((p) => p.endsWith("/replay"))).toEqual([]);
    expect(said.join("\n")).toContain("drf_1");
  });
});
