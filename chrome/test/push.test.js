import { test } from "node:test";
import assert from "node:assert/strict";
import { LABEL, verify, newKeyPair, publicSPKI, b64u } from "../src/crypto.js";
import { keyBytes, subscriptionOf, ensureSubscription, pushMessage } from "../src/push.js";

const VAPID = b64u(Uint8Array.from({ length: 65 }, (_, i) => (i * 7 + 4) % 256));
const SUB = { endpoint: "https://fcm.googleapis.com/fcm/send/abc", keys: { p256dh: "BPk", auth: "au" } };

test("planque's VAPID key is read as the bytes the push service wants", () => {
  const bytes = keyBytes(VAPID);
  assert.equal(bytes.length, 65);
  assert.equal(bytes[0], 4);
  assert.equal(b64u(bytes), VAPID);
});

test("a subscription goes to planque only as an https endpoint with its two keys", () => {
  assert.deepEqual(subscriptionOf({ toJSON: () => SUB }), SUB);
  assert.deepEqual(subscriptionOf({ ...SUB, extra: "x" }), SUB, "nothing else goes along");
  for (const bad of [null, {}, { endpoint: "http://x.example/p", keys: SUB.keys }, { endpoint: SUB.endpoint }, { endpoint: SUB.endpoint, keys: { p256dh: "a" } }, { endpoint: SUB.endpoint, keys: { p256dh: "", auth: "b" } }, { endpoint: "https://x.example/" + "a".repeat(1100), keys: SUB.keys }]) {
    assert.equal(subscriptionOf(bad), null, JSON.stringify(bad));
  }
});

test("the subscription is the one the extension has, else a new silent one, and never without planque's key", async () => {
  const calls = [];
  const pm = (have) => ({ getSubscription: async () => have, subscribe: async (o) => (calls.push(o), { toJSON: () => SUB }) });
  const have = { toJSON: () => SUB };
  assert.equal(await ensureSubscription(pm(have), VAPID), have, "the one it has");
  assert.deepEqual(calls, []);
  const made = await ensureSubscription(pm(null), VAPID);
  assert.deepEqual(made.toJSON(), SUB);
  assert.equal(calls[0].userVisibleOnly, false, "silent: the extension shows no notification");
  assert.deepEqual([...calls[0].applicationServerKey], [...keyBytes(VAPID)]);
  assert.equal(await ensureSubscription(pm(null), ""), null, "a build with no key subscribes to nothing");
  assert.equal(calls.length, 1);
  assert.equal(await ensureSubscription(undefined, VAPID), null);
  assert.equal(await ensureSubscription({ getSubscription: async () => null, subscribe: async () => { throw new Error("push service unreachable"); } }, VAPID), null, "Chrome refusing is not an error");
});

test("the message to planque is signed by this Chrome's key over the subscription", async () => {
  const ext = await newKeyPair();
  const m = await pushMessage(ext.privateKey, SUB);
  assert.equal(m.type, "push");
  assert.deepEqual(m.subscription, SUB);
  const key = b64u(await publicSPKI(ext.publicKey));
  assert.equal(await verify(key, LABEL.push, SUB, m.sig), true, "over the subscription alone, as planque checks it");
  const other = await newKeyPair();
  assert.equal(await verify(b64u(await publicSPKI(other.publicKey)), LABEL.push, SUB, m.sig), false, "another Chrome's key");
  assert.equal(await verify(key, LABEL.push, { ...SUB, endpoint: "https://evil.example/p" }, m.sig), false, "another endpoint");
  assert.equal(await verify(key, LABEL.step, SUB, m.sig), false, "another purpose");
});
