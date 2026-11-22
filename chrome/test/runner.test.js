import { test } from "node:test";
import assert from "node:assert/strict";
import { Runner } from "../src/runner.js";
import { LABEL, sign, newKeyPair, publicSPKI, b64u } from "../src/crypto.js";

// The paired phone: it signs each grant's sites (tafs-chrome/sites/v1) and sensitive allowances.
const phone = await newKeyPair();
const paired = { chromeId: "chr_1", phoneKey: b64u(await publicSPKI(phone.publicKey)) };
const signedSites = async (sites, grant = "g_1") => ({ sites, sig: await sign(phone.privateKey, LABEL.sites, { chrome_id: "chr_1", grant, sites }) });
const SHOP = await signedSites(["shop.example"]);
const PAYPAL = await signedSites(["paypal.com"]);

/** A fake of the parts of Chrome's extension API the runner uses, recording every call. */
function fakeChrome({ permitted = true, page = null } = {}) {
  const calls = [];
  const listeners = {};
  const on = (name) => ({ addListener: (f) => (listeners[name] = f) });
  const tabs = new Map();
  let nextTab = 100;
  const pageFor = (url) => page || { read_id: "rd_" + calls.length, url, title: "t", text: "", fields: [{ ref: "f1", label: "Email", kind: "email" }], controls: [{ ref: "c1", label: "Save" }, { ref: "c2", label: "Pay now" }], links: [] };
  let actAnswer = { ok: true };
  const c = {
    calls,
    listeners,
    tabsMap: tabs,
    setAct: (a) => (actAnswer = a),
    setNextTab: (n) => (nextTab = n),
    webNavigation: { onBeforeNavigate: on("before"), onCommitted: on("committed"), onCreatedNavigationTarget: on("created") },
    tabs: {
      onRemoved: on("removed"),
      query: async () => {
        calls.push(["tabs.query"]);
        return [];
      },
      get: async (id) => ({ id, status: "complete", url: tabs.get(id) }),
      update: async (id, props) => {
        calls.push(["tabs.update", id, props]);
        if (props.url) tabs.set(id, props.url);
      },
      group: async (o) => (calls.push(["tabs.group", o]), 7),
      remove: async (id) => calls.push(["tabs.remove", id]),
    },
    tabGroups: { update: async (id, p) => calls.push(["tabGroups.update", id, p]) },
    windows: {
      create: async (o) => {
        calls.push(["windows.create", o]);
        const id = nextTab++;
        tabs.set(id, o.url);
        return { id: 9, tabs: [{ id }] };
      },
      remove: async (id) => calls.push(["windows.remove", id]),
    },
    permissions: { contains: async () => permitted },
    declarativeNetRequest: { updateSessionRules: async (o) => calls.push(["dnr", o]) },
    scripting: {
      executeScript: async (o) => {
        calls.push(["executeScript", o.files ? o.files.join(",") : o.world || "isolated", o.target.tabId]);
        if (o.files || o.world === "MAIN") return [{}];
        const src = String(o.func);
        if (src.includes("TafsPage.read")) {
          calls.push(["page", "read"]);
          return [{ result: pageFor(tabs.get(o.target.tabId)) }];
        }
        if (src.includes("TafsPage.act")) {
          calls.push(["page", "act start"]);
          if (c.gate) await c.gate;
          calls.push(["page", "act end"]);
          return [{ result: actAnswer }];
        }
        return [{}];
      },
    },
  };
  return c;
}

const sleep = async () => {};

function step(extra) {
  return { id: "op", run: "run_1", taf: "Rae", grant: "g_1", sites: ["shop.example"], sites_signed: SHOP, op: "open", url: "https://shop.example/", ...extra };
}

const runner = (c) => new Runner({ chrome: c, sleep, paired: () => paired });

test("a run opens in a background window of its own, grouped and named, and reads the page", async () => {
  const c = fakeChrome();
  const r = runner(c);
  const out = await r.handle(step());
  assert.equal(out.page.url, "https://shop.example/");
  const win = c.calls.find((x) => x[0] === "windows.create");
  assert.equal(win[1].focused, false, "never in front of the owner's window");
  assert.ok(c.calls.some((x) => x[0] === "tabGroups.update" && x[2].title === "tafs · Rae"));
  assert.ok(c.calls.some((x) => x[0] === "executeScript" && x[1] === "MAIN"), "the dialog guard goes in");
  assert.ok(c.calls.some((x) => x[0] === "executeScript" && x[1] === "src/mask.js,src/page.js"));
});

test("the runner never lists the owner's tabs", async () => {
  const c = fakeChrome();
  const r = runner(c);
  const opened = await r.handle(step());
  await r.handle(step({ op: "read" }));
  await r.handle(step({ op: "act", read_id: opened.page.read_id, act: { kind: "click", ref: "c1" } }));
  assert.ok(!c.calls.some((x) => x[0] === "tabs.query"));
  // A navigation in a tab that isn't the run's is ignored.
  c.listeners.before({ tabId: 5, frameId: 0, url: "https://elsewhere.example/" });
  assert.ok(!c.calls.some((x) => x[0] === "tabs.update" && x[1] === 5));
});

test("an open off the run's sites is refused before any window opens", async () => {
  const c = fakeChrome();
  const r = runner(c);
  assert.equal((await r.handle(step({ url: "https://evil.example/" }))).error.code, "site_not_listed");
  assert.equal((await r.handle(step({ url: "https://www.paypal.com/", sites: ["paypal.com"], sites_signed: PAYPAL, run: "run_2" }))).error.code, "site_blocked");
  // planque saying the owner allowed it isn't enough: the allowance must be the paired phone's signature.
  assert.equal((await r.handle(step({ url: "https://www.paypal.com/", sites: ["paypal.com"], sites_signed: PAYPAL, run: "run_3", sensitive_allowed: ["paypal.com", { site: "paypal.com", sig: "AAAA" }] }))).error.code, "site_blocked");
  assert.ok(!c.calls.some((x) => x[0] === "windows.create"));
});

test("a navigation in a run's tab that leaves its sites is stopped, and a pop-up off them is closed", async () => {
  const c = fakeChrome();
  const r = runner(c);
  await r.handle(step());
  const tab = [...c.tabsMap.keys()][0];
  c.listeners.before({ tabId: tab, frameId: 0, url: "https://evil.example/steal" });
  assert.ok(c.calls.some((x) => x[0] === "tabs.update" && x[1] === tab && x[2].url === "about:blank"));
  c.listeners.created({ sourceTabId: tab, tabId: 555, url: "https://evil.example/" });
  assert.ok(c.calls.some((x) => x[0] === "tabs.remove" && x[1] === 555));
  c.listeners.created({ sourceTabId: tab, tabId: 556, url: "https://shop.example/help" });
  assert.ok(!c.calls.some((x) => x[0] === "tabs.remove" && x[1] === 556));
});

test("an act needs the read it was planned from, and the guard holds before the page is touched", async () => {
  const c = fakeChrome();
  const r = runner(c);
  const opened = await r.handle(step());
  assert.equal((await r.handle(step({ op: "act", read_id: "rd_unknown", act: { kind: "click", ref: "c1" } }))).error.code, "page_changed");
  const before = c.calls.filter((x) => x[0] === "executeScript").length;
  assert.equal((await r.handle(step({ op: "act", read_id: opened.page.read_id, act: { kind: "click", ref: "c2" } }))).error.code, "payment_step");
  assert.equal(c.calls.filter((x) => x[0] === "executeScript").length, before, "nothing ran in the page");
  assert.equal((await r.handle(step({ op: "act", read_id: opened.page.read_id, act: { kind: "fill", ref: "f1", sealed: "sec_1" } }))).error.code, "secret_field", "no sealed values in this build");
});

test("three failed acts in a row pause the run; Resume goes on", async () => {
  const c = fakeChrome({ page: { read_id: "rd_x", url: "https://shop.example/", fields: [], controls: [{ ref: "c1", label: "Save" }], links: [] } });
  const r = runner(c);
  await r.handle(step());
  c.setAct({ ok: false, code: "not_visible" });
  for (let i = 0; i < 3; i++) await r.handle(step({ op: "act", read_id: "rd_x", act: { kind: "click", ref: "c1" } }));
  assert.equal(r.runs.get("run_1").paused, "failures");
  assert.equal((await r.handle(step({ op: "read" }))).error.code, "paused_failures");
  r.resume("run_1");
  assert.ok((await r.handle(step({ op: "read" }))).page);
});

test("the owner's input in a run's tab pauses it, and Stop closes its window", async () => {
  const c = fakeChrome();
  const r = runner(c);
  await r.handle(step());
  const tab = [...c.tabsMap.keys()][0];
  r.ownerInput(tab);
  assert.equal((await r.handle(step({ op: "read" }))).error.code, "paused_by_owner");
  await r.stop("run_1");
  assert.ok(c.calls.some((x) => x[0] === "windows.remove"));
  assert.equal((await r.handle(step({ op: "read" }))).error.code, "stopped");
  assert.equal(r.active, false);
});

test("a site Chrome hasn't let the extension into waits for the owner's yes in the side panel", async () => {
  const c = fakeChrome({ permitted: false });
  const r = runner(c);
  const out = await r.handle(step());
  assert.equal(out.error.code, "needs_site_permission");
  assert.deepEqual(r.state()[0].asks, ["shop.example"]);
  assert.ok(!c.calls.some((x) => x[0] === "executeScript" && x[1] === "src/mask.js,src/page.js"), "nothing read without it");
});

test("a run's tab is fenced by Chrome's own rules before it loads: only its sites, never plain http", async () => {
  const c = fakeChrome();
  const r = runner(c);
  await r.handle(step());
  const tab = [...c.tabsMap.keys()][0];
  const [, rules] = c.calls.find((x) => x[0] === "dnr");
  const byPriority = Object.fromEntries(rules.addRules.map((x) => [x.priority, x]));
  assert.deepEqual(byPriority[1].condition, { tabIds: [tab], resourceTypes: ["main_frame", "sub_frame"] });
  assert.equal(byPriority[1].action.type, "block");
  assert.deepEqual(byPriority[2].condition.requestDomains, ["shop.example"]);
  assert.equal(byPriority[2].action.type, "allow");
  assert.equal(byPriority[3].condition.regexFilter, "^http://");
  const fenced = c.calls.findIndex((x) => x[0] === "dnr");
  const loaded = c.calls.findIndex((x) => x[0] === "tabs.update" && x[2].url === "https://shop.example/");
  assert.ok(fenced < loaded, "fenced before the run's page loads");
  await r.stop("run_1");
  assert.deepEqual(c.calls.filter((x) => x[0] === "dnr").at(-1)[1].removeRuleIds, rules.addRules.map((x) => x.id));
});

test("a run's sites are the phone's signed list for the grant, never planque's word, and never grow", async () => {
  const c = fakeChrome();
  const r = runner(c);
  assert.equal((await r.handle(step({ sites_signed: undefined }))).error.code, "bad_step", "unsigned");
  const planque = await newKeyPair();
  const forged = { sites: ["shop.example"], sig: await sign(planque.privateKey, LABEL.sites, { chrome_id: "chr_1", grant: "g_1", sites: ["shop.example"] }) };
  assert.equal((await r.handle(step({ sites_signed: forged }))).error.code, "bad_step", "signed by planque");
  assert.equal((await r.handle(step({ grant: "g_2" }))).error.code, "bad_step", "another grant's list");
  assert.equal((await r.handle(step({ sites: ["evil.example"] }))).error.code, "bad_step", "a site the list lacks");
  assert.ok((await r.handle(step())).page);
  assert.equal((await r.handle(step({ op: "read", sites: ["shop.example", "evil.example"] }))).error.code, "bad_step", "a run's sites never grow");
  assert.ok(!c.calls.some((x) => x[0] === "windows.create" && false));
});

test("the same act on the same page that answers with the same page twice pauses the run, and Resume starts the count again", async () => {
  const c = fakeChrome({ page: { read_id: "rd_x", url: "https://shop.example/", title: "t", text: "same", fields: [{ ref: "f1", label: "Email", kind: "email" }], controls: [{ ref: "c1", label: "Save" }, { ref: "c3", label: "Next" }], links: [] } });
  const r = runner(c);
  await r.handle(step());
  const act = (extra) => r.handle(step({ op: "act", read_id: "rd_x", act: { kind: "click", ref: "c1" }, ...extra }));
  assert.ok((await act()).page, "the first answers");
  assert.equal(r.runs.get("run_1").paused, null, "once is not a pattern");
  assert.ok((await act({ act: { kind: "click", ref: "c3" } })).page, "another act starts the count again");
  assert.ok((await act()).page);
  assert.equal(r.runs.get("run_1").paused, null);
  assert.ok((await act()).page, "the second identical answer is still given");
  assert.equal(r.runs.get("run_1").paused, "no_progress");
  assert.equal((await r.handle(step({ op: "read" }))).error.code, "paused_no_progress");
  assert.equal(r.state()[0].steps.at(-1).by, "no_progress");
  r.resume("run_1");
  assert.ok((await r.handle(step({ op: "read" }))).page);
  assert.ok((await act()).page);
  assert.equal(r.runs.get("run_1").paused, null, "after Resume the first one counts as the first");
});

test("an act that changes the page is progress, however often it repeats", async () => {
  const page = { read_id: "rd_x", url: "https://shop.example/", title: "t", text: "count 0", fields: [], controls: [{ ref: "c1", label: "More" }], links: [] };
  const c = fakeChrome({ page });
  const r = runner(c);
  await r.handle(step());
  for (let i = 1; i <= 4; i++) {
    page.text = "count " + i; // each click makes the page say something new
    assert.ok((await r.handle(step({ op: "act", read_id: "rd_x", act: { kind: "click", ref: "c1" } }))).page);
  }
  assert.equal(r.runs.get("run_1").paused, null);
});

test("a run's steps never overlap: the next waits for the one running", async () => {
  const c = fakeChrome({ page: { read_id: "rd_x", url: "https://shop.example/", title: "t", text: "", fields: [], controls: [{ ref: "c1", label: "Save" }], links: [] } });
  // Time doesn't pass in these tests, except the step's own timeout, which must wait for the gate.
  const r = new Runner({ chrome: c, sleep: (ms) => (ms >= 20_000 ? new Promise(() => {}) : Promise.resolve()), paired: () => paired });
  const opened = await r.handle(step());
  let release;
  c.gate = new Promise((res) => (release = res));
  const first = r.handle(step({ op: "act", read_id: opened.page.read_id, act: { kind: "click", ref: "c1" } }));
  const second = r.handle(step({ op: "read" }));
  await new Promise((res) => setTimeout(res, 20));
  const order = () => c.calls.filter((x) => x[0] === "page").map((x) => x[1]);
  assert.deepEqual(order().slice(-2), ["read", "act start"], "the second hasn't started");
  release();
  await Promise.all([first, second]);
  assert.deepEqual(order().slice(-4), ["act start", "act end", "read", "read"].slice(0, 4), "the act finished before the next read began");
  c.gate = null;
});

test("a page Chrome restores from its cache off the run's sites is sent to a blank page and recorded", async () => {
  const c = fakeChrome();
  const r = runner(c);
  await r.handle(step());
  const tab = [...c.tabsMap.keys()][0];
  c.listeners.committed({ tabId: tab, frameId: 0, url: "https://evil.example/restored" });
  assert.ok(c.calls.some((x) => x[0] === "tabs.update" && x[1] === tab && x[2].url === "about:blank"));
  assert.equal(r.state()[0].steps.at(-1).error, "site_not_listed");
  c.listeners.committed({ tabId: tab, frameId: 1, url: "https://evil.example/frame" });
  assert.equal(c.calls.filter((x) => x[0] === "tabs.update" && x[2].url === "about:blank").length, 1, "only the main frame");
});

test("a read at an offset asks the page for that window, and a bad offset is a bad step", async () => {
  const c = fakeChrome();
  const r = new Runner({ chrome: c, sleep: (ms) => (ms >= 20_000 ? new Promise(() => {}) : Promise.resolve()), paired: () => paired });
  await r.handle(step());
  const asked = [];
  const run = c.scripting.executeScript;
  c.scripting.executeScript = async (o) => {
    if (o.args && String(o.func).includes("TafsPage.read")) asked.push(o.args[0]);
    return run(o);
  };
  const got = await r.handle(step({ op: "read", offset: 20000 }));
  assert.ok(got.page, JSON.stringify(got));
  assert.ok((await r.handle(step({ op: "read" }))).page);
  assert.deepEqual(asked, [20000, 0], "no offset reads the start");
  for (const offset of [-1, 1.5, "20000", Infinity]) {
    assert.equal((await r.handle(step({ op: "read", offset }))).error.code, "bad_step", String(offset));
  }
  assert.deepEqual(asked, [20000, 0], "a bad offset never reaches the page");
});

test("on a checkout page a taf clicks nothing, and a dialog the page raised stops the next act with what the page said", async () => {
  const checkout = { lines: [{ item: "Blue pen", quantity: 2, price: "2.50" }], total: "5.00", currency: "EUR" };
  const c = fakeChrome({ page: { read_id: "rd_x", url: "https://shop.example/basket", title: "Basket", text: "Total €5.00", fields: [], controls: [{ ref: "c1", label: "Continue" }], links: [], checkout } });
  const r = runner(c);
  const opened = await r.handle(step({ url: "https://shop.example/basket" }));
  assert.deepEqual(opened.page.checkout, checkout, "the checkout goes up as read");
  const before = c.calls.filter((x) => x[0] === "executeScript").length;
  assert.equal((await r.handle(step({ op: "act", read_id: "rd_x", act: { kind: "click", ref: "c1" } }))).error.code, "payment_step", "even a control that doesn't pay");
  assert.equal(c.calls.filter((x) => x[0] === "executeScript").length, before, "nothing ran in the page");
  // A dialog the page raised: the next act is refused by the page, the code goes up as it is, and it counts as a failure.
  const d = fakeChrome({ page: { read_id: "rd_y", url: "https://shop.example/", title: "t", text: "", fields: [], controls: [{ ref: "c1", label: "Save" }], links: [], dialog: { kind: "confirm", text: "Delete everything?" } } });
  const r2 = runner(d);
  const read = await r2.handle(step());
  assert.deepEqual(read.page.dialog, { kind: "confirm", text: "Delete everything?" }, "the read reports the dialog");
  d.setAct({ ok: false, code: "dialog_open" });
  assert.equal((await r2.handle(step({ op: "act", read_id: "rd_y", act: { kind: "click", ref: "c1" } }))).error.code, "dialog_open");
  assert.equal(r2.runs.get("run_1").failures, 1);
});

test("a run nobody sends a step to for five minutes is closed, as Stop does; a paused one, or one with a step running, is not", async () => {
  let clock = 1_000_000;
  const c = fakeChrome();
  const r = new Runner({ chrome: c, sleep, paired: () => paired, now: () => clock });
  await r.handle(step());
  clock += 4 * 60_000;
  await r.sweep();
  assert.equal(r.runs.get("run_1").stopped, false, "four minutes is not idle");
  assert.ok((await r.handle(step({ op: "read" }))).page, "a step starts the count again");
  clock += 4 * 60_000;
  await r.sweep();
  assert.equal(r.runs.get("run_1").stopped, false);
  clock += 2 * 60_000;
  await r.sweep();
  assert.equal(r.runs.get("run_1").stopped, true);
  assert.ok(c.calls.some((x) => x[0] === "windows.remove"), "its window is gone");
  assert.equal(r.state()[0].steps.at(-1).by, "idle");
  assert.equal(r.active, false);
  // The owner's pause is waiting for the owner, not a dropped call.
  const d = fakeChrome();
  const r2 = new Runner({ chrome: d, sleep, paired: () => paired, now: () => clock });
  await r2.handle(step());
  r2.pause("run_1");
  clock += 60 * 60_000;
  await r2.sweep();
  assert.equal(r2.runs.get("run_1").stopped, false);
});

test("a fenced tab's rule ids are small whatever the tab id is, a rule's id never repeats across tabs, and a run's rules go when it ends", async () => {
  const c = fakeChrome();
  const added = [];
  c.declarativeNetRequest.updateSessionRules = async (o) => {
    c.calls.push(["dnr", o]);
    added.push(...(o.addRules || []).map((x) => x.id));
  };
  let stale = [{ id: 77 }, { id: 78 }];
  c.declarativeNetRequest.getSessionRules = async () => stale;
  const r = runner(c);
  // Chrome's tab ids can run into the billions: a rule id has to fit an int32.
  c.setNextTab(2_000_000_000);
  await r.handle(step());
  c.setNextTab(1_900_000_000);
  await r.handle(step({ run: "run_2", id: "op2" }));
  assert.equal(added.length, 6);
  assert.ok(added.every((id) => Number.isInteger(id) && id >= 1 && id <= 2 ** 31 - 1), "every rule id is a valid int32");
  assert.equal(new Set(added).size, 6, "no id repeated across two tabs");
  const removals = c.calls.filter((x) => x[0] === "dnr" && !x[1].addRules);
  assert.deepEqual(removals[0][1].removeRuleIds, [77, 78], "rules an earlier service worker left are cleared first");
  await r.stop("run_1");
  assert.deepEqual(c.calls.filter((x) => x[0] === "dnr").at(-1)[1].removeRuleIds, added.slice(0, 3), "its own rules, not the other run's");
});

test("a window that can't be set up (the fence refuses) is closed, not left open and blank, and the step says why", async () => {
  const c = fakeChrome();
  c.declarativeNetRequest.updateSessionRules = async () => {
    throw new Error("Rule with id 8000000001 does not specify a valid id");
  };
  const r = runner(c);
  const out = await r.handle(step());
  assert.equal(out.error.code, "browser_unavailable");
  assert.match(out.error.detail, /valid id/);
  assert.ok(c.calls.some((x) => x[0] === "windows.remove"), "the window is closed");
  assert.ok(!c.calls.some((x) => x[0] === "tabs.update" && x[2].url === "https://shop.example/"), "nothing was loaded");
  assert.equal(r.active, false);
});

test("two runs opening together in a fresh worker: the stale-rule clear finishes before either fence is added, so neither is removed", async () => {
  const c = fakeChrome();
  const order = [];
  c.declarativeNetRequest.getSessionRules = async () => {
    await new Promise((r) => setTimeout(r, 20));
    return [{ id: 900 }];
  };
  c.declarativeNetRequest.updateSessionRules = async (o) => {
    c.calls.push(["dnr", o]);
    if (o.addRules) order.push("add");
    else if (o.removeRuleIds.includes(900)) order.push("clear");
  };
  const r = new Runner({ chrome: c, sleep: (ms) => new Promise((x) => setTimeout(x, ms > 5 ? 5 : ms)), paired: () => paired });
  await Promise.all([r.handle(step()), r.handle(step({ run: "run_2", id: "op2" }))]);
  assert.deepEqual(order, ["clear", "add", "add"], "one clear, before both fences");
});

const PLAIN = { read_id: "rd_x", url: "https://shop.example/", title: "t", text: "", fields: [], controls: [{ ref: "c1", label: "Save" }], links: [] };

test("an act takes its picture first, a read never does, and a picture that fails never holds the act", async () => {
  const events = [];
  const shots = {
    capture: async ({ page }) => (events.push(["capture", page.url]), { png: "data:image/png;base64,AAAA" }),
    send: async (png, id) => (events.push(["send", id]), "sent"),
  };
  const c = fakeChrome({ page: PLAIN });
  const r = new Runner({ chrome: c, sleep, paired: () => paired, shots });
  const opened = await r.handle(step());
  const read = await r.handle(step({ op: "read" }));
  assert.deepEqual(events, [], "no picture for an open or a read");
  c.calls.length = 0;
  const first = await r.handle(step({ id: "op_act", op: "act", read_id: "rd_x", act: { kind: "click", ref: "c1" } }));
  assert.equal(first.error, undefined);
  assert.deepEqual(events, [["capture", "https://shop.example/"], ["send", "op_act"]]);
  assert.ok(c.calls.some((x) => x[0] === "page" && x[1] === "act end"), "the act happened");
  // A capture that throws, and one that finds nothing, never stop the act.
  for (const capture of [async () => { throw new Error("no window"); }, async () => ({ why: "site" })]) {
    const c2 = fakeChrome({ page: PLAIN });
    const r2 = new Runner({ chrome: c2, sleep, paired: () => paired, shots: { capture, send: async () => { throw new Error("must not be called"); } } });
    const o2 = await r2.handle(step());
    const done = await r2.handle(step({ op: "act", read_id: "rd_x", act: { kind: "click", ref: "c1" } }));
    assert.equal(done.error, undefined);
    assert.ok(c2.calls.some((x) => x[0] === "page" && x[1] === "act end"));
  }
});
