// The test build: a copy of the extension in a temporary folder whose config.js has one more environment, `test`, that
// points at the fake planque on this computer and pins its test key. The extension in chrome/ is never changed: only the
// copy's config.js and manifest.json are rewritten, and a guard test (config.e2e.test.js) checks the real config.js.

import { cpSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";

export const SOURCE = fileURLToPath(new URL("..", import.meta.url));

/** What the copy leaves out: the tests, this harness, the store's listing and anything installed. */
const SKIP = new Set(["test", "e2e", "store", "node_modules", "package.json", "package-lock.json"]);

/**
 * Copies chrome/ to a new temporary folder and points the copy at the fake planque: `api` (http://127.0.0.1:<port>),
 * `relay` (ws://127.0.0.1:<port>/relay) and `planqueKey` (the test key's SPKI, base64url). Returns the folder.
 */
export function buildTestExtension({ api, relay, planqueKey }) {
  const dir = mkdtempSync(join(tmpdir(), "tafs-e2e-ext-"));
  cpSync(SOURCE, dir, {
    recursive: true,
    filter: (src) => {
      const rel = relative(SOURCE, src);
      return rel === "" || !SKIP.has(rel.split(sep)[0]);
    },
  });
  const configPath = join(dir, "src", "config.js");
  writeFileSync(configPath, testConfig(readFileSync(configPath, "utf8"), { api, relay, planqueKey }));
  // The fake planque is plain http on 127.0.0.1: the copy may reach it. The manifest's hash changes with it, which the fake
  // planque takes as it comes (it keeps no allowlist of builds).
  const manifestPath = join(dir, "manifest.json");
  const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
  manifest.host_permissions = [...manifest.host_permissions, "http://127.0.0.1/*"];
  writeFileSync(manifestPath, JSON.stringify(manifest, null, 2));
  return dir;
}

/** The copy's config.js: the `test` environment added after `production`, and ENV set to it. Throws when the file's shape moved. */
export function testConfig(source, { api, relay, planqueKey }) {
  const env = `  test: {
    api: ${JSON.stringify(api)},
    relay: ${JSON.stringify(relay)},
    screenshots: false,
    pushKey: "",
    planqueKey: ${JSON.stringify(planqueKey)},
  },
});`;
  const out = source.replace(/\n\}\);\n/, "\n" + env + "\n").replace(/export const ENV = "production";/, 'export const ENV = "test";');
  if (!out.includes('ENV = "test"') || !out.includes("  test: {")) throw new Error("config.js changed shape: the test build can't point it at the fake planque");
  return out;
}
