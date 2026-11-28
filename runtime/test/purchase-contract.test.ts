import { createHash } from "node:crypto";
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
    expect(c.merchant.match.name_key).toBe(c.merchant.name.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/[^a-z0-9]/g, ""));
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
    expect(unknown.next.sort()).toEqual(["approved", "done", "not_bought"]);
    expect(doc.states.leave_unknown.to_approved).toMatch(/`none` or `reversed`/);
    expect(doc.states.leave_unknown.to_approved).toMatch(/valid_until/);
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
    for (const k of ["title", "subtitle", "total", "total_note", "show_more", "lines", "charges", "delivery", "price_rule", "grant", "buys_now", "nothing_until", "reason", "prompt"]) expect(w[k], k).toBeTruthy();
    expect(w.lines).toHaveLength(c.lines.length);
    // The open list adds up to the total the owner approves: its lines and its charges.
    const cents = (a: string) => Number(a.replace(/[^\d]/g, ""));
    const listed = [...w.lines, ...w.charges].reduce((sum: number, l: { amount: string }) => sum + cents(l.amount), 0);
    expect(listed).toBe(c.price.total);
    for (const l of w.lines) expect(Object.keys(l).sort()).toEqual(["amount", "name", "quantity"]);
    // "At this shop" holds only because the live authorization matches the shop itself.
    expect(w.grant).toMatch(/at this shop/);
    expect(doc.authorization.approves_only_if.join(" ")).toMatch(/merchant\.match/);
    const text = JSON.stringify(doc);
    // A Luhn-shaped run of 13 to 19 digits is never in the contract.
    for (const run of text.match(/\d{13,19}/g) ?? []) {
      const digits = run.split("").reverse().map(Number);
      const sum = digits.reduce((s, d, i) => s + (i % 2 ? (d * 2 > 9 ? d * 2 - 9 : d * 2) : d), 0);
      expect(sum % 10, run).not.toBe(0);
    }
  });

  it("binds the owner's signed bytes to this case's digest, with a vector of the exact message", () => {
    const v = doc.answer.vector;
    const purchase = `purchase:${c.case_id}:${c.version}:${doc.case.digest}`;
    const lines = ["kiff.answer.v1", v.tenant, v.exception_id, v.answer, v.next_step, v.note ? createHash("sha256").update(v.note).digest("hex") : "", String(v.hold_version), v.signed_at, purchase];
    expect(v.message).toBe(lines.join("\n"));
    expect(v.message.split("\n").at(-1)).toBe(purchase);
    expect(createHash("sha256").update(v.message, "utf8").digest("hex")).toBe(v.message_sha256);
    expect(Object.keys(doc.answer.answers).sort()).toEqual(["approve-once", "reject"]);
    expect(doc.answer.value.exception_id).toBe(v.exception_id);
  });

  it("scopes a token as the card is, links the card to the case, and never outlives the decision", () => {
    const t = doc.execution.token.value;
    expect(t.max_amount).toBe(c.grant.cap);
    expect(t.currency).toBe(c.currency);
    // The token names the seller's Stripe profile, the signed one, read from the checkout's own site.
    expect(c.path).toBe("token");
    expect(c.merchant.stripe_profile).toMatch(/^profile_[A-Za-z0-9]{1,64}$/);
    expect(t.seller).toBe(c.merchant.stripe_profile);
    expect(doc.case.about).toMatch(/stripe_profile[^.]*read only from the case's own checkout site/);
    expect(doc.execution.token.rules.join(" ")).toMatch(/before live/);
    expect(Date.parse(t.expires_at)).toBeLessThanOrEqual(Date.parse(doc.decision.by_owner.valid_until));
    const card = doc.execution.value.card;
    expect(card.metadata.case_id).toBe(c.case_id);
    expect(card.metadata.permit_jti).toBeTruthy();
    expect(Date.parse(card.created_at)).toBeGreaterThanOrEqual(Date.parse(doc.decision.by_owner.at));
    expect(doc.case.v1.route).toEqual(["issued_card"]);
  });

  it("signs a receipt over the case's digest, with the issuer's merchant beside the case's", () => {
    const r = doc.receipt.value;
    expect(r.case_id).toBe(c.case_id);
    expect(r.version).toBe(c.version);
    expect(r.merchant.case.category).toBe(c.merchant.category);
    expect(r.merchant.case.stripe_profile).toBe(c.merchant.stripe_profile);
    expect(r.merchant.issuer).toBeTruthy();
    expect(r.amounts.captured).toBeLessThanOrEqual(r.amounts.approved_max);
    expect(doc.states.money).toContain(r.states.money);
    expect(doc.states.order).toContain(r.states.order);
    expect(r.kid).toBeTruthy();
    expect(r.path).toBe(c.path);
    for (const k of ["line", "state", "allowed", "card", "order", "refunds"]) expect(r.words[k], k).toBeTruthy();
    expect(doc.receipt["approver.method"]).toContain(r.approver.method);
    expect(typeof r.approver.self).toBe("boolean");
    expect(doc.receipt.examples.unknown.words.state).toBe("Not known yet");
    expect(doc.receipt.examples.card_fill.words.card).not.toBe(r.words.card);
  });
});
