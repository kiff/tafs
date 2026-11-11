#!/usr/bin/env node
// Loads the Lambda bundle (dist-lambda/index.mjs) as Lambda would, after `npm run bundle`: the tests run the
// TypeScript, and a bundle that can't load answers 502 to every route. Then it draws one logo with the bundled
// worker and WebAssembly, so a bundle missing either fails here and not on the first logo. Runs in CI before
// main deploys.
// Lambda's runtime provides the `awslambda` global for response streaming; it's stood in for here.
import { Worker } from "node:worker_threads";

globalThis.awslambda = { streamifyResponse: (handler) => handler, HttpResponseStream: { from: (stream) => stream } };
const dist = new URL("../dist-lambda/", import.meta.url);
const bundle = await import(new URL("index.mjs", dist));
for (const name of ["handler", "stream"]) {
  if (typeof bundle[name] !== "function") throw new Error(`the bundle has no ${name} function`);
}
const png = await new Promise((resolve, reject) => {
  const worker = new Worker(new URL("logo-worker.mjs", dist), { workerData: {
    svg: `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M2 2h20v20H2z"/></svg>`, px: 96,
    wasm: new URL("index_bg.wasm", dist).pathname } });
  const timer = setTimeout(() => { void worker.terminate(); reject(new Error("the bundled worker took too long")); }, 10_000);
  worker.once("message", (m) => { clearTimeout(timer); void worker.terminate(); m.png ? resolve(m.png) : reject(new Error(`the bundled worker failed: ${m.error}`)); });
  worker.once("error", (err) => { clearTimeout(timer); reject(err); });
});
if (png[1] !== 0x50 || png[2] !== 0x4e || png[3] !== 0x47) throw new Error("the bundled worker drew no PNG");
console.log(`the bundle loads, with handler and stream, and its worker draws a logo (${png.length} bytes)`);
