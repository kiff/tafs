// The one connection to planque (docs/chrome.md, "Connect"). The extension always opens it; nothing connects in. Each connect
// proves both ends: this extension signs planque's challenge with its paired key, and planque signs ours with the key pinned
// in this build. Only then are steps taken, each checked as planque's (protocol.checkStep) before the runner sees it.

import { CLOSE, Nonces, checkStep, checkWelcome, hello, result } from "./protocol.js";
import { randomId } from "./crypto.js";

const PING_MS = 20_000;

export class Relay {
  /**
   * `config` {relayURL, planqueKey, version, manifestHash}; `kept` {keys, pairing}; `runner` handles steps; `onUnpaired()`
   * wipes the extension; `WebSocket` is the browser's (a fake in tests).
   */
  constructor({ config, kept, runner, onUnpaired, onChange = () => {}, WebSocket = globalThis.WebSocket, now = () => Date.now(), nonceStore = null }) {
    Object.assign(this, { config, kept, runner, onUnpaired, onChange, WebSocket, now });
    this.nonces = new Nonces(now, nonceStore);
    this.ws = null;
    this.ready = false;
  }

  get connected() {
    return !!this.ws && this.ready;
  }

  connect() {
    if (this.ws) return;
    const { pairing } = this.kept;
    const url = this.config.relayURL + "?chrome=" + encodeURIComponent(pairing.chromeId);
    const ws = new this.WebSocket(url);
    this.ws = ws;
    this.ready = false;
    this.nonceE = null;
    this.nonceP = null;
    ws.onmessage = (e) => this.receive(e.data).catch(() => this.close());
    ws.onclose = (e) => {
      if (this.ws === ws) {
        this.ws = null;
        this.ready = false;
        clearInterval(this.pinger);
        this.onChange();
      }
      if (e && e.code === CLOSE.unpaired) this.onUnpaired();
    };
    ws.onerror = () => {};
  }

  close() {
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
        this.pinger = setInterval(() => this.runner.active && this.send({ type: "ping" }), PING_MS);
        this.onChange();
        return;
      }
      return this.close(); // anything else before the proofs ends the connection
    }
    if (m.type === "ping") return this.send({ type: "pong" });
    if (m.type === "pong") return;
    if (m.type === "step") {
      const checked = await checkStep(m, { planqueKey: this.config.planqueKey, chromeId: pairing.chromeId, nonces: this.nonces, now: this.now() });
      if (checked.why) {
        // Not run, and said so only when it carries an id we can answer: an unsigned message gets no signed answer.
        const id = m.step && typeof m.step.id === "string" ? m.step.id : null;
        if (id) this.send(await result(keys.privateKey, id, { error: { code: "bad_step", detail: checked.why } }));
        return;
      }
      const out = await this.runner.handle(checked.step);
      this.send(await result(keys.privateKey, checked.step.id, out));
      return;
    }
    if (m.type === "stop" && typeof m.run === "string") {
      // planque ending a run needs no signature: stopping only takes power away.
      return this.runner.stop(m.run);
    }
  }
}
