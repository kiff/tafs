// End to end: pairing and connecting, as docs/chrome.md says ("Pairing: it starts on the phone", "Connect: both ends prove
// themselves"). The owner types the phone's code in the real side panel; the fake planque, playing the phone, checks the
// extension's proof of its key under the code's secret and answers with its own; then the extension opens the relay and both
// ends prove themselves.

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { playwright, start, until, pairNow, SITES } from "./harness.mjs";
import { keyPair } from "./planque.mjs";
import { LABEL, fromB64u, verify } from "../src/crypto.js";
import { extensionProof } from "../src/pairing.js";

const skip = !playwright;
let h;

before(async () => {
  if (!skip) h = await start();
});

after(async () => {
  if (h) await h.close();
});

test("the offer proves the extension's key under the code's secret, and names this build", { skip }, async () => {
  const offer = h.planque.offer;
  assert.ok(offer, "the panel's code reached planque");
  assert.equal(offer.proof, await extensionProof(h.planque.secret, fromB64u(offer.key), offer.kiff_offer || ""));
  assert.match(offer.manifest_hash, /^[0-9a-f]{64}$/, "the manifest's SHA-256 in lower-case hex");
  assert.equal(offer.version, JSON.parse(await h.inPanel("JSON.stringify(chrome.runtime.getManifest())")).version);
  assert.ok(offer.kiff_offer, "the view-only KIFF device rides the same pairing");
  // Only the code's lookup went to planque, never its secret.
  for (const [method, path] of h.planque.requests.map((r) => r.split(" "))) {
    assert.ok(!path.includes(h.planque.secret), method + " " + path);
  }
});

test("the hello is signed by the paired key for planque's challenge, and the welcome is accepted", { skip }, async () => {
  const hello = h.planque.hello;
  assert.equal(hello.chrome_id, h.planque.chromeId);
  const body = { chrome_id: hello.chrome_id, nonce_p: hello.nonce_p, nonce_e: hello.nonce_e, version: hello.version, manifest_hash: hello.manifest_hash };
  assert.equal(await verify(h.planque.extKey, LABEL.hello, body, hello.sig), true);
  assert.equal(hello.manifest_hash, h.planque.offer.manifest_hash);
  // The extension says it's connected only once the welcome checked out with the pinned key, and shows planque's list.
  const state = await until(async () => {
    const s = await h.inPanel("chrome.storage.session.get('state').then((x) => x.state)");
    return s && s.connected ? s : null;
  }, "the extension to say it's connected");
  assert.equal(state.paired, true);
  assert.equal(state.name, "Test Chrome");
  // planque's list, with the phone's signature over the grant's sites as planque relays it (checked where it's used).
  assert.deepEqual(state.connections.map(({ sites_signed: _signed, ...c }) => c), [{ grant: h.planque.grant, agent: h.planque.agent, name: h.planque.taf, sites: SITES }]);
  assert.deepEqual(state.connections[0].sites_signed.sites, SITES);
  assert.equal(await h.inPanel("document.getElementById('pair').hidden"), true, "the panel no longer asks for a code");
});

test("planque's close 4410 unpairs the extension: it wipes itself and asks for a code again", { skip }, async () => {
  h.planque.send({ type: "close", code: 4410, reason: "unpaired" });
  await until(async () => {
    const s = await h.inPanel("chrome.storage.session.get('state').then((x) => x.state || null)");
    return s && !s.paired;
  }, "the extension to say it's unpaired");
  await until(() => h.inPanel("document.getElementById('pair').hidden === false"), "the panel's code field");
  // A step now finds no paired extension: nothing answers it.
  await until(() => !h.planque.socket, "the relay to close");
});

test("a confirmation that isn't the phone's is refused: nothing is paired and the relay never opens", { skip }, async () => {
  // Unpaired by the test before. A new code; planque swaps the phone's key in its answer for one of its own.
  const key = (await keyPair()).spki;
  h.planque.newCode({ forge: (answer) => ({ ...answer, phone_key: key }) });
  await h.inPanel(`(() => {
    document.getElementById("code").value = ${JSON.stringify(h.planque.code)};
    document.getElementById("pairForm").requestSubmit();
    return true;
  })()`);
  const said = await h.inPanel("chrome.i18n.getMessage('pair_error_proof')");
  await until(() => h.inPanel(`document.getElementById("pairStatus").textContent === ${JSON.stringify(said)}`), "the panel to say the proof failed", 15_000);
  const s = await h.inPanel("chrome.storage.session.get('state').then((x) => x.state || null)");
  assert.equal(s.paired, false);
  assert.equal(h.planque.socket, null);
});

test("paired again with a good code, the extension connects under the new Chrome id", { skip }, async () => {
  h.planque.newCode();
  await pairNow(h);
  assert.equal(h.planque.hello.chrome_id, h.planque.chromeId);
  const r = await h.planque.sendStep({ op: "open", run: "run_after_repair", url: h.sites.url("news.test", "/article") });
  assert.equal(r.verified, true);
  assert.equal(r.ok, true, JSON.stringify(r.error));
});
