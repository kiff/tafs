import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { Panels, joinFront, MARK_MS } from "../src/panels.js";
import { joinRefusal, joinedRefusal } from "../src/sites.js";

// The side panels and the runs the person started from one (docs/chrome.md, "A taf works where you are").

function port({ name = "panel", sender = { id: "ext" } } = {}) {
  const on = {};
  return { p: { name, sender, onMessage: { addListener: (f) => (on.msg = f) }, onDisconnect: { addListener: (f) => (on.gone = f) } }, say: (m) => on.msg && on.msg(m), close: () => on.gone && on.gone() };
}

function open(panels, windowId) {
  const x = port();
  assert.equal(panels.connect(x.p, "ext"), true);
  x.say({ windowId });
  return x;
}

test("only the extension's own panel page says a panel is open, and only while its port is", () => {
  const panels = new Panels();
  for (const [what, p] of [["a content script's port", port({ sender: { id: "ext", tab: { id: 5 } } })], ["another extension", port({ sender: { id: "other" } })], ["another port", port({ name: "relay" })]]) {
    assert.equal(panels.connect(p.p, "ext"), false, what);
    p.say({ windowId: 3 });
    assert.equal(panels.isOpen(3), false, what);
  }
  const x = open(panels, 3);
  x.say({ windowId: 9 }); // its window is said once
  assert.equal(panels.isOpen(3), true);
  assert.equal(panels.isOpen(9), false);
  x.close();
  assert.equal(panels.isOpen(3), false);
});

test("a send marks the run tafs cloud's answer names, with the panel's window and the tab in front; a failed send, or one naming no run, marks nothing", async () => {
  let at = 0;
  const panels = new Panels({ now: () => at, sleep: async () => {} });
  open(panels, 3);
  assert.deepEqual(await panels.sent(3, 42, Promise.resolve({ run: "kr_1", message: {} })), { run: "kr_1", message: {} }, "the answer goes back unchanged");
  await panels.sent(3, 42, Promise.resolve({ message: {} }));
  await assert.rejects(panels.sent(3, 42, Promise.reject(new Error("down"))));
  await panels.sent(7, 43, Promise.resolve({ run: "kr_7" })); // no panel open in window 7
  await panels.sent(3, null, Promise.resolve({ run: "kr_8" })); // no tab in front
  assert.deepEqual(panels.marks.map((k) => k.agentRun), ["kr_1"]);
  assert.equal(await panels.take(undefined), null, "a step naming no run");
  assert.equal(await panels.take("kr_2"), null, "another run");
  assert.deepEqual(await panels.take("kr_1"), { windowId: 3, tabId: 42 });
  assert.equal(await panels.take("kr_1"), null, "taken once");
  await panels.sent(3, 42, Promise.resolve({ run: "kr_3" }));
  at += MARK_MS + 1;
  assert.equal(await panels.take("kr_3"), null, "older than two minutes");
});

test("a closed panel's marks go with it", async () => {
  const panels = new Panels({ sleep: async () => {} });
  const x = open(panels, 3);
  await panels.sent(3, 42, Promise.resolve({ run: "kr_1" }));
  x.close();
  open(panels, 3);
  assert.equal(await panels.take("kr_1"), null);
});

test("a run's first step that comes before the send's answer waits for it, a few seconds at most", async () => {
  const panels = new Panels({ sleep: () => new Promise(() => {}) });
  open(panels, 3);
  let answer;
  const sending = panels.sent(3, 42, new Promise((r) => (answer = r)));
  const taking = panels.take("kr_1");
  answer({ run: "kr_1" });
  await sending;
  assert.deepEqual(await taking, { windowId: 3, tabId: 42 });
  // A send that never answers holds the step no longer than the wait.
  const slow = new Panels({ sleep: async () => {} });
  open(slow, 3);
  slow.sent(3, 42, new Promise(() => {}));
  assert.equal(await slow.take("kr_1"), null);
});

test("the worker asks the page consent only for a marked run, before anything of the tab is looked at, and nothing joins without it", async () => {
  const panels = new Panels({ sleep: async () => {} });
  open(panels, 3);
  let asked = 0;
  const consent = (given) => async () => (asked++, given);
  assert.equal(await joinFront({ panels, agentRun: "kr_1", consent: consent(true) }), null, "no mark");
  assert.equal(asked, 0, "not even asked");
  await panels.sent(3, 42, Promise.resolve({ run: "kr_1" }));
  assert.equal(await joinFront({ panels, agentRun: "kr_1", consent: consent(false) }), null, "no consent");
  assert.equal(asked, 1);
  await panels.sent(3, 42, Promise.resolve({ run: "kr_2" }));
  assert.deepEqual(await joinFront({ panels, agentRun: "kr_2", consent: consent(true), off: async () => ["news.example"] }), { windowId: 3, tabId: 42, off: ["news.example"] });
  assert.equal(await joinFront({ panels, agentRun: "kr_3", consent: async () => { throw new Error("tafs cloud down"); } }), null, "a consent that can't be read is no consent");
});

test("which tab may join: the active tab of the panel's window, on the run's sites, never a refused one", () => {
  const tab = { id: 42, windowId: 3, active: true, incognito: false, url: "https://shop.example/a" };
  const rules = { windowId: 3, sites: ["shop.example", "paypal.com"], allowed: ["paypal.com"], off: [] };
  assert.equal(joinRefusal(tab, rules), null);
  assert.equal(joinRefusal({ ...tab, windowId: 4 }, rules), "not_front", "another window");
  assert.equal(joinRefusal({ ...tab, active: false }, rules), "not_front");
  assert.equal(joinRefusal(null, rules), "not_front");
  assert.equal(joinRefusal({ ...tab, pinned: true }, rules), "pinned");
  assert.equal(joinRefusal({ ...tab, incognito: true }, rules), "site_blocked");
  assert.equal(joinRefusal({ ...tab, url: "chrome://settings" }, rules), "bad_url");
  assert.equal(joinRefusal({ ...tab, url: "http://shop.example/" }, rules), "bad_url");
  assert.equal(joinRefusal({ ...tab, url: "https://elsewhere.example/" }, rules), "site_not_listed");
  // A payment site the phone allowed for the run's own tab: never the person's.
  assert.equal(joinRefusal({ ...tab, url: "https://www.paypal.com/" }, rules), "site_blocked");
  assert.equal(joinRefusal({ ...tab, url: "https://mail.shop.example/" }, rules), "site_blocked", "mail");
  assert.equal(joinRefusal(tab, { ...rules, off: ["shop.example"] }), "site_blocked", "switched off");
  // On a step, wherever the tab is now: only the page counts.
  assert.equal(joinedRefusal({ ...tab, active: false, windowId: 9 }, rules), null);
  assert.equal(joinedRefusal({ ...tab, url: "https://elsewhere.example/" }, rules), "site_not_listed");
  assert.equal(joinedRefusal(null, rules), "no_page");
});

test("the worker joins a tab only through the panels' mark and the page consent, and marks a run only from the panel's own sends", () => {
  const sw = readFileSync(new URL("../src/sw.js", import.meta.url), "utf8");
  assert.match(sw, /chrome\.runtime\.onConnect\.addListener\(\(port\) => panels\.connect\(port, chrome\.runtime\.id\)\);/);
  assert.match(sw, /front: \(\{ agentRun \}\) => joinFront\(\{ panels, agentRun, consent: pageConsentReady, off: asideOff \}\),/);
  assert.equal((sw.match(/panels\.sent\(/g) || []).length, 2, "Coach's send and a taf's send, nothing else");
  assert.match(sw.slice(sw.indexOf("taf_send:"), sw.indexOf("attach:")), /panels\.sent\(m\.windowId, m\.tabId, \(await coach\(\)\)\.sendToTaf\(/);
  assert.match(sw.slice(sw.indexOf("coach_send:"), sw.indexOf("aside_status:")), /panels\.sent\(m\.windowId, m\.tabId, \(await coach\(\)\)\.send\(/);
  const panel = readFileSync(new URL("../sidepanel/panel.js", import.meta.url), "utf8");
  assert.match(panel, /chrome\.runtime\.connect\(\{ name: "panel" \}\)/);
  assert.match(panel, /type: "taf_send", [^\n]*tabId: activeTab, windowId: panelWindow/);
});
