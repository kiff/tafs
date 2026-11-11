// Draws one logo (logos.ts › render): an SVG, already checked, as a PNG of at most `px` a side. It runs in a
// worker thread so a drawing that takes too long is stopped. It loads no font, no file and no image: an SVG
// that names an image to load is refused.
import { readFileSync } from "node:fs";
import { parentPort, workerData } from "node:worker_threads";
import { initWasm, Resvg } from "@resvg/resvg-wasm";

try {
  await initWasm(readFileSync(workerData.wasm));
  const options = { font: { loadSystemFonts: false }, imageRendering: 0, shapeRendering: 2, textRendering: 1 };
  const probe = new Resvg(workerData.svg, options);
  if (probe.imagesToResolve().length > 0) throw new Error("it names an image to load");
  const w = probe.width, h = probe.height;
  if (!(w > 0) || !(h > 0)) throw new Error("it has no size");
  // A logo is near square: a huge or very long drawing isn't one.
  if (w > 10000 || h > 10000 || w / h > 16 || h / w > 16) throw new Error("its size isn't a logo's");
  probe.free();
  // Fitted by its longer side, so neither side passes px, whatever its viewBox says.
  const fitted = new Resvg(workerData.svg, { ...options, fitTo: w >= h ? { mode: "width", value: workerData.px } : { mode: "height", value: workerData.px } });
  const image = fitted.render();
  if (image.width > workerData.px || image.height > workerData.px) throw new Error("it drew larger than asked");
  parentPort.postMessage({ png: image.asPng() });
} catch (err) {
  parentPort.postMessage({ error: String(err && err.message ? err.message : err) });
}
