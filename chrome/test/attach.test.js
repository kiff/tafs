import { test } from "node:test";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { LIMITS, MAX_FILES, LONG_EDGE, ACCEPT, mediaTypeOf, refusal, fitted, jpegName, prepare, upload, sha256, readyIDs, uploading, counted, afterSent } from "../src/attach.js";
import { Coach, fileIDs } from "../src/coach.js";

const MB = 1024 * 1024;
const subtle = webcrypto.subtle;

/** A picked file as Chrome's picker gives it: a name, the computer's type, its size and bytes. */
function picked(name, type, size, fill = 1) {
  const bytes = new Uint8Array(size).fill(fill);
  return { name, type, size, arrayBuffer: async () => bytes.buffer };
}

test("the types and limits are the apps' (docs/attachments.md): images 10 MB, PDF 25 MB, text and Office 2 MB", () => {
  assert.equal(MAX_FILES, 5);
  assert.equal(LONG_EDGE, 2576);
  for (const t of ["image/jpeg", "image/png", "image/gif", "image/webp"]) assert.equal(LIMITS[t], 10 * MB, t);
  assert.equal(LIMITS["application/pdf"], 25 * MB);
  for (const t of ["text/plain", "text/csv", "text/markdown"]) assert.equal(LIMITS[t], 2 * MB, t);
  assert.equal(LIMITS["application/vnd.openxmlformats-officedocument.wordprocessingml.document"], 2 * MB);
  assert.equal(LIMITS["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"], 2 * MB);
  assert.equal(Object.keys(LIMITS).length, 10, "nothing else");
  assert.match(ACCEPT, /\.pdf/);
  assert.doesNotMatch(ACCEPT, /\.exe|\.zip|\.html|\.svg|\.heic|\.doc,|\.xls,/);
});

test("a file's type comes from its name first, else from the computer's, and only the types above", () => {
  assert.equal(mediaTypeOf("Report.PDF", ""), "application/pdf");
  assert.equal(mediaTypeOf("data.csv", "application/vnd.ms-excel"), "text/csv", "Windows calls a .csv Excel's");
  assert.equal(mediaTypeOf("notes.md", ""), "text/markdown");
  assert.equal(mediaTypeOf("scan", "image/png"), "image/png");
  assert.equal(mediaTypeOf("page.html", "text/html"), null);
  assert.equal(mediaTypeOf("image.svg", "image/svg+xml"), null, "an SVG can carry script: never");
  assert.equal(mediaTypeOf("old.doc", "application/msword"), null);
  assert.equal(mediaTypeOf("x", "text/plain; charset=utf-8"), "text/plain");
});

test("refused: a sixth file, a kind that can't go, and one over its type's limit, saying the limit", () => {
  assert.deepEqual(refusal({ mediaType: "application/pdf", size: 10, count: 5 }), { code: "too_many" });
  assert.deepEqual(refusal({ mediaType: null, size: 10 }), { code: "type" });
  assert.deepEqual(refusal({ mediaType: "text/plain", size: 0 }), { code: "type" }, "an empty file");
  assert.deepEqual(refusal({ mediaType: "text/plain", size: 2 * MB + 1 }), { code: "too_big", size: "2 MB" });
  assert.deepEqual(refusal({ mediaType: "application/pdf", size: 25 * MB + 1 }), { code: "too_big", size: "25 MB" });
  assert.equal(refusal({ mediaType: "application/pdf", size: 25 * MB, count: 4 }), null);
});

test("an image is redrawn as a JPEG, its long edge at most 2576 px, never enlarged, under its own name as .jpg", async () => {
  assert.deepEqual(fitted(5152, 3864), { width: 2576, height: 1932 });
  assert.deepEqual(fitted(3864, 5152), { width: 1932, height: 2576 });
  assert.deepEqual(fitted(800, 600), { width: 800, height: 600 });
  assert.equal(jpegName("IMG_0042.HEIC.png"), "IMG_0042.HEIC.jpg");
  assert.equal(jpegName(".png"), "photo.jpg");
  const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 1, 2, 3]);
  let drawn = 0;
  const draw = async () => { drawn++; return { arrayBuffer: async () => jpeg.buffer }; };
  // A 30 MB PNG is fine before it's redrawn: only the JPEG that goes is checked against 10 MB.
  const out = await prepare(picked("shot.png", "image/png", 30 * MB), { draw });
  assert.equal(drawn, 1);
  assert.deepEqual(out, { name: "shot.jpg", mediaType: "image/jpeg", bytes: jpeg });
  // An image the browser can't read is refused, never sent as it was.
  const unread = await prepare(picked("x.webp", "image/webp", 100), { draw: async () => { throw new Error("no"); } });
  assert.deepEqual(unread, { refused: { code: "type" } });
});

test("a document goes as it is, checked against its limit; a sixth file never goes", async () => {
  const pdf = await prepare(picked("brief.pdf", "application/pdf", 2048));
  assert.equal(pdf.name, "brief.pdf");
  assert.equal(pdf.mediaType, "application/pdf");
  assert.equal(pdf.bytes.byteLength, 2048);
  assert.deepEqual(await prepare(picked("big.txt", "text/plain", 2 * MB + 1)), { refused: { code: "too_big", size: "2 MB" } });
  assert.deepEqual(await prepare(picked("ok.txt", "text/plain", 10), { count: 5 }), { refused: { code: "too_many" } });
  assert.deepEqual(await prepare(picked("run.exe", "application/octet-stream", 10)), { refused: { code: "type" } });
});

test("upload: declared through the worker with its sha256, then its bytes straight to the https address given, with its method and headers", async () => {
  const bytes = new TextEncoder().encode("hello");
  const declared = [];
  const fetched = [];
  const declare = async (f) => {
    declared.push(f);
    return { attachment: { id: "att_" + "1".repeat(32) }, upload: { url: "https://store.test/up", method: "PUT", headers: { "Content-Type": "text/plain" } } };
  };
  const fetch = async (url, init) => { fetched.push({ url, ...init }); return { ok: true }; };
  const id = await upload({ name: "a.txt", mediaType: "text/plain", bytes }, { declare, fetch, subtle });
  assert.equal(id, "att_" + "1".repeat(32));
  assert.deepEqual(declared, [{ media_type: "text/plain", name: "a.txt", size: 5, sha256: "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824" }]);
  assert.equal(fetched.length, 1);
  assert.equal(fetched[0].url, "https://store.test/up");
  assert.equal(fetched[0].method, "PUT");
  assert.deepEqual(fetched[0].headers, { "Content-Type": "text/plain" });
  assert.equal(fetched[0].credentials, "omit", "no cookie of the person's goes with the bytes");
  assert.equal(fetched[0].body, bytes);
  // Not https, or the store refuses: it didn't go up.
  const http = async () => ({ attachment: { id: "att_x" }, upload: { url: "http://store.test/up" } });
  await assert.rejects(upload({ name: "a.txt", mediaType: "text/plain", bytes }, { declare: http, fetch, subtle }));
  await assert.rejects(upload({ name: "a.txt", mediaType: "text/plain", bytes }, { declare, fetch: async () => ({ ok: false, status: 403 }), subtle }));
  assert.equal(await sha256(new Uint8Array(0), subtle), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
});

test("the tray: only ready files go, Send waits while one goes up, and a file that didn't go up stays after Send", () => {
  const tray = [
    { key: 1, name: "a.pdf", state: "ready", id: "att_a" },
    { key: 2, name: "b.png", state: "uploading" },
    { key: 3, name: "c.exe", state: "refused", why: "no" },
    { key: 4, name: "d.txt", state: "failed" },
  ];
  assert.deepEqual(readyIDs(tray), ["att_a"]);
  assert.equal(uploading(tray), true);
  assert.equal(counted(tray), 2, "going up or ready count toward 5");
  assert.deepEqual(afterSent(tray, ["att_a"]).map((f) => f.key), [2, 3, 4]);
  assert.equal(uploading([]), false);
});

test("the Coach client sends the files' ids with the words, or alone; the page goes only with words", async () => {
  const calls = [];
  const fetch = async (url, init) => {
    calls.push({ url, method: init.method, body: init.body ? JSON.parse(init.body) : undefined });
    const body = url.endsWith("/v1/setup") ? { setup: { id: "taf-coach" } } : { session: "s1", run: "r1", message: { id: "r1.in" } };
    return { ok: true, json: async () => body };
  };
  const c = new Coach({ token: "t", fetch, base: "https://cloud.test" });
  const id = "att_" + "a".repeat(32);
  await c.send("Read this", { clientID: "c1", attachments: [id, id, "nope"] });
  assert.deepEqual(calls.at(-1).body, { text: "Read this", client_message_id: "c1", attachments: [id] });
  const page = { url: "https://shop.example/", title: "Shop", text: "words", truncated: false };
  await c.send("", { clientID: "c2", attachments: [id], page });
  assert.deepEqual(calls.at(-1).body, { text: "", client_message_id: "c2", attachments: [id] }, "files alone: no page");
  await assert.rejects(c.send("  ", { clientID: "c3" }), /nothing to send/);
  await c.sendToTaf("taf-rae", "", "c4", [id]);
  assert.deepEqual(calls.at(-1), { url: "https://cloud.test/v1/tafs/taf-rae/messages", method: "POST", body: { text: "", client_message_id: "c4", attachments: [id] } });
  await c.attach(null, { media_type: "application/pdf", name: "brief.pdf", size: 3, sha256: "f".repeat(64) });
  assert.deepEqual(calls.at(-1), { url: "https://cloud.test/v1/tafs/taf-coach/attachments", method: "POST", body: { media_type: "application/pdf", name: "brief.pdf", size: 3, sha256: "f".repeat(64) } });
  assert.deepEqual(fileIDs(["att_" + "0".repeat(32), 7, null, "att_" + "0".repeat(32)]), ["att_" + "0".repeat(32)]);
  assert.equal(fileIDs(Array.from({ length: 7 }, (_, i) => "att_" + String(i).repeat(32))).length, 5);
});
