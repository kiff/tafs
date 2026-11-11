#!/usr/bin/env node
// Loads the Lambda bundle (dist-lambda/index.mjs) as Lambda would, after `npm run bundle`: the tests run the
// TypeScript, and a bundle that can't load answers 502 to every route. Runs in CI before main deploys.
// Lambda's runtime provides the `awslambda` global for response streaming; it's stood in for here.
globalThis.awslambda = { streamifyResponse: (handler) => handler, HttpResponseStream: { from: (stream) => stream } };
const bundle = await import(new URL("../dist-lambda/index.mjs", import.meta.url));
for (const name of ["handler", "stream"]) {
  if (typeof bundle[name] !== "function") throw new Error(`the bundle has no ${name} function`);
}
console.log("the bundle loads, with handler and stream");
