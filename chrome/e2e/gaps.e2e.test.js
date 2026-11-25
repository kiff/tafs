// End to end: the riskiest gaps the other files left open, in order of risk to the person. The person's data reaching the taf:
// a sensitive site of the grant (a bank) the phone didn't allow, and a site the person switched off ("Not on this site"). A wrong
// action: a step replayed after Chrome stopped the service worker, and a click that would place an order or pay, known by the
// page's semantics alone (docs/chrome.md, "Sensitive sites", "Not on this site", "Steps").

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { playwright, start, newRun, ref, until, tabAt, runState, inWorker } from "./harness.mjs";
import { joining } from "./joining.mjs";
import { BANK_WORDS } from "./sites.mjs";

const skip = !playwright;
let h;
let j;

before(async () => {
  if (skip) return;
  h = await start({ cloud: true });
  j = await joining(h);
});

after(async () => {
  if (h) await h.close();
});

const bankHits = () => h.sites.hits.filter((x) => x.startsWith("bank.test "));

// ---- A sensitive site of the grant ----

/** The grant's sites with a bank among them, as the phone signed them; `allowed`: the sensitive sites it allows, signed. */
const BANKED = ["shop.test", "news.test", "bank.test"];
async function banked(fields, allowed = undefined) {
  return h.planque.sendStep({
    sites: BANKED,
    sites_signed: await h.planque.sitesSigned(BANKED),
    ...(allowed ? { sensitive_allowed: allowed } : {}),
    ...fields,
  });
}

test("a sensitive site of the grant opens only on the phone's own signed allowance; planque's word is not one", { skip }, async () => {
  const url = h.sites.url("bank.test", "/?case=open");
  const before = bankHits().length;
  const none = await banked({ op: "open", run: newRun("bank-none"), url });
  assert.equal(none.ok, false);
  assert.equal(none.error.code, "site_blocked");
  const forged = await banked({ op: "open", run: newRun("bank-forged"), url }, [await h.planque.sensitiveAllowed("bank.test", { key: h.planque.planqueKeys.privateKey })]);
  assert.equal(forged.ok, false);
  assert.equal(forged.error.code, "site_blocked");
  assert.equal(bankHits().length, before, "the bank was never asked for anything");

  const run = newRun("bank-allowed");
  const allowed = await banked({ op: "open", run, url }, [await h.planque.sensitiveAllowed("bank.test")]);
  assert.equal(allowed.ok, true, JSON.stringify(allowed.error));
  assert.ok(allowed.page.text.includes(BANK_WORDS));
  await h.fromPanel({ type: "stop", run });
});

test("the taf's click on a link to a sensitive site of the grant the phone didn't allow: the bank is never asked for anything", {
  skip,
  todo: "runner.js fence() allows every site of the grant (requestDomains: run.sites and run.allowed), a sensitive one the phone didn't allow among them, so Chrome's fence lets the main-frame request to bank.test go; only onBeforeNavigate refuses it (refuseURL: site_blocked) and sends the run's own tab to about:blank, racing the request it doesn't stop. With a connection to the bank already open, bank.test gets GET /?from=link every time, with the person's cookies. The act answers bad_url and the move is recorded as site_not_listed, neither of them the code (site_blocked).",
}, async () => {
  // The person uses their bank in this Chrome, as people do: Chrome holds a connection to it. (With none open yet, the new
  // connection is slow enough that the tab's blank page sometimes wins; with one, the request goes every time.)
  const own = await h.context.newPage();
  await own.goto(h.sites.url("bank.test", "/?case=person"));
  await own.close();
  const run = newRun("bank-link");
  const url = h.sites.url("shop.test", "/to-bank?case=link");
  const opened = await banked({ op: "open", run, url });
  assert.equal(opened.ok, true, JSON.stringify(opened.error));
  const before = bankHits().length;
  const clicked = await banked({ op: "act", run, read_id: opened.page.read_id, act: { kind: "click", ref: ref(opened.page, "Go to your bank", "links") } });
  await new Promise((r) => setTimeout(r, 1000));
  await h.fromPanel({ type: "stop", run });
  assert.ok(!JSON.stringify(clicked).includes(BANK_WORDS), "the bank's page was read");
  assert.deepEqual(bankHits().slice(before), [], "bank.test was asked for its page");
});

// ---- Not on this site ----

test("a site the person switched off: their send from the panel goes without its page, and a run's next read of their tab there takes nothing", { skip }, async () => {
  const url = h.sites.url("news.test", "/article?case=switched-off");
  const { agentRun, tabId } = await j.sendFromPanel(url);
  const run = newRun("switched-off");
  const opened = await j.step({ op: "open", run, url, agent_run: agentRun });
  assert.equal(opened.ok, true, JSON.stringify(opened.error));

  // The person switches news.test off in the panel ("Not on this site").
  assert.deepEqual(await h.fromPanel({ type: "aside_site", host: "news.test", off: true }), { off: true });
  try {
    const read = await j.step({ op: "read", run, agent_run: agentRun });
    assert.equal(read.ok, false, "the run read a page of a site the person switched off");
    assert.equal(read.error.code, "site_blocked");
    assert.ok(!JSON.stringify(read).includes("Tide tables"));
    await h.fromPanel({ type: "stop", run });
    await until(async () => (await inWorker(h, (id) => chrome.tabs.get(id).then((t) => t.groupId), tabId)) === -1, "their tab given back");

    // A send from the panel with that page in front: the words go, its page doesn't.
    await j.owner.bringToFront();
    const sends = h.cloud.sends.length;
    const answer = await h.fromPanel({ type: "taf_send", taf: h.cloud.tafId, words: "Sum up this page", attachments: [], clientID: "c_off_" + Date.now(), tabId, windowId: (await j.front()).windowId });
    assert.equal(answer.error, undefined, JSON.stringify(answer));
    const sent = await until(() => h.cloud.sends.length > sends && h.cloud.sends.at(-1), "the send");
    assert.ok(!sent.page, "the page went with the words: " + JSON.stringify(sent.page && sent.page.url));
    assert.ok(!JSON.stringify(sent).includes("Tide tables"));
  } finally {
    await h.fromPanel({ type: "aside_site", host: "news.test", off: false });
  }
});

// ---- A step replayed after the worker restarts ----

/** Stops the extension's service worker through the DevTools protocol (worker.e2e.test.js), and waits for a new one's welcome. */
async function restartWorker() {
  const welcomed = new Promise((resolve) => h.planque.once("welcome", resolve));
  const cdp = await h.context.newCDPSession(j.owner);
  const status = new Map();
  const ours = new Set();
  cdp.on("ServiceWorker.workerVersionUpdated", (e) => {
    for (const v of e.versions) {
      if (v.scriptURL.startsWith(`chrome-extension://${h.extensionId}/`)) ours.add(v.versionId);
      status.set(v.versionId, v.runningStatus);
    }
  });
  await cdp.send("ServiceWorker.enable");
  const id = await until(() => [...ours].find((v) => status.get(v) === "running"), "the extension's running worker");
  const stopped = until(() => status.get(id) === "stopped", "the worker to stop");
  await cdp.send("ServiceWorker.stopWorker", { versionId: id });
  await stopped;
  await cdp.send("ServiceWorker.disable").catch(() => {});
  await cdp.detach().catch(() => {});
  await h.fromPanel({ type: "state" }).catch(() => {});
  await welcomed;
}

test("a step replayed after Chrome stopped the service worker is still dropped: nothing opens a second time", { skip }, async () => {
  const url = h.sites.url("news.test", "/article?case=replay-restart");
  const sent = await h.planque.step({ op: "open", run: newRun("replay-restart"), url });
  const first = await h.planque.sendRaw(sent);
  assert.equal(first.ok, true, JSON.stringify(first.error));
  assert.equal(j.loads(url), 1);
  await h.fromPanel({ type: "stop", run: sent.step.run });

  await restartWorker();
  const before = h.planque.messages.length;
  h.planque.send(sent);
  // A good step after it is answered, so the replay had its turn.
  const after = await h.planque.sendStep({ op: "open", run: newRun("replay-after"), url: h.sites.url("news.test", "/article?case=replay-after") });
  assert.equal(after.ok, true, JSON.stringify(after.error));
  await new Promise((r) => setTimeout(r, 300));
  assert.deepEqual(h.planque.messages.slice(before).filter((m) => m.type === "result" && m.id === sent.step.id), [], "the replay was answered");
  assert.equal(j.loads(url), 1, "the replay opened the page again");
  await h.fromPanel({ type: "stop", run: after.run || "" }).catch(() => {});
});

// ---- A click that would place an order or pay ----

for (const [kind, what] of [
  ["schema", "a submit in a schema.org Order"],
  ["provider", "a payment provider's button"],
  ["card", "the submit of a form with a card field"],
  ["frame", "a button on a page with a provider's payment frame"],
]) {
  test(`the taf's click on ${what}, whose words say only "Continue", is refused payment_step and nothing is ordered`, { skip }, async () => {
    const run = newRun("order-" + kind);
    const url = h.sites.url("shop.test", `/order-${kind}?case=order`);
    const opened = await j.step({ op: "open", run, url });
    assert.equal(opened.ok, true, JSON.stringify(opened.error));
    const control = opened.page.controls.find((c) => c.label === "Continue");
    assert.ok(control, JSON.stringify(opened.page.controls));
    if (kind === "frame") assert.equal(opened.page.payment_frame, true, "the read says the page has a payment frame");
    else assert.equal(control.places_order, true, "the read marks the control as placing an order");
    const clicked = await j.step({ op: "act", run, read_id: opened.page.read_id, act: { kind: "click", ref: control.ref } });
    assert.equal(clicked.ok, false, "the click went through");
    assert.equal(clicked.error.code, "payment_step");
    const tab = await until(() => tabAt(h, url), "the run's tab");
    assert.equal(await tab.textContent("#out"), "not yet", "nothing was ordered");
    await h.fromPanel({ type: "stop", run });
  });
}
