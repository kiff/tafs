// Runs checked steps in a run's own tabs (docs/chrome.md, "Runs"). Each run opens a background tab in the owner's window (a window of
// its own, unfocused, when there is none) in one tab group, "tafs · <taf>", that never comes to the front. The runner touches only tabs it opened: it never lists the owner's tabs,
// and nothing outside the run's group is ever read or sent. A navigation off the run's sites is stopped in the tab itself,
// whatever started it. The owner's own input in a run's tab pauses the run.

import { refuseURL, bareURL } from "./sites.js";

/**
 * The run's own blank page: where its tab opens and where a refused move is sent. It loads nothing, so the guard lets
 * it be; refusing it sent the tab to about:blank again, and again, which also cancelled the page the step was opening.
 */
const isBlank = (url) => String(url || "").trim().toLowerCase() === "about:blank";
import { refuse } from "./guard.js";
import { parseHandle } from "./vault.js";
import { installDialogGuard } from "./dialogs.js";
import { randomId } from "./crypto.js";
import { checkRelease, checkAllowances, checkSites, Nonces } from "./protocol.js";

const LOAD_TIMEOUT_MS = 30_000;
const SETTLE_MS = 600;
const STEP_TIMEOUT_MS = 20_000;
const FAILURES_TO_PAUSE = 3;
// A run nobody has sent a step to for this long has been dropped (a lost close, a call that timed out): its tabs close (and its window, when it has its own). One
// the owner paused isn't idle, it's waiting for the owner.
const RUN_IDLE_MS = 5 * 60_000;
const PAUSE_CODES = { failures: "paused_failures", no_progress: "paused_no_progress", owner: "paused_by_owner" };

/** A page as the repeat check compares it: everything the taf sees, without the read's own id. */
function pageKey(page) {
  if (!page) return null;
  const { read_id, sealed, ...rest } = page; // eslint-disable-line no-unused-vars
  return rest;
}

export class Runner {
  /**
   * `chrome` is the extension API (a fake in tests); `onChange(state)` hears every change the side panel shows.
   * `sleep` waits, so tests can run without real time.
   */
  constructor({ chrome, onChange = () => {}, sleep = (ms) => new Promise((r) => setTimeout(r, ms)), vault = null, paired = () => null, now = () => Date.now(), releaseStore = null, shots = null }) {
    this.chrome = chrome;
    this.paired = paired; // {chromeId, phoneKey} of the pairing, to check the phone's own allowances
    this.onChange = onChange;
    this.sleep = sleep;
    this.shots = shots; // the picture kept of each act (shot.js); null: none is taken
    this.vault = vault; // sealed sign-ins (vault.js); none, and a sealed fill is refused
    this.now = now;
    this.releases = new Nonces(now, releaseStore); // a release opens one fill, once, even across a worker restart
    this.runs = new Map(); // run id → {id, taf, sites, allowed, windowId, ownWindow, groupId, tabs: Set, tab, token, pages: Map, paused, stopped, failures, steps}
    this.tabRun = new Map(); // tab id → run id
    this.fenceIds = new Map(); // tab id → the first of its three fence rule ids
    this.nextFence = 1;
    this.tails = new Map(); // run id → the step now running, so a run's steps never overlap
    this.listen();
  }

  listen() {
    const c = this.chrome;
    // A navigation in a run's tab that leaves its sites is stopped before it loads.
    c.webNavigation.onBeforeNavigate.addListener((d) => {
      if (d.frameId !== 0) return;
      const run = this.runOfTab(d.tabId);
      if (!run || isBlank(d.url)) return;
      if (refuseURL(d.url, run.sites, run.allowed)) {
        c.tabs.update(d.tabId, { url: "about:blank" });
        this.record(run, { op: "navigate", url: bareURL(d.url), error: "site_not_listed" });
      }
    });
    // The dialog guard goes in as each document of a run's tab starts. A page Chrome restores from its back-forward cache
    // commits with no request, so neither the network fence nor onBeforeNavigate saw it: a committed page off the run's
    // sites is sent to a blank page, and recorded.
    c.webNavigation.onCommitted.addListener((d) => {
      if (d.frameId !== 0) return;
      const run = this.runOfTab(d.tabId);
      if (!run) return;
      if (!isBlank(d.url) && refuseURL(d.url, run.sites, run.allowed)) {
        c.tabs.update(d.tabId, { url: "about:blank" });
        this.record(run, { op: "navigate", url: bareURL(d.url), error: "site_not_listed" });
        return;
      }
      this.guardDialogs(d.tabId, run).catch(() => {});
    });
    // A tab or window a run's page opens joins the run only on its sites; anything else is closed.
    c.webNavigation.onCreatedNavigationTarget.addListener((d) => {
      const run = this.runOfTab(d.sourceTabId);
      if (!run) return;
      if (refuseURL(d.url, run.sites, run.allowed)) {
        c.tabs.remove(d.tabId);
        this.record(run, { op: "open", url: bareURL(d.url), error: "site_not_listed" });
        return;
      }
      run.tabs.add(d.tabId);
      this.tabRun.set(d.tabId, run.id);
      this.fence(run, d.tabId).catch(() => {});
      // The owner may have ungrouped or closed the run's group: the pop-up stays the run's, grouped or not.
      if (run.groupId != null) Promise.resolve(c.tabs.group({ groupId: run.groupId, tabIds: [d.tabId] })).catch(() => {});
    });
    c.tabs.onRemoved.addListener((tabId) => {
      const run = this.runOfTab(tabId);
      if (!run) return;
      run.tabs.delete(tabId);
      this.tabRun.delete(tabId);
      if (run.tab === tabId) run.tab = [...run.tabs][0] ?? null;
    });
  }

  /**
   * Chrome's own rules for a run's tab (declarativeNetRequest session rules): a page or frame request to any host
   * outside the run's sites, or over plain http, is blocked before it's sent, whatever started it (a link, a redirect, a
   * form). onBeforeNavigate stays as the record of a refused move, not the guard.
   */
  /**
   * The ids of a tab's three fence rules. A rule id must be a small positive integer (an int32), and a tab id can be large
   * (`tabId * 4` overflowed it, and the whole run failed as browser_unavailable), so each fenced tab takes the next three
   * from a counter of this runner's own. Nothing but this runner adds session rules, and no run outlives it, so the first
   * fence clears whatever an earlier service worker left.
   */
  async ruleIds(tabId) {
    const dnr = this.chrome.declarativeNetRequest;
    // One promise that every fence awaits, so a second run starting beside the first never has its rules removed by the clear.
    this.clearing ||= (async () => {
      if (!dnr.getSessionRules) return;
      const left = await dnr.getSessionRules().catch(() => []);
      if (left.length) await dnr.updateSessionRules({ removeRuleIds: left.map((r) => r.id) }).catch(() => {});
    })();
    await this.clearing;
    if (!this.fenceIds.has(tabId)) {
      this.fenceIds.set(tabId, this.nextFence);
      this.nextFence += 3;
    }
    const base = this.fenceIds.get(tabId);
    return [base, base + 1, base + 2];
  }

  async fence(run, tabId) {
    const dnr = this.chrome.declarativeNetRequest;
    if (!dnr) return;
    const [one, two, three] = await this.ruleIds(tabId);
    const frames = ["main_frame", "sub_frame"];
    const domains = [...new Set([...run.sites, ...run.allowed])];
    await dnr.updateSessionRules({
      removeRuleIds: [one, two, three],
      addRules: [
        { id: one, priority: 1, action: { type: "block" }, condition: { tabIds: [tabId], resourceTypes: frames } },
        { id: two, priority: 2, action: { type: "allow" }, condition: { tabIds: [tabId], resourceTypes: frames, requestDomains: domains } },
        { id: three, priority: 3, action: { type: "block" }, condition: { tabIds: [tabId], resourceTypes: frames, regexFilter: "^http://" } },
      ],
    });
  }

  async unfence(tabId) {
    const dnr = this.chrome.declarativeNetRequest;
    if (!dnr) return;
    const base = this.fenceIds.get(tabId);
    if (base == null) return;
    this.fenceIds.delete(tabId);
    await dnr.updateSessionRules({ removeRuleIds: [base, base + 1, base + 2] }).catch(() => {});
  }

  runOfTab(tabId) {
    const id = this.tabRun.get(tabId);
    return id ? this.runs.get(id) : null;
  }

  get active() {
    return [...this.runs.values()].some((r) => !r.stopped);
  }

  state() {
    return [...this.runs.values()].map((r) => ({
      id: r.id,
      taf: r.taf,
      site: r.site,
      paused: r.paused,
      stopped: r.stopped,
      steps: r.steps.slice(-50),
    }));
  }

  record(run, entry) {
    run.steps.push({ at: Date.now(), ...entry });
    if (entry.url) {
      try {
        run.site = new URL(entry.url).hostname;
      } catch {
        // a step without a readable URL keeps the last site
      }
    }
    this.onChange(this.state());
  }

  /** The owner touched a run's tab: that run pauses until Resume. */
  ownerInput(tabId) {
    const run = this.runOfTab(tabId);
    if (!run || run.paused || run.stopped) return;
    run.paused = "owner";
    this.record(run, { op: "pause", by: "owner" });
  }

  pause(runId) {
    const run = this.runs.get(runId);
    if (run && !run.stopped) {
      run.paused = "owner";
      this.record(run, { op: "pause", by: "owner" });
    }
  }

  resume(runId) {
    const run = this.runs.get(runId);
    if (run && !run.stopped) {
      run.paused = null;
      run.failures = 0;
      run.last = null;
      this.record(run, { op: "resume" });
    }
  }

  async stop(runId) {
    const run = this.runs.get(runId);
    if (!run || run.stopped) return;
    run.stopped = true;
    this.record(run, { op: "stop" });
    await this.closeRun(run);
  }

  async stopAll() {
    for (const id of [...this.runs.keys()]) await this.stop(id);
  }

  async closeRun(run) {
    for (const t of run.tabs) {
      this.tabRun.delete(t);
      await this.unfence(t);
    }
    // A window of the run's own goes with it; in the owner's window, only the run's own tabs close, never theirs.
    // The run's tabs always close, wherever they are now (the owner may have dragged one out of the run's window); a window of
    // the run's own goes after them, and the owner's window never does.
    if (run.tabs.size) await this.chrome.tabs.remove([...run.tabs]).catch(() => {});
    if (run.ownWindow && run.windowId != null) await this.chrome.windows.remove(run.windowId).catch(() => {});
    run.tabs.clear();
    run.tab = null;
  }

  /**
   * Runs one checked step and answers `{page}` (the tab read back) or `{error: {code, detail?}}`. The step was already
   * verified as planque's (protocol.checkStep); this holds the site and the act rules again.
   */
  handle(step) {
    // One step at a time in a run, as a person works one tab: two acts never overlap (a double click, or a read in the
    // middle of an act). The next waits for the one running; Stop and Pause don't wait.
    const prior = this.tails.get(step.run) || Promise.resolve();
    const mine = prior.then(() => this.handleOne(step));
    const tail = mine.catch(() => {});
    this.tails.set(step.run, tail);
    tail.then(() => {
      if (this.tails.get(step.run) === tail) this.tails.delete(step.run);
    });
    return mine;
  }

  async handleOne(step) {
    const out = await this.handleStep(step);
    const run = this.runs.get(step.run);
    if (run) run.at = this.now();
    return out;
  }

  /** Closes the windows of runs nobody sent a step to for RUN_IDLE_MS, as Stop does. Called by the service worker's alarm. */
  async sweep() {
    for (const run of [...this.runs.values()]) {
      if (run.stopped || run.paused || this.tails.has(run.id)) continue;
      if (this.now() - run.at > RUN_IDLE_MS) {
        run.stopped = true;
        this.record(run, { op: "stop", by: "idle" });
        await this.closeRun(run);
      }
    }
  }

  async handleStep(step) {
    let run = this.runs.get(step.run);
    if (run && !(Array.isArray(step.sites) && step.sites.every((s) => run.sites.includes(s)))) {
      return { error: { code: "bad_step", detail: "a run's sites never grow" } };
    }
    if (!run) {
      // The grant's sites, as the paired phone signed them when the owner approved the grant: never planque's word alone.
      if (!(await checkSites(step, this.paired()))) return { error: { code: "bad_step", detail: "the sites aren't the phone's" } };
      run = {
        id: step.run,
        taf: String(step.taf || "").slice(0, 60),
        sites: step.sites,
        // A sensitive site opens only on the paired phone's own signed allowance for this grant, never planque's word.
        allowed: await checkAllowances(step.sensitive_allowed, { ...(this.paired() || {}), grant: step.grant }),
        windowId: null,
        ownWindow: false,
        groupId: null,
        tabs: new Set(),
        tab: null,
        token: randomId(),
        pages: new Map(),
        paused: null,
        stopped: false,
        failures: 0,
        last: null, // the last act, the read it was planned from and the page it answered with
        at: this.now(), // when its last step began or ended
        steps: [],
        site: "",
      };
      this.runs.set(run.id, run);
    }
    if (run.stopped) return { error: { code: "stopped" } };
    if (run.paused) return { error: { code: PAUSE_CODES[run.paused] || "paused_by_owner" } };
    const out = await this.dispatch(run, step).catch((e) => ({ error: { code: "browser_unavailable", detail: String(e && e.message || e).slice(0, 200) } }));
    this.record(run, { op: step.op, kind: step.act && step.act.kind, url: out.page ? out.page.url : step.url ? bareURL(step.url) : undefined, error: out.error && out.error.code });
    if (out.error && step.op === "act") {
      run.failures += 1;
      if (run.failures >= FAILURES_TO_PAUSE) {
        run.paused = "failures";
        this.record(run, { op: "pause", by: "failures" });
      }
    } else if (!out.error) {
      run.failures = 0;
    }
    if (step.op === "act" && !out.error && out.page) this.noProgress(run, step, out.page);
    return out;
  }

  /**
   * The same act, on the same page, that answers with the same page twice in a row has done nothing, and a third would
   * too: the run pauses with what it read, and the owner decides. A different act, a different page or a different
   * answer starts the count again. The ids a read makes up and the sealed handles don't count as a change.
   */
  noProgress(run, step, after) {
    const before = run.pages.get(step.read_id);
    const key = JSON.stringify({ act: { kind: step.act.kind, ref: step.act.ref, value: step.act.value, sealed: step.act.sealed }, before: pageKey(before), after: pageKey(after) });
    if (run.last === key) {
      run.paused = "no_progress";
      this.record(run, { op: "pause", by: "no_progress" });
    }
    run.last = key;
  }

  async dispatch(run, step) {
    switch (step.op) {
      case "open":
        return this.open(run, step.url);
      case "read":
        if (step.offset != null && !(Number.isSafeInteger(step.offset) && step.offset >= 0)) return { error: { code: "bad_step", detail: "offset" } };
        return this.read(run, step.offset);
      case "act":
        return this.act(run, step);
      case "close":
        run.stopped = true;
        await this.closeRun(run);
        return { page: null };
      default:
        return { error: { code: "bad_step" } };
    }
  }

  async open(run, url) {
    const refused = refuseURL(url, run.sites, run.allowed);
    if (refused) return { error: { code: refused.code } };
    const c = this.chrome;
    if (run.tab == null) {
      // A tab of its own in the window the owner is using, in the background: their active tab and their other tabs are
      // never touched, and the run's group sits beside them. With no such window, a window of its own, not focused.
      const made = await this.runTab();
      // Stop or close landed while the tab was being made: nothing of the run's is left open, least of all among the owner's tabs.
      if (run.stopped) {
        await this.discard(made);
        return { error: { code: "stopped" } };
      }
      const { windowId, tabId, own } = made;
      run.windowId = windowId;
      run.ownWindow = own;
      run.tab = tabId;
      run.tabs.add(tabId);
      this.tabRun.set(tabId, run.id);
      try {
        // Fenced before it loads anything of the run's.
        await this.fence(run, tabId);
        run.groupId = await c.tabs.group({ tabIds: [tabId], createProperties: { windowId } });
        await c.tabGroups.update(run.groupId, { title: "tafs · " + (run.taf || "taf"), color: "orange", collapsed: false });
        if (run.stopped) {
          // Stopped while it was fenced and grouped: its close ran before these tabs were the run's.
          await this.closeRun(run);
          return { error: { code: "stopped" } };
        }
      } catch (e) {
        // A window that could not be set up is not left open, blank and unfenced, on the owner's screen.
        await this.stop(run.id).catch(() => {});
        throw e;
      }
    }
    // In a window of its own the run's tab is the one in front; in the owner's window it stays in the background.
    await c.tabs.update(run.tab, run.ownWindow ? { url, active: true } : { url });
    await this.loaded(run.tab);
    return this.read(run);
  }

  /**
   * Where a run's first tab goes: a new background tab in the normal window the owner last used, or, when there is none (or
   * it's a private window), a window of the run's own, not focused. Only the tab made here is the run's.
   */
  async runTab() {
    const c = this.chrome;
    let win = null;
    try {
      win = await c.windows.getLastFocused({ windowTypes: ["normal"] });
    } catch {
      win = null;
    }
    if (win && win.id != null && !win.incognito && win.type !== "popup") {
      try {
        const tab = await c.tabs.create({ windowId: win.id, url: "about:blank", active: false });
        return { windowId: win.id, tabId: tab.id, own: false };
      } catch {
        // The window closed between the two calls: a window of its own, as when there was none.
      }
    }
    const own = await c.windows.create({ url: "about:blank", focused: false, type: "normal" });
    return { windowId: own.id, tabId: own.tabs[0].id, own: true };
  }

  /** Closes what runTab made, for a run that was stopped before it took it: the tab, or the window of the run's own. */
  async discard({ windowId, tabId, own }) {
    if (own) await this.chrome.windows.remove(windowId).catch(() => {});
    else await this.chrome.tabs.remove([tabId]).catch(() => {});
  }

  async loaded(tabId) {
    const c = this.chrome;
    const started = Date.now();
    for (;;) {
      const tab = await c.tabs.get(tabId);
      if (tab.status === "complete") break;
      if (Date.now() - started > LOAD_TIMEOUT_MS) break;
      await this.sleep(200);
    }
    await this.sleep(SETTLE_MS);
  }

  async guardDialogs(tabId, run) {
    await this.chrome.scripting.executeScript({ target: { tabId }, world: "MAIN", injectImmediately: true, func: installDialogGuard, args: [run.token] });
  }

  async inject(tabId, run) {
    const c = this.chrome;
    await this.guardDialogs(tabId, run).catch(() => {});
    await c.scripting.executeScript({ target: { tabId }, files: ["src/mask.js", "src/page.js"] });
    await c.scripting.executeScript({ target: { tabId }, func: (token) => globalThis.TafsPage.listen(token), args: [run.token] });
  }

  async inTab(tabId, func, args) {
    const c = this.chrome;
    const call = c.scripting.executeScript({ target: { tabId }, func, args });
    const timeout = this.sleep(STEP_TIMEOUT_MS).then(() => {
      throw Object.assign(new Error("the page didn't answer"), { code: "page_unresponsive" });
    });
    const [res] = await Promise.race([call, timeout]);
    return res && res.result;
  }

  async read(run, offset = 0) {
    if (run.tab == null) return { error: { code: "no_page" } };
    const tab = await this.chrome.tabs.get(run.tab);
    const refused = refuseURL(tab.url || "", run.sites, run.allowed);
    if (refused) return { error: { code: refused.code } };
    await this.inject(run.tab, run);
    const page = await this.inTab(run.tab, (o) => globalThis.TafsPage.read(o), [offset]);
    if (!page) return { error: { code: "page_unresponsive" } };
    // The handles of sign-ins saved for this exact origin, never their values: a taf names one to ask for a sealed fill.
    if (this.vault) {
      const handles = await this.vault.handlesFor(new URL(tab.url).origin);
      if (handles.length) page.sealed = handles;
    }
    run.pages.set(page.read_id, page);
    if (run.pages.size > 20) run.pages.delete(run.pages.keys().next().value);
    return { page };
  }

  /**
   * A sealed fill (docs/chrome.md, "Sealed values"): only with the paired phone's own release of this handle, for this
   * step, field and exact origin, unexpired and never used before. The value goes from the vault into the field and nowhere
   * else, then the page is read back with the field masked.
   */
  async fillSealed(run, step, page) {
    const pairing = this.paired();
    if (!this.vault || !pairing || !pairing.phoneKey) return { error: { code: "secret_field" } };
    const named = parseHandle(step.act.sealed);
    const part = named && named.part;
    const field = (page.fields || []).find((f) => f.ref === step.act.ref);
    if (!named || !field || (part === "password" ? field.kind !== "password" : !["text", "email"].includes(field.kind))) {
      return { error: { code: "secret_field" } };
    }
    const tab = await this.chrome.tabs.get(run.tab);
    const origin = new URL(tab.url).origin;
    const released = await checkRelease(step.release, { phoneKey: pairing.phoneKey, chromeId: pairing.chromeId, step, origin, now: this.now() });
    if (!released || !(await this.releases.take(step.release.nonce))) return { error: { code: "release_missing" } };
    const value = await this.vault.open(step.act.sealed, origin);
    if (value == null) return { error: { code: "sealed_unknown" } };
    const done = await this.inTab(run.tab, (ref, id, o, v, p) => globalThis.TafsPage.fillSealed(ref, id, o, v, p), [step.act.ref, step.read_id, origin, value, part]);
    if (!done || !done.ok) return { error: { code: (done && done.code) || "page_unresponsive" } };
    await this.loaded(run.tab);
    return this.read(run);
  }

  /**
   * The picture kept of an act, taken just before it, with the page's secret fields covered (shot.js). The act waits only for
   * the picture to be taken; the encoding and the upload go on after it. Nothing here can stop the act.
   */
  async pictureOf(run, step, page) {
    if (!this.shots) return;
    try {
      const got = await this.shots.capture({ tabId: run.tab, windowId: run.windowId, page });
      if (got && got.png) this.shots.send(got.png, step.id).catch(() => {});
    } catch {
      // A picture that couldn't be taken is no reason to hold an act.
    }
  }

  async act(run, step) {
    if (run.tab == null) return { error: { code: "no_page" } };
    const page = run.pages.get(step.read_id);
    if (!page) return { error: { code: "page_changed" } };
    const refused = refuse(page, step.act);
    if (refused) return { error: { code: refused.code } };
    if (step.act.sealed) return this.fillSealed(run, step, page);
    await this.pictureOf(run, step, page);
    const done = await this.inTab(run.tab, (a, id) => globalThis.TafsPage.act(a, id), [step.act, step.read_id]);
    if (!done || !done.ok) return { error: { code: (done && done.code) || "page_unresponsive" } };
    // A click is never proof of its effect: the page is read back.
    await this.loaded(run.tab);
    return this.read(run);
  }
}
