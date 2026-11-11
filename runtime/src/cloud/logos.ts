/**
 * App logos (docs/api.md, "Logos"): the phones show each app's logo, and load it only from tafs cloud, so
 * no person's address ever reaches a third party. planque names a logo by the URL Composio gives (`icon`);
 * tafs cloud records each one it names in a view, under a key, and serves `/v1/logos/{key}`: fetched once,
 * checked, turned into a small PNG, and kept. Only keys tafs cloud recorded are served, so a phone can never
 * name a URL, and a key the catalog stops naming expires.
 */
import { createHash } from "node:crypto";
import dns from "node:dns";
import { existsSync } from "node:fs";
import https from "node:https";
import { createRequire } from "node:module";
import net from "node:net";
import { fileURLToPath } from "node:url";
import { Worker } from "node:worker_threads";
import type { CoachStore } from "./coach.js";

/** A logo's side, in pixels: the phones draw it small, next to the app's name. */
export const LOGO_PX = 96;
/** The most a logo's source may weigh: Composio's are a few KB. */
export const SOURCE_MAX = 256 * 1024;
/** The most a PNG source may weigh as stored (an item holds it, base64). */
const PNG_MAX = 200 * 1024;
/** The most a PNG source may measure on a side. */
const PNG_SIDE_MAX = 1024;
/** How long a source may take to arrive, and a render to finish. */
export const FETCH_MS = 5_000;
export const RENDER_MS = 2_000;
/** A key the catalog named is kept this long after it last did; its PNG as long. */
export const KEPT_MS = 30 * 24 * 3600_000;
/** A source that failed isn't tried again for this long. */
const BAD_MS = 24 * 3600_000;
/** The most new logos stored in a day, across accounts: with KEPT_MS, the store holds at most about 30 times this. */
export const NEW_A_DAY = 300;
/** The most logos one view names: the rest of its apps show their name alone. */
export const NAMED_MAX = 100;
/** A key is written again only once this long has passed since this instance last wrote it. */
const REWRITE_MS = 24 * 3600_000;

const KEY = /^[0-9a-f]{32}$/;

/** A logo's key: the first 32 hex of its source URL's SHA-256. */
export function logoKey(url: string): string {
  return createHash("sha256").update(url).digest("hex").slice(0, 32);
}

/** The path the phones load a logo from: on tafs cloud, never a URL. */
export function logoPath(key: string): string {
  return `/v1/logos/${key}`;
}

const srcKey = (key: string) => `logo#src#${key}`;
const pngKey = (key: string) => `logo#png#${key}`;
const badKey = (key: string) => `logo#bad#${key}`;
const dayKey = (now: Date) => `logo#new#${now.toISOString().slice(0, 10)}`;

/** Whether a source URL may be fetched: https on the default port, with a host name and no credentials. */
export function fetchable(url: string): boolean {
  try {
    const u = new URL(url);
    return u.protocol === "https:" && (u.port === "" || u.port === "443") && !u.username && !u.password && u.hostname !== "" && !net.isIP(u.hostname.replace(/^\[|\]$/g, ""));
  } catch {
    return false;
  }
}

/** When this instance last recorded each key, so a busy view doesn't write the same keys on every read. */
const recorded = new Map<string, number>();

/**
 * Records the logos a view names and answers each URL's path: at most NAMED_MAX, https only; any other is
 * left out, and its app shows its name alone. Each recorded key is kept KEPT_MS from now.
 */
export async function nameLogos(store: CoachStore | undefined, urls: Array<string | undefined>, now: Date): Promise<Map<string, string>> {
  const paths = new Map<string, string>();
  if (!store) return paths;
  const wanted = [...new Set(urls.filter((u): u is string => typeof u === "string" && fetchable(u)))].slice(0, NAMED_MAX);
  await Promise.all(wanted.map(async (url) => {
    const key = logoKey(url);
    const last = recorded.get(key);
    if (last === undefined || now.getTime() - last >= REWRITE_MS) {
      try {
        await store.put(srcKey(key), { url }, new Date(now.getTime() + KEPT_MS));
        recorded.set(key, now.getTime());
      } catch {
        return; // not recorded, not named: the app shows its name alone
      }
    }
    paths.set(url, logoPath(key));
  }));
  return paths;
}

/** Forgets what this instance recorded (tests). */
export function forgetRecorded(): void {
  recorded.clear();
}

/** A source as fetched: its media type and bytes. */
export interface Source {
  type: string;
  bytes: Uint8Array;
}

/** How logos are fetched and drawn: the real ones by default, fakes in tests. */
export interface LogoDeps {
  fetchSource?: (url: string) => Promise<Source>;
  render?: (svg: string) => Promise<Uint8Array>;
  now?: () => Date;
}

/** A logo as served: a PNG. */
export interface Logo {
  png: Uint8Array;
}

/**
 * The logo at key, or undefined (the route's 404, and the app shows the name alone): an unknown or expired
 * key, a source that failed or isn't a safe image, or a day's budget of new logos spent. Nothing is fetched
 * for a key tafs cloud didn't record.
 */
export async function logoFor(store: CoachStore | undefined, key: string, deps: LogoDeps = {}): Promise<Logo | undefined> {
  if (!store || !KEY.test(key)) return undefined;
  const now = deps.now?.() ?? new Date();
  const src = await store.get(srcKey(key)).catch(() => undefined) as { url?: unknown } | undefined;
  if (typeof src?.url !== "string" || !fetchable(src.url)) return undefined;
  const kept = await store.get(pngKey(key)).catch(() => undefined) as { png?: unknown } | undefined;
  if (typeof kept?.png === "string") return { png: new Uint8Array(Buffer.from(kept.png, "base64")) };
  if (await store.get(badKey(key)).catch(() => undefined)) return undefined;
  const day = await store.get(dayKey(now)).catch(() => undefined) as { n?: unknown } | undefined;
  const made = typeof day?.n === "number" ? day.n : 0;
  if (made >= NEW_A_DAY) return undefined;
  let png: Uint8Array;
  try {
    png = await drawn(await (deps.fetchSource ?? fetchSource)(src.url), deps.render ?? render);
  } catch {
    await store.put(badKey(key), { at: now.toISOString() }, new Date(now.getTime() + BAD_MS)).catch(() => undefined);
    return undefined;
  }
  await store.put(pngKey(key), { png: Buffer.from(png).toString("base64") }, new Date(now.getTime() + KEPT_MS));
  // A count read and written back: under many first reads at once it can run a little past NEW_A_DAY, never far.
  await store.put(dayKey(now), { n: made + 1 }, new Date(now.getTime() + 2 * 24 * 3600_000)).catch(() => undefined);
  return { png };
}

/** A source as a PNG: a PNG checked and kept as it is, or an SVG checked and drawn. Anything else throws. */
async function drawn(source: Source, draw: (svg: string) => Promise<Uint8Array>): Promise<Uint8Array> {
  if (source.bytes.length > SOURCE_MAX) throw new Error("too big");
  if (source.type === "image/png") {
    if (!pngOK(source.bytes)) throw new Error("not a PNG we keep");
    return source.bytes;
  }
  if (source.type !== "image/svg+xml") throw new Error(`not an image we draw: ${source.type}`);
  const svg = new TextDecoder("utf-8", { fatal: true }).decode(source.bytes);
  const why = unsafeSVG(svg);
  if (why) throw new Error(why);
  const png = await draw(svg);
  if (!pngOK(png)) throw new Error("the drawing isn't a PNG");
  return png;
}

/** Whether bytes are a PNG of at most PNG_MAX bytes and PNG_SIDE_MAX pixels a side. */
export function pngOK(b: Uint8Array): boolean {
  const sig = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  if (b.length < 24 || b.length > PNG_MAX || sig.some((v, i) => b[i] !== v)) return false;
  // The first chunk is IHDR: its width and height, big-endian, at bytes 16 and 20.
  if (String.fromCharCode(b[12]!, b[13]!, b[14]!, b[15]!) !== "IHDR") return false;
  const view = new DataView(b.buffer, b.byteOffset, b.byteLength);
  const w = view.getUint32(16), h = view.getUint32(20);
  return w > 0 && h > 0 && w <= PNG_SIDE_MAX && h <= PNG_SIDE_MAX;
}

/**
 * Why an SVG isn't drawn, or "": no DOCTYPE or entity (no entity expansion), no script, and nothing that
 * points outside it. Every href is a fragment or an embedded raster image, and every url() a fragment.
 * The renderer loads nothing either way; this refuses such a logo outright.
 */
export function unsafeSVG(svg: string): string {
  if (!/<svg[\s>]/i.test(svg)) return "no svg element";
  if (/<!DOCTYPE|<!ENTITY/i.test(svg)) return "a DOCTYPE or an entity";
  if (/<script[\s>]|<foreignObject[\s>]|@import/i.test(svg)) return "a script, foreign content or an import";
  for (const m of svg.matchAll(/\b(?:xlink:)?href\s*=\s*(["'])(.*?)\1/gis)) {
    const v = m[2]!.trim();
    if (!v.startsWith("#") && !/^data:image\/(png|jpeg|gif|webp);base64,/i.test(v)) return "an href that points outside";
  }
  if (/\b(?:xlink:)?href\s*=\s*[^"'\s>]/i.test(svg)) return "an unquoted href";
  if (/url\(\s*(?!["']?#)/i.test(svg)) return "a url() that points outside";
  return "";
}

/** The renderer's files: next to the bundle in the Lambda, or in node_modules in tests. */
function rendererFiles(): { worker: URL; wasm: string } {
  const worker = new URL("./logo-worker.mjs", import.meta.url);
  const beside = new URL("./index_bg.wasm", import.meta.url);
  const wasm = existsSync(fileURLToPath(beside)) ? fileURLToPath(beside) : createRequire(import.meta.url).resolve("@resvg/resvg-wasm/index_bg.wasm");
  return { worker, wasm };
}

/**
 * Draws an SVG as a LOGO_PX PNG in a worker thread, stopped after RENDER_MS. The worker loads no font and
 * no file, and refuses an SVG that names an image to load.
 */
export function render(svg: string, opts: { ms?: number; worker?: URL } = {}): Promise<Uint8Array> {
  const files = rendererFiles();
  const ms = opts.ms ?? RENDER_MS;
  return new Promise((resolve, reject) => {
    const worker = new Worker(opts.worker ?? files.worker, { workerData: { svg, px: LOGO_PX, wasm: files.wasm }, resourceLimits: { maxOldGenerationSizeMb: 64 } });
    const timer = setTimeout(() => { void worker.terminate(); reject(new Error("the drawing took too long")); }, ms);
    worker.once("message", (m: { png?: Uint8Array; error?: string }) => {
      clearTimeout(timer);
      void worker.terminate();
      if (m.png) resolve(new Uint8Array(m.png));
      else reject(new Error(m.error ?? "the drawing failed"));
    });
    worker.once("error", (err) => { clearTimeout(timer); reject(err); });
    worker.once("exit", (code) => { clearTimeout(timer); if (code !== 0) reject(new Error(`the drawing stopped (${code})`)); });
  });
}

/**
 * Whether an address may be reached: not private, loopback, link-local (the metadata service among them),
 * shared, documentation, benchmarking, multicast or reserved; an IPv4 address mapped or translated into IPv6
 * is judged as itself.
 */
export function publicAddress(ip: string): boolean {
  if (net.isIPv4(ip)) {
    const [a, b, c] = ip.split(".").map(Number) as [number, number, number, number];
    if (a === 0 || a === 10 || a === 127 || a >= 224) return false;
    if (a === 100 && b >= 64 && b <= 127) return false;
    if (a === 169 && b === 254) return false;
    if (a === 172 && b >= 16 && b <= 31) return false;
    if (a === 192 && (b === 168 || (b === 0 && (c === 0 || c === 2)))) return false;
    if (a === 198 && (b === 18 || b === 19 || (b === 51 && c === 100))) return false;
    if (a === 203 && b === 0 && c === 113) return false;
    return true;
  }
  if (!net.isIPv6(ip)) return false;
  const x = expand6(ip);
  if (!x) return false;
  if (x.every((g) => g === 0)) return false; // ::
  if (x.slice(0, 7).every((g) => g === 0) && x[7] === 1) return false; // ::1
  // ::ffff:a.b.c.d (mapped) and 64:ff9b::a.b.c.d (translated): the IPv4 address inside decides.
  if ((x.slice(0, 5).every((g) => g === 0) && x[5] === 0xffff) || (x[0] === 0x64 && x[1] === 0xff9b && x.slice(2, 6).every((g) => g === 0))) {
    return publicAddress(`${x[6]! >> 8}.${x[6]! & 255}.${x[7]! >> 8}.${x[7]! & 255}`);
  }
  const g0 = x[0]!;
  if ((g0 & 0xfe00) === 0xfc00) return false; // fc00::/7, unique local
  if ((g0 & 0xffc0) === 0xfe80) return false; // fe80::/10, link-local
  if ((g0 & 0xff00) === 0xff00) return false; // ff00::/8, multicast
  if (g0 === 0x2001 && x[1] === 0x0db8) return false; // documentation
  if (g0 === 0x0100 && x.slice(1, 4).every((g) => g === 0)) return false; // discard
  if ((g0 & 0xe000) !== 0x2000) return false; // only global unicast, 2000::/3
  return true;
}

/** An IPv6 address as its eight groups, an IPv4 tail folded in; undefined when it doesn't parse. */
function expand6(ip: string): number[] | undefined {
  let s = ip.toLowerCase().replace(/%.*$/, "");
  const v4 = /(\d+\.\d+\.\d+\.\d+)$/.exec(s);
  if (v4) {
    const p = v4[1]!.split(".").map(Number);
    s = s.slice(0, -v4[1]!.length) + `${((p[0]! << 8) | p[1]!).toString(16)}:${((p[2]! << 8) | p[3]!).toString(16)}`;
  }
  const [head, tail] = s.split("::") as [string, string | undefined];
  const h = head ? head.split(":") : [];
  const t = tail !== undefined && tail ? tail.split(":") : [];
  const fill = tail === undefined ? 0 : 8 - h.length - t.length;
  const groups = [...h, ...Array(Math.max(fill, 0)).fill("0"), ...t].map((g) => parseInt(g, 16));
  return groups.length === 8 && groups.every((g) => Number.isInteger(g) && g >= 0 && g <= 0xffff) ? groups : undefined;
}

/**
 * A lookup that answers only public addresses: when any address the name resolves to isn't public, the
 * lookup fails, so a name can't point the request inside. The connection goes to the address checked here.
 */
export function publicLookup(resolve: typeof dns.lookup = dns.lookup) {
  return (host: string, options: dns.LookupOptions, cb: (err: NodeJS.ErrnoException | null, address: string | dns.LookupAddress[], family?: number) => void): void => {
    resolve(host, { ...options, all: true }, (err, addresses) => {
      if (err) return cb(err, "");
      const list = addresses as dns.LookupAddress[];
      if (!list.length || list.some((a) => !publicAddress(a.address))) {
        return cb(Object.assign(new Error(`${host} resolves to an address that isn't public`), { code: "ENOTPUBLIC" }), "");
      }
      if (options.all) return cb(null, list);
      cb(null, list[0]!.address, list[0]!.family);
    });
  };
}

/**
 * Fetches a logo's source: https only, to a public address, following no redirect, in FETCH_MS at most and
 * SOURCE_MAX bytes at most, and only an SVG or a PNG.
 */
export function fetchSource(url: string, resolve?: typeof dns.lookup): Promise<Source> {
  if (!fetchable(url)) return Promise.reject(new Error("not a URL we fetch"));
  return new Promise((done, fail) => {
    const req = https.get(url, { lookup: publicLookup(resolve) as never, headers: { accept: "image/svg+xml, image/png" }, timeout: FETCH_MS }, (res) => {
      const type = (res.headers["content-type"] ?? "").split(";")[0]!.trim().toLowerCase();
      if (res.statusCode !== 200) { res.resume(); req.destroy(); return fail(new Error(`answered ${res.statusCode}`)); }
      if (type !== "image/svg+xml" && type !== "image/png") { res.resume(); req.destroy(); return fail(new Error(`not an image we draw: ${type}`)); }
      const chunks: Buffer[] = [];
      let size = 0;
      res.on("data", (c: Buffer) => {
        size += c.length;
        if (size > SOURCE_MAX) { req.destroy(); fail(new Error("too big")); return; }
        chunks.push(c);
      });
      res.on("end", () => { clearTimeout(timer); done({ type, bytes: new Uint8Array(Buffer.concat(chunks)) }); });
      res.on("error", fail);
    });
    const timer = setTimeout(() => { req.destroy(); fail(new Error("too slow")); }, FETCH_MS);
    req.on("timeout", () => { req.destroy(); fail(new Error("too slow")); });
    req.on("error", (err) => { clearTimeout(timer); fail(err); });
  });
}
