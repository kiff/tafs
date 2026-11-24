// What the extension keeps (docs/chrome.md, "What's kept"): no sign-in. What an earlier version saved is deleted on start.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { memoryStore, forgetSavedSignIns, SAVED_SIGN_INS } from "../src/store.js";

test("an update deletes the sign-ins an earlier version saved, and keeps the pairing", async () => {
  const store = memoryStore();
  // As 0.2.2 left them: the sealed entries and the key that sealed them, beside the pairing and the keys.
  await store.set("vault", [{ id: "sec_1", origin: "https://shop.example", hasUsername: true, username: { iv: [1], data: [2] }, password: { iv: [3], data: [4] } }]);
  await store.set("vault_key", { type: "secret" });
  await store.set("pairing", { chromeId: "chr_1" });
  await store.set("keys", { publicKey: "pub" });
  await forgetSavedSignIns(store);
  assert.deepEqual([...store.map.keys()].sort(), ["keys", "pairing"]);
  assert.deepEqual(SAVED_SIGN_INS, ["vault", "vault_key"]);
  await forgetSavedSignIns(store); // a start with nothing to delete changes nothing
  assert.deepEqual([...store.map.keys()].sort(), ["keys", "pairing"]);
});

test("the service worker deletes them on every start, before it reads anything kept", () => {
  const sw = readFileSync(new URL("../src/sw.js", import.meta.url), "utf8");
  const load = sw.slice(sw.indexOf("async function load()"));
  assert.ok(load.indexOf("forgetSavedSignIns(indexedStore)") > 0 && load.indexOf("forgetSavedSignIns(indexedStore)") < load.indexOf('indexedStore.get("keys")'));
});

test("nothing in the extension saves, lists or types a sign-in any more", () => {
  const files = [...readdirSync(new URL("../src/", import.meta.url)).map((f) => "../src/" + f), "../sidepanel/panel.js", "../sidepanel/index.html"];
  for (const f of files) {
    const text = readFileSync(new URL(f, import.meta.url), "utf8");
    for (const gone of ["vault_save", "vault_list", "Vault(", "fillSealed", "type=\"password\"", "share_read", "share_target"]) {
      assert.ok(!text.includes(gone), `${f} still has ${gone}`);
    }
  }
});
