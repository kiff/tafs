// Draws one logo (logos.ts › render): an SVG, already checked, or a PNG, already checked, as a PNG of `px` a
// side, the logo centred on a light rounded tile, so it reads on light and dark screens alike. It runs in a
// worker thread so a drawing that takes too long is stopped. It loads no font, no file and no image: an SVG
// that names an image to load is refused. The tile's own image is the logo just drawn, given inline.
import { readFileSync } from "node:fs";
import { parentPort, workerData } from "node:worker_threads";
import { initWasm, Resvg } from "@resvg/resvg-wasm";

/** The share of the tile's side the logo may take, and the tile's corner and colour. */
const INNER = 0.75, CORNER = 0.22, TILE = "#FFFFFF";

/** A PNG's width and height, from its header. */
function pngSize(bytes) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  return { w: view.getUint32(16), h: view.getUint32(20) };
}

try {
  await initWasm(readFileSync(workerData.wasm));
  const options = { font: { loadSystemFonts: false }, imageRendering: 0, shapeRendering: 2, textRendering: 1 };
  const px = workerData.px, inner = Math.round(px * INNER);
  let logo, w, h;
  if (workerData.svg !== undefined) {
    const probe = new Resvg(workerData.svg, options);
    if (probe.imagesToResolve().length > 0) throw new Error("it names an image to load");
    const sw = probe.width, sh = probe.height;
    if (!(sw > 0) || !(sh > 0)) throw new Error("it has no size");
    // A logo is near square: a huge or very long drawing isn't one.
    if (sw > 10000 || sh > 10000 || sw / sh > 16 || sh / sw > 16) throw new Error("its size isn't a logo's");
    probe.free();
    // Fitted by its longer side, so neither side passes the tile's inner box, whatever its viewBox says.
    const fitted = new Resvg(workerData.svg, { ...options, fitTo: sw >= sh ? { mode: "width", value: inner } : { mode: "height", value: inner } });
    const image = fitted.render();
    if (image.width > inner || image.height > inner) throw new Error("it drew larger than asked");
    logo = image.asPng();
    w = image.width; h = image.height;
  } else {
    logo = new Uint8Array(workerData.png);
    const size = pngSize(logo);
    if (!(size.w > 0) || !(size.h > 0) || size.w / size.h > 16 || size.h / size.w > 16) throw new Error("its size isn't a logo's");
    const scale = inner / Math.max(size.w, size.h);
    w = Math.max(1, Math.round(size.w * scale)); h = Math.max(1, Math.round(size.h * scale));
  }
  const b64 = Buffer.from(logo).toString("base64");
  const tile = `<svg xmlns="http://www.w3.org/2000/svg" width="${px}" height="${px}" viewBox="0 0 ${px} ${px}">` +
    `<rect width="${px}" height="${px}" rx="${Math.round(px * CORNER)}" fill="${TILE}"/>` +
    `<image x="${(px - w) / 2}" y="${(px - h) / 2}" width="${w}" height="${h}" href="data:image/png;base64,${b64}"/></svg>`;
  const drawn = new Resvg(tile, options).render();
  if (drawn.width !== px || drawn.height !== px) throw new Error("the tile drew at another size");
  parentPort.postMessage({ png: drawn.asPng() });
} catch (err) {
  parentPort.postMessage({ error: String(err && err.message ? err.message : err) });
}
