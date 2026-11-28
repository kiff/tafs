import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { sha256 } from "../src/cloud/jcs.js";

// contracts/purchase.json: the purchase case every side reads. Written by hand as data until KIFF's own test writes
// it; this reader keeps its promises true as it changes.
const doc = JSON.parse(readFileSync(new URL("../../contracts/purchase.json", import.meta.url), "utf8"));
const c = doc.case.value;

describe("the purchase contract", () => {
  it("signs every field of the case, the delivery address included, under one digest that every side carries", () => {
    expect(Object.keys(c).sort()).toEqual(doc.signed_fields);
    expect(doc.signed_fields).toEqual(expect.arrayContaining(["delivery", "price", "lines", "merchant", "currency", "valid_for_seconds", "path", "version"]));
    const digest = sha256(c);
    expect(doc.case.digest).toBe(digest);
    expect(doc.approval.value.digest).toBe(digest);
    expect(doc.answer.value.purchase.digest).toBe(digest);
    expect(doc.receipt.value.digest).toBe(digest);
    // Any signed change is another digest: the address moved after the decision is another case version.
    expect(sha256({ ...c, delivery: { ...c.delivery, line1: "1 other street" } })).not.toBe(digest);
  });

  it("keeps amounts as integers in minor units, the maximum as the total plus the price rule, and the card's cap at that maximum", () => {
    for (const l of c.lines) expect(Number.isInteger(l.amount) && Number.isInteger(l.quantity) && l.quantity > 0).toBe(true);
    expect(c.price.lines).toBe(c.lines.reduce((s: number, l: { amount: number }) => s + l.amount, 0));
    expect(c.price.total).toBe(c.price.lines + c.price.shipping + c.price.tax);
    expect(c.price.max).toBe(c.price.total + (c.price.rule?.up_to ?? 0));
    expect(c.grant.cap).toBe(c.price.max);
    expect(c.grant.uses).toBe(1);
    expect(c.valid_for_seconds).toBe(900);
    for (const d of [doc.decision.by_owner, doc.decision.within_limits]) expect(d.reserved).toBe(c.price.max);
  });

  it("names a value from its enums for every enumerated field", () => {
    const e = doc.case.enums;
    expect(e.mode).toContain(c.mode);
    expect(e.route).toContain(c.route);
    expect(e.path).toContain(c.path);
    expect(e["payer.kind"]).toContain(c.payer.kind);
    expect(e["grant.kind"]).toContain(c.grant.kind);
    expect(doc.decision["approver.role"]).toContain(doc.decision.by_owner.approver.role);
  });

  it("has unknown as a state that blocks execution until reconciled, and every next state is one it lists", () => {
    const states = doc.states.case.map((s: { state: string }) => s.state);
    for (const s of doc.states.case) for (const n of s.next) expect(states, `${s.state} → ${n}`).toContain(n);
    const unknown = doc.states.case.find((s: { state: string }) => s.state === "unknown");
    expect(unknown.next.sort()).toEqual(["approved", "done"]);
    expect(unknown.means).toMatch(/refused until reconciled/);
    expect(doc.states.order).toContain("unknown");
    expect(doc.execution.outcome).toContain("unknown");
    for (const terminal of ["refused", "superseded", "expired", "done", "not_bought"]) {
      expect(doc.states.case.find((s: { state: string }) => s.state === terminal).next).toEqual([]);
    }
  });

  it("approves an authorization at most once, within the case, and takes a timeout as a decline", () => {
    expect(doc.authorization.timeout_is_decline).toBe(true);
    expect(doc.authorization.deadline_ms).toBeLessThanOrEqual(2000);
    expect(doc.authorization.approves_only_if).toEqual(expect.arrayContaining(["approved_once is false", "the currency is the case's"]));
    for (const d of [doc.decision.by_owner, doc.decision.within_limits]) expect(d.approved_once).toBe(false);
    expect(doc.execution.value.lease.version).toBe(c.version);
  });

  it("gives the apps KIFF's words for every line they draw, and never a card number", () => {
    const w = doc.approval.value.words;
    for (const k of ["title", "total", "show_more", "lines", "delivery", "price_rule", "grant", "buys_now", "reason", "prompt"]) expect(w[k], k).toBeTruthy();
    expect(w.lines).toHaveLength(c.lines.length);
    const text = JSON.stringify(doc);
    // A Luhn-shaped run of 13 to 19 digits is never in the contract.
    for (const run of text.match(/\d{13,19}/g) ?? []) {
      const digits = run.split("").reverse().map(Number);
      const sum = digits.reduce((s, d, i) => s + (i % 2 ? (d * 2 > 9 ? d * 2 - 9 : d * 2) : d), 0);
      expect(sum % 10, run).not.toBe(0);
    }
  });

  it("signs a receipt over the case's digest, with the issuer's merchant beside the case's", () => {
    const r = doc.receipt.value;
    expect(r.case_id).toBe(c.case_id);
    expect(r.version).toBe(c.version);
    expect(r.merchant.case.category).toBe(c.merchant.category);
    expect(r.merchant.issuer).toBeTruthy();
    expect(r.amounts.captured).toBeLessThanOrEqual(r.amounts.approved_max);
    expect(doc.states.money).toContain(r.states.money);
    expect(doc.states.order).toContain(r.states.order);
    expect(r.kid).toBeTruthy();
  });
});
