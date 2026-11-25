// The test build never reaches the extension that ships: its environment and key live only in the temporary copy
// (build.mjs). These checks need no browser.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, rmSync } from "node:fs";
import { join } from "node:path";
import { ENV, ENVIRONMENTS } from "../src/config.js";
import { SOURCE, buildTestExtension, testConfig } from "./build.mjs";

const PRODUCTION_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAExS_lxBTwDdO8wf8ksu6kkIF8r3Z-Vp3tWZUKjlBjQezW0BhCCW_0tkGqRewu0qBL8KDggls0cjFF-NDcnAjH7g";

test("the extension that ships is production's, with production's pinned key, and no test environment", () => {
  assert.equal(ENV, "production");
  assert.deepEqual(Object.keys(ENVIRONMENTS), ["production"]);
  assert.equal(ENVIRONMENTS.production.planqueKey, PRODUCTION_KEY);
  assert.equal(ENVIRONMENTS.production.api, "https://planque.kiff.dev");
  assert.equal(ENVIRONMENTS.production.relay, "wss://relay.planque.kiff.dev");
  const manifest = JSON.parse(readFileSync(join(SOURCE, "manifest.json"), "utf8"));
  assert.deepEqual(manifest.host_permissions, ["https://*/*"], "no http host in the shipped manifest");
});

test("the test build rewrites only its copy", () => {
  const before = readFileSync(join(SOURCE, "src", "config.js"), "utf8");
  const manifestBefore = readFileSync(join(SOURCE, "manifest.json"), "utf8");
  const dir = buildTestExtension({ api: "http://127.0.0.1:9", relay: "ws://127.0.0.1:9/relay", planqueKey: "TESTKEY" });
  try {
    assert.equal(readFileSync(join(SOURCE, "src", "config.js"), "utf8"), before);
    assert.equal(readFileSync(join(SOURCE, "manifest.json"), "utf8"), manifestBefore);
    const copy = readFileSync(join(dir, "src", "config.js"), "utf8");
    assert.match(copy, /export const ENV = "test";/);
    assert.match(copy, /planqueKey: "TESTKEY"/);
    assert.ok(copy.includes(PRODUCTION_KEY), "production's entry is left as it is");
    assert.throws(() => readFileSync(join(dir, "test", "core.test.js")), "the tests aren't copied");
    assert.throws(() => readFileSync(join(dir, "e2e", "build.mjs")), "nor this harness");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("a config.js whose shape moved fails the build rather than shipping production's settings to the test", () => {
  assert.throws(() => testConfig("export const ENV = 'production';", { api: "a", relay: "b", planqueKey: "c" }), /changed shape/);
});
