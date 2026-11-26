// The one connection to planque (docs/chrome.md, "Connect"). The extension always opens it; nothing connects in. Each connect
// proves both ends: the extension says `open`, then signs planque's challenge with its paired key, and planque signs ours with
// the key pinned in this build. Only then are steps taken, each checked as planque's (protocol.checkStep) before the runner
// sees it. planque's relay can't choose a close code, so it says `close` with the code first; a real close code counts too.

import { CLOSE, Nonces, checkStep, checkWelcome, clockOffset, hello, result } from "./protocol.js";
import { randomId } from "./crypto.js";

const PING_MS = 20_000;
/** Steps whose effect has happened by the time they answer: never answered as a failure for their page's size. */
const EFFECT_OPS = new Set(["act", "hand"]);

/** The relay carries at most 128 KB a message: a result above this is answered `page_too_large` instead. */
export const MAX_RESULT_BYTES = 120 * 1024;

/** The text kept of a page cut to fit: enough to read where the taf is, with the rest a `browse_read` away. */
const CUT_TEXT = 4000;
const CUT_OPTIONS = 10;
const CUT_LINKS = 50;

/**
 * Gap 4: smaller and smaller copies of a page too large to send, each saying what it cut (`truncated`), to try in turn. The
 * text goes first, since planque's fingerprint of a read (its refs, labels and options) doesn't count it: a page cut there
 * is still the read the taf acts on. Then a select's options past 10, then links past 50, then the rest of the text and the
 * links. The fingerprint does count options and links (planque's `browser.Read`), so a page cut there reads as another read
 * than the same page uncut. A cut text says where it goes on (`more`, `next_offset`), as a window does. Fields and controls
 * are never cut: they are what an act names.
 */
export function cutPages(page) {
  const out = [];
  let p = page;
  const cut = (what, change) => {
    p = { ...p, ...change, truncated: [...new Set([...(p.truncated || []), what])] };
    out.push(p);
  };
  const text = (n) => {
    let end = Math.min(n, p.text.length);
    if (end > 0 && end < p.text.length) {
      const last = p.text.charCodeAt(end - 1);
      if (last >= 0xd800 && last <= 0xdbff) end -= 1;
    }
    return { text: p.text.slice(0, end), more: true, next_offset: (p.offset || 0) + end };
  };
  if (!page || typeof page !== "object") return out;
  if (typeof p.text === "string" && p.text.length > CUT_TEXT) cut("text", text(CUT_TEXT));
  if ((p.fields || []).some((f) => Array.isArray(f.options) && f.options.length > CUT_OPTIONS)) {
    cut("options", { fields: p.fields.map((f) => (Array.isArray(f.options) && f.options.length > CUT_OPTIONS ? { ...f, options: f.options.slice(0, CUT_OPTIONS) } : f)) });
  }
  if ((p.links || []).length > CUT_LINKS) cut("links", { links: p.links.slice(0, CUT_LINKS) });
  if (typeof p.text === "string" && p.text.length > 0) cut("text", text(0));
  if ((p.links || []).length > 0) cut("links", { links: [] });
  return out;
}

const bytesOf = (answer) => new TextEncoder().encode(JSON.stringify(answer)).length;
/** A connection dropped during a run is opened again at once, then after 2, 4, … seconds, up to 30. */
const RETRY_MS = [1_000, 2_000, 4_000, 8_000, 16_000, 30_000];

/**
 * This Chrome's live connections as planque lists them (the welcome, then `{type: "connections"}` on a change): what the side
 * panel offers to talk to. **For display only, never authority:** what a step may do comes only from the signed step and the
 * connection's phone-signed sites, and nothing here is ever taken as an allowed site. Anything malformed is left out.
 */
export function readConnections(list) {
  if (!Array.isArray(list)) return [];
  const out = [];
  for (const c of list) {
    if (!c || typeof c.grant !== "string" || typeof c.agent !== "string" || !c.agent || typeof c.name !== "string") continue;
    const sites = Array.isArray(c.sites) ? c.sites.filter((x) => typeof x === "string").slice(0, 100) : [];
    // The phone's signature over the grant's sites, when planque relays it: kept as it came, to be verified where it's used
    // (protocol.signedSitesOf), never taken on its word.
    const s = c.sites_signed;
    const signed = s && Array.isArray(s.sites) && s.sites.length <= 100 && s.sites.every((x) => typeof x === "string") && typeof s.sig === "string" ? { sites_signed: { sites: s.sites, sig: s.sig } } : {};
    out.push({ grant: c.grant, agent: c.agent, name: c.name.slice(0, 80), sites, ...signed });
    if (out.length === 50) break;
  }
  return out;
}

export class Relay {
  /**
   * `config` {relayURL, planqueKey, version, manifestHash}; `kept` {keys, pairing}; `runner` handles steps; `onUnpaired()`
   * wipes the extension; `WebSocket` is the browser's (a fake in tests).
   */
  constructor({ config, kept, runner, onUnpaired, onChange = () => {}, onReady = () => {}, onHandMessage = () => {}, onRunStateMessage = () => {}, WebSocket = globalThis.WebSocket, now = () => Date.now(), nonceStore = null }) {
    Object.assign(this, { config, kept, runner, onUnpaired, onChange, onReady, onHandMessage, onRunStateMessage, WebSocket, now });
    this.nonces = new Nonces(now, nonceStore);
    this.ws = null;
    this.ready = false;
    this.pushed = false;
    this.retries = 0;
    this.retry = null;
  }

  get connected() {
    return !!this.ws && this.ready;
  }

  connect() {
    clearTimeout(this.retry);
    this.retry = null;
    if (this.ws) return;
    const { pairing } = this.kept;
    const url = this.config.relayURL + "?chrome=" + encodeURIComponent(pairing.chromeId);
    const ws = new this.WebSocket(url);
    this.ws = ws;
    this.ready = false;
    this.nonceE = null;
    this.nonceP = null;
    this.offset = null;
    // planque can't post until the connection is up, so the extension speaks first; the challenge comes as the answer.
    ws.onopen = () => this.send({ type: "open" });
    ws.onmessage = (e) => this.receive(e.data).catch(() => this.close());
    ws.onclose = (e) => {
      const dropped = this.ws === ws;
      if (dropped) {
        this.ws = null;
        this.ready = false;
        clearInterval(this.pinger);
        this.onChange();
      }
      if (e && e.code === CLOSE.unpaired) return this.unpaired();
      // Dropped under a run (the relay's idle or 2-hour limit, a network change): opened again at once, then backing off.
      if (dropped && this.runner.active) this.reconnectSoon();
    };
    ws.onerror = () => {};
  }

  reconnectSoon() {
    if (this.retry) return;
    const wait = RETRY_MS[Math.min(this.retries, RETRY_MS.length - 1)];
    this.retries += 1;
    this.retry = setTimeout(() => {
      this.retry = null;
      this.connect();
    }, wait);
  }

  /** planque's 4410: wiped once, and never opened again. */
  unpaired() {
    if (this.wiped) return;
    this.wiped = true;
    this.close();
    this.onUnpaired();
  }

  close() {
    clearTimeout(this.retry);
    this.retry = null;
    const ws = this.ws;
    this.ws = null;
    this.ready = false;
    clearInterval(this.pinger);
    if (ws) ws.close();
    this.onChange();
  }

  send(message) {
    if (this.ws) this.ws.send(JSON.stringify(message));
  }

  async receive(data) {
    let m;
    try {
      m = JSON.parse(data);
    } catch {
      return this.close();
    }
    const { keys, pairing } = this.kept;
    // planque closing, at any point: no signature, as with stop, since closing only takes power away. 4410 wipes.
    if (m.type === "close") return m.code === CLOSE.unpaired ? this.unpaired() : this.close();
    if (!this.ready) {
      if (m.type === "challenge" && !this.nonceP && typeof m.nonce_p === "string") {
        this.nonceP = m.nonce_p;
        this.nonceE = randomId();
        this.send(await hello(keys.privateKey, { chromeId: pairing.chromeId, nonceP: this.nonceP, nonceE: this.nonceE, version: this.config.version, manifestHash: this.config.manifestHash }));
        return;
      }
      if (m.type === "welcome" && this.nonceE) {
        const ok = await checkWelcome(m, { planqueKey: this.config.planqueKey, chromeId: pairing.chromeId, nonceP: this.nonceP, nonceE: this.nonceE });
        if (!ok) return this.close();
        this.ready = true;
        this.connections = readConnections(m.connections);
        this.offset = clockOffset(m, this.now());
        this.retries = 0;
        // Pinged while a run is on, and, until planque can wake this worker by push (`pushed`, set once the subscription went), all the
        // time: traffic under 30 s keeps an MV3 worker, and so the socket, alive; an idle one is otherwise down half of each minute.
        this.pushed = false;
        this.pinger = setInterval(() => (this.runner.active || !this.pushed) && this.send({ type: "ping" }), PING_MS);
        this.onChange();
        // Connected and proved: the moment to give planque this Chrome's push subscription (push.js).
        Promise.resolve(this.onReady()).catch(() => {});
        return;
      }
      return this.close(); // anything else before the proofs ends the connection
    }
    if (m.type === "ping") return this.send({ type: "pong" });
    if (m.type === "connections") {
      this.connections = readConnections(m.connections);
      this.onChange();
      return;
    }
    if (m.type === "pong") return;
    // planque has this Chrome's push subscription (`push_kept`), or refused it (`push_refused`): only the first lets an idle worker
    // sleep, since only then can a push wake it. A refusal keeps the idle ping going.
    if (m.type === "push_kept") {
      this.pushed = true;
      return;
    }
    if (m.type === "push_refused") {
      this.pushed = false;
      return;
    }
    // planque has the person's answer to a hand-over (or says it never will): the extension stops keeping it (hand.js).
    if (m.type === "hand_kept" || m.type === "hand_refused") {
      Promise.resolve(this.onHandMessage(m)).catch(() => {});
      return;
    }
    // planque has a run's state (or says it never will): the extension stops keeping it (runstate.js).
    if (m.type === "run_state_kept" || m.type === "run_state_refused") {
      Promise.resolve(this.onRunStateMessage(m)).catch(() => {});
      return;
    }
    if (m.type === "step") {
      const checked = await checkStep(m, { planqueKey: this.config.planqueKey, chromeId: pairing.chromeId, nonces: this.nonces, now: this.now(), offset: this.offset ?? null });
      // A step that doesn't verify (not planque's signature, not for this Chrome, expired, replayed, malformed) is dropped: not
      // run, and given no answer at all. This extension's key never signs over an id and a nonce it can't trust, so a forged step
      // gets nothing signed back. A verified step the runner refuses (a bad act, a site off the run's) keeps its signed refusal.
      if (checked.why) return;
      const out = await this.runner.handle(checked.step);
      let answer = await result(keys.privateKey, checked.step, pairing.chromeId, out);
      // Too large: the page is cut until it fits, and says what was cut. A step whose effect already happened (an act, its
      // back included, or a hand-over) is never answered as a failure, or a taf told its click failed may click Submit again:
      // it's answered done, with no page and `page_dropped`, and the taf reads again. Only an open or a read, which changed
      // nothing a second try would repeat, is `page_too_large`.
      if (bytesOf(answer) > MAX_RESULT_BYTES && out && out.page) {
        for (const page of cutPages(out.page)) {
          answer = await result(keys.privateKey, checked.step, pairing.chromeId, { ...out, page });
          if (bytesOf(answer) <= MAX_RESULT_BYTES) break;
        }
      }
      if (bytesOf(answer) > MAX_RESULT_BYTES) {
        const ran = out && !out.error && EFFECT_OPS.has(checked.step.op);
        answer = ran
          ? await result(keys.privateKey, checked.step, pairing.chromeId, { ...out, page: null, pageDropped: "page_too_large" })
          : await result(keys.privateKey, checked.step, pairing.chromeId, { error: { code: "page_too_large", detail: "the answer is over " + MAX_RESULT_BYTES + " bytes" } });
      }
      this.send(answer);
      return;
    }
    if (m.type === "stop" && typeof m.run === "string") {
      // planque ending a run needs no signature: stopping only takes power away.
      return this.runner.stop(m.run);
    }
  }
}
