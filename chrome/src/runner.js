// Runs checked steps in a run's own tabs (docs/chrome.md, "Runs"). Each run gets a background window with one tab group,
// "tafs · <taf>", that never comes to the front. The runner touches only tabs it opened: it never lists the owner's tabs,
// and nothing outside the run's group is ever read or sent. A navigation off the run's sites is stopped in the tab itself,
// whatever started it. The owner's own input in a run's tab pauses the run.

import { refuseURL, bareURL } from "./sites.js";
import { refuse } from "./guard.js";
import { installDialogGuard } from "./dialogs.js";
import { randomId } from "./crypto.js";
import { checkRelease, checkAllowances, Nonces } from "./protocol.js";

const LOAD_TIMEOUT_MS = 30_000;
const SETTLE_MS = 600;
const STEP_TIMEOUT_MS = 20_000;
const FAILURES_TO_PAUSE = 3;

export class Runner {
  /**
   * `chrome` is the extension API (a fake in tests); `onChange(state)` hears every change the side panel shows.
   * `sleep` waits, so tests can run without real time.
   */
  constructor({ chrome, onChange = () => {}, sleep = (ms) => new Promise((r) => setTimeout(r, ms)), vault = null, paired = () => null, now = () => Date.now(), releaseStore = null }) {
    this.chrome = chrome;
    this.paired = paired; // {chromeId, phoneKey} of the pairing, to check the phone's own allowances
    this.onChange = onChange;
    this.sleep = sleep;
    this.vault = vault; // sealed sign-ins (vault.js); none, and a sealed fill is refused
    this.now = now;
    this.releases = new Nonces(now, releaseStore); // a release opens one fill, once, even across a worker restart
    this.runs = new Map(); // run id → {id, taf, sites, allowed, windowId, groupId, tabs: Set, tab, token, pages: Map, paused, stopped, failures, steps}
    this.tabRun = new Map(); // tab id → run id
    this.listen();
  }

  listen() {
    const c = this.chrome;
    // A navigation in a run's tab that leaves its sites is stopped before it loads.
    c.webNavigation.onBeforeNavigate.addListener((d) => {
      if (d.frameId !== 0) return;
      const run = this.runOfTab(d.tabId);
      if (!run) return;
      if (refuseURL(d.url, run.sites, run.allowed)) {
        c.tabs.update(d.tabId, { url: "about:blank" });
        this.record(run, { op: "navigate", url: bareURL(d.url), error: "site_not_listed" });
      }
    });
    // The dialog guard goes in as each document of a run's tab starts.
    c.webNavigation.onCommitted.addListener((d) => {
      if (d.frameId !== 0) return;
      const run = this.runOfTab(d.tabId);
      if (run) this.guardDialogs(d.tabId, run).catch(() => {});
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
      if (run.groupId != null) c.tabs.group({ groupId: run.groupId, tabIds: [d.tabId] });
    });
    c.tabs.onRemoved.addListener((tabId) => {
      const run = this.runOfTab(tabId);
      if (!run) return;
      run.tabs.delete(tabId);
      this.tabRun.delete(tabId);
      if (run.tab === tabId) run.tab = [...run.tabs][0] ?? null;
    });
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
      asks: r.asks.slice(),
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
    for (const t of run.tabs) this.tabRun.delete(t);
    if (run.windowId != null) await this.chrome.windows.remove(run.windowId).catch(() => {});
    run.tabs.clear();
    run.tab = null;
  }

  /**
   * Runs one checked step and answers `{page}` (the tab read back) or `{error: {code, detail?}}`. The step was already
   * verified as planque's (protocol.checkStep); this holds the site and the act rules again.
   */
  async handle(step) {
    let run = this.runs.get(step.run);
    if (!run) {
      run = {
        id: step.run,
        taf: String(step.taf || "").slice(0, 60),
        sites: step.sites,
        // A sensitive site opens only on the paired phone's own signed allowance for this grant, never planque's word.
        allowed: await checkAllowances(step.sensitive_allowed, { ...(this.paired() || {}), grant: step.grant }),
        windowId: null,
        groupId: null,
        tabs: new Set(),
        tab: null,
        token: randomId(),
        pages: new Map(),
        paused: null,
        stopped: false,
        failures: 0,
        steps: [],
        site: "",
        asks: [], // sites waiting on the owner's yes in Chrome
      };
      this.runs.set(run.id, run);
    }
    if (run.stopped) return { error: { code: "stopped" } };
    if (run.paused) return { error: { code: run.paused === "failures" ? "paused_failures" : "paused_by_owner" } };
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
    return out;
  }

  async dispatch(run, step) {
    switch (step.op) {
      case "open":
        return this.open(run, step.url);
      case "read":
        return this.read(run);
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
      // A window of its own, not focused: the owner's window stays in front.
      const win = await c.windows.create({ url: "about:blank", focused: false, type: "normal" });
      run.windowId = win.id;
      const tabId = win.tabs[0].id;
      run.tab = tabId;
      run.tabs.add(tabId);
      this.tabRun.set(tabId, run.id);
      run.groupId = await c.tabs.group({ tabIds: [tabId], createProperties: { windowId: win.id } });
      await c.tabGroups.update(run.groupId, { title: "tafs · " + (run.taf || "taf"), color: "orange", collapsed: false });
    }
    await c.tabs.update(run.tab, { url, active: true });
    await this.loaded(run.tab);
    return this.read(run);
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

  /** Whether Chrome lets the extension into `url`'s site: the owner allows each site once, from the side panel. */
  async permitted(run, url) {
    const host = new URL(url).hostname;
    const site = run.sites.find((s) => host === s || host.endsWith("." + s)) || host;
    const ok = await this.chrome.permissions.contains({ origins: ["https://" + host + "/*"] });
    if (!ok && !run.asks.includes(site)) {
      run.asks.push(site);
      this.record(run, { op: "ask", site });
    }
    if (ok) run.asks = run.asks.filter((s) => s !== site);
    return ok;
  }

  async read(run) {
    if (run.tab == null) return { error: { code: "no_page" } };
    const tab = await this.chrome.tabs.get(run.tab);
    const refused = refuseURL(tab.url || "", run.sites, run.allowed);
    if (refused) return { error: { code: refused.code } };
    if (!(await this.permitted(run, tab.url))) return { error: { code: "needs_site_permission" } };
    await this.inject(run.tab, run);
    const page = await this.inTab(run.tab, () => globalThis.TafsPage.read(), []);
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
    const [, part] = String(step.act.sealed).split(":");
    const field = (page.fields || []).find((f) => f.ref === step.act.ref);
    if (!field || (part === "password" ? field.kind !== "password" : part === "username" ? !["text", "email"].includes(field.kind) : true)) {
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

  async act(run, step) {
    if (run.tab == null) return { error: { code: "no_page" } };
    const page = run.pages.get(step.read_id);
    if (!page) return { error: { code: "page_changed" } };
    const refused = refuse(page, step.act);
    if (refused) return { error: { code: refused.code } };
    if (step.act.sealed) return this.fillSealed(run, step, page);
    const done = await this.inTab(run.tab, (a, id) => globalThis.TafsPage.act(a, id), [step.act, step.read_id]);
    if (!done || !done.ok) return { error: { code: (done && done.code) || "page_unresponsive" } };
    // A click is never proof of its effect: the page is read back.
    await this.loaded(run.tab);
    return this.read(run);
  }
}
