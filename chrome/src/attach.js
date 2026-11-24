// Files on a message from the side panel (docs/chrome.md, "Files on a message"), as the apps attach them
// (docs/attachments.md): the same types and limits, at most 5 a message, every image redrawn as a JPEG so only its pixels
// go. A file is declared to tafs cloud ({media_type, name, size, sha256}), which answers where to upload its bytes; the
// panel uploads them there itself, straight to storage, never through tafs cloud, and the message names the file by its
// id. The panel picks files only through Chrome's own file picker: it never reads a page, a tab or a folder for one.

const MB = 1024 * 1024;

/** At most this many files on one message, as in the apps. */
export const MAX_FILES = 5;

/** An image's long edge at most, in pixels, once redrawn (the apps' FilePrep.longEdge). */
export const LONG_EDGE = 2576;

/** Each type that may be attached, and the most it may weigh: kiff/agents' limits, as the apps check them first. */
export const LIMITS = Object.freeze({
  "image/jpeg": 10 * MB, "image/png": 10 * MB, "image/gif": 10 * MB, "image/webp": 10 * MB,
  "application/pdf": 25 * MB,
  "text/plain": 2 * MB, "text/csv": 2 * MB, "text/markdown": 2 * MB,
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document": 2 * MB,
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": 2 * MB,
});

/** A file's type by its name's extension: what the computer calls a type varies (a .csv is Excel's on Windows). */
const BY_EXTENSION = Object.freeze({
  jpg: "image/jpeg", jpeg: "image/jpeg", png: "image/png", gif: "image/gif", webp: "image/webp",
  pdf: "application/pdf",
  txt: "text/plain", text: "text/plain", csv: "text/csv", md: "text/markdown", markdown: "text/markdown",
  docx: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  xlsx: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
});

/** What the file picker offers: the types above, by extension and by type. */
export const ACCEPT = [...Object.keys(BY_EXTENSION).map((e) => "." + e), ...Object.keys(LIMITS)].join(",");

/** The type a file goes as, or null when it can't be attached: by its extension first, else the type the computer gave it. */
export function mediaTypeOf(name, type) {
  const ext = /\.([A-Za-z0-9]+)$/.exec(String(name || ""));
  const byName = ext ? BY_EXTENSION[ext[1].toLowerCase()] : undefined;
  if (byName) return byName;
  const t = String(type || "").toLowerCase().split(";")[0].trim();
  return Object.prototype.hasOwnProperty.call(LIMITS, t) ? t : null;
}

/** Whether a file of this type is an image, redrawn before it goes. */
export function isImage(mediaType) {
  return typeof mediaType === "string" && mediaType.startsWith("image/");
}

/** "10 MB", as the limit is said. */
export function megabytes(bytes) {
  return Math.round(bytes / MB) + " MB";
}

/**
 * Why a file can't go, as `{code, size?}`, or null when it can: `type` (not a kind that may be attached), `too_big` (over
 * its type's limit, `size` saying the limit), or `too_many` (the message already has MAX_FILES).
 */
export function refusal({ mediaType, size, count = 0 }) {
  if (count >= MAX_FILES) return { code: "too_many" };
  if (!mediaType || !LIMITS[mediaType]) return { code: "type" };
  if (!Number.isInteger(size) || size < 1) return { code: "type" };
  if (size > LIMITS[mediaType]) return { code: "too_big", size: megabytes(LIMITS[mediaType]) };
  return null;
}

/** An image's size once its long edge is at most LONG_EDGE: never larger than it was. */
export function fitted(width, height, edge = LONG_EDGE) {
  const long = Math.max(width, height);
  if (!(long > 0)) return { width: 0, height: 0 };
  const ratio = Math.min(1, edge / long);
  return { width: Math.max(1, Math.round(width * ratio)), height: Math.max(1, Math.round(height * ratio)) };
}

/** The name a redrawn image goes under: its own, as a .jpg ("photo.jpg" when it had none). */
export function jpegName(name) {
  const base = String(name || "").replace(/\.[^./\\]*$/, "").trim();
  return (base || "photo") + ".jpg";
}

/**
 * An image redrawn as a JPEG, as the apps do: on white (a transparent PNG isn't black), its long edge at most LONG_EDGE,
 * only its pixels kept, so no location or other metadata goes. `draw` is the browser's (createImageBitmap and
 * OffscreenCanvas); tests pass their own. Null when the browser can't read it as an image.
 */
export async function redrawn(blob, draw = browserDraw) {
  try {
    return await draw(blob);
  } catch {
    return null;
  }
}

async function browserDraw(blob) {
  const bitmap = await createImageBitmap(blob, { imageOrientation: "from-image" });
  const { width, height } = fitted(bitmap.width, bitmap.height);
  const canvas = new OffscreenCanvas(width, height);
  const ctx = canvas.getContext("2d");
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, width, height);
  ctx.drawImage(bitmap, 0, 0, width, height);
  bitmap.close();
  return canvas.convertToBlob({ type: "image/jpeg", quality: 0.85 });
}

/** The file's SHA-256, in lower-case hex, as tafs cloud takes it. */
export async function sha256(bytes, subtle = globalThis.crypto.subtle) {
  const sum = new Uint8Array(await subtle.digest("SHA-256", bytes));
  return [...sum].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * A file picked, made ready to go: its type, an image redrawn, checked against its limit. `{name, mediaType, bytes}`, or
 * `{refused: {code, size?}}`. `count` is how many files the message already has.
 */
export async function prepare(file, { count = 0, draw } = {}) {
  let mediaType = mediaTypeOf(file.name, file.type);
  const early = refusal({ mediaType, size: isImage(mediaType) ? 1 : file.size, count });
  if (early) return { refused: early };
  let name = String(file.name || "").slice(0, 200);
  let blob = file;
  if (isImage(mediaType)) {
    blob = await redrawn(file, draw);
    if (!blob) return { refused: { code: "type" } };
    mediaType = "image/jpeg";
    name = jpegName(name);
  }
  const bytes = new Uint8Array(await blob.arrayBuffer());
  const late = refusal({ mediaType, size: bytes.byteLength, count });
  if (late) return { refused: late };
  return { name, mediaType, bytes };
}

/**
 * Sends a prepared file up: declared through the service worker (`declare`, which calls tafs cloud as the session), then
 * its bytes to the address the answer gives, with that answer's method and headers. The file's id, once its bytes are up.
 */
export async function upload(prepared, { declare, fetch = globalThis.fetch.bind(globalThis), subtle } = {}) {
  const digest = await sha256(prepared.bytes, subtle);
  const out = await declare({ media_type: prepared.mediaType, name: prepared.name, size: prepared.bytes.byteLength, sha256: digest });
  const id = out && out.attachment && out.attachment.id;
  const to = out && out.upload;
  if (typeof id !== "string" || !to || typeof to.url !== "string" || !/^https:\/\//.test(to.url)) throw Object.assign(new Error("no upload"), { code: "failed" });
  const res = await fetch(to.url, { method: to.method || "PUT", headers: to.headers || {}, body: prepared.bytes, credentials: "omit" });
  if (!res.ok) throw Object.assign(new Error("upload answered " + res.status), { code: "failed" });
  return id;
}

/*
 * The tray: the files picked for one chat's next message, each `{key, name, state, id?, why?}`, state `uploading`, `ready`,
 * `refused` (never went up, `why` says so) or `failed` (didn't upload). Pure, so the panel only draws it.
 */

/** The ids of the files ready to go with the message. */
export function readyIDs(tray) {
  return (tray || []).filter((f) => f.state === "ready" && typeof f.id === "string").map((f) => f.id);
}

/** Whether a file is still going up: Send waits, so a message never leaves one just attached behind. */
export function uploading(tray) {
  return (tray || []).some((f) => f.state === "uploading");
}

/** How many files count toward the message's MAX_FILES: those going up or ready. */
export function counted(tray) {
  return (tray || []).filter((f) => f.state === "uploading" || f.state === "ready").length;
}

/** The tray once a message went: the files it carried are gone, those that didn't go up stay, with why. */
export function afterSent(tray, sent) {
  const went = new Set(sent || []);
  return (tray || []).filter((f) => !(f.state === "ready" && went.has(f.id)));
}
