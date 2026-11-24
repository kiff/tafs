import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

/*
 * The shared vector for "Allow this from now on", its second half (vectors/agent-draft-keys.json): the apps refuse a draft
 * Card key their check doesn't compare. iOS (HoldAllow.keysKnown) and Android (HoldAllow.keysKnown) each run its cases; this
 * reference walk runs them here too, so a case that says the wrong thing fails in tafs cloud's CI before either app's, and
 * KIFF's answer pinned in contracts/ is the draft every case starts from.
 */

type Json = null | boolean | number | string | Json[] | { [k: string]: Json };
const root = new URL("../../", import.meta.url);
const vector = JSON.parse(readFileSync(new URL("vectors/agent-draft-keys.json", root), "utf8")) as {
  contract: string; keys: Record<string, string[]>; cases: Array<{ name: string; set: Record<string, Json>; ok: boolean }>;
};
const contract = JSON.parse(readFileSync(new URL(vector.contract, root), "utf8")) as { answer: Json };

const isObject = (v: Json | undefined): v is { [k: string]: Json } => typeof v === "object" && v !== null && !Array.isArray(v);

/** The reference walk, as the apps do it. */
function keysKnown(draft: Json, keys: Record<string, string[]>): boolean {
  const only = (o: Json | undefined, set: string): boolean =>
    isObject(o) && Object.entries(o).every(([k, v]) => v === null || keys[set]!.includes(k));
  const list = (v: Json | undefined, each: (x: Json) => boolean) => v === undefined || v === null || (Array.isArray(v) && v.every(each));
  const quantityOf = (o: { [k: string]: Json }): boolean => {
    const q = o.quantity_of;
    if (q === undefined || q === null) return true;
    if (!only(q, "quantity_of") || typeof o.quantity !== "string" || !isObject(q)) return false;
    const m = /^([A-Za-z0-9_]+)\((.+)\)$/.exec(o.quantity);
    const [kind, parameter] = m ? [m[1], m[2]] : [o.quantity, undefined];
    return q.kind === kind && (q.parameter ?? undefined) === parameter;
  };
  const cap = (c: Json) => only(c, "cap") && quantityOf(c as { [k: string]: Json });
  const total = (t: Json) => only(t, "total") && quantityOf(t as { [k: string]: Json });
  const grant = (g: Json) => only(g, "grant") && isObject(g) && list(g.per_action_max, cap) &&
    (g.resources === undefined || g.resources === null || only(g.resources, "resources"));
  const terms = (t: { [k: string]: Json }) => list(t.grants, grant) && list(t.aggregates, total);
  if (!isObject(draft)) return false;
  return list(draft.cards, (card) => {
    if (!only(card, "card") || !isObject(card)) return false;
    if (card.id !== undefined && card.id !== null && card.id !== card.revises) return false;
    if (!terms(card)) return false;
    const current = card.current;
    return current === undefined || current === null || (only(current, "current") && isObject(current) && terms(current));
  });
}

/** Sets an RFC 6901 pointer on a copy, creating the last key. */
function set(doc: Json, pointer: string, value: Json): Json {
  const out = structuredClone(doc);
  const parts = pointer.split("/").slice(1).map((p) => p.replace(/~1/g, "/").replace(/~0/g, "~"));
  let at: Json = out;
  for (const p of parts.slice(0, -1)) at = Array.isArray(at) ? at[Number(p)]! : (at as { [k: string]: Json })[p]!;
  const last = parts.at(-1)!;
  if (Array.isArray(at)) at[Number(last)] = value;
  else (at as { [k: string]: Json })[last] = value;
  return out;
}

describe("A draft Card's keys, as the apps check them before signing", () => {
  // Invariant: a phone signs a draft only when every key of each of its Cards is one its check reads
  // Category: approval
  // Attack: KIFF's draft gains a Card term the apps don't compare (a widening they'd sign unseen)
  // Refused: any non-null Card key outside the vector's lists, a Card id other than the Card it revises, a quantity_of its quantity doesn't say
  for (const c of vector.cases) {
    it(c.name, () => {
      let draft = contract.answer;
      for (const [pointer, value] of Object.entries(c.set)) draft = set(draft, pointer, value);
      expect(keysKnown(draft, vector.keys)).toBe(c.ok);
    });
  }

  it("KIFF's pinned draft has a Card, a Card in force, grants, caps and totals, so every list is walked", () => {
    const card = (contract.answer as { cards: Array<Record<string, unknown>> }).cards[0]!;
    expect(card.current).toBeTruthy();
    expect((card.grants as unknown[]).length).toBeGreaterThan(0);
    expect((card.aggregates as unknown[]).length).toBeGreaterThan(0);
  });
});
