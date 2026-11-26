// End to end: a run's life in the real extension (docs/chrome.md, "Runs", "Pause, Resume and Stop reach the taf", "A step
// handed to the person"). planque's stop and the taf's close end a run and leave its tabs open; the taf's next run closes them.
// The panel's Pause and Resume, and the person's own click in the run's tab, hold its steps; a hand-over's answer reaches
// planque signed by the extension, without what the person typed.

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { playwright, start, newRun, ref, until, tabAt, extensionState } from "./harness.mjs";
import { PASSWORD } from "./sites.mjs";

const skip = !playwright;
let h;

before(async () => {
  if (!skip) h = await start();
});

after(async () => {
  if (h) await h.close();
});

/** A URL no other test opens. */
const unique = (name, path = "/article") => h.sites.url(path === "/article" ? "news.test" : "shop.test", path + "?case=" + name);

async function open(run, url) {
  const r = await h.planque.sendStep({ op: "open", run, url });
  assert.equal(r.ok, true, JSON.stringify(r.error));
  return r;
}

/** The next signed run state the extension gives planque for `run`. */
function runState(run, state) {
  return h.planque.next("run_state", (m) => m.run === run && m.state === state);
}

test("planque's stop ends the run: planque hears it stopped, its next step is refused, and its tab stays open", { skip }, async () => {
  const run = newRun("stop");
  const url = unique("stop");
  await open(run, url);
  const said = runState(run, "stopped");
  h.planque.stopRun(run);
  const m = await said;
  assert.equal(m.verified, true, "the run's state is signed by the extension");
  const r = await h.planque.sendStep({ op: "read", run });
  assert.equal(r.ok, false);
  assert.equal(r.error.code, "stopped");
  assert.ok(tabAt(h, url), "the person still sees the page where the taf stopped");
});

test("close ends the run and leaves its tab; the taf's next run closes it", { skip }, async () => {
  const first = newRun("close");
  const url = unique("close");
  await open(first, url);
  const closed = await h.planque.sendStep({ op: "close", run: first });
  assert.equal(closed.ok, true, JSON.stringify(closed.error));
  assert.equal(closed.page, null);
  const after = await h.planque.sendStep({ op: "read", run: first });
  assert.equal(after.error.code, "stopped");
  assert.ok(tabAt(h, url), "closed, the run's tab stays for the person to see");
  // The same taf's next run: the finished run's tab, still where the run left it and not in front, closes.
  const next = newRun("next");
  const nextURL = unique("next");
  await open(next, nextURL);
  await until(() => !tabAt(h, url), "the finished run's tab to close");
  assert.ok(tabAt(h, nextURL));
});

test("the panel's Stop: the state the panel reads lists none of the stopped run's tabs as soon as the Stop is answered", { skip }, async () => {
  const run = newRun("panel-stop");
  const url = unique("panel-stop");
  await open(run, url);
  const live = await extensionState(h);
  assert.equal(live.runs.find((r) => r.id === run).tabs.length, 1, "the panel lists the run's tab while it works");
  const said = runState(run, "stopped");
  assert.deepEqual(await h.fromPanel({ type: "stop", run }), {});
  // Read at once, with no wait for a later change: the state published with the Stop.
  const now = (await extensionState(h)).runs.find((r) => r.id === run);
  assert.equal(now.stopped, true);
  assert.deepEqual(now.tabs, [], "no tab of the stopped run is listed");
  assert.equal(now.tab, null);
  assert.equal((await said).verified, true);
  assert.ok(tabAt(h, url), "its tab stays open for the person");
});

test("the panel's Pause holds the run's steps until Resume, and planque hears both", { skip }, async () => {
  const run = newRun("pause");
  await open(run, unique("pause"));
  const paused = runState(run, "paused");
  await h.fromPanel({ type: "pause", run });
  assert.equal((await paused).verified, true);
  const held = await h.planque.sendStep({ op: "read", run });
  assert.equal(held.ok, false);
  assert.equal(held.error.code, "paused_by_owner");
  const working = runState(run, "working");
  await h.fromPanel({ type: "resume", run });
  assert.equal((await working).verified, true);
  const read = await h.planque.sendStep({ op: "read", run });
  assert.equal(read.ok, true, JSON.stringify(read.error));
});

test("the person's own click in the run's tab pauses the run", { skip }, async () => {
  const run = newRun("touch");
  const url = unique("touch", "/form");
  const opened = await open(run, url);
  const tab = await until(() => tabAt(h, url), "the run's tab");
  const paused = runState(run, "paused");
  await tab.click("#name");
  await paused;
  const r = await h.planque.sendStep({ op: "act", run, read_id: opened.page.read_id, act: { kind: "fill", ref: ref(opened.page, "Full name"), value: "Typed by the taf" } });
  assert.equal(r.error.code, "paused_by_owner");
  assert.equal(await tab.inputValue("#name"), "", "nothing reached the page");
});

test("a hand-over is answered at once as handed; the person's Done reaches planque signed, without the field's value", { skip }, async () => {
  const run = newRun("hand");
  const url = unique("hand", "/form");
  const opened = await open(run, url);
  const words = "Type your password, then press Done";
  const handed = await h.planque.step({ op: "hand", run, read_id: opened.page.read_id, hand: { words, ref: ref(opened.page, "Password") } });
  const r = await h.planque.sendRaw(handed);
  assert.equal(r.verified, true);
  assert.equal(r.ok, true, JSON.stringify(r.error));
  assert.equal(r.handed, true);
  const state = await until(async () => {
    const s = await h.fromPanel({ type: "state" }).then(() => h.inPanel("chrome.storage.session.get('state').then((x) => x.state)"));
    const mine = s && s.runs.find((x) => x.id === run);
    return mine && mine.hand ? mine : null;
  }, "the panel's Your turn");
  assert.equal(state.hand.words, words);
  // The person types in the page (their typing pauses nothing while it's their turn), then presses Done in the panel.
  const tab = await until(() => tabAt(h, url), "the run's tab");
  await tab.fill("#name", "Typed by the person");
  const answer = h.planque.next("hand_result", (m) => m.hand === handed.step.id);
  assert.deepEqual(await h.fromPanel({ type: "hand_answer", run, outcome: "done" }), { ok: true });
  const m = await answer;
  assert.equal(m.verified, true, "signed by the extension's key");
  assert.equal(m.outcome, "done");
  assert.ok(m.page && Array.isArray(m.page.fields));
  for (const f of m.page.fields) assert.equal(f.value, undefined, f.label + " goes without its value");
  assert.ok(!JSON.stringify(m).includes("Typed by the person"));
  assert.ok(!JSON.stringify(m).includes(PASSWORD));
  // The run goes on: it was the person's turn, not a pause.
  const read = await h.planque.sendStep({ op: "read", run });
  assert.equal(read.ok, true, JSON.stringify(read.error));
});
