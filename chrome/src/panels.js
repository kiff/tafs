// The side panels open in this Chrome, and the runs the person started from one (docs/chrome.md, "A taf works where you are").
// A panel says it's open by holding a port named "panel" to the service worker, with its window's id; it's open for as long as
// the port is. A send from a panel (Coach's chat or a taf's) leaves a mark: that window, the tab in front of it then, and the
// run tafs cloud's answer names (`run`). A run's first step may join that tab only if planque's signed step names the same run
// (`agent_run`), within MARK_MS, and the mark is taken by that one run. A run nobody started from a panel (a schedule, an event,
// the phone, the watch) finds no mark and opens its own tab, as before.

/** How long a send's mark waits for its run's first step. */
export const MARK_MS = 120_000;
/** How long a run's first step waits for a send still on its way to tafs cloud (its answer names the run). */
export const SENDING_WAIT_MS = 5_000;

export class Panels {
  constructor({ now = () => Date.now(), sleep = (ms) => new Promise((r) => setTimeout(r, ms)) } = {}) {
    this.now = now;
    this.sleep = sleep;
    this.ports = new Map(); // window id → the port of the panel open in it
    this.marks = []; // {windowId, tabId, agentRun, at}
    this.sending = new Set(); // sends from a panel not yet answered
  }

  /** A port from the extension's own panel page (never a content script's): the panel is open while it's connected. */
  connect(port, selfId) {
    if (!port || port.name !== "panel") return false;
    const from = port.sender || {};
    if (from.id !== selfId || from.tab) return false;
    let win = null;
    port.onMessage.addListener((m) => {
      if (win !== null || !m || !Number.isInteger(m.windowId)) return;
      win = m.windowId;
      this.ports.set(win, port);
    });
    port.onDisconnect.addListener(() => {
      if (win === null || this.ports.get(win) !== port) return;
      this.ports.delete(win);
      // A closed panel's marks go with it: nothing started there joins a tab any more.
      this.marks = this.marks.filter((k) => k.windowId !== win);
    });
    return true;
  }

  isOpen(windowId) {
    return this.ports.has(windowId);
  }

  /**
   * The person sent from the panel of [windowId], with [tabId] in front: `answer` is the send under way (tafs cloud's answer). Its
   * `run` becomes the mark; a send that fails, or names no run, leaves none. Returns the send's own answer, unchanged.
   */
  async sent(windowId, tabId, answer) {
    const p = Promise.resolve(answer);
    this.sending.add(p);
    try {
      const out = await p;
      if (out && typeof out.run === "string" && out.run && Number.isInteger(windowId) && Number.isInteger(tabId) && this.isOpen(windowId)) {
        this.marks = [...this.marks.filter((k) => k.agentRun !== out.run), { windowId, tabId, agentRun: out.run, at: this.now() }];
      }
      return out;
    } finally {
      this.sending.delete(p);
    }
  }

  /**
   * The mark of the run [agentRun] (planque's signed `agent_run`), taken once: `{windowId, tabId}`, or null. None for a step that
   * names no run, a mark older than MARK_MS, or one whose panel has closed. A send still on its way is waited for, a few seconds
   * at most: its answer is what names the run, and the run's first step can come before it.
   */
  async take(agentRun) {
    if (typeof agentRun !== "string" || !agentRun) return null;
    let mark = this.find(agentRun);
    if (!mark && this.sending.size) {
      await Promise.race([Promise.allSettled([...this.sending]), this.sleep(SENDING_WAIT_MS)]);
      mark = this.find(agentRun);
    }
    if (!mark) return null;
    this.marks = this.marks.filter((k) => k !== mark);
    return { windowId: mark.windowId, tabId: mark.tabId };
  }

  find(agentRun) {
    const now = this.now();
    this.marks = this.marks.filter((k) => now - k.at <= MARK_MS && this.isOpen(k.windowId));
    return this.marks.find((k) => k.agentRun === agentRun) || null;
  }
}

/**
 * The tab a run's first step may join, for the runner to check (runner.js `frontTab`): `{windowId, tabId, off}` or null. Only a
 * run the person started from a panel ([agentRun] matches a mark), and only with the page consent (`consent()`, the same check
 * Coach's send makes), asked before anything of the tab is looked at. Nothing here touches a tab.
 */
export async function joinFront({ panels, agentRun, consent, off = async () => [] }) {
  const mark = await panels.take(agentRun);
  if (!mark) return null;
  if (!(await Promise.resolve().then(consent).catch(() => false))) return null;
  return { windowId: mark.windowId, tabId: mark.tabId, off: await Promise.resolve().then(off).catch(() => []) };
}
