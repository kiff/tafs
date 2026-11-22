import { test } from "node:test";
import assert from "node:assert/strict";
import { canCapture, encode, Shots, signScreenshot, sha256Hex, MAX_BYTES, MAX_WIDTH } from "../src/shot.js";
import { LABEL, verify, newKeyPair, publicSPKI, b64u } from "../src/crypto.js";

test("only an https site that isn't sensitive, a checkout or a payment frame is pictured", () => {
  assert.equal(canCapture("https://shop.example/orders", {}), true);
  assert.equal(canCapture("http://shop.example/orders", {}), false, "never plain http");
  assert.equal(canCapture("chrome://settings", {}), false);
  assert.equal(canCapture("https://shop.example/pay", { payment_frame: true }), false);
  assert.equal(canCapture("https://shop.example/cart", { checkout: { lines: [] } }), false);
  assert.equal(canCapture("https://paypal.com/signin", {}), false, "a sensitive site");
  assert.equal(canCapture("not a url", {}), false);
});

/** Stands in for the browser's own image tools: a bitmap of `w` by `h`, and a canvas that answers sizes per quality. */
function fakeDeps({ w = 2560, h = 1440, sizes = { 0.6: 50_000, 0.4: 30_000 }, type = "image/webp" } = {}) {
  const log = { canvas: null, qualities: [], closed: false };
  return {
    log,
    createImageBitmap: async () => ({ width: w, height: h, close: () => (log.closed = true) }),
    OffscreenCanvas: class {
      constructor(width, height) {
        log.canvas = { width, height };
      }
      getContext() {
        return { drawImage: () => {} };
      }
      async convertToBlob({ type: t, quality }) {
        log.qualities.push([t, quality]);
        return { type, size: sizes[quality] ?? 999_999, arrayBuffer: async () => new ArrayBuffer(sizes[quality] ?? 1) };
      }
    },
  };
}

test("a picture is scaled to at most 1280 px wide and encoded as WebP at quality 0.6", async () => {
  const deps = fakeDeps();
  const out = await encode({}, deps);
  assert.equal(out.size, 50_000);
  assert.deepEqual(deps.log.canvas, { width: MAX_WIDTH, height: 720 }, "the same shape, 1280 wide");
  assert.deepEqual(deps.log.qualities, [["image/webp", 0.6]]);
  assert.equal(deps.log.closed, true, "the bitmap is released");
});

test("a small picture isn't scaled up", async () => {
  const deps = fakeDeps({ w: 800, h: 600 });
  await encode({}, deps);
  assert.deepEqual(deps.log.canvas, { width: 800, height: 600 });
});

test("one that comes out too big is encoded once more at 0.4, and dropped if it is still too big", async () => {
  const again = fakeDeps({ sizes: { 0.6: MAX_BYTES + 1, 0.4: 90_000 } });
  assert.equal((await encode({}, again)).size, 90_000);
  assert.deepEqual(again.log.qualities.map((q) => q[1]), [0.6, 0.4]);
  const never = fakeDeps({ sizes: { 0.6: 400_000, 0.4: MAX_BYTES + 1 } });
  assert.equal(await encode({}, never), null);
});

test("anything that isn't WebP is dropped", async () => {
  assert.equal(await encode({}, fakeDeps({ type: "image/png" })), null);
});

/** A paired Chrome with a front tab at `url`, and the page's mask answering `maskOk`. */
async function rig({ url = "https://shop.example/orders", active = true, maskOk = true, capture = async () => "data:image/png;base64,AAAA", deps, fetchOk = true } = {}) {
  const key = await newKeyPair();
  const calls = [];
  const chrome = {
    tabs: { get: async () => ({ id: 7, url, active }), captureVisibleTab: async (...a) => (calls.push(["capture", ...a]), capture()) },
  };
  const inTab = async (tab, func) => {
    const src = String(func);
    if (src.includes("maskForShot")) return calls.push(["mask"]) && { ok: maskOk, masked: 1 };
    if (src.includes("unmaskShot")) return calls.push(["unmask"]) && true;
    return undefined;
  };
  const fetched = [];
  const fetch = async (u, init) => {
    fetched.push([u, init]);
    if (typeof u === "string" && u.startsWith("data:")) return { blob: async () => ({}) };
    return { ok: fetchOk };
  };
  const shots = new Shots({ chrome, inTab, keys: () => ({ privateKey: key.privateKey }), pairing: () => ({ chromeId: "chr_1" }), base: "https://planque.test", fetch, deps: deps || fakeDeps(), timeout: 30 });
  return { shots, key, calls, fetched };
}

test("a picture is taken with the secret fields covered, and the covers come off after it", async () => {
  const { shots, calls } = await rig();
  const got = await shots.capture({ tabId: 7, windowId: 9, page: {} });
  assert.ok(got.png);
  assert.deepEqual(calls.map((c) => c[0]), ["mask", "capture", "unmask"]);
  assert.deepEqual(calls[1], ["capture", 9, { format: "png" }]);
});

test("the covers come off even when the browser can't take the picture, and a hung one is given up on", async () => {
  const failing = await rig({ capture: async () => { throw new Error("no window"); } });
  assert.equal((await failing.shots.capture({ tabId: 7, windowId: 9, page: {} })).why, "error");
  assert.deepEqual(failing.calls.map((c) => c[0]), ["mask", "capture", "unmask"]);
  const hung = await rig({ capture: () => new Promise(() => {}) });
  const got = await hung.shots.capture({ tabId: 7, windowId: 9, page: {} });
  assert.equal(got.why, "hidden", "gave up after the deadline");
  assert.deepEqual(hung.calls.map((c) => c[0]), ["mask", "capture", "unmask"]);
});

test("no picture when a secret field can't be confirmed covered, on a sensitive site, or when the tab isn't in front", async () => {
  const unmasked = await rig({ maskOk: false });
  assert.equal((await unmasked.shots.capture({ tabId: 7, windowId: 9, page: {} })).why, "mask");
  assert.ok(!unmasked.calls.some((c) => c[0] === "capture"), "never taken");
  assert.deepEqual(unmasked.calls.map((c) => c[0]), ["mask", "unmask"], "the covers still come off");
  const bank = await rig({ url: "https://paypal.com/x" });
  assert.equal((await bank.shots.capture({ tabId: 7, windowId: 9, page: {} })).why, "site");
  assert.equal(bank.calls.length, 0, "nothing was even covered");
  const back = await rig({ active: false });
  assert.equal((await back.shots.capture({ tabId: 7, windowId: 9, page: {} })).why, "hidden");
  const pay = await rig();
  assert.equal((await pay.shots.capture({ tabId: 7, windowId: 9, page: { payment_frame: true } })).why, "site");
});

test("the picture is posted for its step as JSON with the WebP in base64url, signed by the extension's key over the step and its hash", async () => {
  const { shots, key, fetched } = await rig();
  const out = await shots.send("data:image/png;base64,AAAA", "CHROMESTEP#1 a/b");
  assert.equal(out, "sent");
  const [url, init] = fetched.at(-1);
  assert.equal(url, "https://planque.test/v1/extension/steps/CHROMESTEP%231%20a%2Fb/screenshot");
  assert.equal(init.method, "POST");
  assert.deepEqual(init.headers, { "content-type": "application/json" });
  const body = JSON.parse(init.body);
  assert.deepEqual(Object.keys(body).sort(), ["chrome_id", "image", "sig"]);
  assert.equal(body.chrome_id, "chr_1");
  const bytes = Uint8Array.from(Buffer.from(body.image, "base64url"));
  const sha256 = await sha256Hex(bytes);
  const spki = b64u(await publicSPKI(key.publicKey));
  assert.ok(await verify(spki, LABEL.screenshot, { chrome_id: "chr_1", step: "CHROMESTEP#1 a/b", sha256 }, body.sig), "planque can check it");
  assert.ok(!(await verify(spki, LABEL.screenshot, { chrome_id: "chr_1", step: "another", sha256 }, body.sig)), "and it is for this step only");
});

test("a refused upload, or one that fails, is only a word: nothing throws", async () => {
  assert.equal(await (await rig({ fetchOk: false })).shots.send("data:image/png;base64,AAAA", "s1"), "upload");
  const big = await rig({ deps: fakeDeps({ sizes: { 0.6: 500_000, 0.4: 400_000 } }) });
  assert.equal(await big.shots.send("data:image/png;base64,AAAA", "s1"), "size");
  assert.equal(await big.fetched.length, 1, "nothing was posted");
  assert.equal(await signScreenshot((await newKeyPair()).privateKey, { chromeId: "c", step: "s", sha256: "x" }).then((s) => typeof s), "string");
});
