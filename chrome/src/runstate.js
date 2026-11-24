// A run's state, said to planque (docs/chrome.md, "Pause, Resume and Stop reach the taf"): on each Pause, Resume and Stop of a
// run, by the bar, the panel, the person's touch, the run's own failures or the idle sweep, the extension signs
// `{run, state, at}` with this Chrome's key (`tafs-chrome/run_state/v1`) and sends it on the relay. planque keeps the newest, so
// a taf whose step was answered `paused_by_owner` waits for `working` (and sends the step again) instead of ending its turn.
// Kept until planque says it has it, and sent again on each welcome, as a hand-over's answer is (hand.js).

import { LABEL, sign } from "./crypto.js";

/** What planque knows a run's state by. `paused_failures` and `paused_no_progress` are `paused` too. */
export const RUN_STATES = Object.freeze({ pause: "paused", resume: "working", stop: "stopped" });

/** The message for one run's state, signed by this Chrome's key over `{run, state, at}`. */
export async function runStateMessage(privateKey, { run, state, at }) {
  const body = { run, state, at };
  return { type: "run_state", ...body, sig: await sign(privateKey, LABEL.runState, body) };
}

/**
 * The newest state of each run, waiting to reach planque: kept until planque says it has it (`run_state_kept`) or never will
 * (`run_state_refused`, except `not_kept`, a store failure, which goes again on the next welcome). `store` is a small key-value
 * area that survives the worker stopping.
 */
export class RunStates {
  constructor(store) {
    this.store = store;
    // run → the `at` of each state sent and not yet answered, oldest first. planque answers on the same socket in order, and its
    // answer names the run only, so each answer is matched to the oldest one sent.
    this.sent = new Map();
    this.stopped = new Set(); // runs said stopped: final, even once planque has it and it's no longer kept here
  }

  async all() {
    return (await this.store.get()) || [];
  }

  /**
   * A run's new state replaces any older one still waiting. `stopped` is final: once a run is stopped, nothing else is kept for
   * it, so a run that was stopped or swept never says `working` again.
   */
  async add(s) {
    const all = await this.all();
    const was = all.find((x) => x.run === s.run);
    if (this.stopped.has(s.run) || (was && was.state === "stopped")) return false;
    if (s.state === "stopped") this.stopped.add(s.run);
    await this.store.set([...all.filter((x) => x.run !== s.run), { run: s.run, state: s.state, at: s.at }]);
    return true;
  }

  /** Notes a state as sent, so planque's answer to it drops it and never a newer one added meanwhile. */
  sending(s) {
    this.sent.set(s.run, [...(this.sent.get(s.run) || []), s.at]);
  }

  /** planque's answer: the state it answers is done with, unless planque couldn't store it just now (`not_kept`). */
  async heard(m) {
    if (!m || typeof m.run !== "string") return;
    if (m.type !== "run_state_kept" && m.type !== "run_state_refused") return;
    const queue = this.sent.get(m.run) || [];
    const at = queue.shift(); // the state this answer is for
    if (queue.length) this.sent.set(m.run, queue);
    else this.sent.delete(m.run);
    if (m.type === "run_state_refused" && m.code === "not_kept") return; // it goes again on the next welcome, not in a loop
    const all = await this.all();
    const waiting = all.find((x) => x.run === m.run);
    // Only the state answered goes: a newer one added after it stays, to be sent (and answered) in its turn.
    if (!waiting || at === undefined || waiting.at !== at) return;
    await this.store.set(all.filter((x) => x.run !== m.run));
  }
}
