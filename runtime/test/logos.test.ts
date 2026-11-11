import { writeFileSync } from "node:fs";
import { inflateSync } from "node:zlib";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import type dns from "node:dns";
import { beforeEach, describe, expect, it } from "vitest";
import { fetchable, fetchSource, forgetRecorded, LOGO_PX, logoFor, logoKey, logoPath, nameLogos, NEW_A_DAY, pngOK, publicAddress, publicLookup, render, type Source, unsafeSVG, withoutPublicDoctype } from "../src/cloud/logos.js";
import { memoryCoach } from "./fakes.js";

const NOW = new Date("2026-11-11T08:00:00Z");
const URL_OK = "https://logos.composio.dev/api/stripe";
const STRIPE = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="#635BFF" d="M2 2h20v20H2z"/></svg>`;
const svg = (text: string): Source => ({ type: "image/svg+xml", bytes: new TextEncoder().encode(text) });

/** An 8-bit RGBA PNG's pixels, for checking what was drawn: its size, and the [r, g, b, a] at (x, y). */
function pixels(png: Uint8Array) {
  const view = new DataView(png.buffer, png.byteOffset, png.byteLength);
  const w = view.getUint32(16), h = view.getUint32(20);
  const idat: Buffer[] = [];
  for (let at = 8; at < png.length;) {
    const len = view.getUint32(at), type = Buffer.from(png.subarray(at + 4, at + 8)).toString("ascii");
    if (type === "IDAT") idat.push(Buffer.from(png.subarray(at + 8, at + 8 + len)));
    at += 12 + len;
  }
  const raw = inflateSync(Buffer.concat(idat)), stride = w * 4, out = new Uint8Array(h * stride);
  for (let y = 0; y < h; y++) {
    const f = raw[y * (stride + 1)]!;
    for (let x = 0; x < stride; x++) {
      const v = raw[y * (stride + 1) + 1 + x]!, a = x >= 4 ? out[y * stride + x - 4]! : 0;
      const b = y > 0 ? out[(y - 1) * stride + x]! : 0, c = x >= 4 && y > 0 ? out[(y - 1) * stride + x - 4]! : 0;
      const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
      const pred = f === 1 ? a : f === 2 ? b : f === 3 ? Math.floor((a + b) / 2) : f === 4 ? (pa <= pb && pa <= pc ? a : pb <= pc ? b : c) : 0;
      out[y * stride + x] = (v + pred) & 255;
    }
  }
  return { w, h, at: (x: number, y: number) => Array.from(out.subarray(y * stride + x * 4, y * stride + x * 4 + 4)) };
}

/** A store, a source fetcher that counts, and the real renderer unless a test swaps it. */
function setup(source: Source | Error = svg(STRIPE)) {
  const store = memoryCoach(() => NOW);
  const fetched: string[] = [];
  const fetchSource = async (url: string) => { fetched.push(url); if (source instanceof Error) throw source; return source; };
  return { store, fetched, deps: { fetchSource, now: () => NOW } };
}

describe("logos", () => {
  beforeEach(() => forgetRecorded());

  it("names a logo by a path on tafs cloud, never a URL, and records only https URLs", async () => {
    const { store } = setup();
    const paths = await nameLogos(store, [URL_OK, "http://logos.composio.dev/api/x", "https://10.0.0.1/x.svg", "https://user:pw@host/x", undefined], NOW);
    expect([...paths.keys()]).toEqual([URL_OK]);
    const path = paths.get(URL_OK)!;
    expect(path).toBe(`/v1/logos/${logoKey(URL_OK)}`);
    expect(path).toMatch(/^\/v1\/logos\/[0-9a-f]{32}$/);
    expect(logoPath(logoKey(URL_OK))).toBe(path);
  });

  it("fetches a recorded logo once, draws it as a small PNG, and serves it from the store after", async () => {
    const { store, fetched, deps } = setup();
    await nameLogos(store, [URL_OK], NOW);
    const first = await logoFor(store, logoKey(URL_OK), deps);
    expect(first && pngOK(first.png)).toBe(true);
    const view = new DataView(first!.png.buffer, first!.png.byteOffset);
    expect(Math.max(view.getUint32(16), view.getUint32(20))).toBe(LOGO_PX);
    const again = await logoFor(store, logoKey(URL_OK), deps);
    expect(again?.png).toEqual(first!.png);
    expect(fetched).toEqual([URL_OK]);
  });

  it("serves nothing and fetches nothing for a key it didn't record, or one the catalog stopped naming", async () => {
    const { store, fetched, deps } = setup();
    expect(await logoFor(store, logoKey(URL_OK), deps)).toBeUndefined();
    expect(await logoFor(store, "not-a-key", deps)).toBeUndefined();
    await nameLogos(store, [URL_OK], NOW);
    await logoFor(store, logoKey(URL_OK), deps);
    // The record expires (30 days without the catalog naming it): the PNG kept isn't served any more.
    store.held.delete(`logo#src#${logoKey(URL_OK)}`);
    expect(await logoFor(store, logoKey(URL_OK), deps)).toBeUndefined();
    expect(fetched).toEqual([URL_OK]);
  });

  it("refuses a hostile SVG with a 404 and loads nothing it names: an external href, a huge or long viewBox, an entity bomb", async () => {
    const hostile = {
      "external image": `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 10 10"><image xlink:href="https://evil.example/track.png" width="10" height="10"/></svg>`,
      "file image": `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><image href="file:///etc/passwd" width="10" height="10"/></svg>`,
      "external use": `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><use href="https://evil.example/s.svg#a"/></svg>`,
      "external url()": `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect width="10" height="10" style="fill:url(https://evil.example/p)"/></svg>`,
      "billion laughs": `<?xml version="1.0"?><!DOCTYPE svg [<!ENTITY a "lol"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;"><!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">]><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><text>&c;</text></svg>`,
      "huge viewBox": `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000000000 1000000000"><rect width="1000000000" height="1000000000"/></svg>`,
      "long viewBox": `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100000 1"><rect width="100000" height="1"/></svg>`,
      "script": `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><script>fetch("https://evil.example")</script></svg>`,
    };
    for (const [name, text] of Object.entries(hostile)) {
      forgetRecorded();
      const { store, deps } = setup(svg(text));
      const url = `https://logos.composio.dev/api/${name.replace(/\W/g, "")}`;
      await nameLogos(store, [url], NOW);
      expect(await logoFor(store, logoKey(url), deps), name).toBeUndefined();
    }
    // The checks before drawing name why.
    expect(unsafeSVG(hostile["external image"])).toBe("an href that points outside");
    expect(unsafeSVG(hostile["billion laughs"])).toBe("a DOCTYPE or an entity");
    expect(unsafeSVG(hostile["external url()"])).toBe("a url() that points outside");
    expect(unsafeSVG(STRIPE)).toBe("");
  });

  it("draws an SVG with the public SVG 1.1 DOCTYPE many editors write, and still refuses one that declares anything", async () => {
    const doctype = `<!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd">`;
    const editor = `<?xml version="1.0" encoding="UTF-8" standalone="no"?>\n${doctype}\n${STRIPE}`;
    const { store, deps } = setup(svg(editor));
    await nameLogos(store, [URL_OK], NOW);
    expect(pngOK((await logoFor(store, logoKey(URL_OK), deps))!.png)).toBe(true);
    expect(withoutPublicDoctype(editor)).not.toContain("DOCTYPE");
    // An internal subset is where entities are declared: it stays, and is refused.
    const subset = `<!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd" [<!ENTITY a "x">]>${STRIPE}`;
    expect(unsafeSVG(withoutPublicDoctype(subset))).toBe("a DOCTYPE or an entity");
    expect(unsafeSVG(withoutPublicDoctype(`<!DOCTYPE svg SYSTEM "file:///etc/passwd">${STRIPE}`))).toBe("a DOCTYPE or an entity");
  });

  it("draws every logo centred on a light rounded tile, so a dark one reads on a dark screen", async () => {
    const black = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect width="10" height="10" fill="#000"/></svg>`;
    const img = pixels(await render(black));
    expect([img.w, img.h]).toEqual([LOGO_PX, LOGO_PX]);
    expect(img.at(0, 0)[3]).toBe(0); // a rounded corner
    expect(img.at(LOGO_PX / 2, 2)).toEqual([255, 255, 255, 255]); // the tile, around the logo
    expect(img.at(LOGO_PX / 2, LOGO_PX / 2)).toEqual([0, 0, 0, 255]); // the logo, in the middle
  });

  it("stops a drawing that takes too long", async () => {
    const hang = join(tmpdir(), `logo-hang-${process.pid}.mjs`);
    writeFileSync(hang, "setInterval(() => {}, 1000);\n");
    await expect(render(STRIPE, { ms: 200, worker: pathToFileURL(hang) })).rejects.toThrow("took too long");
  });

  it("keeps a failed source for a day without trying again, and stores no more than a day's budget of new logos", async () => {
    const { store, fetched, deps } = setup(new Error("answered 500"));
    await nameLogos(store, [URL_OK], NOW);
    expect(await logoFor(store, logoKey(URL_OK), deps)).toBeUndefined();
    expect(await logoFor(store, logoKey(URL_OK), deps)).toBeUndefined();
    expect(fetched).toEqual([URL_OK]);

    forgetRecorded();
    const spent = setup();
    await spent.store.put(`logo#new#${NOW.toISOString().slice(0, 10)}`, { n: NEW_A_DAY }, new Date(NOW.getTime() + 86_400_000));
    await nameLogos(spent.store, [URL_OK], NOW);
    expect(await logoFor(spent.store, logoKey(URL_OK), spent.deps)).toBeUndefined();
    expect(spent.fetched).toEqual([]);
  });

  it("draws a PNG source on its tile too, and refuses one too big or not a PNG", async () => {
    const png = await render(STRIPE);
    const ok = setup({ type: "image/png", bytes: png });
    await nameLogos(ok.store, [URL_OK], NOW);
    const tiled = (await logoFor(ok.store, logoKey(URL_OK), ok.deps))!.png;
    expect(pngOK(tiled)).toBe(true);
    expect(pixels(tiled).w).toBe(LOGO_PX);
    expect(pixels(tiled).at(LOGO_PX / 2, 2)).toEqual([255, 255, 255, 255]);
    forgetRecorded();
    const fake = setup({ type: "image/png", bytes: new TextEncoder().encode("<svg/>") });
    await nameLogos(fake.store, [URL_OK], NOW);
    expect(await logoFor(fake.store, logoKey(URL_OK), fake.deps)).toBeUndefined();
    forgetRecorded();
    const other = setup({ type: "text/html", bytes: new TextEncoder().encode("<html>") });
    await nameLogos(other.store, [URL_OK], NOW);
    expect(await logoFor(other.store, logoKey(URL_OK), other.deps)).toBeUndefined();
  });

  it("reaches only public addresses, judged after the name resolves", async () => {
    for (const ip of ["10.0.0.1", "127.0.0.1", "169.254.169.254", "172.16.5.4", "192.168.1.1", "100.64.0.1", "0.0.0.0", "224.0.0.1", "::1", "::", "fe80::1", "fd00:ec2::254", "::ffff:169.254.169.254", "64:ff9b::a9fe:a9fe", "2001:db8::1"]) {
      expect(publicAddress(ip), ip).toBe(false);
    }
    for (const ip of ["104.18.12.34", "8.8.8.8", "2606:4700::6810:84e5", "::ffff:8.8.8.8"]) expect(publicAddress(ip), ip).toBe(true);
    const resolver = (answers: dns.LookupAddress[]) => ((_h: string, _o: unknown, cb: (e: null, a: dns.LookupAddress[]) => void) => cb(null, answers)) as unknown as typeof dns.lookup;
    const ask = (answers: dns.LookupAddress[]) => new Promise<string>((done) =>
      publicLookup(resolver(answers))("logos.example", {}, (err, address) => done(err ? `refused: ${(err as NodeJS.ErrnoException).code}` : String(address))));
    expect(await ask([{ address: "104.18.12.34", family: 4 }])).toBe("104.18.12.34");
    expect(await ask([{ address: "169.254.169.254", family: 4 }])).toBe("refused: ENOTPUBLIC");
    // One private answer among public ones refuses the name: a rebinding name can't slip one in.
    expect(await ask([{ address: "104.18.12.34", family: 4 }, { address: "10.0.0.7", family: 4 }])).toBe("refused: ENOTPUBLIC");
  });

  it("fetches only https on the default port to a host name, and a name resolving inside fails before any request", async () => {
    expect(fetchable(URL_OK)).toBe(true);
    for (const bad of ["http://logos.composio.dev/x", "https://logos.composio.dev:8443/x", "https://127.0.0.1/x", "https://[::1]/x", "https://u:p@logos.composio.dev/x", "file:///etc/passwd", "not a url"]) {
      expect(fetchable(bad), bad).toBe(false);
    }
    await expect(fetchSource("http://logos.composio.dev/x")).rejects.toThrow("not a URL we fetch");
    const inside = ((_h: string, _o: unknown, cb: (e: null, a: dns.LookupAddress[]) => void) => cb(null, [{ address: "169.254.169.254", family: 4 }])) as unknown as typeof dns.lookup;
    await expect(fetchSource("https://metadata.example/latest", inside)).rejects.toThrow("isn't public");
  });
});
