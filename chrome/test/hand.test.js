import { test } from "node:test";
import assert from "node:assert/strict";
import { LABEL, verify, newKeyPair, publicSPKI, b64u } from "../src/crypto.js";
import { validWords, withoutValues, handMessage, HandAnswers, WORDS_MAX, HAND_MAX_MS } from "../src/hand.js";
import { result } from "../src/protocol.js";

test("the taf's words are plain text of 1 to 280 characters, with no control or direction character", () => {
  for (const ok of ["Fill in the card number", "a", "é".repeat(WORDS_MAX), "😀".repeat(WORDS_MAX), "Rellena el número · luego pulsa Hecho"]) assert.equal(validWords(ok), true, ok.slice(0, 20));
  for (const bad of ["", "   ", "x".repeat(WORDS_MAX + 1), "a\nb", "a\tb", "a\u0000b", "a‮b", "a⁦b", "a‏b", "a؜b", 5, null, undefined, {}]) assert.equal(validWords(bad), false, JSON.stringify(bad));
  assert.equal(HAND_MAX_MS, 30 * 60_000, "planque's own 30 minutes");
});

test("a page read after the person typed has none of what is in its fields", () => {
  const page = { read_id: "rd", url: "https://shop.example/", title: "Pay", text: "t", fields: [{ ref: "f1", label: "Name", kind: "text", value: "Ana" }, { ref: "f2", label: "Gift", kind: "checkbox", value: "checked" }, { ref: "f3", label: "Pw", kind: "password", masked: true }, { ref: "f4", label: "Size", kind: "select", value: "Large", options: ["Small", "Large"] }], controls: [{ ref: "c1", label: "Pay" }] };
  const out = withoutValues(page);
  assert.ok(out.fields.every((f) => !("value" in f)));
  assert.deepEqual(out.fields.map((f) => f.ref), ["f1", "f2", "f3", "f4"]);
  assert.deepEqual(out.fields[3].options, ["Small", "Large"], "a select's choices are the page's, not the person's");
  assert.equal(out.fields[2].masked, true);
  assert.deepEqual(out.controls, page.controls);
  assert.equal(page.fields[0].value, "Ana", "the read itself isn't changed");
  assert.equal(withoutValues(null), null);
  assert.deepEqual(withoutValues({ title: "x" }), { title: "x", fields: [] });
});

test("the answer is signed by this Chrome's key over {hand, outcome, page?}, under its own label", async () => {
  const k = await newKeyPair();
  const key = b64u(await publicSPKI(k.publicKey));
  const page = { read_id: "rd", fields: [] };
  const done = await handMessage(k.privateKey, { hand: "h1", outcome: "done", page });
  assert.deepEqual({ type: done.type, hand: done.hand, outcome: done.outcome, page: done.page }, { type: "hand_result", hand: "h1", outcome: "done", page });
  assert.equal(await verify(key, LABEL.hand, { hand: "h1", outcome: "done", page }, done.sig), true);
  const declined = await handMessage(k.privateKey, { hand: "h1", outcome: "declined" });
  assert.ok(!("page" in declined), "no page key when there is none");
  assert.equal(await verify(key, LABEL.hand, { hand: "h1", outcome: "declined" }, declined.sig), true);
  assert.equal(await verify(key, LABEL.hand, { hand: "h1", outcome: "done" }, declined.sig), false, "another outcome");
  assert.equal(await verify(key, LABEL.hand, { hand: "h2", outcome: "declined" }, declined.sig), false, "another hand-over");
  assert.equal(await verify(key, LABEL.result, { hand: "h1", outcome: "declined" }, declined.sig), false, "a step's result can never stand for it");
});

test("a step's result says handed, and a result with no handed says nothing of it", async () => {
  const k = await newKeyPair();
  const handed = await result(k.privateKey, { id: "h1", nonce: "n" }, "chr_1", { handed: true });
  assert.equal(handed.handed, true);
  assert.equal(handed.ok, true);
  assert.ok(!("handed" in (await result(k.privateKey, { id: "o1", nonce: "n" }, "chr_1", { page: { read_id: "r" } }))));
});

test("an answer is kept until planque has it, resent as long as it hasn't, and stopped when planque never will", async () => {
  let kept;
  const answers = new HandAnswers({ get: async () => kept, set: async (v) => (kept = v) });
  assert.deepEqual(await answers.all(), []);
  await answers.add({ hand: "h1", outcome: "done", page: { read_id: "r" } });
  await answers.add({ hand: "h2", outcome: "declined", page: null });
  await answers.add({ hand: "h2", outcome: "declined", page: null });
  assert.deepEqual((await answers.all()).map((a) => a.hand), ["h1", "h2"], "each hand-over once");
  await answers.heard({ type: "hand_kept", hand: "h1" });
  assert.deepEqual((await answers.all()).map((a) => a.hand), ["h2"], "kept by planque: done with");
  await answers.add({ hand: "h3", outcome: "done", page: { read_id: "r" } });
  await answers.heard({ type: "hand_refused", hand: "h3", code: "too_large" });
  assert.deepEqual((await answers.all()).find((a) => a.hand === "h3"), { hand: "h3", outcome: "done", page: null }, "too large: Done again, with no page");
  await answers.heard({ type: "hand_refused", hand: "h3", code: "too_large" });
  assert.equal((await answers.all()).some((a) => a.hand === "h3"), false, "and not a third time");
  for (const code of ["unknown_hand", "expired", "unsigned"]) {
    await answers.add({ hand: "x_" + code, outcome: "declined", page: null });
    await answers.heard({ type: "hand_refused", hand: "x_" + code, code });
    assert.equal((await answers.all()).some((a) => a.hand === "x_" + code), false, code);
  }
  // planque couldn't store it just now: it stays to go again, and only a kept answer or a final refusal ends it.
  await answers.add({ hand: "h4", outcome: "done", page: { read_id: "r" } });
  await answers.heard({ type: "hand_refused", hand: "h4", code: "not_kept" });
  assert.deepEqual((await answers.all()).find((a) => a.hand === "h4"), { hand: "h4", outcome: "done", page: { read_id: "r" } }, "not_kept: it stays, page and all");
  await answers.heard({ type: "hand_kept", hand: "h4" });
  assert.equal((await answers.all()).some((a) => a.hand === "h4"), false, "then kept: done with");
  await answers.heard({ type: "hand_kept" });
  await answers.heard(null);
  assert.deepEqual((await answers.all()).map((a) => a.hand), ["h2"], "nothing else changes it");
});
