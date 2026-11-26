import { test } from "node:test";
import { readFileSync } from "node:fs";
import assert from "node:assert/strict";
import { Runner, RUN_IDLE_MS, PAUSED_IDLE_MS, OUTLINE_MS, TURN_QUIET_MS, FENCE_RULES } from "../src/runner.js";
import { SENSITIVE, SENSITIVE_LABELS_URLS, NEVER_OPENED, LOCAL_SUFFIXES, refuseURL, fenceDomains } from "../src/sites.js";
import { LABEL, sign, newKeyPair, publicSPKI, b64u } from "../src/crypto.js";
import { Panels, joinFront, MARK_MS } from "../src/panels.js";

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
  const groups = new Map(); // tab id → its group, as Chrome's tabs.get says it
  let nextTab = 100;
  const pageFor = (url) => page || { read_id: "rd_" + calls.length, url, title: "t", text: "", fields: [{ ref: "f1", label: "Email", kind: "email" }], controls: [{ ref: "c1", label: "Save" }, { ref: "c2", label: "Pay now" }], links: [] };
  let actAnswer = { ok: true };
  const c = {
    calls,
    listeners,
    tabsMap: tabs,
    groupsMap: groups,
    setAct: (a) => (actAnswer = a),
    setNextTab: (n) => (nextTab = n),
    webNavigation: { onBeforeNavigate: on("before"), onCommitted: on("committed"), onCreatedNavigationTarget: on("created"), onErrorOccurred: on("error") },
    tabs: {
      onRemoved: on("removed"),
      query: async () => {
        calls.push(["tabs.query"]);
        return [];
      },
      get: async (id) => ({ id, status: "complete", url: tabs.get(id), groupId: groups.get(id) ?? -1 }),
      update: async (id, props) => {
        calls.push(["tabs.update", id, props]);
        if (props.url) tabs.set(id, props.url);
      },
      group: async (o) => {
        calls.push(["tabs.group", o]);
        if (groupFails) throw new Error("No group");
        for (const t of o.tabIds || []) groups.set(t, o.groupId ?? 7);
        return o.groupId ?? 7;
      },
      remove: async (id) => calls.push(["tabs.remove", id]),
      goBack: async (id) => calls.push(["tabs.goBack", id]),
      goForward: async (id) => calls.push(["tabs.goForward", id]),
      reload: async (id) => calls.push(["tabs.reload", id]),
      ungroup: async (ids) => {
        calls.push(["tabs.ungroup", ids]);
        for (const t of [].concat(ids)) groups.delete(t);
      },
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
      update: async (id, o) => calls.push(["windows.update", id, o]),
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
        const src = String(o.func);
        // The signs (signs.js) are drawn on their own: "signs", so a test that counts what ran in the page can leave them out.
        const signs = (o.files && o.files.join(",") === "src/signs.js") || (!o.files && src.includes("TafsSigns") && !src.includes("TafsPage"));
        calls.push(["executeScript", signs ? "signs" : o.files ? o.files.join(",") : o.world || "isolated", o.target.tabId]);
        if (o.files || o.world === "MAIN") return [{}];
        if (src.includes("TafsPage.read")) {
          calls.push(["page", "read"]);
          return [{ result: pageFor(tabs.get(o.target.tabId)) }];
        }
        // A page with the signs loaded points where it's asked (page.js `aim`); a test that wants an aim to fail says so itself.
        if (src.includes("TafsPage.aim")) return [{ result: { ok: true } }];
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
// What ran in a page, the signs (signs.js) left out: they're drawn, never read or acted.
const ranInPage = (x) => x[0] === "executeScript" && x[1] !== "signs";

function step(extra) {
  return { id: "op", run: "run_1", taf: "Rae", grant: "g_1", sites: ["shop.example"], sites_signed: SHOP, op: "open", url: "https://shop.example/", ...extra };
}

const runner = (c) => new Runner({ chrome: c, sleep, paired: () => paired });

/** A tab's fence rule ids, from the first one Chrome was given: FENCE_RULES of them, one per place in `Runner.fence`. */
const fenceIdsFrom = (ids) => Array.from({ length: FENCE_RULES }, (_, i) => Math.min(...ids) + i);

/** The fence Chrome is given for a tab, as [action, requestDomains or regexFilter or null], by priority then id. */
const fenceShape = (rules) => rules.slice().sort((a, b) => a.priority - b.priority || a.id - b.id).map((x) => [x.priority, x.action.type, x.condition.requestDomains || x.condition.regexFilter || null]);
const FENCE = (granted, allowed = []) => [
  [1, "block", null],
  ...(granted.length ? [[2, "allow", granted]] : []),
  [3, "block", [...SENSITIVE.sites]],
  ...SENSITIVE_LABELS_URLS.map((p) => [3, "block", p]),
  ...(allowed.length ? [[4, "allow", allowed]] : []),
  [5, "block", [...NEVER_OPENED]],
  [5, "block", "^http://"],
  [5, "block", "^https?://[^/?#]*@"],
];

test("a run opens in a background window of its own, grouped and named, and reads the page", async () => {
  const c = fakeChrome();
  const r = runner(c);
  const out = await r.handle(step());
  assert.equal(out.page.url, "https://shop.example/");
  const win = c.calls.find((x) => x[0] === "windows.create");
  assert.equal(win[1].focused, false, "never in front of the owner's window");
  assert.ok(c.calls.some((x) => x[0] === "tabGroups.update" && x[2].title === "tafs · Rae"));
  assert.ok(c.calls.some((x) => ranInPage(x) && x[1] === "MAIN"), "the dialog guard goes in");
  assert.ok(c.calls.some((x) => ranInPage(x) && x[1] === "src/mask.js,src/signs.js,src/page.js"));
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
  // The run's own act heads off its sites (the person's own address is theirs: the fence's stop gives them the tab, below).
  r.runs.get("run_1").stepping = tab;
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
  const before = c.calls.filter((x) => ranInPage(x)).length;
  assert.equal((await r.handle(step({ op: "act", read_id: opened.page.read_id, act: { kind: "click", ref: "c2" } }))).error.code, "payment_step");
  assert.equal(c.calls.filter((x) => ranInPage(x)).length, before, "nothing ran in the page");
  assert.equal((await r.handle(step({ op: "act", read_id: opened.page.read_id, act: { kind: "fill", ref: "f1", sealed: "sec_1" } }))).error.code, "secret_field", "the extension keeps no saved sign-in to fill");
});

/** The page [url] committed in tab [tab], as Chrome says it once a navigation lands. */
const commit = (c, tab, url) => {
  c.tabsMap.set(tab, url);
  c.listeners.committed({ tabId: tab, frameId: 0, url });
};

/** A page the run's own act took tab [tab] to: committed while the act waits for it (`stepping`), as Chrome says it then. */
const runCommit = (r, c, tab, url, run = "run_1") => {
  const held = r.runs.get(run);
  held.stepping = tab;
  commit(c, tab, url);
  held.stepping = null;
};

test("back goes to the page before the run's last in its own tab and reads it there; nothing runs in the page before it", async () => {
  const c = fakeChrome();
  const r = runner(c);
  await r.handle(step());
  const tab = [...c.tabsMap.keys()][0];
  runCommit(r, c, tab, "https://shop.example/");
  // The taf's own click took the tab to a second page.
  runCommit(r, c, tab, "https://shop.example/p2");
  const opened = await r.handle(step({ id: "op2", op: "read" }));
  // Chrome's back lands where it should.
  c.tabs.goBack = async (id) => {
    c.calls.push(["tabs.goBack", id]);
    c.tabsMap.set(id, "https://shop.example/");
  };
  const scripts = c.calls.filter((x) => ranInPage(x)).length;
  const out = await r.handle(step({ id: "op3", op: "act", read_id: opened.page.read_id, act: { kind: "back" } }));
  assert.ok(out.page, JSON.stringify(out));
  assert.equal(out.page.url, "https://shop.example/");
  assert.deepEqual(c.calls.filter((x) => x[0] === "tabs.goBack"), [["tabs.goBack", tab]]);
  assert.ok(!c.calls.some((x) => x[0] === "tabs.update" && x[2].url === "https://shop.example/" && c.calls.indexOf(x) > c.calls.findIndex((y) => y[0] === "tabs.goBack")), "nothing loaded: Chrome's back was right");
  const ran = c.calls.slice(c.calls.findIndex((x) => x[0] === "tabs.goBack")).filter((x) => ranInPage(x)).length;
  assert.equal(c.calls.filter((x) => ranInPage(x)).length - scripts, ran, "only the read after, no act in the page");
  assert.equal((await r.handle(step({ id: "op4", op: "act", read_id: out.page.read_id, act: { kind: "back", ref: "c1" } }))).error.code, "bad_step");
  // Back at the run's first page there: nothing before it is the run's.
  assert.equal((await r.handle(step({ id: "op5", op: "act", read_id: out.page.read_id, act: { kind: "back" } }))).error.code, "no_back");
});

// The harness's finding: after the taf's own click, Chrome's back does nothing (no person's gesture made that entry) and the step
// still answered ok.
test("in a run's own tab, a back Chrome skips loads the page before the taf's click instead; with none before, no_back", async () => {
  const c = fakeChrome();
  const r = runner(c);
  const first = await r.handle(step());
  const tab = [...c.tabsMap.keys()][0];
  assert.equal((await r.handle(step({ id: "op1", op: "act", read_id: first.page.read_id, act: { kind: "back" } }))).error.code, "no_back", "nothing before the run's first page");
  assert.ok(!c.calls.some((x) => x[0] === "tabs.goBack"), "nothing moved");
  runCommit(r, c, tab, "https://shop.example/");
  runCommit(r, c, tab, "https://shop.example/p2");
  const read = await r.handle(step({ id: "op2", op: "read" }));
  // Chrome's back goes nowhere: the tab still shows p2.
  const before = c.calls.length;
  const out = await r.handle(step({ id: "op3", op: "act", read_id: read.page.read_id, act: { kind: "back" } }));
  assert.ok(out.page, JSON.stringify(out));
  assert.equal(out.page.url, "https://shop.example/", "the page before the click");
  const now = c.calls.slice(before);
  const back = now.findIndex((x) => x[0] === "tabs.goBack");
  const load = now.findIndex((x) => x[0] === "tabs.update" && x[1] === tab && x[2].url === "https://shop.example/");
  assert.ok(back >= 0 && load > back, "Chrome's back first, then the page loaded");
  // Its own commit of the page it went back to isn't a new page of the run's: one more back has nothing before it.
  runCommit(r, c, tab, "https://shop.example/");
  assert.equal((await r.handle(step({ id: "op4", op: "act", read_id: out.page.read_id, act: { kind: "back" } }))).error.code, "no_back");
});


test("the panel's head goes to a live run's tab and focuses its window; nothing else, and no run it doesn't hold", async () => {
  const c = fakeChrome();
  const r = runner(c);
  await r.handle(step());
  const tab = [...c.tabsMap.keys()][0];
  const before = c.calls.length;
  assert.deepEqual(await r.goTo("run_1"), {});
  // The signs a run's own sync draws (signs.js) aren't the head's doing.
  const made = c.calls.slice(before).filter((x) => !(x[0] === "executeScript" && x[1] === "signs"));
  assert.deepEqual(made.find((x) => x[0] === "tabs.update"), ["tabs.update", tab, { active: true }]);
  assert.ok(made.every((x) => x[0] === "tabs.update" || x[0] === "windows.update"), "no read, no act");
  assert.deepEqual(await r.goTo("run_9"), { error: "no_run" });
  await r.stop("run_1");
  assert.deepEqual(await r.goTo("run_1"), { error: "no_run" }, "a stopped run");
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
  assert.deepEqual(fenceShape(rules.addRules), FENCE(["shop.example"]));
  for (const x of rules.addRules) {
    assert.deepEqual(x.condition.tabIds, [tab]);
    assert.deepEqual(x.condition.resourceTypes, ["main_frame", "sub_frame"]);
  }
  const fenced = c.calls.findIndex((x) => x[0] === "dnr");
  const loaded = c.calls.findIndex((x) => x[0] === "tabs.update" && x[2].url === "https://shop.example/");
  assert.ok(fenced < loaded, "fenced before the run's page loads");
  await r.stop("run_1");
  assert.deepEqual(c.calls.filter((x) => x[0] === "dnr").at(-1)[1].removeRuleIds, fenceIdsFrom(rules.addRules.map((x) => x.id)));
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

test("a page off the run's sites that the run's own work commits in its own tab is sent to a blank page and recorded", async () => {
  const c = fakeChrome();
  const r = runner(c);
  await r.handle(step());
  const tab = [...c.tabsMap.keys()][0];
  r.runs.get("run_1").stepping = tab;
  c.listeners.committed({ tabId: tab, frameId: 0, url: "https://evil.example/restored" });
  assert.ok(c.calls.some((x) => x[0] === "tabs.update" && x[1] === tab && x[2].url === "about:blank"));
  assert.equal(r.state()[0].steps.at(-1).error, "site_not_listed");
  c.listeners.committed({ tabId: tab, frameId: 1, url: "https://evil.example/frame" });
  assert.equal(c.calls.filter((x) => x[0] === "tabs.update" && x[2].url === "about:blank").length, 1, "only the main frame");
});

// The person's own Back in the run's own tab restores a page off its sites from Chrome's cache: it's their move, so the tab is theirs,
// as it is, with nothing sent to a blank page.
test("a page Chrome restores from its cache off the run's sites on the person's Back gives the run's own tab to them, unmoved", async () => {
  const c = fakeChrome();
  const r = runner(c);
  await r.handle(step());
  const tab = [...c.tabsMap.keys()][0];
  const before = c.calls.length;
  c.listeners.committed({ tabId: tab, frameId: 0, url: "https://evil.example/restored", transitionType: "link", transitionQualifiers: ["forward_back"] });
  await new Promise((done) => setTimeout(done, 0));
  assert.ok(!c.calls.slice(before).some((x) => x[0] === "tabs.update" && x[2].url), "nothing loaded in their tab");
  assert.equal(r.tabRun.has(tab), false, "the run forgets it");
  assert.ok(!r.state()[0].steps.some((x) => x.op === "navigate"), "the person's move is none of the run's steps");
  assert.equal(r.state()[0].site, "shop.example", "its site is still the run's page's");
  assert.equal((await r.handle(step({ id: "op2", op: "read" }))).error.code, "given_back");
});

test("the run's own blank page is never refused: no loop, and the page the step opens loads", async () => {
  const c = fakeChrome();
  const r = runner(c);
  await r.handle(step());
  const tab = [...c.tabsMap.keys()][0];
  r.runs.get("run_1").stepping = tab; // the run's own work, as an act waiting for its page
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
  const before = c.calls.filter((x) => ranInPage(x)).length;
  assert.equal((await r.handle(step({ op: "act", read_id: "rd_x", act: { kind: "click", ref: "c1" } }))).error.code, "payment_step", "even a control that doesn't pay");
  assert.equal(c.calls.filter((x) => ranInPage(x)).length, before, "nothing ran in the page");
  // A dialog the page raised: the next act is refused by the page, the code goes up as it is, and it counts as a failure.
  const d = fakeChrome({ page: { read_id: "rd_y", url: "https://shop.example/", title: "t", text: "", fields: [], controls: [{ ref: "c1", label: "Save" }], links: [], dialog: { kind: "confirm", text: "Delete everything?" } } });
  const r2 = runner(d);
  const read = await r2.handle(step());
  assert.deepEqual(read.page.dialog, { kind: "confirm", text: "Delete everything?" }, "the read reports the dialog");
  d.setAct({ ok: false, code: "dialog_open" });
  assert.equal((await r2.handle(step({ op: "act", read_id: "rd_y", act: { kind: "click", ref: "c1" } }))).error.code, "dialog_open");
  assert.equal(r2.runs.get("run_1").failures, 1);
});

test("a run nobody sends a step to for 150 s ends, as Stop does (its tabs stay); a paused one waits longer, and one with a step running isn't swept", async () => {
  assert.equal(RUN_IDLE_MS, 150_000, "a little over planque's 2 minutes between a turn's calls");
  let clock = 1_000_000;
  const c = fakeChrome();
  const r = new Runner({ chrome: c, sleep, paired: () => paired, now: () => clock });
  await r.handle(step());
  clock += 2 * 60_000;
  await r.sweep();
  assert.equal(r.runs.get("run_1").stopped, false, "two minutes, planque's wait for the turn's next call, is not idle");
  assert.ok((await r.handle(step({ op: "read" }))).page, "a step starts the count again");
  clock += 2 * 60_000;
  await r.sweep();
  assert.equal(r.runs.get("run_1").stopped, false);
  clock += 31_000;
  await r.sweep();
  assert.equal(r.runs.get("run_1").stopped, true);
  assert.ok(!c.calls.some((x) => x[0] === "windows.remove" || x[0] === "tabs.remove"), "its tabs stay for the person");
  assert.equal(c.calls.filter((x) => x[0] === "tabGroups.update").pop()[2].title, "tafs · Rae · done");
  assert.equal(r.state()[0].steps.at(-1).by, "idle");
  assert.equal(r.active, false);
  // The owner's pause waits for the owner, past the 150 s, while a taf holds its step for the Resume.
  const d = fakeChrome();
  const r2 = new Runner({ chrome: d, sleep, paired: () => paired, now: () => clock });
  await r2.handle(step());
  r2.pause("run_1");
  clock += 5 * 60_000;
  await r2.sweep();
  assert.equal(r2.runs.get("run_1").stopped, false, "five minutes paused: still waiting for Resume");
  assert.ok(PAUSED_IDLE_MS > 10 * 60_000, "longer than a taf holds a step for the Resume");
});

test("a paused run a taf still holds is kept; one nothing will resume ends after 15 minutes, and the bar goes", async () => {
  assert.equal(PAUSED_IDLE_MS, 15 * 60_000);
  let clock = 1_000_000;
  const c = fakeChrome();
  const r = new Runner({ chrome: c, sleep, paired: () => paired, now: () => clock });
  await r.handle(step());
  r.pause("run_1");
  // The taf holds its step, sending it again every 10 s: each is answered paused, and each keeps the run.
  for (let i = 0; i < 6 * 20; i++) {
    clock += 10_000;
    assert.equal((await r.handle(step({ id: "h" + i, op: "read" }))).error.code, "paused_by_owner");
    await r.sweep();
  }
  assert.equal(r.runs.get("run_1").stopped, false, "twenty minutes held: never ended under the taf");
  // Resume: the run goes on, and the 150 s count starts at the Resume.
  r.resume("run_1");
  clock += 2 * 60_000;
  await r.sweep();
  assert.equal(r.runs.get("run_1").stopped, false);
  assert.ok((await r.handle(step({ id: "after", op: "read" }))).page, "it goes on after Resume");
  // A pause no step follows (the taf's turn ended on it): kept for 15 minutes from the pause, then ended as Stop is.
  r.pause("run_1");
  clock += PAUSED_IDLE_MS - 1_000;
  await r.sweep();
  assert.equal(r.runs.get("run_1").stopped, false, "just under 15 minutes from the pause");
  clock += 2_000;
  await r.sweep();
  assert.equal(r.runs.get("run_1").stopped, true, "past 15 minutes: nothing will resume it");
  assert.equal(r.state()[0].steps.at(-1).by, "idle");
  assert.equal(r.active, false, "so the bar, the badge and the group stop saying it works");
  assert.ok(!c.calls.some((x) => x[0] === "windows.remove" || x[0] === "tabs.remove"), "its tabs stay for the person");
  // A run paused by its own failures, which no step follows either, ends the same way.
  const d = fakeChrome();
  const r2 = new Runner({ chrome: d, sleep, paired: () => paired, now: () => clock });
  await r2.handle(step());
  await new Promise((done) => setTimeout(done, 0)); // the step's tail is let go once it's answered
  r2.runs.get("run_1").paused = "failures";
  clock += PAUSED_IDLE_MS + 1_000;
  await r2.sweep();
  assert.equal(r2.runs.get("run_1").stopped, true);
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
  assert.equal(added.length, 2 * (FENCE_RULES - 1), "a tab's rules but the allowance's, with no sensitive site allowed (FENCE_RULES ids kept for each)");
  assert.ok(added.every((id) => Number.isInteger(id) && id >= 1 && id <= 2 ** 31 - 1), "every rule id is a valid int32");
  assert.equal(new Set(added).size, 2 * (FENCE_RULES - 1), "no id repeated across two tabs");
  const removals = c.calls.filter((x) => x[0] === "dnr" && !x[1].addRules);
  assert.deepEqual(removals[0][1].removeRuleIds, [77, 78], "rules an earlier service worker left are cleared first");
  await r.stop("run_1");
  assert.deepEqual(c.calls.filter((x) => x[0] === "dnr").at(-1)[1].removeRuleIds, fenceIdsFrom(added.slice(0, FENCE_RULES - 1)), "its own rules, not the other run's");
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
  assert.deepEqual(await r.showHand("run_1"), { ok: true, shown: "field" });
  assert.ok(c.calls.some((x) => x[0] === "tabs.update" && x[1] === tab() && x[2].active === true), "the tab comes to the front");
  assert.deepEqual(outlined, [["f1", "rd_h"]], "only the field named, from the read the hand-over was planned from");
  assert.ok(!c.calls.some((x) => x[0] === "page" && x[1].startsWith("act")), "nothing was clicked or typed");
  assert.equal((await r.showHand("run_x")).error.code, "no_hand");
});

// Show me pressed with nothing drawn read as "it does nothing": it says what it did instead, and the panel says why.
test("Show me says when it couldn't point to the field: the page changed, the field isn't on screen, the page didn't answer, or none was named", async () => {
  for (const [answer, why] of [[{ ok: false, code: "page_changed" }, "page_changed"], [{ ok: false, code: "ref_unknown" }, "ref_unknown"],
    [null, "page_unresponsive"], [{ ok: false, code: "something_else" }, "page_unresponsive"], ["throws", "page_unresponsive"]]) {
    const { c, r, hand, tab } = await handing();
    const run = c.scripting.executeScript;
    c.scripting.executeScript = async (o) => {
      if (!String(o.func).includes("TafsPage.outline")) return run(o);
      if (answer === "throws") throw new Error("Cannot access contents of the page");
      return [{ result: answer }];
    };
    await r.handle(step(hand()));
    assert.deepEqual(await r.showHand("run_1"), { ok: true, shown: "tab", why }, JSON.stringify(answer));
    assert.ok(c.calls.some((x) => x[0] === "tabs.update" && x[1] === tab() && x[2].active === true), "the tab still comes to the front");
  }
  const { c, r, hand } = await handing();
  const outlined = [];
  const run = c.scripting.executeScript;
  c.scripting.executeScript = async (o) => (String(o.func).includes("TafsPage.outline") ? (outlined.push(o.args), [{ result: { ok: true } }]) : run(o));
  await r.handle(step(hand({ hand: { words: "Type your search, then tap Done" } })));
  assert.deepEqual(await r.showHand("run_1"), { ok: true, shown: "tab", why: "no_ref" }, "no field named: the tab, and it says so");
  assert.deepEqual(outlined, [], "nothing to outline");
});

test("Done is recorded as done, Not now as declined, so the step list says each once", async () => {
  const { r, hand } = await handing();
  await r.handle(step(hand()));
  await r.answerHand("run_1", "done");
  await r.handle(step(hand({ id: "h2" })));
  await r.answerHand("run_1", "declined");
  assert.deepEqual(r.state()[0].steps.filter((s) => s.op === "hand").map((s) => s.kind ?? null), [null, "done", null, "declined"]);
});

// A taf works in a few calls, each its own run: the group stays amber between them, and says done only when the taf has stopped.
test("a finished run's group turns grey only after a grace, and the taf's next run in that time keeps it from ever turning grey", async () => {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
  const graces = [];
  const r = new Runner({ chrome: c, sleep: (ms) => (ms === 15_000 ? new Promise((go) => graces.push(go)) : Promise.resolve()), paired: () => paired });
  const greyed = () => c.calls.filter((x) => x[0] === "tabGroups.update" && x[2].color === "grey");
  await r.handle(step());
  await r.stop("run_1");
  assert.equal(graces.length, 1, "the grace started");
  assert.deepEqual(greyed(), [], "not grey at once");
  await r.handle(step({ run: "run_2" }));
  graces[0]();
  await new Promise((go) => setImmediate(go));
  assert.deepEqual(greyed(), [], "the taf's next run started within the grace: never grey");
  assert.equal(c.calls.filter((x) => x[0] === "tabGroups.update").pop()[2].color, "orange", "the taf's group is amber");
  await r.stop("run_2");
  graces[1]();
  await new Promise((go) => setImmediate(go));
  assert.equal(greyed().length, 1, "the taf stopped: its group says done");
  assert.equal(greyed()[0][2].title, "tafs · Rae · done");
});

test("after the worker restarts, a finished run's group that never turned grey says done", async () => {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
  const stored = { done: { Rae: { tabs: [{ id: 41, url: "https://shop.example/" }], window: null, group: 7 } } };
  const doneStore = { get: async () => JSON.parse(JSON.stringify(stored.done)), set: async (d) => (stored.done = JSON.parse(JSON.stringify(d))) };
  const r = new Runner({ chrome: c, sleep, paired: () => paired, doneStore });
  await r.doneTabs();
  assert.deepEqual(c.calls.filter((x) => x[0] === "tabGroups.update").map((x) => [x[1], x[2]]), [[7, { title: "tafs · Rae · done", color: "grey" }]]);
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
  await r.handle(step({ run: "run_b", id: "opb", taf: "Sam", grant: "g_2", sites_signed: await signedSites(["shop.example"], "g_2") }));
  assert.deepEqual(removed(), [], "another taf's tabs stay");
  await r.stop("run_b");
  // The first taf runs again: its earlier tabs close as the new one opens, and nothing of Sam's.
  await r.handle(step({ run: "run_3", id: "op3" }));
  assert.deepEqual(removed(), [[first]].flat(), "only its own earlier run's tabs close");
  assert.equal(Object.keys(stored.done).length, 1, "only Sam's is kept now");
  // A new worker finds what was kept.
  const d = new Runner({ chrome: c, sleep, paired: () => paired, doneStore });
  await d.closeDone("grant:g_2");
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
    ["in use, in a group of the person's own", (c, tab) => { c.front = true; c.groupsMap.set(tab, 99); }, false],
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
    // A tab that stays leaves the run's own "· done" group, so the group of a run that's over goes; never a group the person
    // put it in. A tab that closes needs nothing.
    const theirs = what.includes("of the person's own");
    const ungrouped = c.calls.filter((x) => x[0] === "tabs.ungroup").flatMap((x) => x[1]).includes(tab);
    assert.equal(ungrouped, !closes && !theirs, what + ": out of the run's done group only");
    if (theirs) assert.equal(c.groupsMap.get(tab), 99, "the person's own group keeps it");
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
  assert.deepEqual(Object.keys(await r.doneTabs()), ["grant:g_1"], "only the latest run is kept as the taf's, by its grant");
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

// The floating bar: the tab in front of each window, while a run lives (docs/chrome.md, "While a taf works").
test("while a run lives, the tab in front of each window shows the bar, for its own run or the first; the run's end takes them all away", async () => {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
  const bars = [];
  const exec = c.scripting.executeScript;
  c.scripting.executeScript = async (o) => {
    const src = String(o.func || "");
    if (o.files && o.files.includes("src/bar.js")) bars.push(["inject", o.target.tabId]);
    if (src.includes("TafsBar.show")) bars.push(["show", o.target.tabId, o.args[0]]);
    if (src.includes("TafsBar.hide")) bars.push(["hide", o.target.tabId]);
    return exec(o);
  };
  const r = new Runner({ chrome: c, sleep: (ms) => (ms >= 20_000 ? new Promise(() => {}) : Promise.resolve()), paired: () => paired, say: (key, sub) => (key === "working" ? sub[0] + " is working" : key === "working_here" ? sub[0] + " is working here" : key === "bar_paused" ? sub[0] + " is paused" : "") });
  await r.handle(step());
  const runTab = [...c.tabsMap.keys()][0];
  // Three windows: the run's (its tab in front), the person's on a page of their own, and one on Chrome's own page.
  c.windows.getAll = async () => [{ id: 1 }, { id: 2 }, { id: 3 }];
  c.tabs.query = async ({ windowId }) => [windowId === 1 ? { id: runTab, url: "https://shop.example/" } : windowId === 2 ? { id: 501, url: "https://news.example/today" } : { id: 502, url: "chrome://settings" }];
  bars.length = 0;
  await r.queueBars();
  const shown = bars.filter((b) => b[0] === "show");
  assert.deepEqual(shown.map((b) => [b[1], b[2].run, b[2].here, b[2].words]), [[runTab, "run_1", true, "Rae is working here"], [501, "run_1", false, "Rae is working"]], "where it works, it says so");
  assert.ok(!bars.some((b) => b[1] === 502), "Chrome's own pages are left alone");
  assert.deepEqual(shown[0][2].labels, { go: "Go to its tab", pause: "Pause", resume: "Resume", stop: "Stop" });
  // Paused: the bar says so, and offers Resume.
  bars.length = 0;
  r.pause("run_1");
  await r.queueBars();
  assert.ok(bars.filter((b) => b[0] === "show").every((b) => b[2].paused && b[2].words === "Rae is paused"));
  // Stopped: every tab that showed it is cleared.
  bars.length = 0;
  await r.stop("run_1");
  await r.queueBars();
  assert.deepEqual([...new Set(bars.filter((b) => b[0] === "hide").map((b) => b[1]))].sort(), [501, runTab].sort(), "both tabs that showed it");
  assert.ok(!bars.some((b) => b[0] === "show"));
});

test("a press on the bar acts only on a run this Chrome holds, as the panel's buttons do", async () => {
  const c = fakeChrome();
  const r = runner(c);
  await r.handle(step());
  assert.deepEqual(await r.barPress("run_9", "stop"), { error: "no_run" });
  assert.deepEqual(await r.barPress("run_1", "explode"), { error: "bad_act" });
  await r.barPress("run_1", "pause", 501);
  assert.equal(r.runs.get("run_1").paused, "owner");
  assert.deepEqual(await r.barPress("run_1", "resume", 501), { error: "not_here" }, "Resume gives power back: never from another page's bar");
  assert.equal(r.runs.get("run_1").paused, "owner");
  await r.barPress("run_1", "resume", [...c.tabsMap.keys()][0]);
  assert.equal(r.runs.get("run_1").paused, null);
  await r.barPress("run_1", "stop");
  assert.equal(r.runs.get("run_1").stopped, true);
  assert.deepEqual(r.state()[0].steps.filter((s) => s.op === "stop").map((s) => s.by), ["person"]);
  assert.deepEqual(await r.barPress("run_1", "go"), { error: "no_run" }, "a stopped run is no one's to go to");
});

test("Stop on the bar ends the run at once, for that tab's run only; a hand-over open is told Not now", async () => {
  const { c, r, answers, hand, tab } = await handing();
  await r.handle(step(hand()));
  await r.stopByPerson(999); // no run's tab
  assert.equal(r.runs.get("run_1").stopped, false);
  await r.stopByPerson(tab());
  assert.equal(r.runs.get("run_1").stopped, true);
  assert.deepEqual(r.state()[0].steps.filter((s) => s.op === "stop"), [{ at: r.state()[0].steps.find((s) => s.op === "stop").at, op: "stop", by: "person" }]);
  assert.deepEqual(answers.at(-1), { hand: "h1", outcome: "declined", page: null });
  assert.equal((await r.handle(step({ op: "read" }))).error.code, "stopped", "planque's next step is told the person stopped it");
  assert.ok(!c.calls.some((x) => x[0] === "tabs.remove"), "its tabs stay, as with Stop");
});

// The signals that a taf is at work: the group says so for the whole run, and each act shows where it lands first.

test("the run's group says its taf is working, in the extension's words, until the run ends", async () => {
  const c = fakeChrome();
  const r = new Runner({ chrome: c, sleep, paired: () => paired, say: (key, sub) => (key === "working" ? sub[0] + " is working" : "") });
  await r.handle(step());
  assert.ok(c.calls.some((x) => x[0] === "tabGroups.update" && x[2].title === "tafs · Rae is working" && x[2].color === "orange"));
});

test("an act on a ref is outlined first, after its picture, for a moment; back and a scroll of the page outline nothing", async () => {
  const c = fakeChrome({ page: { read_id: "rd_x", url: "https://shop.example/", fields: [], controls: [{ ref: "c1", label: "Save" }], links: [] } });
  const order = [];
  const run = c.scripting.executeScript;
  c.scripting.executeScript = async (o) => {
    const src = String(o.func || "");
    if (src.includes("TafsPage.aim")) {
      order.push(["outline", ...o.args]);
      return [{ result: { ok: true } }];
    }
    if (src.includes("TafsPage.act")) order.push(["act", o.args[0].kind]);
    return run(o);
  };
  const waited = [];
  const shots = { capture: async () => (order.push(["picture"]), null), send: async () => {} };
  const r = new Runner({ chrome: c, sleep: (ms) => (ms >= 20_000 ? new Promise(() => {}) : (waited.push(ms), Promise.resolve())), paired: () => paired, shots });
  const opened = await r.handle(step());
  assert.ok(opened.page, JSON.stringify(opened));
  await r.handle(step({ op: "act", read_id: opened.page.read_id, act: { kind: "click", ref: "c1" } }));
  assert.deepEqual(order, [["picture"], ["outline", "c1", opened.page.read_id, ""], ["act", "click"]], "the pointer goes to it, outlined, after the picture");
  assert.ok(waited.includes(OUTLINE_MS), "a moment to see it");
  order.length = 0;
  const now = r.runs.get("run_1").pages.keys();
  let last;
  for (const id of now) last = id;
  await r.handle(step({ op: "act", read_id: last, act: { kind: "scroll" } }));
  assert.ok(!order.some((x) => x[0] === "outline"), "a scroll of the page names no ref");
});

test("a Pause, the person's own input or a Stop while the outline shows holds the act: the page is never touched", async () => {
  for (const [what, code] of [["pause", "paused_by_owner"], ["input", "paused_by_owner"], ["stop", "stopped"]]) {
    const c = fakeChrome({ page: { read_id: "rd_x", url: "https://shop.example/", fields: [], controls: [{ ref: "c1", label: "Save" }], links: [] } });
    const order = [];
    const exec = c.scripting.executeScript;
    c.scripting.executeScript = async (o) => {
      const src = String(o.func || "");
      if (src.includes("TafsPage.aim")) return [{ result: { ok: true } }];
      if (src.includes("TafsPage.unoutline")) order.push("unoutline");
      if (src.includes("TafsSigns.off")) order.push("signs off");
      if (src.includes("TafsPage.act")) order.push("act");
      return exec(o);
    };
    let r;
    const during = async () => {
      if (what === "pause") r.pause("run_1");
      if (what === "input") r.ownerInput([...c.tabsMap.keys()][0]);
      if (what === "stop") await r.stop("run_1");
    };
    r = new Runner({ chrome: c, paired: () => paired, sleep: (ms) => (ms >= 20_000 ? new Promise(() => {}) : ms === OUTLINE_MS ? during() : Promise.resolve()) });
    await r.handle(step());
    const out = await r.handle(step({ op: "act", read_id: "rd_x", act: { kind: "click", ref: "c1" } }));
    assert.equal(out.error && out.error.code, code, what);
    assert.ok(!order.includes("act"), what + ": the page's act is never called");
    if (what !== "stop") assert.ok(order.includes("unoutline"), what + ": the outline is taken away");
    assert.ok(order.includes("signs off"), what + ": the glow and the pointer go with it");
  }
});

test("a worker that starts again sweeps the bar and signs only from tabs drawn in before, never a tab no run touched", async () => {
  // A first worker draws in its run's tab; the list of drawn tabs is kept (storage.session in the extension).
  let kept = [];
  const drawnStore = { get: async () => kept, set: async (ids) => { kept = ids; } };
  const c1 = fakeChrome();
  const r1 = new Runner({ chrome: c1, sleep, paired: () => paired, drawnStore });
  await r1.handle(step());
  const runTab = r1.runs.get("run_1").tab;
  assert.ok(kept.includes(runTab), "the run's tab is kept as drawn in: " + JSON.stringify(kept));
  // A tab the person has open (a bank, say) that no run touched is never in the list.
  const theirs = 777;
  assert.ok(!kept.includes(theirs));
  // A new worker: it holds no run, and sweeps the kept tabs alone.
  const c2 = fakeChrome();
  const swept = [];
  const exec = c2.scripting.executeScript;
  c2.scripting.executeScript = async (o) => {
    swept.push(o.target.tabId);
    return exec(o);
  };
  const r2 = new Runner({ chrome: c2, sleep, paired: () => paired, drawnStore });
  const before = [...kept];
  await r2.sweepDrawn();
  assert.deepEqual([...new Set(swept)].sort(), [...before].sort(), "only kept tabs are reached");
  assert.ok(swept.includes(runTab), "the earlier run's tab is swept");
  assert.ok(!swept.includes(theirs), "a tab never drawn in gets nothing run in it");
  assert.deepEqual(kept, [], "the list is emptied after the sweep");
  // With nothing kept, a sweep runs nothing in any page.
  swept.length = 0;
  await r2.sweepDrawn();
  assert.deepEqual(swept, []);
});

test("an act whose pointer can't aim is refused, failing closed: the page's act is never called", async () => {
  // Without the aim there's no check that the act lands where the pointer is (`sameSpot`): a missing TafsSigns, a page
  // that doesn't answer, or an aim refused in its own words all hold the act.
  for (const [aimed, code] of [[null, "page_unresponsive"], [{ ok: false }, "page_unresponsive"], [{ ok: false, code: "page_changed" }, "page_changed"], ["throw", "page_unresponsive"]]) {
    const c = fakeChrome({ page: { read_id: "rd_x", url: "https://shop.example/", fields: [], controls: [{ ref: "c1", label: "Save" }], links: [] } });
    const order = [];
    const exec = c.scripting.executeScript;
    c.scripting.executeScript = async (o) => {
      const src = String(o.func || "");
      if (src.includes("TafsPage.aim")) {
        if (aimed === "throw") throw new Error("no TafsSigns");
        return [{ result: aimed }];
      }
      if (src.includes("TafsSigns.off")) order.push("signs off");
      if (src.includes("TafsPage.act")) order.push("act");
      return exec(o);
    };
    const r = new Runner({ chrome: c, paired: () => paired, sleep: (ms) => (ms >= 20_000 ? new Promise(() => {}) : Promise.resolve()) });
    await r.handle(step());
    const out = await r.handle(step({ op: "act", read_id: "rd_x", act: { kind: "click", ref: "c1" } }));
    assert.equal(out.error && out.error.code, code, JSON.stringify(aimed));
    assert.ok(!order.includes("act"), JSON.stringify(aimed) + ": the page's act is never called");
    assert.ok(order.includes("signs off"), JSON.stringify(aimed) + ": the signs go");
  }
});

test("the person closing a run's last tab ends the run as their Stop; closing one of two, or a tab of no run, doesn't", async () => {
  const c = fakeChrome();
  const r = runner(c);
  await r.handle(step());
  const [first] = [...r.runs.get("run_1").tabs];
  // A pop-up of the run's page joins it: closing it leaves the run working in its first tab.
  c.listeners.created({ sourceTabId: first, tabId: 555, url: "https://shop.example/help" });
  c.listeners.removed(555);
  await new Promise((done) => setTimeout(done, 0));
  assert.equal(r.runs.get("run_1").stopped, false, "one of two closed: the run goes on");
  c.listeners.removed(999);
  await new Promise((done) => setTimeout(done, 0));
  assert.equal(r.runs.get("run_1").stopped, false, "a tab of no run changes nothing");
  c.listeners.removed(first);
  await new Promise((done) => setTimeout(done, 0));
  assert.equal(r.runs.get("run_1").stopped, true, "its last tab closed: the run ends");
  const last = r.state()[0].steps.at(-1);
  assert.deepEqual([last.op, last.by], ["stop", "person"]);
  assert.ok(!r.state().some((x) => !x.stopped), "nothing says a taf works");
  assert.equal((await r.handle(step({ op: "read" }))).error.code, "stopped", "and planque's next step on it is told");
});

test("a group whose run is over says so in the extension's words, as it said the taf was working", async () => {
  const c = fakeChrome();
  const r = new Runner({ chrome: c, sleep, paired: () => paired, say: (key, sub) => (key === "group_done" ? sub[0] + " is done" : key === "working" ? sub[0] + " is working" : "") });
  await r.handle(step());
  await r.greyGroup("Rae", 7);
  assert.deepEqual(c.calls.filter((x) => x[0] === "tabGroups.update").pop()[2], { title: "tafs · Rae is done", color: "grey" });
  assert.equal(new Runner({ chrome: c, sleep, paired: () => paired }).doneTitle("Rae"), "tafs · Rae · done", "with no words, as before");
});

test("each Pause, Resume and Stop, whoever made it, is said to planque as the run's state, and a stopped run says nothing more", async () => {
  let clock = Date.parse("2026-11-24T15:20:00Z");
  const said = [];
  const c = fakeChrome();
  const r = new Runner({ chrome: c, sleep, paired: () => paired, now: () => clock, onRunState: (s) => said.push(s) });
  await r.handle(step());
  assert.deepEqual(said, [], "a step is no change of state");
  r.pause("run_1");
  clock += 1_000;
  r.resume("run_1");
  clock += 1_000;
  await r.stop("run_1", false, "person");
  assert.deepEqual(said, [
    { run: "run_1", state: "paused", at: "2026-11-24T15:20:00.000Z" },
    { run: "run_1", state: "working", at: "2026-11-24T15:20:01.000Z" },
    { run: "run_1", state: "stopped", at: "2026-11-24T15:20:02.000Z" },
  ]);
  r.resume("run_1");
  r.pause("run_1");
  assert.equal(said.length, 3, "nothing after the stop");
  // The person's touch pauses too, and the idle sweep stops.
  const said2 = [];
  const d = fakeChrome();
  const r2 = new Runner({ chrome: d, sleep, paired: () => paired, now: () => clock, onRunState: (s) => said2.push(s) });
  await r2.handle(step());
  await new Promise((done) => setTimeout(done, 0));
  r2.ownerInput(r2.runs.get("run_1").tab);
  clock += PAUSED_IDLE_MS + 1_000;
  await r2.sweep();
  assert.deepEqual(said2.map((s) => s.state), ["paused", "stopped"]);
});

test("Pause, a long wait, then Resume: the held act never touches the page, planque hears one paused and one working, the bar works again, and the act runs once", async () => {
  let clock = Date.parse("2026-11-24T17:00:00Z");
  const said = [];
  const c = fakeChrome({ page: { read_id: "rd_x", url: "https://shop.example/", title: "t", text: "", fields: [{ ref: "f1", label: "Email", kind: "email" }], controls: [{ ref: "c1", label: "Save" }], links: [] } });
  const r = new Runner({ chrome: c, sleep, paired: () => paired, now: () => clock, onRunState: (s) => said.push(s) });
  await r.handle(step());
  const read = await r.handle(step({ id: "rd", op: "read" }));
  const act = () => r.handle(step({ id: "a1", op: "act", read_id: read.page.read_id, act: { kind: "click", ref: "c1" } }));
  const acts = () => c.calls.filter((x) => x[0] === "page" && x[1] === "act start").length;
  r.pause("run_1");
  const run = r.runs.get("run_1");
  assert.equal(r.barState(run, true).paused, true, "the bar says paused");
  // The taf holds its act through a ten-minute pause, sending it again every 10 s, as kiff/agents' planque tool does.
  for (let i = 0; i < 60; i++) {
    clock += 10_000;
    assert.equal((await act()).error.code, "paused_by_owner");
    await r.sweep();
  }
  assert.equal(acts(), 0, "nothing reached the page while paused");
  assert.equal(run.stopped, false, "ten minutes held: still waiting for Resume");
  r.resume("run_1");
  assert.equal(r.barState(run, true).paused, false, "the bar says working again");
  assert.equal(r.state()[0].paused, null);
  const out = await act();
  assert.ok(out.page && !out.error, "the held act goes on after Resume");
  assert.equal(acts(), 1, "and runs exactly once");
  assert.deepEqual(said.map((s) => s.state), ["paused", "working"], "one paused and one working, not one per held step");
  assert.equal(said[0].at, "2026-11-24T17:00:00.000Z");
  assert.equal(said[1].at, "2026-11-24T17:10:00.000Z");
});

// The owner's 0.2.8 walk-through (D): the group still said the taf was working right after Stop.
test("the person's Stop greys the run's group at once; planque's stop and a run's own end keep the grace", async () => {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
  const graces = [];
  const r = new Runner({ chrome: c, sleep: (ms) => (ms === 15_000 ? new Promise((go) => graces.push(go)) : Promise.resolve()), paired: () => paired });
  const greyed = () => c.calls.filter((x) => x[0] === "tabGroups.update" && x[2].color === "grey");
  await r.handle(step());
  await r.stop("run_1", false, "person");
  assert.equal(graces.length, 0, "no grace to wait for");
  assert.equal(greyed().length, 1, "grey at once");
  assert.equal(greyed()[0][2].title, "tafs · Rae · done");
  await r.handle(step({ run: "run_2" }));
  await r.stop("run_2");
  assert.equal(graces.length, 1, "planque's stop: the taf may call again within the grace");
  assert.equal(greyed().length, 1, "not grey yet");
});

// The owner's 0.2.8 walk-through (C): the panel said "Wiki lookup travaille" after the taf's final reply, until Stop.
test("the taf's turn over ends its quiet run here: grey at once, forgotten, planque never told it stopped, and a later call goes on in a new run", async () => {
  let clock = 1_000_000;
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
  const states = [];
  const r = new Runner({ chrome: c, sleep, paired: () => paired, now: () => clock, onRunState: (s) => states.push(s) });
  await r.handle(step());
  await new Promise((go) => setImmediate(go)); // the step's slot is let go once its answer is out
  clock += 4_000;
  assert.deepEqual(await r.endTurn("run_1"), { ended: false, retryIn: TURN_QUIET_MS - 4_000 }, "a step a moment ago: not yet, and when to ask again");
  assert.equal(r.state()[0].stopped, false);
  clock += TURN_QUIET_MS;
  assert.deepEqual(await r.endTurn("run_1"), { ended: true });
  assert.deepEqual(r.state(), [], "the panel's card and the action's badge go: the run isn't listed");
  assert.equal(c.calls.filter((x) => x[0] === "tabGroups.update").pop()[2].title, "tafs · Rae · done", "its group says done at once");
  assert.ok(!c.calls.some((x) => x[0] === "windows.remove" || x[0] === "tabs.remove"), "its tabs stay for the person");
  assert.deepEqual(states, [], "not a Stop: planque isn't told the run stopped, so a Pause later still reaches the taf");
  // The person's next message within planque's 2 minutes comes back to the same run: its read finds no page (planque then
  // goes on in a new run, as after the worker restarts), never "stopped", which tells the taf the owner stopped it.
  assert.equal((await r.handle(step({ op: "read" }))).error.code, "no_page");
});

test("the turn over never ends a run that's paused, waits on the person, or has a step running", async () => {
  let clock = 1_000_000;
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
  const r = new Runner({ chrome: c, sleep, paired: () => paired, now: () => clock });
  await r.handle(step());
  await new Promise((go) => setImmediate(go));
  r.pause("run_1");
  clock += 60_000;
  assert.deepEqual(await r.endTurn("run_1"), { ended: false }, "paused: it waits for the person's Resume");
  r.resume("run_1");
  clock += 60_000;
  r.tails.set("run_1", Promise.resolve());
  assert.deepEqual(await r.endTurn("run_1"), { ended: false }, "a step under way");
  r.tails.delete("run_1");
  assert.deepEqual(await r.endTurn("nope"), { ended: false }, "a run it doesn't hold");
  assert.deepEqual(await r.endTurn("run_1"), { ended: true });
});

// A taf works where the person is (docs/chrome.md, "A taf works where you are"): a run the person started from the side panel
// joins the tab in front of that panel's window. The panel is open in window 3; the person's tab, 42, is in front of it.
const THEIRS = 42;

/** A panel's port to the worker, from the extension's own page. */
function panelPort(windowId) {
  const on = {};
  const port = { name: "panel", sender: { id: "ext" }, onMessage: { addListener: (f) => (on.msg = f) }, onDisconnect: { addListener: (f) => (on.gone = f) } };
  return { port, open: () => on.msg({ windowId }), close: () => on.gone() };
}

/**
 * A Chrome with the person's tab in front of window 3 (`tab` changes it), the side panel open there, and the worker's panels: the
 * person sent from that panel, and tafs cloud's answer named run "kr_1" (`mark: false`: no send). `consent` is the page consent.
 */
async function joining({ tab = {}, consent = true, off = [], mark = true, clock = { at: 1_000_000 }, page = null } = {}) {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW, page });
  const theirs = { id: THEIRS, windowId: 3, active: true, incognito: false, pinned: false, groupId: -1, url: "https://shop.example/", ...tab };
  c.tabsMap.set(THEIRS, theirs.url);
  c.looked = []; // every tab the runner asked Chrome about
  const get = c.tabs.get;
  c.tabs.get = async (id) => {
    c.looked.push(id);
    return id === THEIRS ? { ...theirs, status: "complete", url: c.tabsMap.get(id), groupId: c.groupsMap.get(id) ?? theirs.groupId } : get(id);
  };
  const exec = c.scripting.executeScript;
  c.scripting.executeScript = async (o) => {
    if (String(o.func || "").includes("TafsBar.hide")) c.calls.push(["bar.hide", o.target.tabId]);
    return exec(o);
  };
  const panels = new Panels({ now: () => clock.at, sleep: async () => {} });
  const p = panelPort(3);
  panels.connect(p.port, "ext");
  p.open();
  if (mark) await panels.sent(3, THEIRS, Promise.resolve({ run: "kr_1" }));
  const asked = { consent: 0 };
  const panel = {
    front: ({ agentRun }) => joinFront({ panels, agentRun, consent: async () => (asked.consent++, consent), off: async () => off }),
    isOpen: (w) => panels.isOpen(w),
    off: async () => off,
  };
  // A page that answers is never outrun by the step timeout here (the bar wrapper above adds a turn).
  const r = new Runner({ chrome: c, sleep: (ms) => (ms >= 20_000 ? new Promise(() => {}) : Promise.resolve()), paired: () => paired, panel, now: () => clock.at });
  return { c, r, panels, port: p, asked, clock };
}

const started = (extra) => step({ agent_run: "kr_1", ...extra });
// Every call that names tab [id]: a script in it, an update, a close, a group, a fence rule.
const touched = (c, id) => c.calls.filter((x) => (x[0] === "tabs.group" ? x[1].tabIds.includes(id) : x[0] === "dnr" ? (x[1].addRules || []).some((y) => (y.condition.tabIds || []).includes(id)) : [].concat(x[1]).includes(id) || x[2] === id));

test("a run the person started from the side panel works in the tab in front: it joins the group, fenced first, read through page.js, and Chrome follows it", async () => {
  const { c, r } = await joining();
  const out = await r.handle(started());
  assert.ok(out.page, JSON.stringify(out));
  assert.equal(out.page.url, "https://shop.example/");
  assert.ok(!c.calls.some((x) => x[0] === "tabs.create" || x[0] === "windows.create"), "no tab or window of its own");
  const fenced = c.calls.findIndex((x) => x[0] === "dnr" && x[1].addRules && x[1].addRules[0].condition.tabIds[0] === THEIRS);
  const grouped = c.calls.findIndex((x) => x[0] === "tabs.group");
  assert.ok(fenced >= 0 && fenced < grouped, "fenced before anything else");
  assert.deepEqual(c.calls[grouped][1], { tabIds: [THEIRS], createProperties: { windowId: 3 } });
  assert.ok(!c.calls.some((x) => x[0] === "tabs.update" && x[2].url), "read where it is, never loaded again");
  assert.deepEqual(c.calls.find((x) => x[0] === "tabs.update"), ["tabs.update", THEIRS, { active: true }], "Chrome follows the action");
  assert.ok(!c.calls.some((x) => x[0] === "windows.update"), "never raises or focuses a window");
  // What's read goes through the same read as any run's tab: mask.js and page.js, then TafsPage.read, in that tab.
  assert.ok(c.calls.some((x) => ranInPage(x) && x[1] === "src/mask.js,src/signs.js,src/page.js" && x[2] === THEIRS));
  assert.ok(c.calls.some((x) => x[0] === "page" && x[1] === "read"));
  assert.deepEqual(r.state()[0].tabs, [THEIRS]);
});

test("the tab in front already at the asked address is read in place, not loaded again", async () => {
  const { c, r } = await joining();
  assert.ok((await r.handle(started())).page);
  assert.ok(!c.calls.some((x) => x[0] === "tabs.update" && x[2].url), "not loaded again");
  assert.ok(c.calls.some((x) => x[0] === "tabs.update" && x[1] === THEIRS && x[2].active === true));
});

test("a scheduled run with the panel open and an allowed tab in front gets its own tab; so does a step naming no run, or another", async () => {
  for (const [what, extra] of [["no agent_run (a schedule, an event, the phone, today's planque)", {}], ["another run's agent_run", { agent_run: "kr_other" }], ["an empty agent_run", { agent_run: "" }]]) {
    const { c, r, asked } = await joining();
    assert.ok((await r.handle(step(extra))).page, what);
    assert.deepEqual(c.calls.find((x) => x[0] === "tabs.create")[1], { windowId: 3, url: "about:blank", active: false }, what + ": its own tab, in the background, as before");
    assert.deepEqual(touched(c, THEIRS), [], what + ": the person's tab is never touched");
    assert.ok(!c.looked.includes(THEIRS), what + ": nor even looked at");
    assert.equal(asked.consent, 0, what + ": no mark, nothing asked");
    assert.equal(c.calls.find((x) => x[0] === "tabs.update" && x[2].url)[2].active, undefined, what + ": Chrome doesn't follow a run nobody started from the panel");
  }
});

test("a mark is taken by the first run that names it, once; an expired one, or a closed panel's, joins nothing", async () => {
  const { c, r } = await joining();
  await r.handle(started());
  await r.stop("run_1", false, "person");
  await r.handle(started({ run: "run_2", id: "op2" }));
  assert.ok(c.calls.some((x) => x[0] === "tabs.create"), "a second run naming the same agent_run opens its own tab");
  assert.equal(r.runs.get("run_2").joined.size, 0);

  const late = await joining();
  late.clock.at += MARK_MS + 1;
  await late.r.handle(started());
  assert.deepEqual(touched(late.c, THEIRS), [], "a mark older than two minutes joins nothing");

  const closed = await joining();
  closed.port.close();
  await closed.r.handle(started());
  assert.deepEqual(touched(closed.c, THEIRS), [], "the panel closed: its mark went with it");
});

test("a mark from window A never lets a run join a tab in window B", async () => {
  // The person moved the tab to another window since their send, or it isn't the one in front any more.
  for (const tab of [{ windowId: 8 }, { active: false }]) {
    const { c, r } = await joining({ tab });
    await r.handle(started());
    assert.deepEqual(touched(c, THEIRS), [], JSON.stringify(tab));
    assert.ok(c.calls.some((x) => x[0] === "tabs.create"), "its own tab");
  }
});

test("the tab in front never joins off the run's sites, on a refused site, private, pinned, in a group of the person's, or not https", async () => {
  const both = await signedSites(["shop.example", "paypal.com"]);
  for (const [what, tab, off] of [
    ["off the run's sites", { url: "https://elsewhere.example/" }],
    ["a payment site, even listed", { url: "https://www.paypal.com/" }],
    ["mail", { url: "https://mail.shop.example/inbox" }],
    ["the Web Store", { url: "https://chromewebstore.google.com/detail/x" }],
    ["a site the person switched off", {}, ["shop.example"]],
    ["a private tab", { incognito: true }],
    ["Chrome's own page", { url: "chrome://settings" }],
    ["plain http", { url: "http://shop.example/" }],
    ["pinned", { pinned: true }],
    ["in a group of the person's own", { groupId: 99 }],
  ]) {
    const { c, r } = await joining({ tab, off: off || [] });
    await r.handle(started({ sites: ["shop.example", "paypal.com"], sites_signed: both }));
    assert.deepEqual(touched(c, THEIRS), [], what);
    assert.ok(c.calls.some((x) => x[0] === "tabs.create"), what + ": its own tab");
  }
});

test("without the page consent, the worker never lets the tab in front join, and never looks at it: the run opens its own tab as today", async () => {
  const { c, r, asked } = await joining({ consent: false });
  assert.ok((await r.handle(started())).page);
  assert.equal(asked.consent, 1, "the consent is asked, by the worker");
  assert.ok(!c.looked.includes(THEIRS), "the tab isn't looked at");
  assert.deepEqual(touched(c, THEIRS), []);
  assert.deepEqual(c.calls.find((x) => x[0] === "tabs.create")[1], { windowId: 3, url: "about:blank", active: false });
});

test("a navigation in a joined tab that leaves its sites is stopped, and a pop-up off them is closed", async () => {
  const { c, r } = await joining();
  await r.handle(started());
  const rules = c.calls.find((x) => x[0] === "dnr" && x[1].addRules)[1].addRules;
  assert.deepEqual(fenceShape(rules), FENCE(["shop.example"]), "the same fence as a run's own tab");
  assert.ok(rules.every((x) => x.condition.tabIds.join() === String(THEIRS)));
  c.listeners.before({ tabId: THEIRS, frameId: 0, url: "https://evil.example/steal" });
  assert.ok(!c.calls.some((x) => x[0] === "tabs.update" && x[1] === THEIRS && x[2].url), "the fence blocks it; the person's page stays");
  c.listeners.created({ sourceTabId: THEIRS, tabId: 555, url: "https://evil.example/" });
  assert.ok(c.calls.some((x) => x[0] === "tabs.remove" && x[1] === 555));
  c.listeners.created({ sourceTabId: THEIRS, tabId: 556, url: "https://shop.example/help" });
  assert.ok(!c.calls.some((x) => x[0] === "tabs.remove" && x[1] === 556));
  assert.ok(c.calls.some((x) => x[0] === "tabs.group" && x[1].tabIds[0] === 556 && x[1].groupId === 7), "a pop-up on its sites joins the same group");
});

test("every step checks the joined tab again: a page it may no longer show is refused, and an open goes on in a tab of the run's own", async () => {
  const { c, r } = await joining({ page: PLAIN });
  const off = [];
  r.panel.off = async () => off;
  const first = await r.handle(started());
  // The person switched the site off in the panel since.
  off.push("shop.example");
  const scripts = c.calls.filter((x) => ranInPage(x)).length;
  assert.equal((await r.handle(started({ id: "op2", op: "read" }))).error.code, "site_blocked");
  assert.equal((await r.handle(started({ id: "op3", op: "act", read_id: first.page.read_id, act: { kind: "click", ref: "c1" } }))).error.code, "site_blocked");
  assert.equal(c.calls.filter((x) => ranInPage(x)).length, scripts, "nothing ran in their tab");
  // The person's tab moved off the run's sites (a page the fence let through before the grant changed, say): refused too.
  off.length = 0;
  c.tabsMap.set(THEIRS, "https://elsewhere.example/");
  assert.equal((await r.handle(started({ id: "op4", op: "read" }))).error.code, "site_not_listed");
  // An open: the run's own tab, in the same window and group, fenced; the person's tab is left where it is.
  const out = await r.handle(started({ id: "op5", op: "open", url: "https://shop.example/other" }));
  assert.ok(out.page, JSON.stringify(out));
  const made = c.calls.find((x) => x[0] === "tabs.create");
  assert.deepEqual(made[1], { windowId: 3, url: "about:blank", active: false });
  const own = r.runs.get("run_1").tab;
  assert.notEqual(own, THEIRS);
  assert.ok(c.calls.some((x) => x[0] === "tabs.group" && x[1].groupId === 7 && x[1].tabIds[0] === own));
  assert.ok(c.calls.some((x) => x[0] === "dnr" && x[1].addRules && x[1].addRules[0].condition.tabIds[0] === own));
  assert.equal(c.tabsMap.get(THEIRS), "https://elsewhere.example/", "the person's tab stays on its page");
  assert.deepEqual(c.calls.filter((x) => x[0] === "tabs.update" && x[2].url).at(-1), ["tabs.update", own, { url: "https://shop.example/other", active: true }], "Chrome follows to the new tab");
});

test("the person typing, clicking or scrolling in the joined tab pauses the run", async () => {
  const { r } = await joining();
  await r.handle(started());
  r.ownerInput(THEIRS);
  assert.equal(r.runs.get("run_1").paused, "owner");
  assert.equal((await r.handle(started({ id: "op2", op: "read" }))).error.code, "paused_by_owner");
});

test("Stop hands the tab back: out of the group, unfenced, forgotten by the run, its bar gone, still open", async () => {
  const { c, r } = await joining();
  await r.handle(started());
  const rules = c.calls.find((x) => x[0] === "dnr" && x[1].addRules)[1].addRules.map((x) => x.id);
  await r.stop("run_1", false, "person");
  assert.ok(c.calls.some((x) => x[0] === "tabs.ungroup" && x[1].includes(THEIRS)), "1. it leaves the group");
  assert.deepEqual(c.calls.filter((x) => x[0] === "dnr" && !x[1].addRules).at(-1)[1].removeRuleIds, fenceIdsFrom(rules), "2. its fence rules go");
  assert.equal(r.tabRun.has(THEIRS), false, "3. the run forgets it");
  assert.ok(c.calls.some((x) => x[0] === "bar.hide" && x[1] === THEIRS), "4. its bar goes");
  assert.ok(!c.calls.some((x) => x[0] === "tabs.remove"), "5. it stays open");
  // A late step from planque is told stopped, and never reaches the tab.
  const before = c.calls.length;
  assert.equal((await r.handle(started({ id: "op2", op: "read" }))).error.code, "stopped");
  c.listeners.before({ tabId: THEIRS, frameId: 0, url: "https://elsewhere.example/" });
  r.ownerInput(THEIRS);
  assert.deepEqual(c.calls.slice(before), [], "the late step, their navigation and their input are theirs again");
  assert.deepEqual(await r.doneTabs(), { "grant:g_1": { name: "Rae", tabs: [], window: null, group: 7 } }, "not kept as the taf's");
});

test("a joined tab is never closed by the extension: not at unpairing, an idle end, the turn over, the close step, planque's stop, nor the taf's next run", async () => {
  const settle = () => new Promise((go) => setImmediate(go));
  const ends = {
    unpair: (r) => r.stopAll(),
    idle: async (r, clock) => {
      await settle();
      clock.at += RUN_IDLE_MS + 1;
      await r.sweep();
    },
    turn_over: async (r, clock) => {
      await settle();
      clock.at += TURN_QUIET_MS + 1;
      assert.deepEqual(await r.endTurn("run_1"), { ended: true });
    },
    close: (r) => r.handle(started({ id: "op9", op: "close" })),
    planque_stop: (r) => r.stop("run_1"),
  };
  for (const [what, end] of Object.entries(ends)) {
    const { c, r, clock } = await joining();
    await r.handle(started());
    await end(r, clock);
    assert.ok(!c.calls.some((x) => x[0] === "tabs.remove" && [].concat(x[1]).includes(THEIRS)), what + ": never closed");
    assert.equal(r.tabRun.has(THEIRS), false, what + ": no longer the run's");
    // The taf's next run (nobody started it from the panel: its own tab), then unpairing.
    await r.handle(step({ run: "run_n", id: "opn" }));
    await r.stop("run_n");
    await r.stopAll();
    assert.ok(!c.calls.some((x) => x[0] === "tabs.remove" && [].concat(x[1]).includes(THEIRS)), what + ": nor by the next run or unpairing");
    assert.ok(c.calls.some((x) => x[0] === "tabs.ungroup" && x[1].includes(THEIRS)), what + ": at most it leaves the group");
  }
});

test("one group per taf per window: the taf's done group there works again, and its old tabs aren't the new run's", async () => {
  const { c, r } = await joining({ mark: false });
  c.tabGroups.get = async (id) => ({ id, windowId: 3 });
  // A first run nobody started from the panel: its own tab, in group 7, then done.
  await r.handle(step());
  const old = r.runs.get("run_1").tab;
  await r.stop("run_1");
  // The person sends from the panel; the run it starts joins their tab, in the same group, which says it works again.
  const panels = new Panels({ now: () => 1, sleep: async () => {} });
  const p = panelPort(3);
  panels.connect(p.port, "ext");
  p.open();
  await panels.sent(3, THEIRS, { run: "kr_2" });
  r.panel = { front: ({ agentRun }) => joinFront({ panels, agentRun, consent: async () => true }), isOpen: (w) => panels.isOpen(w), off: async () => [] };
  const before = c.calls.length;
  await r.handle(step({ run: "run_2", id: "op2", agent_run: "kr_2" }));
  const made = c.calls.slice(before);
  assert.deepEqual(made.filter((x) => x[0] === "tabs.group").map((x) => x[1]), [{ groupId: 7, tabIds: [THEIRS] }], "into the taf's group, no new one");
  assert.deepEqual(made.find((x) => x[0] === "tabGroups.update").slice(1), [7, { title: "tafs · Rae", color: "orange", collapsed: false }]);
  // A group worked in again is no consent for the tabs left in it: the old one is the run's no more, and nothing runs there.
  assert.equal(r.tabRun.has(old), false);
  assert.deepEqual(r.state().find((x) => x.id === "run_2").tabs, [THEIRS]);
  assert.ok(!made.some((x) => ranInPage(x) && x[2] === old), "nothing runs in the old tab");
  assert.ok(made.some((x) => x[0] === "tabs.remove" && x[1].includes(old)), "the old tab, still where its run left it, closes as before");
});

test("Chrome follows the run only while the panel is open in its window", async () => {
  const { c, r, port } = await joining();
  await r.handle(started());
  port.close();
  await r.handle(started({ id: "op2", url: "https://shop.example/next" }));
  const own = r.runs.get("run_1").tab;
  assert.notEqual(own, THEIRS, "an open elsewhere goes to a tab of the run's own");
  assert.deepEqual(c.calls.filter((x) => x[0] === "tabs.update" && x[2].url).at(-1), ["tabs.update", own, { url: "https://shop.example/next" }], "the panel closed: no tab is brought to the front");
});

// The security read (02:30): planque reuses a taf's run for its next call whatever agent run makes it, so a schedule or a phone
// run right after the person's panel run reached the run that held their tab.
test("a step of another agent run never works in the person's joined tab: it goes back to them first, and the run works in a tab of its own", async () => {
  const { c, r } = await joining();
  await r.handle(started());
  assert.equal(r.runs.get("run_1").joined.has(THEIRS), true, "the panel run joined their tab");
  for (const other of [{}, { agent_run: "kr_other" }]) {
    const before = c.calls.length;
    const out = await r.handle(started({ id: "op_" + Object.keys(other).length, op: "read", agent_run: undefined, ...other }));
    const now = c.calls.slice(before);
    assert.equal(r.runs.get("run_1").joined.size, 0, "given back");
    assert.equal(r.tabRun.has(THEIRS), false, "forgotten");
    // Only the bar's hide reaches their tab: no page script, no read, no act.
    assert.ok(!now.some((x) => ranInPage(x) && x[2] === THEIRS && x[1] !== "isolated"), "no page script in their tab");
    assert.ok(!now.some((x) => x[0] === "page"), "nothing read or acted on");
    assert.ok(!now.some((x) => x[0] === "tabs.remove" && [].concat(x[1]).includes(THEIRS)), "still open");
    if (!Object.keys(other).length) {
      assert.ok(now.some((x) => x[0] === "tabs.ungroup" && x[1].includes(THEIRS)), "out of the group");
      assert.ok(now.some((x) => x[0] === "bar.hide" && x[1] === THEIRS), "its bar goes");
      assert.equal(out.error && out.error.code, "no_page", "a read with no tab of its own yet: planque goes on in a new run");
    }
  }
  // An open now goes to a tab of the run's own, and their tab can't join again.
  const opened = await r.handle(started({ id: "op_open", agent_run: "kr_other" }));
  assert.ok(opened.page, "opened in a tab of its own");
  assert.notEqual(r.runs.get("run_1").tab, THEIRS);
  assert.equal(r.runs.get("run_1").joined.size, 0);
});

// The owner's 0.2.13 walk-through: a taf asked to read "this page" opened the site's home first, and the person's own article was
// navigated away. The person's tab is read where it is, and never sent elsewhere.
test("an open elsewhere never navigates the person's joined tab", async () => {
  const { c, r } = await joining();
  await r.handle(started());
  assert.equal(r.runs.get("run_1").joined.has(THEIRS), true, "joined, read in place");
  const out = await r.handle(started({ id: "op2", url: "https://shop.example/other" }));
  assert.ok(out.page, JSON.stringify(out));
  assert.ok(!c.calls.some((x) => x[0] === "tabs.update" && x[1] === THEIRS && x[2].url), "no tabs.update with a url on their tab, ever");
  assert.equal(c.tabsMap.get(THEIRS), "https://shop.example/", "their tab still shows its page");
  assert.ok(!c.calls.some((x) => x[0] === "tabs.remove" && [].concat(x[1]).includes(THEIRS)), "nor closed");
});

test("the open goes to a tab of the run's own in the same group", async () => {
  const { c, r } = await joining();
  await r.handle(started());
  await r.handle(started({ id: "op2", url: "https://shop.example/other" }));
  const own = r.runs.get("run_1").tab;
  assert.notEqual(own, THEIRS);
  assert.deepEqual(c.calls.find((x) => x[0] === "tabs.create")[1], { windowId: 3, url: "about:blank", active: false });
  const fenced = c.calls.findIndex((x) => x[0] === "dnr" && x[1].addRules && x[1].addRules[0].condition.tabIds[0] === own);
  const loaded = c.calls.findIndex((x) => x[0] === "tabs.update" && x[1] === own && x[2].url);
  assert.ok(fenced >= 0 && fenced < loaded, "fenced before it loads");
  assert.ok(c.calls.some((x) => x[0] === "tabs.group" && x[1].groupId === 7 && x[1].tabIds[0] === own), "in the run's group");
  assert.deepEqual(c.calls[loaded], ["tabs.update", own, { url: "https://shop.example/other", active: true }], "Chrome follows to it, as before");
  // A read now reads the run's own tab; their tab is still the run's, read again only by an open of its own address.
  const scripts = c.calls.filter((x) => ranInPage(x) && x[2] === THEIRS).length;
  assert.ok((await r.handle(started({ id: "op3", op: "read" }))).page);
  assert.equal(c.calls.filter((x) => ranInPage(x) && x[2] === THEIRS).length, scripts, "the read is the run's own tab's");
});

test("an open of the joined tab's own address reads it in place, even from a tab of the run's own", async () => {
  const { c, r } = await joining();
  await r.handle(started());
  // A read of the joined tab reads it where it is.
  const read = await r.handle(started({ id: "op_r", op: "read" }));
  assert.ok(read.page);
  assert.ok(c.calls.some((x) => ranInPage(x) && x[1] === "src/mask.js,src/signs.js,src/page.js" && x[2] === THEIRS));
  await r.handle(started({ id: "op2", url: "https://shop.example/other" }));
  assert.notEqual(r.runs.get("run_1").tab, THEIRS);
  const before = c.calls.length;
  const back = await r.handle(started({ id: "op3", url: "https://shop.example/" }));
  assert.ok(back.page, JSON.stringify(back));
  assert.equal(r.runs.get("run_1").tab, THEIRS, "the run is back on their tab");
  const now = c.calls.slice(before);
  assert.ok(!now.some((x) => x[0] === "tabs.update" && x[2].url), "nothing loaded again");
  assert.ok(now.some((x) => x[0] === "page" && x[1] === "read"));
  assert.ok(!now.some((x) => x[0] === "tabs.create"), "no further tab");
});

test("a first open of another address than the tab in front shows joins nothing: the run opens its own tab, theirs is untouched", async () => {
  const { c, r } = await joining({ tab: { url: "https://shop.example/basket" } });
  const out = await r.handle(started());
  assert.ok(out.page);
  assert.deepEqual(touched(c, THEIRS), [], "their tab isn't the run's, and nothing is done to it");
  assert.equal(r.runs.get("run_1").joined.size, 0);
  assert.deepEqual(c.calls.find((x) => x[0] === "tabs.create")[1], { windowId: 3, url: "about:blank", active: false });
  assert.equal(c.tabsMap.get(THEIRS), "https://shop.example/basket");
});

// The owner's walk-through: two cards for one taf, the older paused, the newer working, each with its own Resume or Pause.
test("a newer run of the taf ends an older run of it the person paused, as their Stop would; the newer takes nothing of it", async () => {
  const said = [];
  const { c, r } = await joining();
  r.onRunState = (s) => said.push(s);
  await r.handle(started());
  r.pause("run_1");
  // A schedule's run of the same taf (no agent_run): the paused run ends first, its tab handed back.
  const before = c.calls.length;
  const out = await r.handle(step({ run: "run_2", id: "op2" }));
  assert.ok(out.page);
  const old = r.runs.get("run_1");
  assert.equal(old.stopped, true);
  assert.deepEqual(old.steps.at(-1).by, "newer_run");
  assert.deepEqual(said.map((s) => [s.run, s.state]), [["run_1", "paused"], ["run_1", "stopped"]], "planque hears it stopped");
  const now = c.calls.slice(before);
  const ungrouped = now.findIndex((x) => x[0] === "tabs.ungroup" && x[1].includes(THEIRS));
  const made = now.findIndex((x) => x[0] === "tabs.create");
  assert.ok(ungrouped >= 0 && ungrouped < made, "handed back before the newer run's first step");
  assert.ok(now.some((x) => x[0] === "bar.hide" && x[1] === THEIRS), "its bar goes");
  assert.ok(!now.some((x) => x[0] === "tabs.remove" && [].concat(x[1]).includes(THEIRS)), "still open");
  assert.equal(r.tabRun.has(THEIRS), false, "forgotten");
  const fresh = r.runs.get("run_2");
  assert.equal(fresh.joined.size, 0, "the newer run doesn't inherit their tab");
  assert.ok(![...fresh.tabs].includes(THEIRS));
  assert.equal(fresh.agentRun, null, "nor the panel's mark");
  assert.ok(!now.some((x) => ranInPage(x) && x[2] === THEIRS && x[1] !== "isolated"), "nothing runs in their tab");
});

test("a newer run leaves an older run of the taf that's working, and a paused run of another taf or another grant", async () => {
  for (const [what, older, newer] of [
    ["working", {}, {}],
    ["another taf, paused", { pause: true }, { taf: "Bo" }],
    ["another grant of a same-named taf, paused", { pause: true }, { grant: "g_2", sites_signed: await signedSites(["shop.example"], "g_2") }],
  ]) {
    const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
    const r = new Runner({ chrome: c, sleep, paired: () => paired });
    await r.handle(step());
    if (older.pause) r.pause("run_1");
    await r.handle(step({ run: "run_2", id: "op2", ...newer }));
    assert.equal(r.runs.get("run_1").stopped, false, what);
    assert.ok(!r.runs.get("run_1").steps.some((s) => s.op === "stop"), what);
  }
});

test("a joined tab still at the page it joined at has no back: no_back, and nothing moves", async () => {
  const { c, r } = await joining();
  const first = await r.handle(started());
  const before = c.calls.length;
  assert.equal((await r.handle(started({ id: "op2", op: "act", read_id: first.page.read_id, act: { kind: "back" } }))).error.code, "no_back");
  assert.ok(!c.calls.slice(before).some((x) => x[0] === "tabs.goBack" || (x[0] === "tabs.update" && x[2].url)), "the person's tab isn't moved");
});

// The security read: a back past the joined page must never let the taf read what the person had before, nor a page off the sites.
test("the person closing a run's tab takes its fence rules with it, even once the run has let it go", async () => {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
  const r = runner(c);
  await r.handle(step());
  const tab = [...c.tabsMap.keys()][0];
  const ids = c.calls.find((x) => x[0] === "dnr" && x[1].addRules)[1].addRules.map((x) => x.id);
  // A pop-up on its sites: fenced too.
  c.listeners.created({ sourceTabId: tab, tabId: 556, url: "https://shop.example/help" });
  await new Promise((go) => setImmediate(go));
  const popup = c.calls.filter((x) => x[0] === "dnr" && x[1].addRules).at(-1)[1].addRules.map((x) => x.id);
  c.listeners.removed(556);
  await new Promise((go) => setImmediate(go));
  assert.deepEqual(c.calls.filter((x) => x[0] === "dnr" && !x[1].addRules).at(-1)[1].removeRuleIds, fenceIdsFrom(popup), "the pop-up's rules go when it closes");
  // Their last tab closed: its rules go, and the run ends as their Stop.
  c.listeners.removed(tab);
  await new Promise((go) => setImmediate(go));
  assert.ok(c.calls.some((x) => x[0] === "dnr" && !x[1].addRules && x[1].removeRuleIds.join() === fenceIdsFrom(ids).join()), "its rules are removed");
  assert.equal(r.fenceIds.size, 0, "no fence left");
  assert.equal(r.runs.get("run_1").stopped, true);
});

test("unpairing removes every fence rule, those the runner knows and any left in Chrome", async () => {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
  let rules = [];
  c.declarativeNetRequest.getSessionRules = async () => rules;
  c.declarativeNetRequest.updateSessionRules = async (o) => {
    c.calls.push(["dnr", o]);
    rules = rules.filter((x) => !(o.removeRuleIds || []).includes(x.id)).concat((o.addRules || []).map((x) => ({ id: x.id })));
  };
  const r = runner(c);
  await r.handle(step());
  // A rule Chrome kept that the runner lost track of (the leak the harness found).
  rules.push({ id: 900 });
  await r.stopAll();
  assert.deepEqual(rules, [], "no session rule left");
  assert.equal(r.fenceIds.size, 0);
});

test("a fence Chrome won't install fails closed: a further tab closes before it loads, a pop-up closes, and the person's tab is never moved", async () => {
  const { c, r } = await joining();
  await r.handle(started());
  const update = c.declarativeNetRequest.updateSessionRules;
  c.declarativeNetRequest.updateSessionRules = async (o) => {
    if (o.addRules) throw new Error("rule limit reached");
    return update(o);
  };
  const out = await r.handle(started({ id: "op2", url: "https://shop.example/other" }));
  assert.equal(out.error && out.error.code, "browser_unavailable");
  const made = c.calls.find((x) => x[0] === "tabs.create");
  assert.ok(made, "a tab of the run's own was made");
  const own = c.calls.filter((x) => x[0] === "tabs.remove").map((x) => [].concat(x[1])).flat();
  assert.ok(!c.calls.some((x) => x[0] === "tabs.update" && x[2].url === "https://shop.example/other"), "nothing loaded unfenced");
  assert.ok(own.length === 1 && own[0] !== THEIRS, "the unfenced blank tab closes; theirs never");
  assert.equal(r.runs.get("run_1").tab, THEIRS, "the run is still on their tab");
  assert.equal(c.tabsMap.get(THEIRS), "https://shop.example/");
  // A pop-up the fence can't hold closes.
  c.listeners.created({ sourceTabId: THEIRS, tabId: 557, url: "https://shop.example/help" });
  await new Promise((go) => setImmediate(go));
  assert.ok(c.calls.some((x) => x[0] === "tabs.remove" && [].concat(x[1]).includes(557)));
  assert.equal(r.tabRun.has(557), false);
});

// The harness's finding: giving the person's tab back emptied the run's group, Chrome ended it, and the next open failed
// (browser_unavailable) naming a group that was gone.
test("after the person's tab goes back, the run's next open makes its group again and goes on", async () => {
  const { c, r } = await joining();
  await r.handle(started());
  const dead = new Set();
  const group = c.tabs.group;
  c.tabs.group = async (o) => {
    if (o.groupId != null && dead.has(o.groupId)) {
      c.calls.push(["tabs.group", o]);
      throw new Error("No group with id: " + o.groupId);
    }
    if (o.groupId == null) {
      c.calls.push(["tabs.group", o]);
      for (const t of o.tabIds) c.groupsMap.set(t, 8);
      return 8;
    }
    return group(o);
  };
  // A step of another agent run: their tab goes back, the group empties, Chrome ends it.
  await r.handle(started({ id: "op2", op: "read", agent_run: undefined }));
  dead.add(7);
  const out = await r.handle(started({ id: "op3", agent_run: undefined, url: "https://shop.example/other" }));
  assert.ok(out.page, JSON.stringify(out));
  const run = r.runs.get("run_1");
  assert.equal(run.groupId, 8, "a new group");
  assert.ok(c.calls.some((x) => x[0] === "tabGroups.update" && x[1] === 8 && x[2].title === "tafs · Rae" && x[2].color === "orange"), "named and coloured as the run's");
  assert.equal(run.stopped, false);
});

test("a navigation in a joined tab off the run's sites is blocked by the fence and the person's page stays: never about:blank", async () => {
  const { c, r } = await joining();
  await r.handle(started());
  c.listeners.before({ tabId: THEIRS, frameId: 0, url: "https://evil.example/steal" });
  assert.ok(!c.calls.some((x) => x[0] === "tabs.update" && x[1] === THEIRS && x[2].url), "no tabs.update with a url on their tab");
  assert.equal(r.runs.get("run_1").joined.has(THEIRS), true, "a blocked request leaves the tab the run's");
  assert.ok(!r.runs.get("run_1").steps.some((x) => x.op === "navigate"), "not recorded here: a block in the run's own act is recorded once, by onErrorOccurred");
});

// The security read: a page off the run's sites that commits in a joined tab is mostly the person's own Back from Chrome's cache.
// Moving the tab (a goBack) would take their tab another step: it's given back to them as it is.
test("a joined tab that commits a page off the run's sites is given back as it is, never moved, and the run's next step there is page_changed", async () => {
  for (const [what, extra] of [["the person's own Back (from Chrome's cache)", { transitionQualifiers: ["forward_back"] }], ["any other commit", {}]]) {
    const { c, r } = await joining({ page: PLAIN });
    const first = await r.handle(started());
    const before = c.calls.length;
    c.tabsMap.set(THEIRS, "https://elsewhere.example/");
    c.listeners.committed({ tabId: THEIRS, frameId: 0, url: "https://elsewhere.example/", transitionType: "link", ...extra });
    await new Promise((go) => setImmediate(go));
    const now = c.calls.slice(before);
    assert.ok(!now.some((x) => x[0] === "tabs.goBack" || x[0] === "tabs.goForward" || (x[0] === "tabs.update" && x[2].url)), what + ": their tab isn't moved");
    assert.ok(!now.some((x) => x[0] === "tabs.remove" && [].concat(x[1]).includes(THEIRS)), what + ": nor closed");
    const run = r.runs.get("run_1");
    assert.equal(run.joined.has(THEIRS), false, what + ": given back");
    assert.equal(r.tabRun.has(THEIRS), false, what + ": forgotten");
    assert.ok(now.some((x) => x[0] === "tabs.ungroup" && x[1].includes(THEIRS)), what + ": out of the group");
    assert.ok(now.some((x) => x[0] === "bar.hide" && x[1] === THEIRS), what + ": its bar goes");
    assert.ok(now.some((x) => x[0] === "dnr" && !x[1].addRules), what + ": its fence goes");
    assert.equal(r.fenceIds.has(THEIRS), false, what);
    assert.ok(!run.steps.some((x) => x.op === "navigate"), what + ": the person's move is none of the run's steps");
    assert.equal(run.site, "shop.example", what + ": its site is still the run's page's");
    // The run's next step is never in their tab: they moved it, so nothing of that page is the run's.
    const after = c.calls.length;
    assert.equal((await r.handle(started({ id: "op2", op: "read" }))).error.code, "given_back", what + ": a read");
    assert.equal((await r.handle(started({ id: "op3", op: "act", read_id: first.page.read_id, act: { kind: "click", ref: "c1" } }))).error.code, "given_back", what + ": an act");
    assert.ok(!c.calls.slice(after).some((x) => x[0] === "page" || (ranInPage(x) && x[2] === THEIRS)), what + ": nothing runs in their tab");
  }
});

// The run's own `back` that Chrome takes off the sites in a joined tab is put right by that back, not given back.
// The security read: a worker restart left a joined tab fenced until the next run's first fence.
test("a runner made over rules a stopped worker left removes them all, and a fence added right after it survives", async () => {
  const c = fakeChrome();
  let rules = [{ id: 1 }, { id: 2 }, { id: 3 }, { id: 400 }];
  c.declarativeNetRequest.getSessionRules = async () => {
    await new Promise((go) => setTimeout(go, 10));
    return rules;
  };
  c.declarativeNetRequest.updateSessionRules = async (o) => {
    c.calls.push(["dnr", o]);
    rules = rules.filter((x) => !(o.removeRuleIds || []).includes(x.id)).concat((o.addRules || []).map((x) => ({ id: x.id })));
  };
  const r = runner(c);
  // A step at once, before the sweep has answered.
  const out = await r.handle(step());
  assert.ok(out.page, JSON.stringify(out));
  const added = c.calls.find((x) => x[0] === "dnr" && x[1].addRules)[1].addRules.map((x) => x.id);
  assert.deepEqual(rules.map((x) => x.id).sort((a, b) => a - b), [...added].sort((a, b) => a - b), "the leftovers are gone, the fresh fence stays");
  const sweep = c.calls.findIndex((x) => x[0] === "dnr" && !x[1].addRules);
  const fence = c.calls.findIndex((x) => x[0] === "dnr" && x[1].addRules);
  assert.ok(sweep >= 0 && sweep < fence, "the sweep before the fence");
  assert.deepEqual(c.calls[sweep][1].removeRuleIds.sort((a, b) => a - b), [1, 2, 3, 400]);
});

// The security read: only the run's own work goes on a joined tab's trail.
test("the panel hears a stopped run without its tabs at once", async () => {
  const seen = [];
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
  const r = new Runner({ chrome: c, sleep, paired: () => paired, onChange: (s) => seen.push(s) });
  await r.handle(step());
  await r.stop("run_1", false, "person");
  const last = seen.at(-1).find((x) => x.id === "run_1");
  assert.equal(last.stopped, true);
  assert.deepEqual(last.tabs, [], "no tabs listed for it");
  assert.equal(last.tab, null);
});

/** A joined tab, with the run's act on its sites done (the hub's link, say, during the act's own wait), at "/p2". */
async function joinedAfterClick() {
  const j = await joining({ page: PLAIN });
  const first = await j.r.handle(started());
  // The act's click commits its page while the act waits for it: the run's own work.
  j.c.setAct({ ok: true });
  const exec = j.c.scripting.executeScript;
  j.c.scripting.executeScript = async (o) => {
    const out = await exec(o);
    if (String(o.func || "").includes("TafsPage.act")) {
      j.c.tabsMap.set(THEIRS, "https://shop.example/p2");
      j.c.listeners.committed({ tabId: THEIRS, frameId: 0, url: "https://shop.example/p2", transitionType: "link", transitionQualifiers: [] });
    }
    return out;
  };
  const clicked = await j.r.handle(started({ id: "op_click", op: "act", read_id: first.page.read_id, act: { kind: "click", ref: "c1" } }));
  j.c.scripting.executeScript = exec;
  return { ...j, first, clicked };
}

test("the run's own act in a joined tab may take it to another page on its sites: the tab stays the run's, and that page is read", async () => {
  const { r, clicked } = await joinedAfterClick();
  assert.ok(clicked.page, JSON.stringify(clicked));
  assert.equal(r.runs.get("run_1").joined.has(THEIRS), true);
  assert.deepEqual(r.runs.get("run_1").trail.get(THEIRS), ["https://shop.example/", "https://shop.example/p2"]);
  assert.ok((await r.handle(started({ id: "op_r", op: "read" }))).page);
});

test("the taf's back in a joined tab is always no_back, and nothing moves, even after its own click", async () => {
  const { c, r, clicked } = await joinedAfterClick();
  const before = c.calls.length;
  assert.equal((await r.handle(started({ id: "op_b", op: "act", read_id: clicked.page.read_id, act: { kind: "back" } }))).error.code, "no_back");
  const now = c.calls.slice(before);
  assert.ok(!now.some((x) => x[0] === "tabs.goBack" || x[0] === "tabs.goForward" || (x[0] === "tabs.update" && x[2].url)), "the person's tab isn't moved");
  assert.ok(!now.some((x) => x[0] === "page"), "nothing read");
  assert.equal(c.tabsMap.get(THEIRS), "https://shop.example/p2");
  assert.equal(r.runs.get("run_1").joined.has(THEIRS), true, "still the run's");
});

// The harness's finding in real Chrome: the person's own Back to their earlier page, on the run's sites, was taken as the run's work.
for (const [what, move] of [
  ["their Back, on the run's sites", { url: "https://shop.example/theirs-before", transitionType: "link", transitionQualifiers: ["forward_back"] }],
  ["an address they typed", { url: "https://shop.example/typed", transitionType: "typed", transitionQualifiers: ["from_address_bar"] }],
  ["their own click (no act of the run's under way)", { url: "https://shop.example/their-click", transitionType: "link", transitionQualifiers: [] }],
  ["a reload", { url: "https://shop.example/", transitionType: "reload", transitionQualifiers: [] }],
]) {
  test(`the person's own move in a joined tab gives it back at once, unread: ${what}; the run's next read and act are given_back`, async () => {
    const { c, r } = await joining({ page: PLAIN });
    const first = await r.handle(started());
    const before = c.calls.length;
    c.tabsMap.set(THEIRS, move.url);
    c.listeners.committed({ tabId: THEIRS, frameId: 0, ...move });
    await new Promise((go) => setImmediate(go));
    const run = r.runs.get("run_1");
    assert.equal(run.joined.has(THEIRS), false, "given back");
    assert.equal(r.tabRun.has(THEIRS), false, "forgotten");
    assert.equal(r.fenceIds.has(THEIRS), false, "unfenced");
    assert.equal(run.trail.has(THEIRS), false, "never on the run's trail");
    assert.ok(c.calls.slice(before).some((x) => x[0] === "tabs.ungroup" && x[1].includes(THEIRS)), "out of the group");
    assert.ok(!c.calls.slice(before).some((x) => x[0] === "tabs.goBack" || x[0] === "tabs.goForward" || (x[0] === "tabs.update" && x[2].url)), "never moved");
    const after = c.calls.length;
    assert.equal((await r.handle(started({ id: "op2", op: "read" }))).error.code, "given_back");
    assert.equal((await r.handle(started({ id: "op3", op: "act", read_id: first.page.read_id, act: { kind: "fill", ref: "f1", value: "x" } }))).error.code, "given_back");
    assert.ok(!c.calls.slice(after).some((x) => x[0] === "page" || (ranInPage(x) && x[2] === THEIRS)), "nothing read, nothing acted");
    // An open goes on in a tab of the run's own.
    const opened = await r.handle(started({ id: "op4", url: "https://shop.example/other" }));
    assert.ok(opened.page, JSON.stringify(opened));
    assert.notEqual(r.runs.get("run_1").tab, THEIRS);
  });
}

test("the person's Back while the run's act waits for its page is still theirs: given back, and the act answers given_back", async () => {
  const { c, r, first } = await (async () => {
    const j = await joining({ page: PLAIN });
    return { ...j, first: await j.r.handle(started()) };
  })();
  const exec = c.scripting.executeScript;
  c.scripting.executeScript = async (o) => {
    const out = await exec(o);
    if (String(o.func || "").includes("TafsPage.act")) {
      // In the act's wait, the person presses Back to their earlier page, on the run's sites.
      c.tabsMap.set(THEIRS, "https://shop.example/theirs-before");
      c.listeners.committed({ tabId: THEIRS, frameId: 0, url: "https://shop.example/theirs-before", transitionType: "link", transitionQualifiers: ["forward_back"] });
    }
    return out;
  };
  const before = c.calls.length;
  const out = await r.handle(started({ id: "op2", op: "act", read_id: first.page.read_id, act: { kind: "click", ref: "c1" } }));
  assert.equal(out.error && out.error.code, "given_back");
  assert.equal(out.page, undefined, "nothing read");
  assert.equal(r.runs.get("run_1").joined.has(THEIRS), false, "given back");
  assert.ok(!c.calls.slice(before).some((x) => x[0] === "page" && x[1] === "read"));
  assert.equal(r.runs.get("run_1").trail.has(THEIRS), false);
});

test("a page the person goes to in a joined tab while the run is paused gives it back: never read after Resume", async () => {
  const { c, r } = await joining({ page: PLAIN });
  await r.handle(started());
  r.ownerInput(THEIRS);
  commit(c, THEIRS, "https://shop.example/their-own");
  r.resume("run_1");
  const before = c.calls.length;
  assert.equal((await r.handle(started({ id: "op2", op: "read" }))).error.code, "given_back");
  assert.ok(!c.calls.slice(before).some((x) => x[0] === "page" || (ranInPage(x) && x[2] === THEIRS)), "nothing read");
  assert.equal(r.runs.get("run_1").joined.has(THEIRS), false, "given back");
});

// The harness's finding: the person's Back to a page off the run's sites is a new request the fence blocks (Chrome's error page,
// net::ERR_BLOCKED_BY_CLIENT, nothing committed), so the tab stayed the run's. Since 0.2.17 the tab is given back on that error page
// and nothing is asked for again: a navigation the page starts by itself after a step looks the same, and must never be sent by the
// extension. Their own next try loads, as the tab is no longer fenced.
test("a navigation the fence blocks in a joined tab outside a step gives the tab back on Chrome's block page; nothing is asked again", async () => {
  for (const [what, shown] of [
    ["Chrome's error page at the blocked address", "https://elsewhere.example/account"],
    ["their tab still on the joined page", "https://shop.example/"],
  ]) {
    const { c, r } = await joining({ page: PLAIN });
    await r.handle(started());
    const rules = c.calls.find((x) => x[0] === "dnr" && x[1].addRules)[1].addRules.map((x) => x.id);
    const before = c.calls.length;
    c.tabsMap.set(THEIRS, shown);
    c.listeners.error({ tabId: THEIRS, frameId: 0, url: "https://elsewhere.example/account?leak=v", error: "net::ERR_BLOCKED_BY_CLIENT" });
    await new Promise((go) => setTimeout(go, 5));
    const now = c.calls.slice(before);
    assert.ok(now.some((x) => x[0] === "dnr" && !x[1].addRules && x[1].removeRuleIds.join() === fenceIdsFrom(rules).join()), what + ": unfenced");
    assert.ok(!now.some((x) => x[0] === "tabs.reload" || x[0] === "tabs.goBack" || x[0] === "tabs.goForward" || (x[0] === "tabs.update" && x[2].url)), what + ": the extension asks for nothing, and moves nothing");
    assert.equal(r.runs.get("run_1").joined.has(THEIRS), false, what + ": given back");
    assert.equal(r.tabRun.has(THEIRS), false, what);
    assert.ok(!r.runs.get("run_1").steps.some((x) => x.op === "navigate"), what + ": the person's move is none of the run's steps");
    assert.equal(r.runs.get("run_1").site, "shop.example", what + ": its site is still the run's page's");
    assert.equal((await r.handle(started({ id: "op2", op: "read" }))).error.code, "given_back", what);
    // Their own next try is theirs: nothing of the extension's answers it, and no rule holds the tab.
    const later = c.calls.length;
    c.tabsMap.set(THEIRS, "https://elsewhere.example/account");
    c.listeners.before({ tabId: THEIRS, frameId: 0, url: "https://elsewhere.example/account" });
    c.listeners.committed({ tabId: THEIRS, frameId: 0, url: "https://elsewhere.example/account", transitionType: "typed", transitionQualifiers: [] });
    await new Promise((go) => setTimeout(go, 5));
    assert.deepEqual(c.calls.slice(later), [], what + ": their tab is theirs");
    assert.equal(r.fenceIds.has(THEIRS), false, what + ": no rule holds it");
  }
});

test("an off-site navigation the fence blocks during the run's act in a joined tab: the act is site_not_listed, and the tab is given back unmoved", async () => {
  const { c, r } = await joining({ page: PLAIN });
  const first = await r.handle(started());
  const exec = c.scripting.executeScript;
  c.scripting.executeScript = async (o) => {
    const out = await exec(o);
    if (String(o.func || "").includes("TafsPage.act")) c.listeners.error({ tabId: THEIRS, frameId: 0, url: "https://elsewhere.example/form", error: "net::ERR_BLOCKED_BY_CLIENT" });
    return out;
  };
  const before = c.calls.length;
  const out = await r.handle(started({ id: "op2", op: "act", read_id: first.page.read_id, act: { kind: "click", ref: "c1" } }));
  assert.equal(out.error && out.error.code, "site_not_listed");
  await new Promise((go) => setTimeout(go, 5));
  const now = c.calls.slice(before);
  assert.ok(!now.some((x) => x[0] === "tabs.goBack" || x[0] === "tabs.goForward" || x[0] === "tabs.reload" || (x[0] === "tabs.update" && x[2].url)), "nothing moves it, nothing loads the blocked address");
  assert.equal(r.runs.get("run_1").joined.has(THEIRS), false, "given back");
  assert.ok(!now.some((x) => x[0] === "page" && x[1] === "read"), "nothing read");
});

// The harness's finding: an off-site click or submit in a joined tab made Chrome commit its own error page there, as a new entry.
test("an act the page says would leave the run's sites is refused before it fires, recorded, and the tab stays the run's where it was", async () => {
  const { c, r } = await joining({ page: PLAIN });
  const first = await r.handle(started());
  const seen = [];
  const exec = c.scripting.executeScript;
  c.scripting.executeScript = async (o) => {
    if (String(o.func || "").includes("TafsPage.act")) seen.push(o.args);
    return exec(o);
  };
  c.setAct({ ok: false, code: "site_not_listed", url: "https://elsewhere.example/ask" });
  const out = await r.handle(started({ id: "op2", op: "act", read_id: first.page.read_id, act: { kind: "click", ref: "c1" } }));
  assert.equal(out.error && out.error.code, "site_not_listed");
  assert.deepEqual(seen[0][2], { fence: { granted: ["shop.example"], allowed: [], sensitive: [...SENSITIVE.sites], labels: SENSITIVE.labels.source, never: [...NEVER_OPENED], local: [...LOCAL_SUFFIXES] } }, "the page is given what the run's fence lets through");
  assert.ok(r.runs.get("run_1").steps.some((x) => x.op === "navigate" && x.url === "https://elsewhere.example/ask" && x.error === "site_not_listed"), "recorded as a refused move");
  assert.equal(r.runs.get("run_1").joined.has(THEIRS), true, "still the run's");
  assert.equal(c.tabsMap.get(THEIRS), "https://shop.example/");
  assert.ok(!c.calls.some((x) => x[0] === "tabs.goBack" || (x[0] === "tabs.update" && x[1] === THEIRS && x[2].url)));
  // A tab of the run's own is left to the fence: the page isn't given the sites.
  c.setAct({ ok: true });
  await r.handle(started({ id: "op3", url: "https://shop.example/other" }));
  const own = await r.handle(started({ id: "op4", op: "read" }));
  await r.handle(started({ id: "op5", op: "act", read_id: own.page.read_id, act: { kind: "click", ref: "c1" } }));
  assert.equal(seen.at(-1)[2], null);
});

test("the page's own check: a link or a form's submit off the run's sites, by its resolved address; a javascript: link is a control", () => {
  // The rule page.js holds (headsTo, onSites), as sites.js reads a host: https, and the site itself or under it.
  const src = readFileSync(new URL("../src/page.js", import.meta.url), "utf8");
  for (const piece of ['function headsTo(el, a)', 'el.closest("a[href]")', 'el.hasAttribute("formaction") ? el.formAction : form.action', 'a.kind === "press" && a.value === "Enter"', 'u.protocol === "https:" || u.protocol === "http:" ? u : null', 'url.protocol !== "https:"', 'host === s || host.endsWith("." + s)', 'if (why) return { ok: false, code: why, url: bare(to.href) }']) {
    assert.ok(src.includes(piece), piece);
  }
  // It runs before any act touches the page: after the secret-field refusal, before the switch that clicks or submits.
  const check = src.indexOf("const to = headsTo(el, a)");
  assert.ok(check > src.indexOf('code: "secret_field" }') && check < src.indexOf("switch (a.kind)", check), "checked before the act");
});

// The run's own tabs, as the end-to-end harness found them: the person's own move in one (an address typed, their Back) was taken
// for the run's, and the run's next read read their page. Only the run's own work moves a tab of the run's; any other move makes
// it the person's, as a joined tab already was.
const pause0 = () => new Promise((done) => setTimeout(done, 0));
const reads = (c) => c.calls.filter((x) => x[0] === "page" && x[1] === "read").length;

for (const [what, move] of [
  ["an address they typed", { transitionType: "typed", transitionQualifiers: ["from_address_bar"] }],
  ["their Back", { transitionType: "link", transitionQualifiers: ["forward_back"] }],
  ["their Forward", { transitionType: "link", transitionQualifiers: ["forward_back", "from_address_bar"] }],
  ["a reload", { transitionType: "reload", transitionQualifiers: [] }],
]) {
  test(`${what} in the run's own tab, on its sites, gives the tab to the person: unmoved, unread, given_back, and the next open takes a fresh tab`, async () => {
    const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
    const r = runner(c);
    await r.handle(step());
    const tab = r.runs.get("run_1").tab;
    const before = c.calls.length;
    const read = reads(c);
    c.tabsMap.set(tab, "https://shop.example/account");
    c.listeners.committed({ tabId: tab, frameId: 0, url: "https://shop.example/account", ...move });
    await pause0();
    const now = c.calls.slice(before);
    assert.ok(!now.some((x) => (x[0] === "tabs.update" && x[2].url) || x[0] === "tabs.remove" || x[0] === "tabs.goBack" || x[0] === "tabs.reload"), "nothing moves or closes their tab");
    assert.ok(now.some((x) => x[0] === "tabs.ungroup" && [].concat(x[1]).includes(tab)), "it leaves the run's group");
    assert.ok(now.some((x) => x[0] === "dnr" && (x[1].removeRuleIds || []).length), "its fence goes");
    assert.equal(r.tabRun.has(tab), false, "the run forgets it");
    for (const [op, extra] of [["read", {}], ["act", { read_id: "rd_any", act: { kind: "click", ref: "c1" } }], ["hand", { read_id: "rd_any", hand: { words: "Sign in" } }]]) {
      assert.equal((await r.handle(step({ id: "op_" + op, op, ...extra }))).error.code, "given_back", op);
    }
    assert.equal(reads(c), read, "nothing of their page is read");
    assert.ok(!c.calls.slice(before).some((x) => x[0] === "page" && x[1] === "act start"), "nothing acts in it");
    // The run's next page: a fresh tab of its own, never theirs again.
    const opened = await r.handle(step({ id: "op_open", url: "https://shop.example/cart" }));
    assert.ok(opened.page, JSON.stringify(opened));
    const fresh = r.runs.get("run_1").tab;
    assert.notEqual(fresh, tab, "a tab of its own");
    assert.ok(c.calls.some((x) => x[0] === "tabs.create" && x[1].url === "about:blank"), "made fresh");
    assert.ok(!c.calls.some((x) => x[0] === "tabs.update" && x[1] === tab && x[2].url && c.calls.indexOf(x) > before), "their tab never loaded again");
    assert.equal(opened.page.url, "https://shop.example/cart");
  });
}

test("the person's click in the run's own tab pauses the run, and the page it opens makes the tab theirs", async () => {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
  const r = runner(c);
  await r.handle(step());
  const tab = r.runs.get("run_1").tab;
  r.ownerInput(tab);
  c.tabsMap.set(tab, "https://shop.example/orders");
  c.listeners.committed({ tabId: tab, frameId: 0, url: "https://shop.example/orders", transitionType: "link", transitionQualifiers: [] });
  await pause0();
  assert.equal(r.tabRun.has(tab), false, "theirs now");
  r.resume("run_1");
  assert.equal((await r.handle(step({ id: "op2", op: "read" }))).error.code, "given_back");
});

test("a commit the run didn't ask for in its own tab (a page's own redirect, a late one) is the person's too", async () => {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
  const r = runner(c);
  await r.handle(step());
  const tab = r.runs.get("run_1").tab;
  c.tabsMap.set(tab, "https://shop.example/elsewhere");
  c.listeners.committed({ tabId: tab, frameId: 0, url: "https://shop.example/elsewhere", transitionType: "link", transitionQualifiers: ["client_redirect"] });
  await pause0();
  assert.equal(r.tabRun.has(tab), false);
  assert.equal((await r.handle(step({ id: "op2", op: "read" }))).error.code, "given_back");
});

test("the run's own open, act and back in its own tab stay the run's: the redirect while it loads, the act's page, a back Chrome marks forward_back", async () => {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
  const r = runner(c);
  // The open's own redirect commits while it loads.
  const update = c.tabs.update;
  c.tabs.update = async (id, props) => {
    await update(id, props);
    if (props.url && props.url !== "about:blank") {
      const to = props.url.endsWith("/start") ? "https://shop.example/home" : props.url;
      c.tabsMap.set(id, to);
      c.listeners.committed({ tabId: id, frameId: 0, url: to, transitionType: "link", transitionQualifiers: to === props.url ? [] : ["server_redirect"] });
    }
  };
  const first = await r.handle(step({ url: "https://shop.example/start" }));
  assert.ok(first.page, JSON.stringify(first));
  const tab = r.runs.get("run_1").tab;
  assert.equal(r.tabRun.get(tab), "run_1", "still the run's after its redirect");
  // The act's click takes it to p2, committed while the act waits for its page.
  let clicked = false;
  const exec = c.scripting.executeScript;
  c.scripting.executeScript = async (o) => {
    const out = await exec(o);
    if (String(o.func || "").includes("TafsPage.act")) clicked = true;
    return out;
  };
  const tabsGet = c.tabs.get;
  c.tabs.get = async (id) => {
    if (id === tab && clicked) {
      clicked = false;
      c.tabsMap.set(tab, "https://shop.example/p2");
      c.listeners.committed({ tabId: tab, frameId: 0, url: "https://shop.example/p2", transitionType: "link", transitionQualifiers: [] });
    }
    return tabsGet(id);
  };
  const acted = await r.handle(step({ id: "op2", op: "act", read_id: first.page.read_id, act: { kind: "fill", ref: "f1", value: "a@b.example" } }));
  assert.ok(acted.page, JSON.stringify(acted));
  assert.equal(r.tabRun.get(tab), "run_1", "still the run's after its act");
  // Its back: Chrome's goBack commits with forward_back, while the back is the run's.
  c.tabs.goBack = async (id) => {
    c.calls.push(["tabs.goBack", id]);
    c.tabsMap.set(id, "https://shop.example/home");
    c.listeners.committed({ tabId: id, frameId: 0, url: "https://shop.example/home", transitionType: "link", transitionQualifiers: ["forward_back"] });
  };
  const back = await r.handle(step({ id: "op3", op: "act", read_id: acted.page.read_id, act: { kind: "back" } }));
  assert.ok(back.page, JSON.stringify(back));
  assert.equal(back.page.url, "https://shop.example/home");
  assert.equal(r.tabRun.get(tab), "run_1", "still the run's after its back");
});

test("a page the run's page opens (a pop-up) is the run's for its first page; the person's move there afterwards makes it theirs", async () => {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
  const r = runner(c);
  await r.handle(step());
  const tab = r.runs.get("run_1").tab;
  c.listeners.created({ sourceTabId: tab, tabId: 555, url: "https://shop.example/popup" });
  c.tabsMap.set(555, "https://shop.example/popup");
  c.listeners.committed({ tabId: 555, frameId: 0, url: "https://shop.example/popup", transitionType: "link", transitionQualifiers: [] });
  await pause0();
  assert.equal(r.tabRun.get(555), "run_1", "its first page is the run's");
  c.listeners.committed({ tabId: 555, frameId: 0, url: "https://shop.example/other", transitionType: "typed", transitionQualifiers: [] });
  await pause0();
  assert.equal(r.tabRun.has(555), false, "then the person's");
  assert.equal(r.tabRun.get(tab), "run_1", "the run's own tab is unchanged");
});

test("a navigation the fence blocks in the run's own tab outside a step gives the tab to them on Chrome's block page; nothing is asked again", async () => {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
  const r = runner(c);
  await r.handle(step());
  const tab = r.runs.get("run_1").tab;
  c.listeners.before({ tabId: tab, frameId: 0, url: "https://news.example/" });
  assert.ok(!c.calls.some((x) => x[0] === "tabs.update" && x[1] === tab && x[2].url === "about:blank"), "their tab is never sent to a blank page");
  const blockedAt = c.calls.length;
  c.listeners.error({ tabId: tab, frameId: 0, url: "https://news.example/", error: "net::ERR_BLOCKED_BY_CLIENT" });
  await pause0();
  await pause0();
  assert.equal(r.tabRun.has(tab), false, "theirs now");
  assert.ok(!r.state()[0].steps.some((x) => x.op === "navigate"), "the person's move is none of the run's steps");
  assert.equal(r.state()[0].site, "shop.example", "its site is still the run's page's");
  assert.equal(r.fenceIds.has(tab), false, "no fence rule left on their tab");
  assert.ok(c.calls.some((x) => x[0] === "dnr" && (x[1].removeRuleIds || []).length), "unfenced");
  assert.ok(!c.calls.slice(blockedAt).some((x) => x[0] === "tabs.reload" || x[0] === "tabs.goBack" || (x[0] === "tabs.update" && x[1] === tab && x[2].url)), "the extension asks for nothing again: their own next try loads");
  assert.ok(c.calls.some((x) => x[0] === "tabs.ungroup" && [].concat(x[1]).includes(tab)), "out of the run's group");
  assert.equal((await r.handle(step({ id: "op2", op: "read" }))).error.code, "given_back");
  const opened = await r.handle(step({ id: "op3", url: "https://shop.example/cart" }));
  assert.ok(opened.page, JSON.stringify(opened));
  assert.notEqual(r.runs.get("run_1").tab, tab, "the next open takes a fresh tab of its own");
});

test("a navigation the fence stops while the run's own act waits in its own tab is not the person's", async () => {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
  const r = runner(c);
  await r.handle(step());
  const tab = r.runs.get("run_1").tab;
  r.runs.get("run_1").stepping = tab;
  c.listeners.error({ tabId: tab, frameId: 0, url: "https://news.example/", error: "net::ERR_BLOCKED_BY_CLIENT" });
  await pause0();
  assert.equal(r.tabRun.get(tab), "run_1", "still the run's");
  assert.ok(!c.calls.some((x) => x[0] === "tabs.update" && x[2].url === "https://news.example/"), "nothing re-asked");
});

// Two tafs with one name under two grants (two connections, each a taf of its own): the second never closes, joins or works in the
// first's kept tabs or its "· done" group.
test("two tafs named Rae under two grants never share or close each other's kept tabs or groups", async () => {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
  c.tabGroups.get = async (id) => ({ id, windowId: 3 });
  const r = runner(c);
  await r.handle(step());
  const first = r.runs.get("run_1").tab;
  await r.stop("run_1");
  assert.deepEqual(Object.keys(await r.doneTabs()), ["grant:g_1"]);
  let made = 7;
  const group = c.tabs.group;
  c.tabs.group = async (o) => (o.groupId != null ? group(o) : (made++, group({ ...o, groupId: made })));
  const before = c.calls.length;
  await r.handle(step({ run: "run_2", id: "op2", grant: "g_2", sites_signed: await signedSites(["shop.example"], "g_2") }));
  const second = r.runs.get("run_2").tab;
  const now = c.calls.slice(before);
  assert.ok(!now.some((x) => x[0] === "tabs.remove"), "the first Rae's kept tab stays");
  assert.ok(!now.some((x) => x[0] === "tabs.group" && x[1].groupId === 7), "never put in the first Rae's group");
  assert.ok(!touched(c, first).some((x) => c.calls.indexOf(x) >= before), "nothing of the second Rae's touches the first's tab");
  assert.equal(r.tabRun.has(first), false, "the second never reads or acts in the first's tab");
  await r.stop("run_2");
  assert.deepEqual(Object.keys(await r.doneTabs()).sort(), ["grant:g_1", "grant:g_2"], "each kept under its own grant");
  // The first Rae again: its own kept tab closes, never the second's.
  const at = c.calls.length;
  await r.handle(step({ run: "run_3", id: "op3" }));
  const removed = c.calls.slice(at).filter((x) => x[0] === "tabs.remove").flatMap((x) => x[1]);
  assert.ok(removed.includes(first) && !removed.includes(second), JSON.stringify(removed));
});

test("a person's tab in another same-named taf's done group is never joined", async () => {
  const { c, r } = await joining();
  c.tabGroups.get = async (id) => ({ id, windowId: 3 });
  // The other Rae (grant g_2) finished a run whose done group (5) now holds the person's front tab.
  r.done = { "grant:g_2": { name: "Rae", tabs: [], window: null, group: 5 } };
  c.groupsMap.set(THEIRS, 5);
  const out = await r.handle(started());
  assert.ok(out.page, JSON.stringify(out));
  assert.equal(r.runs.get("run_1").joined.size, 0, "not joined");
  assert.notEqual(r.runs.get("run_1").tab, THEIRS, "a tab of its own");
});

test("an entry kept before 0.2.16 under the taf's name alone is never matched to a run, and unpairing still closes it", async () => {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
  c.tabGroups.get = async (id) => ({ id, windowId: 3 });
  c.tabsMap.set(77, "https://shop.example/old");
  const stored = { done: { Rae: { tabs: [{ id: 77, url: "https://shop.example/old" }], window: null, group: 9 } } };
  const doneStore = { get: async () => JSON.parse(JSON.stringify(stored.done)), set: async (d) => (stored.done = JSON.parse(JSON.stringify(d))) };
  const r = new Runner({ chrome: c, sleep, paired: () => paired, doneStore });
  await r.handle(step());
  assert.ok(!c.calls.some((x) => x[0] === "tabs.remove"), "its tab isn't the new run's to close");
  assert.ok(!c.calls.some((x) => x[0] === "tabs.group" && x[1].groupId === 9), "nor its group the new run's");
  assert.ok(c.calls.some((x) => x[0] === "tabGroups.update" && x[1] === 9 && x[2].title === "tafs · Rae · done"), "still greyed under its name");
  await r.stopAll();
  assert.ok(c.calls.some((x) => x[0] === "tabs.remove" && [].concat(x[1]).includes(77)), "unpairing closes it");
});

// A page of the run's sites that, a moment after the taf's act returned, sends itself off the run's sites (a delayed redirect, a
// refresh) with what the taf filled: the fence stops it, and the extension never asks for that address itself, in a tab of the run's
// own or in one of the person's it joined (0.2.17). Nothing tells such a navigation from the person's own, so neither is re-asked.
const FIELD = { read_id: "rd_f", url: "https://shop.example/", title: "t", text: "", fields: [{ ref: "f1", label: "Name", kind: "text" }], controls: [{ ref: "c1", label: "Save" }], links: [] };
for (const where of ["own", "joined"]) {
  test(`a navigation the page starts by itself off the run's sites after a step (${where} tab) is never sent by the extension`, async () => {
    const { c, r, tab, go } = where === "own"
      ? await (async () => {
          const c = fakeChrome({ ownerWindow: OWNER_WINDOW, page: FIELD });
          const r = runner(c);
          const first = await r.handle(step());
          return { c, r, tab: r.runs.get("run_1").tab, go: (extra) => step(extra), first };
        })()
      : await (async () => {
          const { c, r } = await joining({ page: FIELD });
          await r.handle(started());
          return { c, r, tab: THEIRS, go: (extra) => started(extra) };
        })();
    const read = await r.handle(go({ id: "op_r", op: "read" }));
    const filled = await r.handle(go({ id: "op_f", op: "act", read_id: read.page.read_id, act: { kind: "fill", ref: "f1", value: "secret-value-1" } }));
    assert.ok(filled.page, JSON.stringify(filled));
    // The step has returned. The page now leaves by itself, carrying the value.
    const leak = "https://elsewhere.example/?leak=secret-value-1";
    const before = c.calls.length;
    c.listeners.before({ tabId: tab, frameId: 0, url: leak });
    c.listeners.error({ tabId: tab, frameId: 0, url: leak, error: "net::ERR_BLOCKED_BY_CLIENT" });
    await new Promise((done) => setTimeout(done, 5));
    const now = c.calls.slice(before);
    assert.ok(!now.some((x) => x[0] === "tabs.reload" || x[0] === "tabs.goBack" || x[0] === "tabs.goForward" || (x[0] === "tabs.update" && x[2].url)), "the extension asks for nothing");
    assert.ok(!JSON.stringify(now).includes("secret-value-1"), "the value is in nothing the extension did");
    assert.equal(r.tabRun.has(tab), false, "the tab is given back");
    assert.equal(r.fenceIds.has(tab), false, "unfenced");
    assert.equal((await r.handle(go({ id: "op_n", op: "read" }))).error.code, "given_back");
  });
}

// A move the person made is never one of the run's steps, and the run's site is only ever its own page's: the panel's card and
// site line once showed the person's destination ("Stopped a move to other.test"), from their own blocked address or Back (0.2.17).
for (const where of ["own", "joined"]) {
  for (const [what, events] of [
    ["an address they typed off the sites", (c, tab) => {
      c.listeners.before({ tabId: tab, frameId: 0, url: "https://other.example/typed" });
      c.listeners.error({ tabId: tab, frameId: 0, url: "https://other.example/typed", error: "net::ERR_BLOCKED_BY_CLIENT" });
    }],
    ["their Back off the sites", (c, tab) => {
      c.listeners.before({ tabId: tab, frameId: 0, url: "https://other.example/before" });
      c.listeners.error({ tabId: tab, frameId: 0, url: "https://other.example/before", error: "net::ERR_BLOCKED_BY_CLIENT" });
    }],
    ["their Back to a page Chrome kept off the sites", (c, tab) => {
      c.tabsMap.set(tab, "https://other.example/kept");
      c.listeners.committed({ tabId: tab, frameId: 0, url: "https://other.example/kept", transitionType: "link", transitionQualifiers: ["forward_back"] });
    }],
  ]) {
    test(`${what}, in a ${where} tab, is no step of the run's, and the run's site stays its own page's`, async () => {
      const { c, r, tab } = where === "own"
        ? await (async () => {
            const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
            const r = runner(c);
            await r.handle(step());
            return { c, r, tab: r.runs.get("run_1").tab };
          })()
        : await (async () => {
            const { c, r } = await joining();
            await r.handle(started());
            return { c, r, tab: THEIRS };
          })();
      const steps = r.runs.get("run_1").steps.length;
      events(c, tab);
      await new Promise((done) => setTimeout(done, 5));
      const run = r.state()[0];
      assert.equal(run.steps.length, steps, "nothing recorded: " + JSON.stringify(run.steps.slice(steps)));
      assert.equal(run.site, "shop.example");
      assert.equal(r.tabRun.has(tab), false, "the tab is theirs");
    });
  }
}

test("a block during the run's own act is the run's refused move, recorded once, and the run's site stays its own page's", async () => {
  for (const where of ["own", "joined"]) {
    const { c, r, tab } = where === "own"
      ? await (async () => {
          const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
          const r = runner(c);
          await r.handle(step());
          return { c, r, tab: r.runs.get("run_1").tab };
        })()
      : await (async () => {
          const { c, r } = await joining();
          await r.handle(started());
          return { c, r, tab: THEIRS };
        })();
    const run = r.runs.get("run_1");
    run.stepping = tab;
    c.listeners.before({ tabId: tab, frameId: 0, url: "https://other.example/x" });
    c.listeners.error({ tabId: tab, frameId: 0, url: "https://other.example/x", error: "net::ERR_BLOCKED_BY_CLIENT" });
    await new Promise((done) => setTimeout(done, 5));
    const refused = run.steps.filter((x) => x.op === "navigate" && x.error === "site_not_listed");
    assert.deepEqual(refused.map((x) => x.url), ["https://other.example/x"], where + ": recorded once");
    assert.equal(run.site, "shop.example", where + ": the run's site isn't where its page tried to go");
  }
});

// Chrome's fence says what refuseURL says (0.2.19). A small evaluator of declarativeNetRequest's rules, as Chrome applies them to a
// tab's page request: the matching rule of highest priority wins, an allow beating a block at the same priority; requestDomains
// match a domain and its subdomains; a regexFilter is tried on the whole address, case-insensitively (RE2's syntax here is a subset
// JavaScript reads the same). The address is the one Chrome sends: lower case, its host in xn-- form.
function dnrAllows(rules, tabId, raw) {
  let u;
  try {
    u = new URL(raw);
  } catch {
    return false;
  }
  const host = u.hostname.toLowerCase().replace(/\.$/, "");
  const matches = (r) => {
    const c = r.condition;
    if (c.tabIds && !c.tabIds.includes(tabId)) return false;
    if (c.resourceTypes && !c.resourceTypes.includes("main_frame")) return false;
    if (c.requestDomains && !c.requestDomains.some((d) => host === d || host.endsWith("." + d))) return false;
    if (c.regexFilter && !new RegExp(c.regexFilter, "i").test(u.href)) return false;
    return true;
  };
  const hit = rules.filter(matches).sort((a, b) => b.priority - a.priority || (a.action.type === "allow" ? -1 : 1))[0];
  return !!hit && hit.action.type === "allow";
}

const FENCE_GRANTS = [
  [["example.com"], []],
  [["example.com"], ["bank.example.com"]],
  [["google.com"], []],
  [["github.io", "co.uk", "shop.test"], []],
  [["paypal.com"], []],
  [["paypal.com"], ["paypal.com"]],
  [["127.0.0.1", "localhost", "printer.local", "shop.test"], []],
  [["shop.test"], ["bank.other.test", "paypal.com"]],
  [["shop.test", "bank.shop.test"], []],
];
const FENCE_URLS = [
  "https://example.com/", "https://www.example.com/a?b=c#d", "https://bank.example.com/?from=link", "https://sub.bank.example.com/",
  "https://my-bank.example.com/", "https://BANK.Example.com/", "https://ebank.example.com/", "https://banking.example.com/",
  "https://bankrupt.example.com/", "https://banks.example.com/", "https://bank.example.com:8443/x", "https://gov.example.com/",
  "https://example.com.bank.evil/", "https://bank.example.com.other.test/", "http://example.com/", "http://bank.example.com/",
  "https://accounts.google.com/", "https://www.google.com/", "https://mail.google.com/", "https://pay.google.com/",
  "https://x.github.io/", "https://foo.co.uk/", "https://shop.test/", "https://bank.shop.test/", "https://www.bank.shop.test/",
  "https://bänk.shop.test/", "https://xn--bnk-sna.shop.test/", "https://www.paypal.com/", "https://paypal.com/",
  "https://notpaypal.com/", "https://127.0.0.1/", "https://localhost/", "https://printer.local/", "https://bank.other.test/",
  "https://shop.test.:443/", "https://shop.test/a/bank/b",
  // Full addresses: user info, ports, a sensitive word only in the path, query or fragment, case, a trailing dot.
  "https://x@bank.shop.test/", "https://x:y@shop.test/", "https://shop.test@bank.test/", "https://x@shop.test/", "https://x@example.com/",
  "https://bank.shop.test:443/", "https://shop.test/?q=bank.", "https://shop.test/#bank.", "https://shop.test/bank.", "https://shop.test/?next=https://bank.test/",
  "https://SHOP.TEST/Bank", "https://BANK.SHOP.TEST/", "https://bank.shop.test./", "https://x@bank.example.com/", "https://u:p@accounts.google.com/",
];

test("Chrome's fence and refuseURL give the same answer for every address, grant and allowance", async () => {
  for (const [sites, allowed] of FENCE_GRANTS) {
    const c = fakeChrome();
    const r = runner(c);
    await r.ruleIds(5); // the runner's first fence ids, as Runner.fence takes them
    await r.fence({ sites, allowed }, 5);
    const rules = c.calls.filter((x) => x[0] === "dnr" && x[1].addRules).at(-1)[1].addRules;
    for (const url of FENCE_URLS) {
      const js = refuseURL(url, sites, allowed) === null;
      assert.equal(dnrAllows(rules, 5, url), js, `${url} under ${JSON.stringify(sites)} allowing ${JSON.stringify(allowed)}: refuseURL says ${js ? "go" : JSON.stringify(refuseURL(url, sites, allowed))}`);
    }
  }
});

test("the fence's label pattern and sensitive() agree on every host", async () => {
  const { sensitive } = await import("../src/sites.js");
  const hosts = ["bank.test", "my-bank.example.com", "bank-of.example", "x.bank", "banking", "ebank.com", "bankrupt.com", "banks.com", "a.gov.uk", "gov",
    "governor.com", "irs.gov", "first-irs.example", "creditunion.example", "credit-union.example", "credit--union.example", "sante.fr", "santé.fr",
    "xn--sant-epa.fr", "health.example", "healthy.example", "x.medicaid", "news.test", "example.com", "b-a-n-k.com", "bank1.com"];
  const res = SENSITIVE_LABELS_URLS.map((p) => new RegExp(p, "i"));
  const re = { test: (u) => res.some((x) => x.test(u)) };
  for (const h of hosts) {
    const host = new URL("https://" + h + "/").hostname;
    const labelled = SENSITIVE.labels.test(host);
    assert.equal(re.test("https://" + host + "?q=1"), labelled, host + " with a query");
    assert.equal(re.test("https://" + host + "/x"), labelled, host);
    assert.equal(re.test("https://" + host + ":8443/"), labelled, host + " with a port");
    assert.equal(re.test("https://" + host), labelled, host + " with no path");
    if (labelled) assert.equal(sensitive(host), true);
  }
});

test("a sensitive-site pattern Chrome can't hold fails the fence: the run's tab is closed before it loads", async () => {
  const c = fakeChrome();
  c.declarativeNetRequest.isRegexSupported = async () => ({ isSupported: false, reason: "memoryLimitExceeded" });
  const r = runner(c);
  const out = await r.handle(step());
  assert.ok(out.error, JSON.stringify(out));
  assert.ok(!c.calls.some((x) => x[0] === "tabs.update" && x[2].url === "https://shop.example/"), "nothing of the run's loads");
  assert.ok(!c.calls.some((x) => x[0] === "dnr" && x[1].addRules), "no fence half made");
  assert.equal(r.runs.get("run_1") && r.runs.get("run_1").tabs.size || 0, 0, "no tab left the run's, unfenced");
});

test("a fence holds FENCE_RULES ids a tab, all of them go with the tab and at unpairing, and an allowance adds its rule", async () => {
  const c = fakeChrome();
  const r = runner(c);
  await r.handle(step({ sensitive_allowed: undefined }));
  const tab = r.runs.get("run_1").tab;
  const first = c.calls.find((x) => x[0] === "dnr" && x[1].addRules)[1];
  assert.deepEqual(first.removeRuleIds, fenceIdsFrom(first.addRules.map((x) => x.id)), "every id of the tab's is cleared first");
  assert.equal(first.addRules.length, FENCE_RULES - 1, "no allowance, no rule for it");
  await r.fence({ sites: ["example.com"], allowed: ["bank.example.com"] }, tab);
  const second = c.calls.filter((x) => x[0] === "dnr" && x[1].addRules).at(-1)[1];
  assert.equal(second.addRules.length, FENCE_RULES);
  assert.deepEqual(second.addRules.find((x) => x.priority === 4).condition.requestDomains, ["bank.example.com"]);
  await r.stopAll();
  const removed = c.calls.filter((x) => x[0] === "dnr" && !x[1].addRules).flatMap((x) => x[1].removeRuleIds);
  for (const id of fenceIdsFrom(first.addRules.map((x) => x.id))) assert.ok(removed.includes(id), "rule " + id + " removed");
});

test("a taf's act that heads for a sensitive site in its own tab is site_blocked, answered and recorded so", async () => {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW, page: { read_id: "rd_b", url: "https://shop.example/", title: "t", text: "", fields: [], controls: [], links: [{ ref: "l1", label: "Bank", href: "https://bank.shop.example/?from=link" }] } });
  const r = runner(c);
  const first = await r.handle(step());
  const tab = r.runs.get("run_1").tab;
  const exec = c.scripting.executeScript;
  c.scripting.executeScript = async (o) => {
    const out = await exec(o);
    // The click starts its navigation: the backstop sees it, and the fence stops it.
    if (String(o.func || "").includes("TafsPage.act")) c.listeners.before({ tabId: tab, frameId: 0, url: "https://bank.shop.example/?from=link" });
    return out;
  };
  const out = await r.handle(step({ id: "op2", op: "act", read_id: first.page.read_id, act: { kind: "click", ref: "l1" } }));
  assert.equal(out.error && out.error.code, "site_blocked", JSON.stringify(out));
  const refused = r.runs.get("run_1").steps.filter((x) => x.op === "navigate");
  assert.deepEqual(refused.map((x) => [x.url, x.error]), [["https://bank.shop.example/", "site_blocked"]]);
});


// The signs on the page (signs.js, 0.2.23): the glow on the tab a run works in while it works, the pointer with its words.
function watchSigns(c) {
  const seen = [];
  const exec = c.scripting.executeScript;
  c.scripting.executeScript = async (o) => {
    const src = String(o.func || "");
    if (src.includes("TafsSigns.working")) seen.push(["working", o.target.tabId, ...(o.args || [])]);
    if (src.includes("TafsSigns.off")) seen.push(["off", o.target.tabId]);
    if (src.includes("TafsPage.aim")) seen.push(["aim", o.target.tabId, ...o.args]);
    return exec(o);
  };
  return seen;
}

test("the glow is on the tab the run works in while it works: a pause takes it away, Resume brings it back, the end takes it for good", async () => {
  const c = fakeChrome({ ownerWindow: OWNER_WINDOW });
  const seen = watchSigns(c);
  const r = runner(c);
  await r.handle(step());
  const tab = [...c.tabsMap.keys()][0];
  await r.queueBars();
  assert.ok(seen.some((x) => x[0] === "working" && x[1] === tab), "working: the glow");
  seen.length = 0;
  r.pause("run_1");
  await r.queueBars();
  assert.deepEqual(seen.filter((x) => x[1] === tab).map((x) => x[0]), ["off"], "paused: every sign goes, none comes back");
  seen.length = 0;
  r.resume("run_1");
  await r.queueBars();
  assert.ok(seen.some((x) => x[0] === "working" && x[1] === tab), "resumed: the glow again");
  seen.length = 0;
  await r.stop("run_1");
  await r.queueBars();
  assert.ok(seen.some((x) => x[0] === "off" && x[1] === tab), "stopped: gone");
  assert.ok(!seen.some((x) => x[0] === "working"), "and never back");
});

test("a tab the person takes back loses every sign at once, and none is drawn there again", async () => {
  const { c, r } = await joining({ page: PLAIN });
  const seen = watchSigns(c);
  await r.handle(started());
  await r.queueBars();
  assert.ok(seen.some((x) => x[0] === "working" && x[1] === THEIRS), "their tab glows while the run works in it");
  r.ownerInput(THEIRS);
  seen.length = 0;
  commit(c, THEIRS, "https://shop.example/their-own");
  await new Promise((go) => setTimeout(go, 5));
  r.resume("run_1");
  await r.queueBars();
  await r.handle(started({ id: "op2", op: "read" }));
  await r.queueBars();
  assert.ok(seen.some((x) => x[0] === "off" && x[1] === THEIRS), "taken back: the signs go");
  assert.ok(!seen.some((x) => (x[0] === "working" || x[0] === "aim") && x[1] === THEIRS), "and are never drawn there again");
});

test("the pointer's words are the step's own: Reading on a read, Clicking on a click, none for a kind it has no word for", async () => {
  const c = fakeChrome({ page: PLAIN });
  const seen = watchSigns(c);
  const r = new Runner({ chrome: c, sleep: (ms) => (ms >= 20_000 ? new Promise(() => {}) : Promise.resolve()), paired: () => paired, say: (key) => (key.startsWith("pointer_") ? "<" + key + ">" : "") });
  const opened = await r.handle(step());
  assert.ok(seen.some((x) => x[0] === "working" && x[2] === "<pointer_read>"), "a read says it reads");
  await r.handle(step({ id: "op2", op: "act", read_id: opened.page.read_id, act: { kind: "click", ref: "c1" } }));
  assert.deepEqual(seen.find((x) => x[0] === "aim").slice(2), ["c1", opened.page.read_id, "<pointer_click>"]);
  assert.equal(r.pointerWords("fill"), "<pointer_fill>");
  assert.equal(r.pointerWords("back"), "", "back names no element and has no word");
});
