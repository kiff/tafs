import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { PLANQUE_CONTRACTS, planqueContract, layOver, welcomeAsPlanque, connectionsAsPlanque, stepAsPlanque, STEP_OP_FIELDS, withPhoneSignature } from "./planque-contracts.mjs";
import { readConnections } from "../src/relay.js";
import { clockOffset, signedSitesOf } from "../src/protocol.js";
import { newKeyPair } from "../src/crypto.js";

// planque's golden files (contracts/chrome-*.json, test/planque-contracts.mjs): the seam the tests and the fake planque read
// them through, and, once planque writes them, what the extension makes of them.

/** A directory of contracts for one test, written here and removed after: never one in the repository. */
function contractsDir(files) {
  const dir = mkdtempSync(join(tmpdir(), "planque-contracts-"));
  for (const [name, answer] of Object.entries(files)) writeFileSync(join(dir, PLANQUE_CONTRACTS[name]), JSON.stringify({ about: "a test's own", answer }));
  return { url: pathToFileURL(dir + "/"), done: () => rmSync(dir, { recursive: true, force: true }) };
}

test("with none of planque's files, every message is the fake's own, as before", () => {
  const empty = contractsDir({});
  try {
    for (const name of Object.keys(PLANQUE_CONTRACTS)) assert.equal(planqueContract(name, empty.url), null, name);
    const welcome = { type: "welcome", sig: "s", connections: [{ grant: "g", agent: "a", name: "Rae", sites: ["x.test"] }], now: 1 };
    assert.deepEqual(welcomeAsPlanque(welcome, empty.url), welcome);
    assert.deepEqual(connectionsAsPlanque({ type: "connections", connections: [] }, empty.url), { type: "connections", connections: [] });
    const step = { id: "op_1", nonce: "n", op: "open", url: "https://x.test/" };
    assert.deepEqual(stepAsPlanque(step, empty.url), step);
  } finally {
    empty.done();
  }
});

test("with planque's files, its fields reach the extension, this run's values win, and a field planque doesn't send is caught", () => {
  const conn = { grant: "grt_golden", agent: "agt_golden", name: "Golden", sites: ["golden.test"], sites_signed: { sites: ["golden.test"], sig: "golden" }, kind: "browser" };
  const files = contractsDir({
    welcome: { type: "welcome", sig: "golden", connections: [conn], now: 0, server: "planque-golden" },
    connections: { type: "connections", connections: [conn] },
    step: { type: "step", sig: "golden", step: { id: "op_golden", nonce: "golden", exp: 0, chrome_id: "chr_golden", run: "run_golden", taf: "Golden", grant: "grt_golden", sites: ["golden.test"], sites_signed: { sites: ["golden.test"], sig: "golden" }, agent_run: "agr_golden", op: "open", url: "https://golden.test/" } },
  });
  try {
    const live = { grant: "g", agent: "a", name: "Rae", sites: ["x.test"], sites_signed: { sites: ["x.test"], sig: "s" } };
    const w = welcomeAsPlanque({ type: "welcome", sig: "s", connections: [live], now: 5 }, files.url);
    assert.equal(w.server, "planque-golden", "a field planque sends and the fake didn't");
    assert.equal(w.sig, "s");
    assert.deepEqual(w.connections, [{ ...live, kind: "browser" }], "each connection as planque lists one");
    assert.deepEqual(connectionsAsPlanque({ type: "connections", connections: [live] }, files.url).connections, [{ ...live, kind: "browser" }]);
    // A step: planque's envelope, never its op's own fields in another op.
    const act = stepAsPlanque({ id: "op_1", nonce: "n", exp: 9, chrome_id: "chr_1", run: "run_1", taf: "Rae", grant: "g", sites: ["x.test"], sites_signed: live.sites_signed, op: "act", read_id: "rd_1", act: { kind: "click", ref: "e1" } }, files.url);
    assert.equal(act.agent_run, "agr_golden", "the envelope's field the fake didn't set");
    assert.equal(act.url, undefined, "the golden open's url isn't carried into an act");
    assert.equal(act.op, "act");
    assert.deepEqual(STEP_OP_FIELDS, ["op", "url", "read_id", "act", "hand", "offset", "sensitive_allowed"]);
    // The fake sending what planque doesn't is a test failure, not a quiet difference.
    assert.throws(() => welcomeAsPlanque({ type: "welcome", sig: "s", connections: [], now: 5, extra: true }, files.url), /chrome-welcome\.json has no extra/);
    assert.throws(() => stepAsPlanque({ id: "op_1", made_up: 1, op: "read" }, files.url), /planque's step has no made_up/);
    assert.throws(() => layOver({ a: 1 }, { b: 2 }, "x"), /x has no b/);
  } finally {
    files.done();
  }
});

// Until planque writes its files these are skipped: dropping them into contracts/ runs them, with no change here.
const welcome = planqueContract("welcome");
const connections = planqueContract("connections");
const step = planqueContract("step");
const waiting = "planque's golden file isn't in contracts/ yet";

test("planque's welcome: every connection it lists is one the extension keeps, and its time is read", { skip: !welcome && waiting }, () => {
  assert.equal(welcome.type, "welcome");
  assert.equal(typeof welcome.sig, "string");
  assert.ok(Array.isArray(welcome.connections));
  assert.equal(readConnections(welcome.connections).length, welcome.connections.length, "none left out as malformed");
  assert.equal(typeof welcome.now, "number", "planque's time, for the clock offset");
  assert.equal(clockOffset(welcome, welcome.now), 0);
});

test("planque's connections on a change: every one is kept, with the phone's signature over its sites", { skip: !connections && waiting }, () => {
  assert.equal(connections.type, "connections");
  const kept = readConnections(connections.connections);
  assert.equal(kept.length, connections.connections.length, "none left out as malformed");
  for (const c of kept) assert.ok(c.sites_signed, `${c.grant}: planque relays the phone's signature, or no page goes for it`);
});

test("planque's step: the fields the extension checks before it runs one", { skip: !step && waiting }, () => {
  assert.equal(step.type, "step");
  assert.equal(typeof step.sig, "string");
  const s = step.step;
  for (const k of ["id", "nonce", "chrome_id", "run", "grant"]) assert.equal(typeof s[k], "string", k);
  assert.equal(typeof s.exp, "number");
  assert.ok(["open", "read", "act", "hand", "close"].includes(s.op), s.op);
  assert.ok(Array.isArray(s.sites) && s.sites.length > 0);
  assert.ok(s.sites_signed && Array.isArray(s.sites_signed.sites) && typeof s.sites_signed.sig === "string");
});

test("planque's connection, signed again by the paired phone, gives its sites; without its signature, none", { skip: !connections && waiting }, async () => {
  const phone = await newKeyPair();
  const pairing = { phoneKey: phone.publicKey, chromeId: "chr_golden" };
  const c = connections.connections[0];
  const signed = await withPhoneSignature(c, { phoneKey: phone.privateKey, chromeId: pairing.chromeId });
  assert.equal(signed.grant, c.grant, "the file's grant");
  assert.deepEqual(await signedSitesOf([c.grant], [signed], pairing), ["wikipedia.org"]);
  // The file's own sig is a placeholder: as it is, nothing is taken.
  assert.deepEqual(await signedSitesOf([c.grant], [c], pairing), [], "planque's placeholder signs nothing");
  // planque's connection without the phone's signature: its sites are never taken.
  const { sites_signed: _dropped, ...unsigned } = c; // eslint-disable-line no-unused-vars
  assert.deepEqual(await signedSitesOf([c.grant], [unsigned], pairing), []);
  assert.deepEqual(await signedSitesOf([c.grant], [await withPhoneSignature(unsigned, { phoneKey: phone.privateKey, chromeId: pairing.chromeId })], pairing), [], "nothing to sign again");
});
