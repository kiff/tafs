import { test } from "node:test";
import assert from "node:assert/strict";
import { LABEL, verify, newKeyPair, publicSPKI, b64u } from "../src/crypto.js";
import { RUN_STATES, RunStates, runStateMessage } from "../src/runstate.js";

function memory() {
  let v;
  return { get: async () => v, set: async (x) => { v = x; } };
}

test("a run's state is signed by this Chrome's key over {run, state, at}, under its own label", async () => {
  assert.equal(LABEL.runState, "tafs-chrome/run_state/v1", "planque's labelRunState");
  const k = await newKeyPair();
  const key = b64u(await publicSPKI(k.publicKey));
  const body = { run: "run_1", state: "paused", at: "2026-11-24T15:20:00.000Z" };
  const m = await runStateMessage(k.privateKey, body);
  assert.deepEqual({ type: m.type, run: m.run, state: m.state, at: m.at }, { type: "run_state", ...body });
  assert.equal(await verify(key, LABEL.runState, body, m.sig), true);
  assert.equal(await verify(key, LABEL.runState, { ...body, state: "working" }, m.sig), false, "another state");
  assert.equal(await verify(key, LABEL.runState, { ...body, run: "run_2" }, m.sig), false, "another run");
  assert.equal(await verify(key, LABEL.hand, body, m.sig), false, "a hand-over's answer can never stand for it");
  assert.deepEqual(RUN_STATES, { pause: "paused", resume: "working", stop: "stopped" });
});

test("only each run's newest state waits; planque's answer drops the state it answers, never a newer one", async () => {
  const s = new RunStates(memory());
  await s.add({ run: "r1", state: "paused", at: "t1" });
  await s.add({ run: "r2", state: "paused", at: "t1" });
  s.sending({ run: "r1", at: "t1" });
  await s.add({ run: "r1", state: "working", at: "t2" }); // Resume, before planque answered the Pause
  s.sending({ run: "r1", at: "t2" });
  assert.deepEqual((await s.all()).map((x) => [x.run, x.state]), [["r2", "paused"], ["r1", "working"]]);
  await s.heard({ type: "run_state_kept", run: "r1" }); // the answer to the Pause
  assert.deepEqual((await s.all()).find((x) => x.run === "r1").state, "working", "the Resume still waits for its own answer");
  await s.heard({ type: "run_state_kept", run: "r1" }); // the answer to the Resume
  assert.equal((await s.all()).some((x) => x.run === "r1"), false);
  // An answer for a run nothing was sent for changes nothing.
  await s.heard({ type: "run_state_kept", run: "r2" });
  assert.equal((await s.all()).some((x) => x.run === "r2"), true);
});

test("a store failure keeps the state for the next welcome; any other refusal drops it", async () => {
  const s = new RunStates(memory());
  await s.add({ run: "r1", state: "paused", at: "t1" });
  s.sending({ run: "r1", at: "t1" });
  await s.heard({ type: "run_state_refused", run: "r1", code: "not_kept" });
  assert.equal((await s.all()).length, 1, "not_kept: sent again on the next welcome, not in a loop");
  s.sending({ run: "r1", at: "t1" });
  await s.heard({ type: "run_state_refused", run: "r1", code: "unknown_run" });
  assert.equal((await s.all()).length, 0, "sending it again would change nothing");
  await s.heard({ type: "something_else", run: "r1" });
  await s.heard(null);
});

test("stopped is final: a run stopped or swept never says working again, even once planque has the stop", async () => {
  const s = new RunStates(memory());
  await s.add({ run: "r1", state: "paused", at: "t1" });
  assert.equal(await s.add({ run: "r1", state: "stopped", at: "t2" }), true);
  assert.equal(await s.add({ run: "r1", state: "working", at: "t3" }), false, "nothing after a stop waits");
  assert.deepEqual((await s.all()).map((x) => x.state), ["stopped"]);
  s.sending({ run: "r1", at: "t2" });
  await s.heard({ type: "run_state_kept", run: "r1" });
  assert.equal((await s.all()).length, 0);
  assert.equal(await s.add({ run: "r1", state: "working", at: "t4" }), false, "still final after planque kept it");
  assert.equal((await s.all()).length, 0);
});
