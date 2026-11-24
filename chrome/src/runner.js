// Runs checked steps in a run's own tabs (docs/chrome.md, "Runs"). Each run opens a background tab in the owner's window (a window of
// its own, unfocused, when there is none) in one tab group, "tafs · <taf>", that never comes to the front. The runner touches only tabs it opened: it never lists the owner's tabs,
// and nothing outside the run's group is ever read or sent. A navigation off the run's sites is stopped in the tab itself,
// whatever started it. The owner's own input in a run's tab pauses the run.

import { refuseURL, bareURL } from "./sites.js";
import { HAND_MAX_MS, validWords, withoutValues } from "./hand.js";

/**
 * The run's own blank page: where its tab opens and where a refused move is sent. It loads nothing, so the guard lets
 * it be; refusing it sent the tab to about:blank again, and again, which also cancelled the page the step was opening.
 */
const isBlank = (url) => String(url || "").trim().toLowerCase() === "about:blank";
import { refuse } from "./guard.js";
import { installDialogGuard } from "./dialogs.js";
import { randomId } from "./crypto.js";
import { checkAllowances, checkSites } from "./protocol.js";

const LOAD_TIMEOUT_MS = 30_000;
const SETTLE_MS = 600;
// After an act, how long the tab gets to start the navigation the act began (a click's page change begins a moment after the click),
// as 100 ms looks. A click that navigates nothing costs this and no more.
const NAV_WAIT_MS = 1000;
const NAV_LOOK_MS = 100;
const STEP_TIMEOUT_MS = 20_000;
const FAILURES_TO_PAUSE = 3;
// A run nobody has sent a step to for this long has been dropped (a lost close, a call that timed out): its tabs close (and its window, when it has its own). One
// the owner paused isn't idle, it's waiting for the owner.
const RUN_IDLE_MS = 5 * 60_000;
// A finished run's group turns grey ("· done") this long after it ends, unless the taf's next run starts first (greyLater).
const DONE_GRACE_MS = 15_000;
// Why Show me pointed to no field, as the panel says it: the page changed, the field isn't on screen, the page didn't answer.
const SHOW_WHY = new Set(["page_changed", "ref_unknown", "page_unresponsive"]);
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
  constructor({ chrome, onChange = () => {}, sleep = (ms) => new Promise((r) => setTimeout(r, ms)), paired = () => null, now = () => Date.now(), shots = null, onHandAnswer = () => {}, doneStore = null }) {
    this.chrome = chrome;
    this.paired = paired; // {chromeId, phoneKey} of the pairing, to check the phone's own allowances
    this.onChange = onChange;
    this.sleep = sleep;
    this.shots = shots; // the picture kept of each act (shot.js); null: none is taken
    this.onHandAnswer = onHandAnswer; // the person's answer to a hand-over, to give planque ({hand, outcome, page}); the service worker signs and keeps it
    this.now = now;
    this.runs = new Map(); // run id → {id, taf, sites, allowed, windowId, ownWindow, groupId, tabs: Set, tab, token, pages: Map, paused, stopped, failures, steps}
    this.tabRun = new Map(); // tab id → run id
    this.doneStore = doneStore; // {get(), set(obj)}: the tabs each taf's last finished run left, kept where a stopped worker finds them again
    this.done = null; // taf → {tabs: [id], window: id | null}, loaded on first use
    this.fenceIds = new Map(); // tab id → the first of its three fence rule ids
    this.nextFence = 1;
    this.tails = new Map(); // run id → the step now running, so a run's steps never overlap
    this.greying = new Map(); // taf → the mark of its finished run whose group turns grey after DONE_GRACE_MS (greyLater)
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
  }

  /** The owner touched a run's tab: that run pauses until Resume. */
  ownerInput(tabId) {
    const run = this.runOfTab(tabId);
    if (!run || run.paused || run.stopped) return;
    // While a step is handed to the person, their typing in the page is what was asked: it pauses nothing, and a touch after
    // Done or Not now pauses as before.
    if (this.handOpen(run)) return;
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

  /**
   * Ends a run. Its tabs stay open (the group renamed "· done"): the person sees the pages the taf worked on, and nothing opens and
   * closes under them. Only the latest finished run of each taf keeps its tabs: that taf's next run closes them when it opens.
   * `close` closes them at once instead: unpairing, and a run that failed to set up.
   */
  async stop(runId, close = false) {
    const run = this.runs.get(runId);
    if (!run || run.stopped) return;
    run.stopped = true;
    this.record(run, { op: "stop" });
    // A run that never loaded a page has nothing of the person's to keep: its blank tab goes, as before.
    await this.closeRun(run, close || !run.opened);
  }

  async stopAll() {
    for (const id of [...this.runs.keys()]) await this.stop(id, true);
    await this.closeAllDone();
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
  async closeDone(taf, gently = false) {
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
    if (left.window != null && !kept) await c.windows.remove(left.window).catch(() => {});
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
    for (const id of run.tabs) tabs.push({ id, url: ((await this.chrome.tabs.get(id).catch(() => null)) || {}).url || null });
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

  greyGroup(taf, groupId) {
    return Promise.resolve(this.chrome.tabGroups.update(groupId, { title: "tafs · " + taf + " · done", color: "grey" })).catch(() => {});
  }

  async closeRun(run, close = true) {
    // A step still handed to the person ends with the run: planque is told "not now", not left to its 30 minutes.
    if (run.hand) this.endHand(run, "declined");
    for (const t of run.tabs) {
      this.tabRun.delete(t);
      await this.unfence(t);
    }
    if (!close) {
      // The run's tabs are the person's now: no longer the run's, in a group that says it is done. Nothing of the run's closes.
      if (run.groupId != null) this.greyLater(run.taf || "taf", run.groupId);
      await this.leaveDone(run);
      run.tabs.clear();
      run.tab = null;
      return;
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
      if (run.stopped || run.paused || this.tails.has(run.id) || this.handOpen(run)) continue; // a person takes longer than the idle limit
      if (this.now() - run.at > RUN_IDLE_MS) {
        run.stopped = true;
        this.record(run, { op: "stop", by: "idle" });
        await this.closeRun(run, false);
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
      // A tab of its own in the window the owner is using, in the background: their active tab and their other tabs are
      // never touched, and the run's group sits beside them. With no such window, a window of its own, not focused.
      // The taf's last finished run's tabs close now, so the calls of one taf never pile up; the person saw the page where it stopped.
      this.greying.delete(run.taf || "taf");
      await this.closeDone(run.taf || "taf", true);
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
        await this.stop(run.id, true).catch(() => {});
        throw e;
      }
    }
    // In a window of its own the run's tab is the one in front; in the owner's window it stays in the background.
    run.opened = true;
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

  async act(run, step) {
    if (run.tab == null) return { error: { code: "no_page" } };
    const page = run.pages.get(step.read_id);
    if (!page) return { error: { code: "page_changed" } };
    const refused = refuse(page, step.act);
    if (refused) return { error: { code: refused.code } };
    // The extension keeps no saved sign-in and never types one: a step naming one is refused, as a secret field is.
    if (step.act.sealed) return { error: { code: "secret_field" } };
    await this.pictureOf(run, step, page);
    const before = await this.chrome.tabs.get(run.tab).catch(() => null);
    const done = await this.inTab(run.tab, (a, id) => globalThis.TafsPage.act(a, id), [step.act, step.read_id]);
    if (!done || !done.ok) return { error: { code: (done && done.code) || "page_unresponsive" } };
    // A click is never proof of its effect: the page is read back, after the navigation it started has begun and ended.
    await this.loaded(run.tab, before ? before.url || "" : undefined);
    return this.read(run);
  }
}
