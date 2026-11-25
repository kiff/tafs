// Runs checked steps in a run's own tabs (docs/chrome.md, "Runs"). Each run opens a background tab in the owner's window (a window of
// its own, unfocused, when there is none) in one tab group, "tafs · <taf>", that never comes to the front. A run the person started
// from the side panel works where they are instead ("A taf works where you are"): the tab in front of that panel's window joins the
// run, only on the run's sites, with the page consent, and only when the run opens the very page it shows (an open elsewhere goes to
// a tab of the run's own: the person's tab is never navigated away), and Chrome follows the run's tab while the panel is open there. The runner
// touches only tabs it opened and that one tab: it never lists the owner's tabs, and nothing outside the run's group is ever read or
// sent. A navigation off the run's sites is stopped in the tab itself, whatever started it. The owner's own input in a run's tab
// pauses the run.

import { RUN_STATES } from "./runstate.js";
import { refuseURL, bareURL, joinRefusal, joinedRefusal } from "./sites.js";
import { HAND_MAX_MS, validWords, withoutValues } from "./hand.js";

/**
 * The run's own blank page: where its tab opens and where a refused move is sent. It loads nothing, so the guard lets
 * it be; refusing it sent the tab to about:blank again, and again, which also cancelled the page the step was opening.
 */
const isBlank = (url) => String(url || "").trim().toLowerCase() === "about:blank";

/** Whether two addresses are the same page, as Chrome writes them: a joined tab already there is read in place, not loaded again. */
function sameAddress(a, b) {
  try {
    return new URL(String(a)).href === new URL(String(b)).href;
  } catch {
    return false;
  }
}
import { refuse } from "./guard.js";
import { installDialogGuard } from "./dialogs.js";
import { randomId } from "./crypto.js";
import { checkAllowances, checkSites } from "./protocol.js";

const LOAD_TIMEOUT_MS = 30_000;
/**
 * A navigation Chrome says the person made (`webNavigation`'s transition): Back or Forward, an address typed, a reload, a bookmark,
 * a search from the address bar. Never a run's, even while one of its acts waits for its page.
 */
const PERSONS_TRANSITIONS = new Set(["typed", "reload", "auto_bookmark", "generated", "keyword", "keyword_generated"]);
function persons(d) {
  return (Array.isArray(d.transitionQualifiers) && d.transitionQualifiers.includes("forward_back")) || PERSONS_TRANSITIONS.has(d.transitionType);
}

/** How many addresses a tab's trail keeps for `back`. */
const TRAIL_MAX = 50;
const SETTLE_MS = 600;
/**
 * How long a run's new tab may take to show its blank page before the step's page goes into it (`blankReady`). Navigating a tab
 * whose about:blank hasn't committed yet races it: under load the blank page commits after the navigation began and aborts it
 * (net::ERR_ABORTED), and the tab is left "unloaded" on about:blank.
 */
const BLANK_WAIT_MS = 10_000;
// After an act, how long the tab gets to start the navigation the act began (a click's page change begins a moment after the click),
// as 100 ms looks. A click that navigates nothing costs this and no more.
const NAV_WAIT_MS = 1000;
const NAV_LOOK_MS = 100;
const STEP_TIMEOUT_MS = 20_000;
const FAILURES_TO_PAUSE = 3;
// A run nobody has sent a step to for this long has ended (its turn is over, a lost close, a call that timed out): it ends as Stop
// does, its tabs kept. planque keeps a taf's run for the turn's next call only within 2 minutes of the last one (chromeRunIdle), so
// this is a little over that: the bar, the badge and the group stop saying the taf works soon after its turn. One the owner paused
// isn't idle, it's waiting for the owner.
export const RUN_IDLE_MS = 150_000;
/**
 * A paused run that gets no step for this long ends as Stop does: nothing will resume it (the taf's turn is over), so the bar
 * and the group stop saying it works or is paused. Above the 10 minutes a taf holds its step for the owner's Resume, so a held
 * run is never ended under it.
 */
export const PAUSED_IDLE_MS = 15 * 60_000;
// A finished run's group turns grey ("· done") this long after it ends, unless the taf's next run starts first (greyLater).
// A run someone ended (the person's Stop, the idle sweep, the taf's turn over) has no next call to wait for: it greys at once.
const DONE_GRACE_MS = 15_000;
/**
 * How long a run has had no step before the taf's turn being over, in tafs cloud's word, ends it here (endTurn): a step planque
 * sends while the taf's chat is being read can't land on a run that just ended under it.
 */
export const TURN_QUIET_MS = 10_000;
// Why Show me pointed to no field, as the panel says it: the page changed, the field isn't on screen, the page didn't answer.
const SHOW_WHY = new Set(["page_changed", "ref_unknown", "page_unresponsive"]);
const PAUSE_CODES = { failures: "paused_failures", no_progress: "paused_no_progress", owner: "paused_by_owner" };

/** A page as the repeat check compares it: everything the taf sees, without the read's own id. */
function pageKey(page) {
  if (!page) return null;
  const { read_id, sealed, ...rest } = page; // eslint-disable-line no-unused-vars
  return rest;
}

/** How long the element an act is about to touch stays outlined before the act: long enough to see, short enough not to slow a run. */
export const OUTLINE_MS = 500;

export class Runner {
  /**
   * `chrome` is the extension API (a fake in tests); `onChange(state)` hears every change the side panel shows.
   * `sleep` waits, so tests can run without real time.
   */
  constructor({ chrome, onChange = () => {}, sleep = (ms) => new Promise((r) => setTimeout(r, ms)), say = (key, sub) => (globalThis.chrome && globalThis.chrome.i18n ? globalThis.chrome.i18n.getMessage(key, sub) : ""), paired = () => null, now = () => Date.now(), shots = null, onHandAnswer = () => {}, onRunState = () => {}, doneStore = null, panel = null }) {
    this.chrome = chrome;
    // The side panels (panels.js), from the service worker: `front({agentRun})` the tab a run started from a panel may join
    // ({windowId, tabId, off} or null, the page consent checked first), `isOpen(windowId)`, and `off()` the hosts the person
    // switched off. None: every run opens its own tab.
    this.panel = panel;
    this.paired = paired; // {chromeId, phoneKey} of the pairing, to check the phone's own allowances
    this.onChange = onChange;
    this.sleep = sleep;
    this.say = say; // the extension's own words (chrome.i18n), for the bar a run shows in its tab
    this.shots = shots; // the picture kept of each act (shot.js); null: none is taken
    this.onRunState = onRunState; // a run's Pause, Resume or Stop ({run, state, at}), for planque (runstate.js); the service worker signs and keeps it
    this.onHandAnswer = onHandAnswer; // the person's answer to a hand-over, to give planque ({hand, outcome, page}); the service worker signs and keeps it
    this.now = now;
    this.runs = new Map(); // run id → {id, taf, sites, allowed, windowId, ownWindow, groupId, tabs: Set, joined: Set, tab, agentRun, follow, token, pages: Map, paused, stopped, failures, steps}
    this.tabRun = new Map(); // tab id → run id
    this.doneStore = doneStore; // {get(), set(obj)}: the tabs each taf's last finished run left, kept where a stopped worker finds them again
    this.done = null; // taf → {tabs: [{id, url, joined?}], window: id | null, group: id | null}, loaded on first use
    this.fenceIds = new Map(); // tab id → the first of its three fence rule ids
    this.nextFence = 1;
    this.tails = new Map(); // run id → the step now running, so a run's steps never overlap
    this.greying = new Map(); // taf → the mark of its finished run whose group turns grey after DONE_GRACE_MS (greyLater)
    this.listen();
    // A worker that stopped left its fences in Chrome, a joined tab's among them: every session rule goes now (only this runner adds
    // them), before any fence of this runner's is added (`ruleIds` waits for it), so the sweep never removes a fresh one.
    this.ready = this.unfenceAll().catch(() => {});
  }

  listen() {
    const c = this.chrome;
    // A navigation in a run's tab that leaves its sites is stopped before it loads.
    c.webNavigation.onBeforeNavigate.addListener((d) => {
      if (d.frameId !== 0) return;
      const run = this.runOfTab(d.tabId);
      if (!run || isBlank(d.url)) return;
      if (refuseURL(d.url, run.sites, run.allowed)) {
        // A tab of the person's that joined the run is never sent anywhere: Chrome's fence blocks the request, and the page they
        // had stays. A tab of the run's own goes to its blank page, as before.
        if (!run.joined.has(d.tabId)) c.tabs.update(d.tabId, { url: "about:blank" });
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
      const off = !isBlank(d.url) && refuseURL(d.url, run.sites, run.allowed);
      if (run.joined.has(d.tabId)) {
        // A tab of the person's that joined the run is never moved by the extension. Only the run's own act, while it waits for its
        // page (`stepping`), may take it to another page, on the run's sites. Any other commit there is the person's (their Back or
        // Forward, an address typed, a reload, their own click, the page's own redirect): the tab is given back to them as it is,
        // unfenced and forgotten, and the run's next step answers `page_changed`. One off the run's sites in the act's own wait is
        // given back too, and the act is refused `site_not_listed`.
        const ours = run.stepping === d.tabId && !persons(d) && !off;
        if (!ours) {
          if (run.stepping === d.tabId && off) run.stepError = "site_not_listed";
          this.giveBack(run, [d.tabId], run.stepping !== d.tabId || persons(d)).catch(() => {});
          if (off) this.record(run, { op: "navigate", url: bareURL(d.url), error: "site_not_listed" });
          return;
        }
        this.noteAddress(run, d.tabId, d.url);
        this.guardDialogs(d.tabId, run).catch(() => {});
        return;
      }
      if (off) {
        // A tab of the run's own goes to its blank page.
        c.tabs.update(d.tabId, { url: "about:blank" });
        this.record(run, { op: "navigate", url: bareURL(d.url), error: "site_not_listed" });
        return;
      }
      if (!isBlank(d.url)) this.noteAddress(run, d.tabId, d.url);
      this.guardDialogs(d.tabId, run).catch(() => {});
    });
    // A navigation in a joined tab that fails (above all Chrome's fence, net::ERR_BLOCKED_BY_CLIENT, which shows its error page at
    // the blocked address and commits nothing) gives the tab back as it is. The person's own (their Back to a page off the run's
    // sites, say), once the fence is gone, is let through: the tab, no longer the run's, loads the address they asked for, and
    // nothing else of theirs moves. In a run's act, the act is refused `site_not_listed`.
    if (c.webNavigation.onErrorOccurred) {
      c.webNavigation.onErrorOccurred.addListener((d) => {
        if (d.frameId !== 0) return;
        const run = this.runOfTab(d.tabId);
        if (!run || !run.joined.has(d.tabId)) return;
        const inStep = run.stepping === d.tabId;
        const blocked = /ERR_BLOCKED_BY_CLIENT/.test(String(d.error || ""));
        if (inStep) run.stepError = "site_not_listed";
        if (blocked) this.record(run, { op: "navigate", url: bareURL(d.url), error: "site_not_listed" });
        this.giveBack(run, [d.tabId], !inStep)
          .then(async () => {
            if (inStep || !blocked || isBlank(d.url)) return;
            const tab = await c.tabs.get(d.tabId).catch(() => null);
            if (!tab) return;
            // Chrome's error page at their address: loaded again in place. Otherwise their navigation, asked for once more.
            if (sameAddress(tab.url, d.url)) await c.tabs.reload(d.tabId);
            else await c.tabs.update(d.tabId, { url: d.url });
          })
          .catch(() => {});
      });
    }
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
      // Fenced, or closed: a pop-up Chrome's rules can't hold never stays open unfenced.
      this.fence(run, d.tabId).catch(() => {
        run.tabs.delete(d.tabId);
        this.tabRun.delete(d.tabId);
        Promise.resolve(c.tabs.remove(d.tabId)).catch(() => {});
        this.record(run, { op: "open", url: bareURL(d.url), error: "browser_unavailable" });
      });
      // The owner may have ungrouped or closed the run's group: the pop-up stays the run's, in a group made again if Chrome ended it.
      this.groupTab(run, d.tabId, run.windowId).catch(() => {});
    });
    c.tabs.onRemoved.addListener((tabId) => {
      // Its fence goes with it, whoever's it was and whatever the run knows of it: a rule naming a closed tab is never left behind.
      if (this.fenceIds.has(tabId)) this.unfence(tabId).catch(() => {});
      // A tab the person took back from a run and has now closed: the run's next step is told so no more.
      for (const r of this.runs.values()) if (r.taken === tabId) r.taken = null;
      const run = this.runOfTab(tabId);
      if (!run) return;
      run.tabs.delete(tabId);
      run.joined.delete(tabId);
      run.trail.delete(tabId);
      this.tabRun.delete(tabId);
      if (run.tab === tabId) run.tab = [...run.tabs][0] ?? null;
      // The person closed the run's last tab: that's their Stop. The run ends now (a hand-over open answers not now), rather than
      // saying the taf works until the idle sweep. A run still setting its first tab up has none yet and isn't touched.
      if (!run.tabs.size && run.opened && !run.stopped) this.stop(run.id, false, "person").catch(() => {});
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
   * from a counter of this runner's own. Nothing but this runner adds session rules, and no run outlives it, so the runner
   * clears whatever an earlier service worker left as it's made.
   */
  async ruleIds(tabId) {
    // The clear made with the runner (`ready`), which every fence awaits, so a run starting beside another never has its rules
    // removed by it.
    await this.ready;
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

  /** Every fence rule goes (unpairing): those this runner knows, and any session rule left, as only this runner adds them. */
  async unfenceAll() {
    const dnr = this.chrome.declarativeNetRequest;
    if (!dnr) return;
    const ids = new Set();
    for (const base of this.fenceIds.values()) [base, base + 1, base + 2].forEach((id) => ids.add(id));
    this.fenceIds.clear();
    if (dnr.getSessionRules) for (const r of (await dnr.getSessionRules().catch(() => [])) || []) ids.add(r.id);
    if (ids.size) await dnr.updateSessionRules({ removeRuleIds: [...ids] }).catch(() => {});
  }

  /**
   * The addresses the run's own work took tab [tabId] to, from its committed navigations, oldest first: `back` goes only to the one
   * before the last. A joined tab's starts at the address it joined at, so `back` there never reaches a page the person had before.
   */
  noteAddress(run, tabId, url) {
    if (run.backing === tabId || !run.tabs.has(tabId)) return;
    // In a joined tab, only the run's own act, while it waits for its page, adds a page (the commit listener gives any other back);
    // and never while the run is paused.
    if (run.joined.has(tabId) && (run.stepping !== tabId || run.paused)) return;
    const list = run.trail.get(tabId) || [];
    if (!list.length || !sameAddress(list[list.length - 1], url)) list.push(String(url));
    if (list.length > TRAIL_MAX) list.splice(0, list.length - TRAIL_MAX);
    run.trail.set(tabId, list);
  }

  /**
   * Puts [tabId] in [run]'s group, in [windowId]. A group Chrome ended (the person's tab given back emptied it) is made again, with
   * the run's working title; never an error for the step. `reuseDone`: the taf's done group in that window may work again (the
   * caller then names the group itself).
   */
  async groupTab(run, tabId, windowId, reuseDone = false) {
    const c = this.chrome;
    const into = (g) => (g == null ? Promise.resolve(null) : Promise.resolve(c.tabs.group({ groupId: g, tabIds: [tabId] })).then(() => g, () => null));
    let g = await into(run.groupId);
    if (g == null && reuseDone) g = await into(await this.tafGroup(run.taf || "taf", windowId));
    if (g != null) {
      run.groupId = g;
      return false;
    }
    run.groupId = await c.tabs.group({ tabIds: [tabId], createProperties: { windowId } });
    if (!reuseDone) await Promise.resolve(c.tabGroups.update(run.groupId, { title: this.workingTitle(run), color: "orange", collapsed: false })).catch(() => {});
    return true;
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
      // Whose run it is, by the grant its steps came under, and its tabs: the panel follows the tab (coachchat.js, tafOfTab).
      grant: r.grant,
      tab: r.tab,
      tabs: [...r.tabs],
      site: r.site,
      paused: r.paused,
      stopped: r.stopped,
      hand: this.handOpen(r) ? { id: r.hand.id, words: r.hand.words, ref: r.hand.ref !== undefined } : null,
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
    this.queueBars();
    // Every Pause, Resume and Stop goes through here, whoever made it: planque hears the run's new state.
    if (RUN_STATES[entry.op]) {
      Promise.resolve(this.onRunState({ run: run.id, state: RUN_STATES[entry.op], at: new Date(this.now()).toISOString() })).catch(() => {});
    }
  }

  /**
   * The person pressed Stop on the bar in a run's tab: the run's browser work ends at once, as the panel's Stop does, and
   * planque's next step for it is answered `stopped` (the taf is told the person stopped it). Its tabs stay, as with Stop.
   */
  async stopByPerson(tabId) {
    const run = this.runOfTab(tabId);
    if (!run || run.stopped) return;
    await this.stop(run.id, false, "person");
  }

  /**
   * A press on the floating bar (bar.js), for run [runId]: Go to its tab, Pause, Resume or Stop, as the panel's own buttons do.
   * Only a run this Chrome holds and that still lives; anything else does nothing.
   */
  async barPress(runId, act, tabId = null) {
    const run = this.runs.get(runId);
    if (!run || run.stopped) return { error: "no_run" };
    // Resume gives power back: only from the run's own tab, never from a bar drawn on another page (bar.js shows it only there).
    if (act === "resume" && !run.tabs.has(tabId)) return { error: "not_here" };
    if (act === "go") return this.goTo(runId);
    if (act === "pause") return this.pause(runId);
    if (act === "resume") return this.resume(runId);
    if (act === "stop") return this.stop(runId, false, "person");
    return { error: "bad_act" };
  }

  /** What the bar says for [run]: in the extension's words, with its buttons' labels. */
  barState(run, here) {
    const taf = run.taf || "taf";
    const say = (key, sub, or) => this.say(key, sub) || or;
    return {
      run: run.id,
      here,
      paused: !!run.paused,
      words: run.paused ? say("bar_paused", [taf], taf) : say("working", [taf], taf),
      labels: { go: say("bar_go", [], "Go to its tab"), pause: say("pause", [], "Pause"), resume: say("resume", [], "Resume"), stop: say("stop", [], "Stop") },
    };
  }

  /** Brings the bars up to date, one sync at a time; a change while one runs is taken by the next. */
  queueBars() {
    if (this.barsQueued) return this.barsQueued;
    const before = this.barsRunning || Promise.resolve();
    this.barsQueued = before.then(() => {
      this.barsQueued = null;
      this.barsRunning = this.syncBars().catch(() => {});
      return this.barsRunning;
    });
    return this.barsQueued;
  }

  /**
   * The floating bar (D1 v2, docs/chrome.md, "While a taf works"): while a run lives, the tab in front of each normal window
   * shows it, for the run whose tab it is or else the first live run; with none, it goes from every tab it was on. Only https
   * tabs, which the extension may reach; Chrome's own pages and the like are left alone. Nothing is read from those tabs: the
   * bar is drawn in them, and that's all.
   */
  async syncBars() {
    const live = [...this.runs.values()].filter((r) => !r.stopped && r.tab != null);
    const want = new Map();
    if (live.length) {
      const wins = await (async () => this.chrome.windows.getAll({ windowTypes: ["normal"] }))().catch(() => []);
      for (const w of wins || []) {
        const [tab] = await (async () => this.chrome.tabs.query({ active: true, windowId: w.id }))().catch(() => []);
        if (!tab || typeof tab.id !== "number" || !/^https:\/\//.test(tab.url || "")) continue;
        const own = this.runOfTab(tab.id);
        const run = own && !own.stopped ? own : live[0];
        want.set(tab.id, this.barState(run, run.tabs.has(tab.id)));
      }
    }
    for (const t of this.barTabs || []) {
      if (!want.has(t)) await this.inTab(t, () => globalThis.TafsBar && globalThis.TafsBar.hide()).catch(() => {});
    }
    for (const [t, s] of want) {
      try {
        await this.chrome.scripting.executeScript({ target: { tabId: t }, files: ["src/bar.js"] });
        await this.inTab(t, (x) => globalThis.TafsBar.show(x), [s]);
      } catch {
        // A page that can't take it is no reason to fail: the panel and the group still show the run.
      }
    }
    this.barTabs = new Set(want.keys());
  }

  /** The owner touched a run's tab: that run pauses until Resume. */
  ownerInput(tabId) {
    const run = this.runOfTab(tabId);
    if (!run || run.paused || run.stopped) return;
    // While a step is handed to the person, their typing in the page is what was asked: it pauses nothing, and a touch after
    // Done or Not now pauses as before.
    if (this.handOpen(run)) return;
    run.paused = "owner";
    run.at = this.now();
    this.record(run, { op: "pause", by: "owner" });
  }

  /**
   * The panel's head (D2): brings a live run's tab to the front, in its window. Nothing else: no act, no read. A run this
   * Chrome doesn't hold, or one stopped or with no tab yet, is `no_run`.
   */
  async goTo(runId) {
    const run = this.runs.get(runId);
    if (!run || run.stopped || run.tab == null) return { error: "no_run" };
    await this.chrome.tabs.update(run.tab, { active: true });
    if (run.windowId != null && this.chrome.windows && this.chrome.windows.update) {
      await Promise.resolve(this.chrome.windows.update(run.windowId, { focused: true })).catch(() => {});
    }
    return {};
  }

  pause(runId) {
    const run = this.runs.get(runId);
    if (run && !run.stopped) {
      run.paused = "owner";
      run.at = this.now();
      this.record(run, { op: "pause", by: "owner" });
    }
  }

  resume(runId) {
    const run = this.runs.get(runId);
    if (run && !run.stopped) {
      run.paused = null;
      run.failures = 0;
      run.last = null;
      run.at = this.now();
      this.record(run, { op: "resume" });
    }
  }

  /**
   * Ends a run. Its tabs stay open (the group renamed "· done"): the person sees the pages the taf worked on, and nothing opens and
   * closes under them. Only the latest finished run of each taf keeps its tabs: that taf's next run closes them when it opens.
   * `close` closes them at once instead: unpairing, and a run that failed to set up.
   */
  async stop(runId, close = false, by = null) {
    const run = this.runs.get(runId);
    if (!run || run.stopped) return;
    run.stopped = true;
    this.record(run, by ? { op: "stop", by } : { op: "stop" });
    // A run that never loaded a page has nothing of the person's to keep: its blank tab goes, as before. One the person stopped
    // says done at once: nothing of the taf's will follow it in the grace.
    // The person's Stop hands a tab of theirs that joined the run back to them: out of the group, still open; so does the end of
    // a paused run by the taf's newer one.
    await this.closeRun(run, close || !run.opened, by != null, by === "person" || by === "newer_run");
    // The panel hears the run without its tabs at once, not at the next change.
    this.onChange(this.state());
    this.queueBars();
  }

  /**
   * The taf's turn is over, in tafs cloud's word (its chat says it isn't working, read by the panel): its run here stops saying it
   * works (the bar, the panel's card, the group, now "· done") instead of waiting for the idle sweep. Only a run with nothing
   * under way: no step running, none for TURN_QUIET_MS, not paused, no step handed to the person.
   *
   * It isn't a Stop: planque isn't told the run stopped, and the run is forgotten here, as after the worker restarts. planque
   * keeps a taf's run for its next call (chromeRunIdle), so the person's next message may come back to it: a call that opens a
   * page finds none in it and goes on in a new run (planque's `restart`), and a bare read is answered `no_page`; neither is
   * told "the owner stopped this run".
   */
  async endTurn(runId) {
    const run = this.runs.get(runId);
    if (!run || run.stopped || run.paused || this.tails.has(run.id) || this.handOpen(run)) return { ended: false };
    // Too soon after its last step: the panel asks again once it's quiet that long, if tafs cloud still says so then.
    const quiet = this.now() - run.at;
    if (quiet < TURN_QUIET_MS) return { ended: false, retryIn: TURN_QUIET_MS - quiet };
    run.stopped = true; // a step racing in while its tabs are let go is refused, not run in them
    await this.closeRun(run, false, true);
    this.runs.delete(run.id);
    this.onChange(this.state());
    this.queueBars();
    return { ended: true };
  }

  async stopAll() {
    for (const id of [...this.runs.keys()]) await this.stop(id, true);
    await this.closeAllDone();
    await this.unfenceAll();
  }

  async doneTabs() {
    if (!this.done) {
      this.done = (this.doneStore && (await this.doneStore.get())) || {};
      // A worker that stopped during a finished run's grace never greyed its group: every kept one says done now.
      for (const [taf, left] of Object.entries(this.done)) if (left && left.group != null) await this.greyGroup(taf, left.group);
    }
    return this.done;
  }

  /**
   * What a taf's last finished run left: its tabs close (and a window of its own), once, and the record goes. `gently` (a taf's next run):
   * a kept tab is the person's now, so it closes only while it is still at the address the run left it and isn't the tab in front
   * of the window they are using; one they moved or are using stays, leaving the run's group if it's still in it (never a group of
   * the person's own), and so does a window of its own that still holds one. Unpairing closes them all.
   */
  async closeDone(taf, gently = false, keep = null, keepWindow = null) {
    const done = await this.doneTabs();
    const left = done[taf];
    if (!left) return;
    delete done[taf];
    if (this.doneStore) await this.doneStore.set(done);
    const c = this.chrome;
    const close = [];
    const spared = [];
    let kept = false;
    for (const t of left.tabs) {
      const id = typeof t === "number" ? t : t.id;
      // The taf's next run holds it again (the tab in front, joined once more), or another run does: it's that run's now.
      if ((keep && keep.has(id)) || this.tabRun.has(id)) {
        kept = true;
        continue;
      }
      // A tab of the person's that joined the run is theirs: never closed, whatever ends; at most it leaves the run's group.
      if (typeof t !== "number" && t.joined) {
        const tab = await c.tabs.get(id).catch(() => null);
        if (tab && left.group != null && tab.groupId === left.group) spared.push(id);
        continue;
      }
      if (!gently) {
        close.push(id);
        continue;
      }
      const tab = await c.tabs.get(id).catch(() => null);
      if (!tab) continue; // already gone
      const win = tab.active ? await c.windows.get(tab.windowId).catch(() => null) : null;
      const inUse = tab.active && win && win.focused;
      const moved = typeof t !== "number" && t.url != null && (tab.url || "") !== t.url;
      if (inUse || moved) {
        kept = true;
        // Still in the run's own group: it leaves it. One the person put in a group of their own stays there.
        if (left.group != null && tab.groupId === left.group) spared.push(id);
      } else close.push(id);
    }
    if (close.length) await c.tabs.remove(close).catch(() => {});
    // A tab the person has taken stays where it is, but leaves the taf's "· done" group: that group named a run that is over,
    // and Chrome ends a group once it's empty. So a person who watches each run doesn't collect one done group per run.
    if (spared.length) await c.tabs.ungroup(spared).catch(() => {});
    if (left.window != null && !kept && left.window !== keepWindow) await c.windows.remove(left.window).catch(() => {});
  }

  /**
   * The taf's group in [windowId] to work in again (one group per taf per window): its last finished run's, while Chrome still
   * has it in that window. Null when there is none, or Chrome can't say.
   */
  async tafGroup(taf, windowId) {
    const left = (await this.doneTabs())[taf];
    if (!left || left.group == null || !this.chrome.tabGroups.get) return null;
    const g = await Promise.resolve(this.chrome.tabGroups.get(left.group)).catch(() => null);
    return g && g.windowId === windowId ? left.group : null;
  }

  async closeAllDone() {
    for (const taf of Object.keys(await this.doneTabs())) await this.closeDone(taf);
  }

  /** A run that ended leaves its tabs as the taf's last: an earlier run's of the same taf close now. */
  async leaveDone(run) {
    const taf = run.taf || "taf";
    await this.closeDone(taf, true);
    const done = await this.doneTabs();
    const tabs = [];
    for (const id of run.tabs) {
      const url = ((await this.chrome.tabs.get(id).catch(() => null)) || {}).url || null;
      tabs.push(run.joined.has(id) ? { id, url, joined: true } : { id, url });
    }
    done[taf] = { tabs, window: run.ownWindow && run.windowId != null ? run.windowId : null, group: run.groupId ?? null };
    if (this.doneStore) await this.doneStore.set(done);
  }

  /**
   * A finished run's group says done (grey) only after DONE_GRACE_MS: a taf works in a few calls, each its own run, and its next run
   * in that time cancels it, so the person sees the group stay amber while the taf works and waits, not blink grey between calls.
   */
  greyLater(taf, groupId) {
    const mark = {};
    this.greying.set(taf, mark);
    Promise.resolve(this.sleep(DONE_GRACE_MS)).then(() => {
      if (this.greying.get(taf) !== mark) return; // the taf's next run started: its group is the one that counts
      this.greying.delete(taf);
      return this.greyGroup(taf, groupId);
    }).catch(() => {});
  }

  /**
   * The run's group says that its taf is working, in the extension's own words ("Rae is working"), for as long as the run
   * lives: one of the signals, with the bar and the action's badge, that a taf is at work in this Chrome. Its end turns it
   * grey, "· done".
   */
  workingTitle(run) {
    const taf = run.taf || "taf";
    return "tafs · " + (this.say("working", [taf]) || taf);
  }

  greyGroup(taf, groupId) {
    return Promise.resolve(this.chrome.tabGroups.update(groupId, { title: this.doneTitle(taf), color: "grey" })).catch(() => {});
  }

  /** A group whose run is over, in the extension's words ("Rae is done"), as `workingTitle` is while it works. */
  doneTitle(taf) {
    const name = taf || "taf";
    return "tafs · " + (this.say("group_done", [name]) || name + " · done");
  }

  async closeRun(run, close = true, doneNow = false, handBack = false) {
    // A step still handed to the person ends with the run: planque is told "not now", not left to its 30 minutes.
    if (run.hand) this.endHand(run, "declined");
    for (const t of run.tabs) {
      // The bar goes with the run: the tab is the person's again (the next sync takes it from any other tab).
      await this.inTab(t, () => globalThis.TafsBar && globalThis.TafsBar.hide()).catch(() => {});
      this.tabRun.delete(t);
      await this.unfence(t);
    }
    // A tab of the person's that joined the run is never closed by the extension. Their Stop, and an end that closes the run's
    // tabs (unpairing, a run that couldn't be set up), give it back out of the group; any other end leaves it in the "· done" group.
    const joined = [...run.tabs].filter((t) => run.joined.has(t));
    if (joined.length && (close || handBack)) {
      await Promise.resolve(this.chrome.tabs.ungroup(joined)).catch(() => {});
      for (const t of joined) {
        run.tabs.delete(t);
        run.joined.delete(t);
      }
    }
    if (!close) {
      // The run's tabs are the person's now: no longer the run's, in a group that says it is done. Nothing of the run's closes.
      if (run.groupId != null) {
        const taf = run.taf || "taf";
        if (doneNow) {
          this.greying.delete(taf);
          await this.greyGroup(taf, run.groupId);
        } else this.greyLater(taf, run.groupId);
      }
      await this.leaveDone(run);
      run.tabs.clear();
      run.joined.clear();
      run.trail.clear();
      run.tab = null;
      return;
    }
    // A window of the run's own goes with it; in the owner's window, only the run's own tabs close, never theirs.
    // The run's tabs always close, wherever they are now (the owner may have dragged one out of the run's window); a window of
    // the run's own goes after them, and the owner's window never does.
    if (run.tabs.size) await this.chrome.tabs.remove([...run.tabs]).catch(() => {});
    if (run.ownWindow && run.windowId != null) await this.chrome.windows.remove(run.windowId).catch(() => {});
    run.tabs.clear();
    run.trail.clear();
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

  /**
   * Ends, as Stop does, the runs nobody sent a step to for RUN_IDLE_MS, or for PAUSED_IDLE_MS since a paused one's last step,
   * pause or Resume. Called by the service worker's alarm.
   */
  async sweep() {
    for (const run of [...this.runs.values()]) {
      if (run.stopped || this.tails.has(run.id) || this.handOpen(run)) continue; // a person takes longer than the idle limit
      if (this.now() - run.at > (run.paused ? PAUSED_IDLE_MS : RUN_IDLE_MS)) {
        run.stopped = true;
        this.record(run, { op: "stop", by: "idle" });
        await this.closeRun(run, false, true);
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
        grant: typeof step.grant === "string" ? step.grant : "",
        sites: step.sites,
        // A sensitive site opens only on the paired phone's own signed allowance for this grant, never planque's word.
        allowed: await checkAllowances(step.sensitive_allowed, { ...(this.paired() || {}), grant: step.grant }),
        windowId: null,
        ownWindow: false,
        groupId: null,
        tabs: new Set(),
        joined: new Set(), // tabs of the person's that joined the run: never closed by the extension
        // The run the person started from a side panel, as planque's signed step names it (`agent_run`): what lets the tab in
        // front of that panel's window join. None (a schedule, an event, the phone): the run opens its own tab.
        agentRun: typeof step.agent_run === "string" && step.agent_run ? step.agent_run : null,
        follow: null, // the panel's window, for a run started there: Chrome follows the run's tab while that panel is open
        tab: null,
        trail: new Map(), // tab id → the addresses the run's work took it to (noteAddress), for `back`
        stepping: null, // the tab an act of the run's is working in, until its page has loaded: a commit there then is the act's
        stepError: null, // why that act failed in a joined tab, said by the navigation listeners (site_not_listed)
        taken: null, // the joined tab the person moved while the run was on it: its next read, act or hand-over is given_back
        backing: null, // the tab a `back` is moving now: its commits aren't the run's new pages
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
      // One run of a taf at a time on the person's screen: a newer run of the taf (same name, same grant: never another taf's),
      // whatever started it, ends an older one the person left paused, as their Stop would, before its own first step: a tab of
      // theirs the old run joined goes back to them, still open, and planque is told it stopped. The new run takes nothing of it.
      // One still working is left alone: it may be a call of its own.
      for (const old of [...this.runs.values()]) {
        if (old !== run && !old.stopped && old.paused && old.taf === run.taf && old.grant === run.grant) await this.stop(old.id, false, "newer_run");
      }
    }
    // planque keeps a taf's run for its next call, whatever agent run makes it: a step of another agent run (a schedule, the
    // phone, a later message) never works in a tab of the person's that their panel run joined. That tab goes back to them
    // first, as Stop gives it back, and the step goes on in a tab of the run's own; the run can't join one again.
    const stepRun = typeof step.agent_run === "string" && step.agent_run ? step.agent_run : null;
    if (run.joined.size && stepRun !== run.agentRun) await this.giveBackJoined(run, stepRun);
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
    // The person moved the tab of theirs the run was working in: nothing of that page is the run's. Its next read, act or hand-over
    // is refused, until it opens a page of its own.
    if (run.taken != null && (step.op === "read" || step.op === "act" || step.op === "hand")) return { error: { code: "given_back" } };
    if (step.op === "open") run.taken = null;
    switch (step.op) {
      case "open":
        return this.open(run, step.url);
      case "hand":
        return this.hand(run, step);
      case "read":
        if (step.offset != null && !(Number.isSafeInteger(step.offset) && step.offset >= 0)) return { error: { code: "bad_step", detail: "offset" } };
        return this.read(run, step.offset);
      case "act":
        return this.act(run, step);
      case "close":
        run.stopped = true;
        await this.closeRun(run, false);
        return { page: null };
      default:
        return { error: { code: "bad_step" } };
    }
  }

  /** Whether a step is handed to the person in this run and still open: not answered, not past its 30 minutes. */
  handOpen(run) {
    if (!run.hand) return false;
    if (this.now() >= run.hand.until) {
      run.hand = null; // planque's own 30 minutes end it too
      return false;
    }
    return true;
  }

  /** Closes the run's open hand-over with `outcome` and gives the answer to planque, with `page` when there is one. */
  endHand(run, outcome, page = null) {
    const hand = run.hand;
    run.hand = null;
    if (hand) this.onHandAnswer({ hand: hand.id, outcome, page });
  }

  /**
   * A step handed to the person ("fill in the card number, then tap Done"): the panel shows "Your turn" with the taf's words and the
   * person does it in the page. It is answered at once as handed; the person's Done or Not now comes later (`answerHand`). Checked
   * here as any step is, trusting nothing of planque's checks: the words, the field or button named, the read it was planned from, and
   * the page's site.
   */
  async hand(run, step) {
    if (run.tab == null) return { error: { code: "no_page" } };
    const h = step.hand;
    if (!h || typeof h !== "object" || !validWords(h.words) || (h.ref !== undefined && typeof h.ref !== "string")) {
      return { error: { code: "bad_step", detail: "the words of a hand-over are 1 to 280 characters of plain text" } };
    }
    const page = run.pages.get(step.read_id);
    if (!page) return { error: { code: "page_changed" } };
    if (h.ref !== undefined && ![...(page.fields || []), ...(page.controls || [])].some((x) => x.ref === h.ref)) {
      return { error: { code: "bad_step", detail: "the field or button isn't in the read" } };
    }
    const tab = await this.chrome.tabs.get(run.tab);
    const refused = refuseURL(tab.url || "", run.sites, run.allowed);
    if (refused) return { error: { code: refused.code } };
    const theirs = await this.joinedNow(run, run.tab);
    if (theirs) return { error: { code: theirs } };
    // One at a time: a new one ends the one still open as declined.
    if (run.hand) this.endHand(run, "declined");
    run.hand = { id: step.id, words: h.words, ref: h.ref, readId: step.read_id, until: this.now() + HAND_MAX_MS };
    this.onChange(this.state());
    return { handed: true };
  }

  /**
   * The person's answer to the hand-over: Done reads the page back for the taf **without what is in its fields**, Not now says no.
   * `{error}` when none is open.
   */
  async answerHand(runId, outcome) {
    const run = this.runs.get(runId);
    if (!run || !this.handOpen(run) || (outcome !== "done" && outcome !== "declined")) return { error: { code: "no_hand" } };
    if (outcome === "declined") {
      this.endHand(run, "declined");
      this.record(run, { op: "hand", kind: "declined" });
      this.onChange(this.state());
      return { ok: true };
    }
    let page = null;
    try {
      // Without what is in the fields, and without the text of an editable region: what the person typed is not the taf's.
      const read = await this.read(run, 0, { withoutEditable: true });
      if (read.page) page = withoutValues(read.page);
    } catch {
      // The page couldn't be read back: Done still goes to the taf, with no page, and it reads again.
    }
    this.endHand(run, "done", page);
    this.record(run, { op: "hand", kind: "done" });
    this.onChange(this.state());
    return { ok: true };
  }

  /**
   * "Show me": the run's tab to the front, and the field or button the taf named outlined (nothing else, nothing scrolled or clicked).
   * It says what it did, so the panel never leaves a press unanswered: `shown` is "field" when the outline is drawn, else "tab", with
   * `why` when a field was named and couldn't be pointed to (`page_changed`, `ref_unknown`, `page_unresponsive`) or "no_ref" when none was.
   */
  async showHand(runId) {
    const run = this.runs.get(runId);
    if (!run || !this.handOpen(run) || run.tab == null) return { error: { code: "no_hand" } };
    await this.chrome.tabs.update(run.tab, { active: true });
    if (run.windowId != null && this.chrome.windows.update) await this.chrome.windows.update(run.windowId, { focused: true }).catch(() => {});
    if (run.hand.ref === undefined) return { ok: true, shown: "tab", why: "no_ref" };
    let out = null;
    try {
      out = await this.inTab(run.tab, (ref, id) => (globalThis.TafsPage ? globalThis.TafsPage.outline(ref, id) : { ok: false, code: "page_changed" }), [run.hand.ref, run.hand.readId]);
    } catch (e) {
      out = { ok: false, code: (e && e.code) || "page_unresponsive" };
    }
    if (out && out.ok) return { ok: true, shown: "field" };
    const why = out && SHOW_WHY.has(out.code) ? out.code : "page_unresponsive";
    return { ok: true, shown: "tab", why };
  }

  async open(run, url) {
    const refused = refuseURL(url, run.sites, run.allowed);
    if (refused) return { error: { code: refused.code } };
    const c = this.chrome;
    if (run.tab == null) {
      // The tab in front of the side panel's window, for a run the person started there and only on what the run may reach;
      // otherwise a tab of its own in the window the owner is using, in the background: their active tab and their other tabs are
      // never touched, and the run's group sits beside them. With no such window, a window of its own, not focused.
      const taf = run.taf || "taf";
      this.greying.delete(taf);
      const front = await this.frontTab(run, url);
      const made = front || (await this.runTab());
      // A tab of the run's own shows its blank page before anything goes into it (BLANK_WAIT_MS); the person's tab already shows theirs.
      if (!front) await this.blankReady(made.tabId);
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
      if (made.joined) {
        // A tab the person took back from another run, joined again: that run's word about it goes.
        for (const r of this.runs.values()) if (r.taken === tabId) r.taken = null;
        run.joined.add(tabId);
        run.trail.set(tabId, [String(url)]);
      }
      this.tabRun.set(tabId, run.id);
      try {
        // Fenced before it loads anything of the run's: the person's tab as much as a tab of the run's own.
        await this.fence(run, tabId);
        // One group per taf per window: the taf's last group there says it works again, rather than a new one beside it. A run whose
        // group Chrome ended (its tabs given back) has one made again.
        await this.groupTab(run, tabId, windowId, true);
        await c.tabGroups.update(run.groupId, { title: this.workingTitle(run), color: "orange", collapsed: false });
        if (run.stopped) {
          // Stopped while it was fenced and grouped: its close ran before these tabs were the run's.
          await this.closeRun(run);
          return { error: { code: "stopped" } };
        }
      } catch (e) {
        // A window that could not be set up is not left open, blank and unfenced, on the owner's screen.
        await this.stop(run.id, true).catch(() => {});
        throw e;
      }
      // The taf's last finished run's tabs close now, so the calls of one taf never pile up; the person saw the page where it
      // stopped. Only once the new tab is in the group, so a group worked in again isn't emptied (and ended) first.
      await this.closeDone(taf, true, run.tabs, windowId);
    } else {
      // The person's tab is never navigated away (docs/chrome.md, "A taf works where you are"). A tab of theirs the run joined
      // that shows exactly this address, and may still be a run's, is read where it is (the run goes back to it from a tab of
      // its own). Any other address goes to a tab of the run's own, in the same window and group; their tab stays as it is.
      const here = await this.joinedShowing(run, url);
      if (here != null) run.tab = here;
      else if (run.joined.has(run.tab)) await this.moreTab(run);
    }
    run.opened = true;
    // Chrome follows the run: its tab comes to the front of the panel's window, while the panel is open there. Otherwise, in a
    // window of its own the run's tab is the one in front; in the owner's window it stays in the background.
    const follow = this.follows(run);
    if (run.joined.has(run.tab)) {
      // The person's tab, which shows that page (frontTab, joinedShowing): read as it is, never loaded again nor sent elsewhere.
      if (follow) await c.tabs.update(run.tab, { active: true });
    } else await c.tabs.update(run.tab, run.ownWindow || follow ? { url, active: true } : { url });
    await this.loaded(run.tab);
    return this.read(run);
  }

  /** Whether Chrome follows [run]'s tab: a run started from the side panel, in that panel's window, while it's still open. */
  follows(run) {
    return run.follow != null && run.follow === run.windowId && !!this.panel && !!this.panel.isOpen && this.panel.isOpen(run.windowId) === true;
  }

  /**
   * The tab in front of the side panel's window, to join [run] as it opens [url] (docs/chrome.md, "A taf works where you are"), or
   * null: the run then opens its own tab. Only for a run the person started from that panel (the worker's `front`: planque's signed
   * `agent_run` matches their send's mark, and the page consent holds), and only a tab that is still the active one of that window,
   * not pinned, in no group but the taf's own, held by no other run, whose page and [url] both pass `joinedRefusal`.
   */
  async frontTab(run, url) {
    if (!this.panel || !run.agentRun) return null;
    const front = await Promise.resolve().then(() => this.panel.front({ agentRun: run.agentRun, grant: run.grant })).catch(() => null);
    if (!front || typeof front.windowId !== "number" || typeof front.tabId !== "number") return null;
    run.follow = front.windowId;
    if (this.tabRun.has(front.tabId)) return null;
    const tab = await this.chrome.tabs.get(front.tabId).catch(() => null);
    const off = Array.isArray(front.off) ? front.off : [];
    if (joinRefusal(tab, { windowId: front.windowId, sites: run.sites, allowed: run.allowed, off })) return null;
    // It joins only to be read where it is: a first open of any other address never navigates it away (a taf that didn't know
    // which page "this page" was sent the person's article to the site's home). The run opens its own tab instead.
    if (!sameAddress(tab.url, url)) return null;
    if (joinedRefusal({ ...tab, url }, { sites: run.sites, allowed: run.allowed, off })) return null;
    // A tab in a group of the person's own stays there: the run never takes it out of it.
    if (typeof tab.groupId === "number" && tab.groupId !== -1 && tab.groupId !== (await this.tafGroup(run.taf || "taf", front.windowId))) return null;
    return { windowId: front.windowId, tabId: tab.id, own: false, joined: true };
  }

  /**
   * Why a step may not work in [tabId] now, when it's a tab of the person's that joined [run] (with [url], the page it would open
   * there): a step's code, or null. Checked on every step, as the person may have moved the tab, or switched its site off, since.
   * A tab of the run's own isn't checked here (it has refuseURL alone, as before).
   */
  async joinedNow(run, tabId, url = null) {
    if (!run.joined.has(tabId)) return null;
    const tab = await this.chrome.tabs.get(tabId).catch(() => null);
    if (!tab) return "no_page";
    const off = this.panel && this.panel.off ? await Promise.resolve().then(() => this.panel.off()).catch(() => []) : [];
    const rules = { sites: run.sites, allowed: run.allowed, off: Array.isArray(off) ? off : [] };
    const refused = joinedRefusal(tab, rules) || (url != null ? joinedRefusal({ ...tab, url }, rules) : null);
    if (refused) return refused;
    // Only a page the run's work reached in it, from the one it joined at: never one the person had before (a `back` Chrome took
    // past the joined page, say), even on the run's sites.
    const trail = run.trail.get(tabId);
    if (trail && trail.length && !trail.some((a) => sameAddress(a, tab.url))) return "page_changed";
    return null;
  }

  /**
   * The tab of the person's that [run] joined which shows exactly [url] and may still be a run's (`joinedNow`), the run's current
   * tab first, or null: an open of that address reads it where it is.
   */
  async joinedShowing(run, url) {
    const order = [...run.joined].sort((a, b) => (b === run.tab) - (a === run.tab));
    for (const t of order) {
      const tab = await this.chrome.tabs.get(t).catch(() => null);
      if (tab && sameAddress(tab.url, url) && !(await this.joinedNow(run, t, url))) return t;
    }
    return null;
  }

  /**
   * The tabs of the person's that [run] joined go back to them, as Stop gives them back (out of the group, unfenced, forgotten, the
   * bar gone, still open), while the run itself goes on, now as [agentRun]'s: a step of another agent run reached it.
   */
  async giveBackJoined(run, agentRun) {
    await this.giveBack(run, [...run.joined]);
    run.agentRun = agentRun;
    run.follow = null;
  }

  /**
   * [tabs] of the person's that [run] joined go back to them: their bar hidden, forgotten, unfenced, out of the group, still open.
   * `byPerson`: they moved it themselves, so the run's next step that would read or act there answers `given_back` (`taken`),
   * until it opens a page of its own.
   */
  async giveBack(run, tabs, byPerson = false) {
    const joined = tabs.filter((t) => run.joined.has(t));
    if (byPerson && joined.includes(run.tab)) run.taken = run.tab;
    for (const t of joined) {
      this.tabRun.delete(t);
      run.tabs.delete(t);
      run.joined.delete(t);
      run.trail.delete(t);
      await this.inTab(t, () => globalThis.TafsBar && globalThis.TafsBar.hide()).catch(() => {});
      await this.unfence(t);
    }
    if (joined.length) await Promise.resolve(this.chrome.tabs.ungroup(joined)).catch(() => {});
    if (run.tab != null && joined.includes(run.tab)) run.tab = [...run.tabs][0] ?? null;
    // The panel hears the run without that tab at once.
    if (joined.length) {
      this.onChange(this.state());
      this.queueBars();
    }
  }

  /** A further tab of the run's own, in its window and group, fenced before it loads, and the one the run works in from now on. */
  async moreTab(run) {
    const c = this.chrome;
    const tab = await c.tabs.create({ windowId: run.windowId, url: "about:blank", active: false });
    run.tabs.add(tab.id);
    this.tabRun.set(tab.id, run.id);
    try {
      await this.fence(run, tab.id);
    } catch (e) {
      // Unfenced, it loads nothing: the blank tab closes and the step says why.
      run.tabs.delete(tab.id);
      this.tabRun.delete(tab.id);
      await Promise.resolve(c.tabs.remove([tab.id])).catch(() => {});
      throw e;
    }
    run.tab = tab.id;
    await this.groupTab(run, tab.id, run.windowId).catch(() => {});
    // Its blank page committed before the step's page goes in, or Chrome aborts that navigation under load.
    await this.blankReady(tab.id);
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

  /** Waits while a new tab is still loading its about:blank, BLANK_WAIT_MS at most, so the step's navigation never races it. */
  async blankReady(tabId) {
    for (let waited = 0; waited < BLANK_WAIT_MS; waited += 50) {
      const tab = await this.chrome.tabs.get(tabId).catch(() => null);
      if (!tab || tab.status !== "loading") return;
      await this.sleep(50);
    }
  }

  /** Closes what runTab made, for a run that was stopped before it took it: the tab, or the window of the run's own. */
  async discard({ windowId, tabId, own, joined }) {
    if (joined) return; // the person's tab: never closed, and nothing was done to it yet
    if (own) await this.chrome.windows.remove(windowId).catch(() => {});
    else await this.chrome.tabs.remove([tabId]).catch(() => {});
  }

  /**
   * Waits for the tab to finish loading, then SETTLE_MS. With `from` (the address the tab had before an act), it first waits up to
   * NAV_WAIT_MS for the tab to start a navigation: a status other than `complete`, or another address. Without it, the read after a click comes
   * before the page the click starts, because `complete` is still the old page's status.
   */
  async loaded(tabId, from) {
    const c = this.chrome;
    if (from !== undefined) {
      for (let waited = 0; waited < NAV_WAIT_MS; waited += NAV_LOOK_MS) {
        const now = await c.tabs.get(tabId).catch(() => null);
        if (!now || now.status !== "complete" || (now.url || "") !== from) break;
        await this.sleep(NAV_LOOK_MS);
      }
    }
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

  async read(run, offset = 0, opts = {}) {
    if (run.tab == null) return { error: { code: "no_page" } };
    const tab = await this.chrome.tabs.get(run.tab);
    const refused = refuseURL(tab.url || "", run.sites, run.allowed);
    if (refused) return { error: { code: refused.code } };
    const theirs = await this.joinedNow(run, run.tab);
    if (theirs) return { error: { code: theirs } };
    await this.inject(run.tab, run);
    const page = await this.inTab(run.tab, (o, p) => globalThis.TafsPage.read(o, p), [offset, opts]);
    if (!page) return { error: { code: "page_unresponsive" } };
    run.pages.set(page.read_id, page);
    if (run.pages.size > 20) run.pages.delete(run.pages.keys().next().value);
    return { page };
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

  /**
   * Back: to the page before the last one the run's work took this tab to (its trail), and only there. Chrome skips a history entry
   * no person's gesture made (a taf's own click), so its back can land further: in a tab of the run's own, that page is loaded
   * instead; in a joined tab, which the run never sends anywhere, it's put back (forward) and refused `no_back`. With no page before
   * in the trail (a joined tab still at the page it joined at), `no_back`, and nothing moves.
   */
  async back(run) {
    const c = this.chrome;
    const tabId = run.tab;
    // A joined tab: never. The run only reads it where it joined and acts there; Chrome skips the entries its acts leave, so a back
    // would land on a page the person had before (and load it again). Nothing moves.
    if (run.joined.has(tabId)) return { error: { code: "no_back" } };
    const trail = [...(run.trail.get(tabId) || [])];
    if (trail.length < 2) return { error: { code: "no_back" } };
    const want = trail[trail.length - 2];
    if (refuseURL(want, run.sites, run.allowed)) return { error: { code: "no_back" } };
    const from = await c.tabs.get(tabId).catch(() => null);
    run.backing = tabId;
    try {
      await Promise.resolve(c.tabs.goBack(tabId)).catch(() => {});
      await this.loaded(tabId, from ? from.url || "" : undefined);
      const now = await c.tabs.get(tabId).catch(() => null);
      if (!(now && sameAddress(now.url, want))) {
        await c.tabs.update(tabId, { url: want });
        await this.loaded(tabId, now ? now.url || "" : undefined);
      }
      run.trail.set(tabId, trail.slice(0, -1));
    } finally {
      run.backing = null;
    }
    return this.read(run);
  }

  async act(run, step) {
    if (run.tab == null) return { error: { code: "no_page" } };
    const page = run.pages.get(step.read_id);
    if (!page) return { error: { code: "page_changed" } };
    const refused = refuse(page, step.act);
    if (refused) return { error: { code: refused.code } };
    // The extension keeps no saved sign-in and never types one: a step naming one is refused, as a secret field is.
    if (step.act.sealed) return { error: { code: "secret_field" } };
    // The person's own tab is checked again as it is now: they may have moved it since the read, or switched its site off.
    const theirs = await this.joinedNow(run, run.tab);
    if (theirs) return { error: { code: theirs } };
    // Back (gap 7): where the tab was before, read and fenced as any page is; nothing is clicked or typed.
    if (step.act.kind === "back") return this.back(run);
    await this.pictureOf(run, step, page);
    // Where the taf is about to act, shown for a moment first (Show me's outline), after the picture so it isn't in it. Only
    // for an act on a ref: back and a scroll of the page name none. The act takes the outline away.
    if (step.act.ref) {
      const shown = await this.inTab(run.tab, (r, id) => globalThis.TafsPage.outline(r, id), [step.act.ref, step.read_id]).catch(() => null);
      if (shown && shown.ok) await this.sleep(OUTLINE_MS);
      // The outline is what makes a person react ("it's about to click that"): a Pause, their own click or key in the page
      // (which pauses the run), or a Stop in those moments holds the act. Nothing reaches the page.
      if (run.stopped || run.paused) {
        if (run.tab != null) await this.inTab(run.tab, () => globalThis.TafsPage.unoutline()).catch(() => {});
        return { error: { code: run.stopped ? "stopped" : PAUSE_CODES[run.paused] || "paused_by_owner" } };
      }
    }
    const before = await this.chrome.tabs.get(run.tab).catch(() => null);
    const tabId = run.tab;
    run.stepping = tabId;
    run.stepError = null;
    try {
      // In the person's tab, the run's sites go with the act: a link or a form's submit that would leave them is refused in the page
      // before it fires, so Chrome never puts its "blocked" page in place of theirs. A tab of the run's own is left to the fence,
      // which sends it to its blank page.
      const opts = run.joined.has(tabId) ? { sites: [...run.sites, ...run.allowed] } : null;
      const done = await this.inTab(tabId, (a, id, o) => globalThis.TafsPage.act(a, id, o), [step.act, step.read_id, opts]);
      if (done && done.code === "site_not_listed" && done.url) this.record(run, { op: "navigate", url: done.url, error: "site_not_listed" });
      if (!done || !done.ok) return { error: { code: (done && done.code) || "page_unresponsive" } };
      // A click is never proof of its effect: the page is read back, after the navigation it started has begun and ended.
      await this.loaded(tabId, before ? before.url || "" : undefined);
      if (run.stepError) return { error: { code: run.stepError } };
      // The person moved their tab in the act's wait (their Back, say): it's theirs again, and nothing of it is read.
      if (run.taken != null) return { error: { code: "given_back" } };
    } finally {
      run.stepping = null;
      run.stepError = null;
    }
    return this.read(run);
  }
}
