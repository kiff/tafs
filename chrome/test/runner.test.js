import { test } from "node:test";
import assert from "node:assert/strict";
import { Runner } from "../src/runner.js";

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
    scripting: {
      executeScript: async (o) => {
        calls.push(["executeScript", o.files ? o.files.join(",") : o.world || "isolated", o.target.tabId]);
        if (o.files || o.world === "MAIN") return [{}];
        const src = String(o.func);
        if (src.includes("TafsPage.read")) return [{ result: pageFor(tabs.get(o.target.tabId)) }];
        if (src.includes("TafsPage.act")) return [{ result: actAnswer }];
        return [{}];
      },
    },
  };
  return c;
}

const sleep = async () => {};

function step(extra) {
  return { id: "op", run: "run_1", taf: "Rae", sites: ["shop.example"], op: "open", url: "https://shop.example/", ...extra };
}

test("a run opens in a background window of its own, grouped and named, and reads the page", async () => {
  const c = fakeChrome();
  const r = new Runner({ chrome: c, sleep });
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
  const r = new Runner({ chrome: c, sleep });
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
  const r = new Runner({ chrome: c, sleep });
  assert.equal((await r.handle(step({ url: "https://evil.example/" }))).error.code, "site_not_listed");
  assert.equal((await r.handle(step({ url: "https://www.paypal.com/", sites: ["paypal.com"], run: "run_2" }))).error.code, "site_blocked");
  assert.ok(!c.calls.some((x) => x[0] === "windows.create"));
});

test("a navigation in a run's tab that leaves its sites is stopped, and a pop-up off them is closed", async () => {
  const c = fakeChrome();
  const r = new Runner({ chrome: c, sleep });
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
  const r = new Runner({ chrome: c, sleep });
  const opened = await r.handle(step());
  assert.equal((await r.handle(step({ op: "act", read_id: "rd_unknown", act: { kind: "click", ref: "c1" } }))).error.code, "page_changed");
  const before = c.calls.filter((x) => x[0] === "executeScript").length;
  assert.equal((await r.handle(step({ op: "act", read_id: opened.page.read_id, act: { kind: "click", ref: "c2" } }))).error.code, "payment_step");
  assert.equal(c.calls.filter((x) => x[0] === "executeScript").length, before, "nothing ran in the page");
  assert.equal((await r.handle(step({ op: "act", read_id: opened.page.read_id, act: { kind: "fill", ref: "f1", sealed: "sec_1" } }))).error.code, "secret_field", "no sealed values in this build");
});

test("three failed acts in a row pause the run; Resume goes on", async () => {
  const c = fakeChrome({ page: { read_id: "rd_x", url: "https://shop.example/", fields: [], controls: [{ ref: "c1", label: "Save" }], links: [] } });
  const r = new Runner({ chrome: c, sleep });
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
  const r = new Runner({ chrome: c, sleep });
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
  const r = new Runner({ chrome: c, sleep });
  const out = await r.handle(step());
  assert.equal(out.error.code, "needs_site_permission");
  assert.deepEqual(r.state()[0].asks, ["shop.example"]);
  assert.ok(!c.calls.some((x) => x[0] === "executeScript" && x[1] === "src/mask.js,src/page.js"), "nothing read without it");
});
