// One end-to-end setup: the fixture sites, the fake planque, the test build of the extension in Chromium (Playwright), and the
// pairing done as the owner does it, by typing the phone's code in the side panel. A test file starts one in `before` and
// closes it in `after`; its tests then send steps with `h.planque.sendStep(...)` and look at the tabs with Playwright.

import { createRequire } from "node:module";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { buildTestExtension } from "./build.mjs";
import { FakeCloud, FakePlanque } from "./planque.mjs";
import { FixtureSites } from "./sites.mjs";

const require = createRequire(import.meta.url);

/** Playwright, or null where it isn't installed (the tests then skip); in CI a missing one is a failure. */
export function loadPlaywright() {
  try {
    return require("playwright");
  } catch {
    if (process.env.CI) throw new Error("Playwright is needed for the end-to-end tests in CI");
    return null;
  }
}

export const playwright = loadPlaywright();

/** The sites of the one connection the phone approved for these tests. */
export const SITES = ["shop.test", "news.test"];

/** Waits until `check()` is truthy, looking every 50 ms; throws `what` after `timeout`. */
export async function until(check, what, timeout = 10_000) {
  const end = Date.now() + timeout;
  for (;;) {
    const got = await check();
    if (got) return got;
    if (Date.now() > end) throw new Error("timed out waiting for " + what);
    await new Promise((r) => setTimeout(r, 50));
  }
}

/**
 * `cloud`: the fake tafs cloud and KIFF answer the extension too (planque.mjs `FakeCloud`), so the side panel can send to the
 * taf and a run can join the person's tab. Without it, both names fail to resolve, and the extension has no Coach.
 */
export async function start({ pair = true, cloud = false, args = [], bfcache = false, lang = "en-US" } = {}) {
  const h = { dialogs: [], cleanup: [] };
  h.sites = await new FixtureSites().start();
  h.cleanup.push(() => h.sites.stop());
  // KIFF and tafs cloud on the fixture sites' server: their names, and the HTTPS port, mapped to it.
  const mapped = [];
  if (cloud) {
    h.cloud = new FakeCloud().on(h.sites);
    for (const host of [FakeCloud.KIFF, FakeCloud.CLOUD]) mapped.push(`MAP ${host} 127.0.0.1:${h.sites.port}`);
  }
  h.planque = await new FakePlanque({ sites: SITES }).start();
  h.cleanup.push(() => h.planque.stop());
  h.dir = buildTestExtension({ api: h.planque.api, relay: h.planque.relayURL, planqueKey: h.planque.planqueKeys.spki });
  h.cleanup.push(() => rmSync(h.dir, { recursive: true, force: true }));
  h.profile = mkdtempSync(join(tmpdir(), "tafs-e2e-profile-"));
  h.cleanup.push(() => rmSync(h.profile, { recursive: true, force: true }));
  // The full Chromium (channel "chromium") in its new headless mode: the headless shell loads no extension.
  h.context = await playwright.chromium.launchPersistentContext(h.profile, {
    headless: true,
    channel: "chromium",
    ignoreHTTPSErrors: true,
    // On Linux Chromium takes its interface language, which chrome.i18n follows, from the environment as well as from --lang.
    env: { ...process.env, LANGUAGE: lang.replace("-", "_"), LANG: lang.replace("-", "_") + ".UTF-8" },
    // Playwright turns Chrome's back/forward cache off (`--disable-back-forward-cache`); `bfcache` leaves it on, as people have it.
    ...(bfcache ? { ignoreDefaultArgs: ["--disable-back-forward-cache"] } : {}),
    args: [
      "--disable-extensions-except=" + h.dir,
      "--load-extension=" + h.dir,
      // The fixture sites are on this computer, and nothing else is reached: every other name fails to resolve.
      "--host-resolver-rules=" + ["MAP *.test 127.0.0.1", "MAP google.com 127.0.0.1", "MAP *.google.com 127.0.0.1", "MAP *.github.io 127.0.0.1", ...mapped, "MAP * ~NOTFOUND", "EXCLUDE 127.0.0.1"].join(", "),
      "--ignore-certificate-errors",
      // Straight to 127.0.0.1, whatever proxy the computer has set.
      "--no-proxy-server",
      // The extension's words in English whatever the computer's language (or in `lang`): the panel's lines are checked word for word.
      "--lang=" + lang,
      ...args,
    ],
  });
  h.cleanup.unshift(() => h.context.close());
  // A dialog that reaches Chromium is accepted here, so a page's confirm() the extension failed to answer would read as yes.
  // `holdDialogs`: a test that has to see a dialog, then answer it itself, has it held open in `held` instead.
  h.held = [];
  const watch = (p) => p.on("dialog", (d) => {
    h.dialogs.push({ type: d.type(), message: d.message(), url: p.url() });
    if (h.holdDialogs) h.held.push(d);
    else d.accept().catch(() => {});
  });
  h.context.pages().forEach(watch);
  h.context.on("page", watch);
  const worker = h.context.serviceWorkers()[0] || (await h.context.waitForEvent("serviceworker", { timeout: 15_000 }));
  h.extensionId = new URL(worker.url()).host;
  await openPanel(h);
  if (pair) await pairNow(h);
  h.close = async () => {
    for (const f of h.cleanup) await Promise.resolve().then(f).catch(() => {});
  };
  return h;
}

/**
 * The side panel, opened as the owner opens it (a click, which `chrome.sidePanel.open` needs), and a way to run code in it.
 * The service worker answers only the extension's own pages outside a tab, so the panel is the one place its messages
 * (pair, poll, pause, resume, a hand-over's answer) come from. Playwright doesn't list a side panel among a context's pages,
 * so the panel is reached through Chromium's DevTools protocol instead.
 */
async function openPanel(h) {
  const tab = h.context.pages()[0] || (await h.context.newPage());
  await tab.goto(`chrome-extension://${h.extensionId}/sidepanel/index.html`);
  await tab.evaluate(() => {
    const b = document.createElement("button");
    b.id = "e2e-open-panel";
    b.textContent = "Open the side panel";
    b.style.cssText = "position:fixed;inset:0;z-index:2147483647";
    b.onclick = async () => chrome.sidePanel.open({ windowId: (await chrome.windows.getCurrent()).id });
    document.body.append(b);
  });
  await tab.click("#e2e-open-panel");
  const cdp = await h.context.newCDPSession(tab);
  const target = await until(async () => (await cdp.send("Target.getTargets")).targetInfos.find((t) => t.type === "page" && t.url.endsWith("/sidepanel/index.html") && !t.attached), "the side panel");
  const { sessionId } = await cdp.send("Target.attachToTarget", { targetId: target.targetId, flatten: false });
  const waiting = new Map();
  let n = 0;
  cdp.on("Target.receivedMessageFromTarget", (e) => {
    const m = JSON.parse(e.message);
    if (waiting.has(m.id)) {
      waiting.get(m.id)(m);
      waiting.delete(m.id);
    }
  });
  const call = (method, params) => new Promise((resolve) => {
    n += 1;
    waiting.set(n, resolve);
    cdp.send("Target.sendMessageToTarget", { sessionId, message: JSON.stringify({ id: n, method, params }) }).catch(() => {});
  });
  /** Runs `expression` in the side panel and resolves with its value (a promise is awaited). */
  h.inPanel = async (expression) => {
    const r = await call("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
    if (r.error || (r.result && r.result.exceptionDetails)) throw new Error("in the panel: " + JSON.stringify(r.error || r.result.exceptionDetails));
    return r.result.result.value;
  };
  /** Sends `message` to the service worker from the side panel, as the panel's own buttons do, and resolves with its answer. */
  h.fromPanel = (message) => h.inPanel(`chrome.runtime.sendMessage(${JSON.stringify(message)})`);
  // The tab that opened the panel goes back to blank: it's the owner's tab, beside which a run's tab opens.
  await tab.goto("about:blank");
  // Ready once panel.js has taken the form's submit (it's a module: it runs after the page has its elements).
  await until(() => h.inPanel("typeof document.getElementById('pairForm')?.onsubmit === 'function'"), "the panel's pairing form");
}

/** Pairs by the code, typed in the panel's form and sent with its button, and waits for the relay's welcome. */
export async function pairNow(h) {
  const welcomed = h.planque.connected(20_000);
  await h.inPanel(`(() => {
    document.getElementById("code").value = ${JSON.stringify(h.planque.code)};
    document.getElementById("pairForm").requestSubmit();
    return true;
  })()`);
  await welcomed;
}

/**
 * Runs `func(arg)` in the extension's service worker (Playwright evaluates there as in a page) and resolves with its value: how a
 * test reads what only the worker can, such as Chrome's session rules. The worker Chromium runs now, should it have restarted.
 */
export async function inWorker(h, func, arg) {
  const worker = await until(() => h.context.serviceWorkers().find((w) => w.url().startsWith(`chrome-extension://${h.extensionId}/`)), "the service worker");
  return worker.evaluate(func, arg);
}

/** How many fence rules a run's tab holds here (runner.js `fence`): every one but the allowance's, as no grant here allows a sensitive site. */
export { FENCE_RULES } from "../src/runner.js";
import { FENCE_RULES as ALL_RULES } from "../src/runner.js";
export const RULES_PER_TAB = ALL_RULES - 1;

/** Chrome's declarativeNetRequest session rules, as the service worker reads them: the fences of the runs' tabs. */
export function sessionRules(h) {
  return inWorker(h, () => chrome.declarativeNetRequest.getSessionRules());
}

/** The session rules scoped to tab `tabId`. */
export async function rulesOf(h, tabId) {
  return (await sessionRules(h)).filter((r) => (r.condition.tabIds || []).includes(tabId));
}

/** The extension's state as the panel reads it (sw.js `publish`): its runs, each with its tab ids and its steps. */
export function extensionState(h) {
  return h.inPanel("chrome.storage.session.get('state').then((x) => x.state || null)");
}

/** The run `run` as the extension last published it, or undefined. */
export async function runState(h, run) {
  const s = await extensionState(h);
  return s && s.runs.find((r) => r.id === run);
}

/** Chrome's own view of tab `tabId` (its URL, group, whether it's active), or null once it's closed. */
export function tabInfo(h, tabId) {
  return inWorker(h, (id) => chrome.tabs.get(id).then((t) => ({ id: t.id, url: t.url, groupId: t.groupId, active: t.active, windowId: t.windowId }), () => null), tabId);
}

/** The tab Playwright sees at `url` (exactly), or undefined. */
export function tabAt(h, url) {
  return h.context.pages().find((p) => p.url() === url);
}

/** The run ids a test uses, each new: a run's sites and its tabs are its own. */
let runs = 0;
export function newRun(name = "run") {
  runs += 1;
  return `run_${name}_${process.pid}_${runs}`;
}

/** The ref of the field (or control, or link) labelled `label` in a read page. */
export function ref(page, label, kind = "fields") {
  const x = (page[kind] || []).find((f) => f.label === label);
  if (!x) throw new Error(`no ${kind} labelled ${label} in ${JSON.stringify(page[kind])}`);
  return x.ref;
}

/**
 * Fills Chrome's limit on session rules from the service worker, with rules no tab names (ids from `first`), so the next fence the
 * extension adds is refused by Chrome as it would be at that limit; the extension's code is as it ships. Gives how many were added.
 * `unfillSessionRules` takes them away.
 */
export function fillSessionRules(h, first = 1_000_000) {
  return inWorker(h, async (first) => {
    const dnr = chrome.declarativeNetRequest;
    const room = dnr.MAX_NUMBER_OF_SESSION_RULES - (await dnr.getSessionRules()).length;
    const rules = Array.from({ length: room }, (_, i) => ({ id: first + i, priority: 1, action: { type: "block" }, condition: { requestDomains: ["filler" + i + ".invalid"], resourceTypes: ["main_frame"] } }));
    for (let i = 0; i < rules.length; i += 500) await dnr.updateSessionRules({ addRules: rules.slice(i, i + 500) });
    return { room, total: (await dnr.getSessionRules()).length, max: dnr.MAX_NUMBER_OF_SESSION_RULES };
  }, first);
}

export function unfillSessionRules(h, first = 1_000_000) {
  return inWorker(h, async (first) => {
    const dnr = chrome.declarativeNetRequest;
    await dnr.updateSessionRules({ removeRuleIds: (await dnr.getSessionRules()).map((r) => r.id).filter((id) => id >= first) });
  }, first);
}
