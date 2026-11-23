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
function fakeChrome({ page = null, ownerWindow = null, groupFails = false, createFails = false } = {}) {
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
      group: async (o) => {
        calls.push(["tabs.group", o]);
        if (groupFails) throw new Error("No group");
        return 7;
      },
      remove: async (id) => calls.push(["tabs.remove", id]),
      ungroup: async (ids) => calls.push(["tabs.ungroup", ids]),
      create: async (o) => {
        calls.push(["tabs.create", o]);
        if (createFails) throw new Error("No window with id");
        const id = nextTab++;
        tabs.set(id, o.url);
        return { id };
      },
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
      // The window the owner last used, when a test gives one; none, as Chrome with no normal window open, throws.
      getLastFocused: async (o) => {
        calls.push(["windows.getLastFocused", o]);
        if (!ownerWindow) throw new Error("No last-focused window");
        return ownerWindow;
      },
    },
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

test("the owner's input in a run's tab pauses it, and Stop leaves its tabs and window open", async () => {
  const c = fakeChrome();
  const r = runner(c);
  await r.handle(step());
  const tab = [...c.tabsMap.keys()][0];
  r.ownerInput(tab);
  assert.equal((await r.handle(step({ op: "read" }))).error.code, "paused_by_owner");
  await r.stop("run_1");
  assert.ok(!c.calls.some((x) => x[0] === "windows.remove" || x[0] === "tabs.remove"), "what the taf worked on stays for the person");
  assert.equal((await r.handle(step({ op: "read" }))).error.code, "stopped");
  assert.equal(r.active, false);
});

const OWNER_WINDOW = { id: 3, type: "normal", incognito: false };

test("a run opens a background tab in the owner's window, and Stop leaves it open, in a group that says done", async () => {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
  const r = runner(c);
  await r.handle(step());
  assert.ok(!c.calls.some((x) => x[0] === "windows.create"), "no window of its own");
  assert.deepEqual(c.calls.find((x) => x[0] === "windows.getLastFocused")[1], { windowTypes: ["normal"] });
  const made = c.calls.find((x) => x[0] === "tabs.create");
  assert.deepEqual(made[1], { windowId: 3, url: "about:blank", active: false }, "in the owner's window, in the background");
  const tab = [...c.tabsMap.keys()][0];
  assert.deepEqual(c.calls.find((x) => x[0] === "tabs.group")[1], { tabIds: [tab], createProperties: { windowId: 3 } }, "the group is the run's tab alone");
  const nav = c.calls.find((x) => x[0] === "tabs.update" && x[2].url === "https://shop.example/");
  assert.equal(nav[2].active, undefined, "the owner's active tab stays in front");
  await r.stop("run_1");
  assert.ok(!c.calls.some((x) => x[0] === "tabs.remove" || x[0] === "windows.remove"), "nothing closes");
  const done = c.calls.filter((x) => x[0] === "tabGroups.update").pop();
  assert.equal(done[2].title, "tafs · Rae · done", "the group says the run is done");
  assert.equal(r.active, false);
  // Unpairing is what closes it.
  const u = fakeChrome({ ownerWindow: OWNER_WINDOW });
  const q = runner(u);
  await q.handle(step());
  await q.stopAll();
  assert.deepEqual(u.calls.filter((x) => x[0] === "tabs.remove").map((x) => x[1]), [[[...u.tabsMap.keys()][0]]], "the run's tab, nothing else");
  assert.ok(!u.calls.some((x) => x[0] === "windows.remove"), "the owner's window stays");
});

test("a run in the owner's window touches only tab ids it created (unpairing closes them)", async () => {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
  const r = runner(c);
  await r.handle(step());
  const tab = [...c.tabsMap.keys()][0];
  // The owner's own tabs have ids the run never made (1, 2): a navigation or a close of theirs is not the run's.
  c.listeners.before({ tabId: 1, frameId: 0, url: "https://elsewhere.example/" });
  c.listeners.created({ sourceTabId: 2, tabId: 3000, url: "https://elsewhere.example/" });
  c.listeners.removed?.(1);
  // A tab the run's page opens on its sites joins the run, and goes with it.
  c.listeners.created({ sourceTabId: tab, tabId: 556, url: "https://shop.example/help" });
  await r.handle(step({ op: "read" }));
  await r.stopAll();
  const ours = new Set([tab, 556]);
  for (const x of c.calls.filter((y) => y[0] === "executeScript")) assert.ok(ours.has(x[2]), `a script ran in tab ${x[2]}, which the run didn't open`);
  const fenced = c.calls.filter((x) => x[0] === "dnr").flatMap((x) => (x[1].addRules || []).flatMap((rule) => rule.condition.tabIds || []));
  assert.ok(fenced.length && fenced.every((id) => ours.has(id)), "the fence rules name only the run's tabs");
  const touched = c.calls.filter((x) => ["tabs.update", "tabs.remove", "tabs.group"].includes(x[0]));
  for (const [kind, ...args] of touched) {
    const ids = kind === "tabs.group" ? args[0].tabIds : [].concat(args[0]);
    for (const id of ids) assert.ok(ours.has(id), `${kind} reached tab ${id}, which the run didn't open`);
  }
  assert.ok(!c.calls.some((x) => x[0] === "tabs.query"), "the owner's tabs are never listed");
  assert.deepEqual([...c.calls.filter((x) => x[0] === "tabs.remove").flatMap((x) => x[1])].sort(), [tab, 556].sort(), "only those two close");
});

test("with no window to join, or a private one, a run has a window of its own, which Stop leaves and unpairing closes", async () => {
  for (const ownerWindow of [null, { id: 4, type: "normal", incognito: true }, { id: 5, type: "popup", incognito: false }]) {
    const c = fakeChrome({ ownerWindow });
    const r = runner(c);
    await r.handle(step());
    assert.ok(c.calls.some((x) => x[0] === "windows.create" && x[1].focused === false), JSON.stringify(ownerWindow));
    assert.ok(!c.calls.some((x) => x[0] === "tabs.create"), "nothing opened in a private or popup window");
    const nav = c.calls.find((x) => x[0] === "tabs.update" && x[2].url === "https://shop.example/");
    assert.equal(nav[2].active, true, "in its own window the run's tab is the one in front");
    await r.stop("run_1");
    assert.ok(!c.calls.some((x) => x[0] === "windows.remove" || x[0] === "tabs.remove"), "Stop leaves it");
    const u = fakeChrome({ ownerWindow });
    const q = runner(u);
    await q.handle(step());
    await q.stopAll();
    c.calls.length = 0;
    c.calls.push(...u.calls);
    assert.ok(c.calls.some((x) => x[0] === "windows.remove" && x[1] === 9));
    // The window goes with its tab: the tab is closed first, then the window.
    assert.deepEqual(c.calls.filter((x) => x[0] === "tabs.remove").map((x) => x[1].length), [1]);
  }
});

test("a run's own window the owner has emptied (its tab dragged out) still has its tab closed at unpairing", async () => {
  const c = fakeChrome();
  const r = runner(c);
  await r.handle(step());
  const tab = [...c.tabsMap.keys()][0];
  await r.stopAll();
  assert.deepEqual(c.calls.filter((x) => x[0] === "tabs.remove").map((x) => x[1]), [[tab]]);
  assert.ok(c.calls.some((x) => x[0] === "windows.remove"));
});

test("a Stop that lands while the tab is being made leaves nothing open, in the owner's window or its own", async () => {
  for (const ownerWindow of [OWNER_WINDOW, null]) {
    const c = fakeChrome({ ownerWindow });
    const r = runner(c);
    let release;
    const gate = new Promise((resolve) => (release = resolve));
    const made = ownerWindow ? c.tabs.create : c.windows.create;
    let reached = false; // the open is at the tab being made, not before it: a timer would only guess
    (ownerWindow ? c.tabs : c.windows)[ownerWindow ? "create" : "create"] = async (o) => ((reached = true), await gate, made(o));
    const opening = r.handle(step());
    while (!reached) await new Promise((resolve) => setTimeout(resolve, 1));
    await r.stop("run_1"); // nothing of the run's exists yet to close
    release();
    const out = await opening;
    assert.equal(out.error.code, "stopped", JSON.stringify(ownerWindow));
    const tab = [...c.tabsMap.keys()][0];
    if (ownerWindow) assert.deepEqual(c.calls.filter((x) => x[0] === "tabs.remove").map((x) => x[1]), [[tab]], "the tab made is closed");
    else assert.ok(c.calls.some((x) => x[0] === "windows.remove" && x[1] === 9), "the window made is closed");
    assert.ok(!c.calls.some((x) => x[0] === "tabs.group" || (x[0] === "tabs.update" && x[2].url)), "nothing was grouped or navigated");
    assert.equal(r.active, false);
  }
});

test("a Stop that lands while the tab is being fenced and grouped closes it, and nothing is navigated", async () => {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
  const r = runner(c);
  let release;
  const gate = new Promise((resolve) => (release = resolve));
  const update = c.tabGroups.update;
  let reached = false;
  c.tabGroups.update = async (...args) => ((reached = true), await gate, update(...args));
  const opening = r.handle(step());
  while (!reached) await new Promise((resolve) => setTimeout(resolve, 1));
  await r.stop("run_1"); // the tab is the run's by now: this closes it
  release();
  assert.equal((await opening).error.code, "stopped");
  const tab = [...c.tabsMap.keys()][0];
  assert.ok(c.calls.filter((x) => x[0] === "tabs.remove").every((x) => x[1].length === 1 && x[1][0] === tab));
  assert.ok(!c.calls.some((x) => x[0] === "tabs.update" && x[2].url), "no page loaded in a stopped run's tab");
  assert.equal(r.active, false);
});

test("a tab group the owner has broken up doesn't lose the run its pop-up, and a window that closed makes a window of its own", async () => {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW, groupFails: false });
  const r = runner(c);
  await r.handle(step());
  const tab = [...c.tabsMap.keys()][0];
  c.tabs.group = async () => {
    throw new Error("No group with id 7");
  };
  c.listeners.created({ sourceTabId: tab, tabId: 556, url: "https://shop.example/help" });
  await new Promise((resolve) => setTimeout(resolve, 5));
  await r.stopAll();
  assert.deepEqual(c.calls.filter((x) => x[0] === "tabs.remove").flatMap((x) => x[1]).sort(), [tab, 556].sort(), "the pop-up is still the run's");
  // The owner's window closed between getLastFocused and tabs.create.
  const d = fakeChrome({ ownerWindow: OWNER_WINDOW, createFails: true });
  const s = runner(d);
  assert.ok((await s.handle(step())).page);
  assert.ok(d.calls.some((x) => x[0] === "windows.create"), "a window of its own");
});

test("when the tab can't be grouped in the owner's window, only the run's tab closes", async () => {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW, groupFails: true });
  const r = runner(c);
  assert.equal((await r.handle(step())).error.code, "browser_unavailable");
  const tab = [...c.tabsMap.keys()][0];
  assert.deepEqual(c.calls.filter((x) => x[0] === "tabs.remove").map((x) => x[1]), [[tab]]);
  assert.ok(!c.calls.some((x) => x[0] === "windows.remove"), "the owner's window stays");
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

test("the run's own blank page is never refused: no loop, and the page the step opens loads", async () => {
  const c = fakeChrome();
  const r = runner(c);
  await r.handle(step());
  const tab = [...c.tabsMap.keys()][0];
  const blanks = () => c.calls.filter((x) => x[0] === "tabs.update" && x[2].url === "about:blank").length;
  const before = blanks();
  // The window opens on about:blank, and a refused move is sent there: neither is a move to refuse.
  c.listeners.before({ tabId: tab, frameId: 0, url: "about:blank" });
  c.listeners.committed({ tabId: tab, frameId: 0, url: "about:blank" });
  assert.equal(blanks(), before, "about:blank is not sent to about:blank again");
  assert.ok(!r.state()[0].steps.some((s) => s.op === "navigate"), "and no refused move is recorded for it");
  // A real move off the run's sites is still refused, once.
  c.listeners.before({ tabId: tab, frameId: 0, url: "https://evil.example/" });
  assert.equal(blanks(), before + 1);
  assert.equal(r.state()[0].steps.at(-1).url, "https://evil.example/");
  // Only the exact blank page: any other page off the web is still refused (a looser match would let these through).
  let n = blanks();
  for (const url of ["about:srcdoc", "about:blank#x", "chrome://settings/", "data:text/html,hi", "javascript:void(0)"]) {
    c.listeners.before({ tabId: tab, frameId: 0, url });
    assert.equal(blanks(), ++n, url + " is still refused");
  }
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

test("a run nobody sends a step to for five minutes ends, as Stop does (its tabs stay); a paused one, or one with a step running, is not", async () => {
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
  assert.ok(!c.calls.some((x) => x[0] === "windows.remove" || x[0] === "tabs.remove"), "its tabs stay for the person");
  assert.equal(c.calls.filter((x) => x[0] === "tabGroups.update").pop()[2].title, "tafs · Rae · done");
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

// A step handed to the person (docs/chrome-panel.md): the taf says what it needs done in the page, the person does it, and Done or
// Not now goes back to planque as its own answer. The extension acts for no one here.
const FILLED = { read_id: "rd_h", url: "https://shop.example/pay", title: "Pay", text: "Pay now", fields: [{ ref: "f1", label: "Card number", kind: "card", masked: true }, { ref: "f2", label: "Name", kind: "text", value: "Ana Pérez" }, { ref: "f3", label: "Gift", kind: "checkbox", value: "checked" }], controls: [{ ref: "c1", label: "Pay now" }], links: [] };

async function handing() {
  const c = fakeChrome({ page: FILLED });
  const answers = [];
  let clock = 1_000_000;
  const r = new Runner({ chrome: c, sleep, paired: () => paired, now: () => clock, onHandAnswer: (a) => answers.push(a) });
  await r.handle(step());
  const hand = (extra = {}, words = "Fill in the card number, then tap Done") => ({ id: "h1", op: "hand", read_id: "rd_h", hand: { words, ref: "f1" }, ...extra });
  return { c, r, answers, hand, tick: (ms) => (clock += ms), tab: () => [...c.tabsMap.keys()][0] };
}

test("a step handed to the person is answered at once as handed, and waits for them", async () => {
  const { r, answers, hand } = await handing();
  const out = await r.handle(step(hand()));
  assert.deepEqual(out, { handed: true });
  assert.deepEqual(r.state()[0].hand, { id: "h1", words: "Fill in the card number, then tap Done", ref: true });
  assert.deepEqual(answers, [], "nothing is answered for the person");
});

test("the extension checks a hand-over itself: its words, the field it names, the read, and the page's site", async () => {
  const { r, hand } = await handing();
  const bad = { "no words": hand({}, ""), "only spaces": hand({}, "   "), "a newline": hand({}, "Fill\nin"), "a direction mark": hand({}, "Fill in ‮elif"), "281 characters": hand({}, "a".repeat(281)), "words that aren't text": hand({ hand: { words: 7 } }), "a ref that isn't text": hand({ hand: { words: "ok", ref: 3 } }), "no hand": { id: "h2", op: "hand", read_id: "rd_h" } };
  for (const [why, s] of Object.entries(bad)) assert.equal((await r.handle(step(s))).error.code, "bad_step", why);
  assert.equal((await r.handle(step(hand({ hand: { words: "ok", ref: "f9" } })))).error.code, "bad_step", "a ref that is not in the read");
  assert.equal((await r.handle(step(hand({ read_id: "rd_other" })))).error.code, "page_changed", "a read the extension doesn't have");
  assert.equal((await r.handle(step(hand({ hand: { words: "a".repeat(280) } })))).handed, true, "280 characters are fine");
  assert.equal((await r.handle(step(hand({ id: "h3", hand: { words: "ok" } })))).handed, true, "no ref is fine");
  assert.equal(r.state()[0].hand.ref, false);
});

test("a hand-over on a page off the run's sites is refused, as any step is", async () => {
  const { c, r, hand, tab } = await handing();
  c.tabsMap.set(tab(), "https://evil.example/pay");
  assert.equal((await r.handle(step(hand()))).error.code, "site_not_listed");
  assert.equal(r.state()[0].hand, null);
});

test("the person typing in the page doesn't pause the run while a step is handed to them, and does after", async () => {
  const { r, hand, tab } = await handing();
  await r.handle(step(hand()));
  r.ownerInput(tab());
  assert.ok((await r.handle(step({ id: "r1", op: "read" }))).page, "typing was what was asked");
  await r.answerHand("run_1", "done");
  r.ownerInput(tab());
  assert.equal((await r.handle(step({ id: "r2", op: "read" }))).error.code, "paused_by_owner", "a touch after the answer pauses as before");
});

test("a run with a step handed to the person isn't swept as idle, until the hand-over's 30 minutes are up", async () => {
  const { r, hand, tick } = await handing();
  await r.handle(step(hand()));
  tick(10 * 60_000);
  await r.sweep();
  assert.equal(r.state()[0].stopped, false, "ten minutes: the person is still at it");
  assert.notEqual(r.state()[0].hand, null);
  tick(21 * 60_000);
  await r.sweep();
  assert.equal(r.state()[0].hand, null, "past 30 minutes it is over");
  assert.equal(r.state()[0].stopped, true, "and the run is idle like any other");
});

test("Done reads the page back without what is in its fields; Not now says no; either answers once", async () => {
  const { r, answers, hand } = await handing();
  await r.handle(step(hand()));
  assert.deepEqual(await r.answerHand("run_1", "done"), { ok: true });
  assert.equal(answers.length, 1);
  assert.equal(answers[0].hand, "h1");
  assert.equal(answers[0].outcome, "done");
  const page = answers[0].page;
  assert.ok(page.fields.length === 3 && page.fields.every((f) => !("value" in f)), JSON.stringify(page.fields));
  assert.deepEqual(page.fields.map((f) => f.label), ["Card number", "Name", "Gift"], "the taf learns the fields are there");
  assert.equal(page.title, "Pay");
  assert.equal(r.state()[0].hand, null);
  assert.equal((await r.answerHand("run_1", "done")).error.code, "no_hand", "answered once");
  await r.handle(step(hand({ id: "h2" })));
  assert.deepEqual(await r.answerHand("run_1", "declined"), { ok: true });
  assert.deepEqual(answers[1], { hand: "h2", outcome: "declined", page: null });
  assert.equal((await r.answerHand("run_1", "maybe")).error.code, "no_hand");
  assert.equal((await r.answerHand("run_x", "done")).error.code, "no_hand");
});

test("a new hand-over ends the one still open as declined, and Stop ends it too", async () => {
  const { r, answers, hand } = await handing();
  await r.handle(step(hand()));
  await r.handle(step(hand({ id: "h2" })));
  assert.deepEqual(answers, [{ hand: "h1", outcome: "declined", page: null }], "one open per run");
  assert.equal(r.state()[0].hand.id, "h2");
  await r.stop("run_1");
  assert.deepEqual(answers.at(-1), { hand: "h2", outcome: "declined", page: null }, "planque is told, not left to its 30 minutes");
});

test("Show me brings the run's tab to the front and outlines the field the taf named, and clicks nothing", async () => {
  const { c, r, hand, tab } = await handing();
  const outlined = [];
  const run = c.scripting.executeScript;
  c.scripting.executeScript = async (o) => (String(o.func).includes("TafsPage.outline") ? (outlined.push(o.args), [{ result: { ok: true } }]) : run(o));
  c.windows.update = async (id, props) => c.calls.push(["windows.update", id, props]);
  await r.handle(step(hand()));
  assert.deepEqual(await r.showHand("run_1"), { ok: true });
  assert.ok(c.calls.some((x) => x[0] === "tabs.update" && x[1] === tab() && x[2].active === true), "the tab comes to the front");
  assert.deepEqual(outlined, [["f1", "rd_h"]], "only the field named, from the read the hand-over was planned from");
  assert.ok(!c.calls.some((x) => x[0] === "page" && x[1].startsWith("act")), "nothing was clicked or typed");
  assert.equal((await r.showHand("run_x")).error.code, "no_hand");
});

// The read after an act waits for the navigation the act starts (the owner's run 2: "The page still showed the start page afterwards").
async function actOn(c, extra = {}) {
  const waits = [];
  const r = new Runner({ chrome: c, sleep: (ms) => (ms >= 20_000 ? new Promise(() => {}) : (waits.push(ms), Promise.resolve())), paired: () => paired });
  await r.handle(step());
  const out = await r.handle(step({ op: "act", read_id: "rd_x", act: { kind: "click", ref: "c1" }, ...extra }));
  return { out, waits, runner: r };
}

test("a click that navigates is read after the new page loads, not from the old page still marked complete", async () => {
  const c = fakeChrome({ page: PLAIN });
  const base = c.tabs.get;
  let state = "before";
  let loadingPolls = 0;
  c.tabs.get = async (id) => {
    const t = await base(id);
    if (state === "before") return t;
    // The click has happened: a moment later the tab starts loading the results page, then finishes.
    if (loadingPolls < 3) {
      loadingPolls++;
      return loadingPolls === 1 ? t : { ...t, status: "loading", url: "https://shop.example/results" };
    }
    return { ...t, status: "complete", url: "https://shop.example/results" };
  };
  const inject = c.scripting.executeScript;
  c.scripting.executeScript = async (o) => {
    if (o.func && String(o.func).includes("TafsPage.act")) state = "after";
    return inject(o);
  };
  const { out } = await actOn(c);
  assert.ok(out.page, "the act answers a page");
  // The tab was asked again after the click until it had started loading and finished.
  assert.ok(loadingPolls >= 3, "the runner kept looking until the navigation had begun and ended; polls: " + loadingPolls);
});

test("a click that navigates nothing waits one second at most for a navigation and then reads", async () => {
  const c = fakeChrome({ page: PLAIN });
  const { out, waits } = await actOn(c);
  assert.ok(out.page, JSON.stringify(out));
  const looked = waits.filter((ms) => ms === 100).length;
  assert.equal(looked, 10, "ten looks of 100 ms, then it reads");
});

test("a run that ends by planque's close step or stop keeps its tabs; one that never loaded a page, and unpairing, close them", async () => {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
  const r = runner(c);
  await r.handle(step());
  const closed = await r.handle(step({ id: "op3", op: "close" }));
  assert.deepEqual(closed, { page: null });
  assert.ok(!c.calls.some((x) => x[0] === "tabs.remove" || x[0] === "windows.remove"), "the close step leaves the tabs");
  assert.equal(r.runs.get("run_1").tab, null, "no longer the run's");
  assert.equal(r.runs.get("run_1").tabs.size, 0);
  // A touch in a tab the run let go of is no run's business.
  const tab = [...c.tabsMap.keys()][0];
  r.ownerInput(tab);
  assert.equal(r.runs.get("run_1").paused, null);
});

test("a taf keeps only its latest finished run's tabs: its next run closes the earlier ones, another taf's stay, unpairing closes all", async () => {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
  const stored = {};
  const doneStore = { get: async () => JSON.parse(JSON.stringify(stored.done || {})), set: async (d) => (stored.done = JSON.parse(JSON.stringify(d))) };
  const r = new Runner({ chrome: c, sleep, paired: () => paired, doneStore });
  const removed = () => c.calls.filter((x) => x[0] === "tabs.remove").flatMap((x) => x[1]);
  await r.handle(step());
  const first = [...c.tabsMap.keys()].at(-1);
  await r.stop("run_1");
  assert.deepEqual(removed(), [], "the finished run's tabs stay");
  // Another taf's run: the first taf's tabs are not its to close.
  await r.handle(step({ run: "run_b", id: "opb", taf: "Sam" }));
  assert.deepEqual(removed(), [], "another taf's tabs stay");
  await r.stop("run_b");
  // The first taf runs again: its earlier tabs close as the new one opens, and nothing of Sam's.
  await r.handle(step({ run: "run_3", id: "op3" }));
  assert.deepEqual(removed(), [[first]].flat(), "only its own earlier run's tabs close");
  assert.equal(Object.keys(stored.done).length, 1, "only Sam's is kept now");
  // A new worker finds what was kept.
  const d = new Runner({ chrome: c, sleep, paired: () => paired, doneStore });
  await d.closeDone("Sam");
  assert.equal(removed().length, 2, "a restarted worker closes what the last one kept");
  r.done = null; // this runner read the store before the other one closed Sam's
  await r.stop("run_3");
  await r.stopAll();
  assert.equal(removed().length, 3, "unpairing closes the last taf's too");
  assert.deepEqual(stored.done, {});
});

test("a taf's next run leaves a kept tab the person has moved or is using, and closes one still where the run left it", async () => {
  for (const [what, mutate, closes] of [
    ["still where it was left", () => {}, true],
    ["moved to another page", (c, tab) => c.tabsMap.set(tab, "https://elsewhere.example/"), false],
    ["in front of the window in use", (c) => { c.front = true; }, false],
  ]) {
    const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
    c.front = false;
    const get = c.tabs.get;
    c.tabs.get = async (id) => ({ ...(await get(id)), windowId: 3, active: c.front });
    c.windows.get = async (id) => ({ id, focused: true });
    const r = runner(c);
    await r.handle(step());
    const tab = [...c.tabsMap.keys()][0];
    await r.stop("run_1");
    mutate(c, tab);
    await r.handle(step({ run: "run_2", id: "op2" }));
    const gone = c.calls.filter((x) => x[0] === "tabs.remove").flatMap((x) => x[1]).includes(tab);
    assert.equal(gone, closes, what);
    // A tab that stays leaves the taf's "· done" group, so the group of a run that's over goes; a tab that closes needs nothing.
    const ungrouped = c.calls.filter((x) => x[0] === "tabs.ungroup").flatMap((x) => x[1]).includes(tab);
    assert.equal(ungrouped, !closes, what + ": out of its done group");
    assert.deepEqual(await r.doneTabs(), {}, "the record goes either way: a tab the person has taken is not the taf's to track");
  }
});

test("a person watching each run of a taf doesn't collect one done group per run: each earlier run's tab leaves its group", async () => {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
  // The owner's hand-over test: the latest run's tab is always the one in front, in the window they use.
  const get = c.tabs.get;
  c.tabs.get = async (id) => ({ ...(await get(id)), windowId: 3, active: true });
  c.windows.get = async (id) => ({ id, focused: true });
  const r = runner(c);
  const tabs = [];
  for (const n of [1, 2, 3]) {
    await r.handle(step({ run: "run_" + n, id: "op" + n }));
    tabs.push([...c.tabsMap.keys()].at(-1));
    await r.stop("run_" + n);
  }
  assert.deepEqual(c.calls.filter((x) => x[0] === "tabs.remove").flatMap((x) => x[1]), [], "nothing the person is looking at closes");
  assert.deepEqual(c.calls.filter((x) => x[0] === "tabs.ungroup").flatMap((x) => x[1]), tabs.slice(0, 2), "the two earlier runs' tabs leave their groups");
  assert.deepEqual(Object.keys(await r.doneTabs()), ["Rae"], "only the latest run is kept as the taf's");
});

test("unpairing closes a kept tab the person has moved too", async () => {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
  const r = runner(c);
  await r.handle(step());
  const tab = [...c.tabsMap.keys()][0];
  await r.stop("run_1");
  c.tabsMap.set(tab, "https://elsewhere.example/");
  await r.stopAll();
  assert.ok(c.calls.filter((x) => x[0] === "tabs.remove").flatMap((x) => x[1]).includes(tab));
});
