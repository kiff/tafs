// A fake planque for the end-to-end tests: the pairing routes, the relay and the paired phone, on 127.0.0.1, with keys made
// for the test run. It speaks the protocol of docs/chrome.md ("Pairing", "Connect", "Steps") with the extension's own
// modules (src/crypto.js, src/pairing.js), so a step it signs is one the real extension checks exactly as it checks
// planque's. Nothing here is an LLM or a network service: a test decides every step.
//
// It plays two parties. As **planque**, it holds the step key the test build pins (build.mjs), answers the extension's offer,
// proves itself on connect, and signs steps. As **the phone**, it holds its own key: it confirms the pairing under the code's
// secret and signs the grant's sites, which planque alone can't make.

import { createServer } from "node:http";
import { createHash, randomBytes } from "node:crypto";
import { EventEmitter } from "node:events";
import { LABEL, b64u, fromB64u, concat, hmac, sha256, sign, verify, publicSPKI, randomId } from "../src/crypto.js";
import { ALPHABET, LOOKUP_LENGTH, SECRET_LENGTH, extensionProof, hex } from "../src/pairing.js";
import { readFileSync } from "node:fs";
import { welcomeAsPlanque, connectionsAsPlanque, connectionAsPlanque, stepAsPlanque } from "../test/planque-contracts.mjs";

/** tafs cloud's failed run, as its own contract has it (written by its tests): the fake's `failed` starts from it. */
const CONTRACT_FAILED = JSON.parse(readFileSync(new URL("../../contracts/conversation-failed.json", import.meta.url), "utf8")).answer.failed;

const ECDSA = { name: "ECDSA", namedCurve: "P-256" };

/** A P-256 key pair whose public half can be exported, and that SPKI as base64url. */
export async function keyPair() {
  const keys = await crypto.subtle.generateKey(ECDSA, true, ["sign", "verify"]);
  return { ...keys, spki: b64u(await publicSPKI(keys.publicKey)) };
}

function code(n) {
  return [...randomBytes(n)].map((b) => ALPHABET[b % ALPHABET.length]).join("");
}

export class FakePlanque extends EventEmitter {
  /** `grant` and `sites` are the one connection this Chrome has: what the phone signed when the owner approved it. */
  constructor({ grant = "grt_e2e", agent = "agt_e2e", taf = "Rae", sites = [] } = {}) {
    super();
    Object.assign(this, { grant, agent, taf, sites });
    this.newCode();
    this.socket = null; // the connection that completed both proofs
    this.pending = new Map(); // step id → {resolve, reject, timer}
    this.messages = []; // every message the extension sent on the relay, in order
    this.requests = []; // every HTTP request, as "METHOD path"
  }

  /**
   * A new pairing on the phone: a new code, and a new id for the Chrome that pairs with it. `forge` (a function of the
   * confirmation) changes the phone's answer after it is signed, for a test of an answer that isn't the phone's.
   */
  newCode({ forge = null } = {}) {
    this.lookup = code(LOOKUP_LENGTH);
    this.secret = code(SECRET_LENGTH);
    this.chromeId = randomId("chr_");
    this.pairingId = randomId("pair_");
    this.offer = null; // the extension's offer, once checked
    this.extKey = null; // the extension's public key (SPKI base64url), from the offer
    this.forge = forge;
  }

  /** The code the owner types in the side panel: the lookup, then the secret, in groups of four. */
  get code() {
    return (this.lookup + this.secret).match(/.{1,4}/g).join("-");
  }

  async start() {
    this.planqueKeys = await keyPair();
    this.phoneKeys = await keyPair();
    this.server = createServer((req, res) => this.http(req, res).catch((e) => {
      res.writeHead(500).end(String(e && e.message));
    }));
    this.server.on("upgrade", (req, sock) => this.upgrade(req, sock));
    await new Promise((resolve) => this.server.listen(0, "127.0.0.1", resolve));
    this.port = this.server.address().port;
    this.api = "http://127.0.0.1:" + this.port;
    this.relayURL = "ws://127.0.0.1:" + this.port + "/relay";
    return this;
  }

  async stop() {
    for (const p of this.pending.values()) clearTimeout(p.timer);
    if (this.socket) this.socket.destroy();
    this.server.closeAllConnections();
    await new Promise((resolve) => this.server.close(resolve));
  }

  // ---- HTTP: the pairing routes, and nothing else ----

  async http(req, res) {
    const url = new URL(req.url, this.api);
    this.requests.push(req.method + " " + url.pathname);
    const headers = { "access-control-allow-origin": "*", "access-control-allow-headers": "content-type, authorization", "access-control-allow-methods": "GET, POST, OPTIONS" };
    const send = (status, body) => {
      res.writeHead(status, { ...headers, ...(body !== undefined ? { "content-type": "application/json" } : {}) });
      res.end(body !== undefined ? JSON.stringify(body) : undefined);
    };
    if (req.method === "OPTIONS") return send(204);
    const pairing = /^\/v1\/extension\/pairings\/([A-Z0-9]+)$/.exec(url.pathname);
    if (pairing && req.method === "POST") {
      if (pairing[1] !== this.lookup) return send(404, { error: "unknown_pairing" });
      if (this.offer) return send(409, { error: "used" });
      const body = JSON.parse(await readBody(req));
      // What the phone checks before it signs: the extension's key, under the code's secret planque never sees.
      const want = await extensionProof(this.secret, fromB64u(body.key), body.kiff_offer || "");
      if (body.proof !== want) return send(400, { error: "proof" });
      this.offer = body;
      this.extKey = body.key;
      this.emit("offer", body);
      return send(201, { pairing: this.pairingId });
    }
    if (pairing && req.method === "GET") {
      if (pairing[1] !== this.lookup || !this.offer) return send(404, { error: "unknown_pairing" });
      if (url.searchParams.get("key") !== this.extKey) return send(404, { error: "unknown_pairing" });
      const answer = await this.confirmation();
      return send(200, this.forge ? this.forge(answer) : answer);
    }
    if (url.pathname === "/v1/extension/unpair" && req.method === "POST") return send(204);
    return send(404, { error: "not_found" });
  }

  /** The phone's answer to the offer, as `pairing.checkConfirmation` reads it. */
  async confirmation() {
    const phoneKey = this.phoneKeys.spki;
    const ours = await sha256(fromB64u(this.extKey));
    const phoneProof = b64u(await hmac(this.secret, LABEL.phoneProof, concat(fromB64u(phoneKey), ours)));
    const name = "Test Chrome";
    const body = { pairing: this.pairingId, chrome_key_sha256: hex(ours), name, phone_key: phoneKey, phone_proof: phoneProof };
    return { pairing: this.pairingId, chrome_id: this.chromeId, name, phone_key: phoneKey, phone_proof: phoneProof, phone_sig: await sign(this.phoneKeys.privateKey, LABEL.pair, body) };
  }

  // ---- The relay ----

  /**
   * The relay down, as when planque or the network is (`relayDown()`): the open connection closes and no new one is taken until
   * `relayUp()`. The extension tries again on its own (relay.js `reconnectSoon`, while a run lives).
   */
  relayDown() {
    this.down = true;
    if (this.socket) this.socket.close(1001);
  }

  relayUp() {
    this.down = false;
  }

  upgrade(req, sock) {
    if (this.down) {
      sock.end("HTTP/1.1 503 Service Unavailable\r\n\r\n");
      return;
    }
    const url = new URL(req.url, this.api);
    if (url.pathname !== "/relay" || url.searchParams.get("chrome") !== this.chromeId) {
      sock.end("HTTP/1.1 404 Not Found\r\n\r\n");
      return;
    }
    const ws = new Socket(req, sock);
    const conn = { ws, nonceP: null, ready: false };
    ws.on("message", (text) => this.relayMessage(conn, text).catch((e) => this.emit("error", e)));
    ws.on("close", () => {
      if (this.socket === ws) this.socket = null;
      this.emit("disconnected");
    });
  }

  async relayMessage(conn, text) {
    const m = JSON.parse(text);
    if (conn.ready) this.messages.push(m);
    const { ws } = conn;
    if (!conn.ready) {
      if (m.type === "open" && !conn.nonceP) {
        conn.nonceP = randomId();
        return ws.send(JSON.stringify({ type: "challenge", nonce_p: conn.nonceP }));
      }
      if (m.type === "hello" && conn.nonceP) {
        const body = { chrome_id: m.chrome_id, nonce_p: m.nonce_p, nonce_e: m.nonce_e, version: m.version, manifest_hash: m.manifest_hash };
        const ok = m.chrome_id === this.chromeId && m.nonce_p === conn.nonceP && (await verify(this.extKey, LABEL.hello, body, m.sig));
        if (!ok) {
          ws.send(JSON.stringify({ type: "close", code: 4401, reason: "unknown Chrome" }));
          return ws.close(4401);
        }
        conn.ready = true;
        this.hello = m;
        if (this.socket && this.socket !== ws) this.socket.close(1000);
        this.socket = ws;
        const sig = await sign(this.planqueKeys.privateKey, LABEL.welcome, { chrome_id: this.chromeId, nonce_p: conn.nonceP, nonce_e: m.nonce_e });
        // The connection as planque lists it, with the phone's signature over its sites, as the panel checks it (signedSitesOf).
        // planque's own welcome and connection (contracts/chrome-welcome.json, once planque writes it), with this run's proof.
        ws.send(JSON.stringify(welcomeAsPlanque({ type: "welcome", sig, connections: [await this.connection()], now: Date.now() })));
        this.emit("welcome", m);
        return;
      }
      return ws.close(1008);
    }
    if (m.type === "ping") return ws.send(JSON.stringify({ type: "pong" }));
    if (m.type === "result") return this.resultIn(m);
    if (m.type === "run_state") {
      const body = { run: m.run, state: m.state, at: m.at };
      m.verified = await verify(this.extKey, LABEL.runState, body, m.sig);
      this.emit("run_state", m);
      return ws.send(JSON.stringify({ type: "run_state_kept", run: m.run }));
    }
    if (m.type === "hand_result") {
      const body = { hand: m.hand, outcome: m.outcome, ...(m.page ? { page: m.page } : {}) };
      m.verified = await verify(this.extKey, LABEL.hand, body, m.sig);
      this.emit("hand_result", m);
      return ws.send(JSON.stringify({ type: "hand_kept", hand: m.hand }));
    }
  }

  async resultIn(m) {
    const { type, sig, ...body } = m; // eslint-disable-line no-unused-vars
    m.verified = await verify(this.extKey, LABEL.result, body, sig);
    this.emit("result", m);
    const p = this.pending.get(m.id);
    if (!p) return;
    this.pending.delete(m.id);
    clearTimeout(p.timer);
    p.resolve(m);
  }

  /** Resolves once a connection has completed both proofs (now, when one already has). */
  connected(timeout = 20_000) {
    if (this.socket) return Promise.resolve();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("the extension never connected")), timeout);
      this.once("welcome", () => {
        clearTimeout(timer);
        resolve();
      });
    });
  }

  send(message) {
    if (!this.socket) throw new Error("the extension isn't connected");
    this.socket.send(JSON.stringify(message));
  }

  /** This Chrome's one connection as planque lists it, with the phone's signature over its sites (signedSitesOf checks it). */
  async connection() {
    return connectionAsPlanque({ grant: this.grant, agent: this.agent, name: this.taf, sites: this.sites, sites_signed: await this.sitesSigned() });
  }

  /** planque's `{type: "connections"}` on a change (contracts/chrome-connections.json, once planque writes it). */
  async sendConnections(connections) {
    this.send(connectionsAsPlanque({ type: "connections", connections: connections || [await this.connection()] }));
  }

  // ---- Steps ----

  /** The phone's signature over a grant's sites (`tafs-chrome/sites/v1`). */
  async sitesSigned(sites = this.sites, { grant = this.grant, key = this.phoneKeys.privateKey } = {}) {
    return { sites, sig: await sign(key, LABEL.sites, { chrome_id: this.chromeId, grant, sites }) };
  }

  /** A sensitive site the phone allows for a grant (`tafs-chrome/sensitive/v1`), signed with `key` (the phone's by default). */
  async sensitiveAllowed(site, { grant = this.grant, key = this.phoneKeys.privateKey } = {}) {
    return { site, sig: await sign(key, LABEL.sensitive, { chrome_id: this.chromeId, grant, site }) };
  }

  /**
   * A step as planque signs it: `fields` are the op's own (`op`, `url`, `read_id`, `act`, `hand`, `offset`) and may override any
   * other (`run`, `sites`, `exp`, `chrome_id`, …). `signedSites` are the sites the phone signed (the grant's by default);
   * `key` the key that signs the step (planque's by default).
   */
  async step(fields, { signedSites = this.sites, key = this.planqueKeys.privateKey, phoneKey } = {}) {
    // planque's own step (contracts/chrome-step.json, once planque writes it): its envelope, this step's op and values.
    const step = stepAsPlanque({
      id: randomId("op_"),
      nonce: randomId(),
      exp: Date.now() + 20_000,
      chrome_id: this.chromeId,
      run: "run_e2e",
      taf: this.taf,
      grant: this.grant,
      sites: this.sites,
      sites_signed: await this.sitesSigned(signedSites, phoneKey ? { key: phoneKey } : {}),
      ...fields,
    });
    return { type: "step", step, sig: await sign(key, LABEL.step, step) };
  }

  /** Sends a step message as it is and resolves with the extension's result for its id. */
  sendRaw(message, timeout = 45_000) {
    const id = message.step && message.step.id;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error("no result for " + (message.step && message.step.op) + " " + id));
      }, timeout);
      this.pending.set(id, { resolve, reject, timer });
      this.send(message);
    });
  }

  /** Signs a step (see `step`), sends it, and resolves with the extension's result. */
  async sendStep(fields, opts) {
    return this.sendRaw(await this.step(fields, opts));
  }

  /** planque's unsigned stop of a run. */
  stopRun(run) {
    this.send({ type: "stop", run });
  }

  /** Resolves with the next message of `type` the extension sends that `match` takes. */
  next(type, match = () => true, timeout = 15_000) {
    return new Promise((resolve, reject) => {
      const on = (m) => {
        if (!match(m)) return;
        clearTimeout(timer);
        this.off(type, on);
        resolve(m);
      };
      const timer = setTimeout(() => {
        this.off(type, on);
        reject(new Error("no " + type));
      }, timeout);
      this.on(type, on);
    });
  }
}

/**
 * The fake tafs cloud and KIFF, for the side panel's part of a run (docs/chrome.md, "A taf works where you are"): only what a
 * send from the panel goes through. As **KIFF**, it answers the extension's claim of its view-only device at once, as if the
 * owner had approved it on the phone (`POST /v1/pair/claim`), and its logout. As **tafs cloud**, it keeps the person's page
 * consent (`/v1/me/page-consent`), lists the account's one taf (`GET /v1/tafs`, matched to the connection by its KIFF agent),
 * and answers a message to that taf (`POST /v1/tafs/{id}/messages`) with the agent run it starts (`run`), which planque's steps
 * then name as `agent_run`. The taf's chat (`GET …/conversation`) has the person's messages (`<run>.in`, as tafs cloud names
 * them) and, when a test sets `failNext` (fields over tafs cloud's own `failed`, contracts/conversation-failed.json), the next
 * message's run failed; a message after it that doesn't fail closes it, as tafs cloud does. Every call must carry the claimed
 * session's bearer. Anything else is 404.
 *
 * It answers on the fixture sites' HTTPS server (sites.mjs `serve`), for the hosts the extension calls (`api.kiff.dev`,
 * `cloud.tafs.kiff.dev`), which the harness maps to that server: nothing leaves the computer.
 */
export class FakeCloud {
  static KIFF = "api.kiff.dev";
  static CLOUD = "cloud.tafs.kiff.dev";

  constructor({ agent = "agt_e2e", taf = "Rae", tafId = "taf_e2e", coachId = "taf_coach_e2e" } = {}) {
    Object.assign(this, { agent, taf, tafId, coachId });
    this.token = "kfs_" + randomBytes(16).toString("hex");
    this.tenant = "tnt_e2e";
    this.subject = "sub_e2e";
    this.consent = { given: false };
    this.claimed = false;
    this.sends = []; // every message to the taf: {text, page, run}
    this.coachSends = []; // every message to Coach: {text, page, run}
    this.requests = []; // every call, as "METHOD host path"
    this.thread = []; // the taf's chat, as tafs cloud reads it
    this.failed = null; // its failed run, as tafs cloud's contract has it
    this.failNext = null; // fields over the contract's `failed` for the next message's run
  }

  /** Serves KIFF's and tafs cloud's hosts on `sites` (a FixtureSites). */
  on(sites) {
    sites.serve(FakeCloud.KIFF, (req, res) => this.http(FakeCloud.KIFF, req, res).catch((e) => res.writeHead(500).end(String(e && e.message))));
    sites.serve(FakeCloud.CLOUD, (req, res) => this.http(FakeCloud.CLOUD, req, res).catch((e) => res.writeHead(500).end(String(e && e.message))));
    return this;
  }

  async http(host, req, res) {
    const url = new URL(req.url, "https://" + host);
    this.requests.push(req.method + " " + host + " " + url.pathname);
    const send = (status, body) => {
      res.writeHead(status, body !== undefined ? { "content-type": "application/json" } : {});
      res.end(body !== undefined ? JSON.stringify(body) : undefined);
    };
    const body = req.method === "POST" ? JSON.parse((await readBody(req)) || "{}") : null;
    if (host === FakeCloud.KIFF) {
      if (req.method === "POST" && url.pathname === "/v1/pair/claim") {
        if (typeof body.nonce !== "string" || typeof body.pub !== "string" || typeof body.proof !== "string") return send(400, { error: "invalid_request" });
        this.claimed = true;
        return send(200, { access_token: this.token, approval_mode: "view", tenant: this.tenant, subject: this.subject, expires_in: 3600 });
      }
      if (req.method === "POST" && url.pathname === "/v1/auth/device/logout") return send(204);
      return send(404, { error: "not_found" });
    }
    if (req.headers.authorization !== "Bearer " + this.token) return send(401, { error: "unauthorized" });
    if (url.pathname === "/v1/me/page-consent") {
      if (req.method === "POST") this.consent = { given: true, version: String(body.version || "") };
      if (req.method === "DELETE") this.consent = { given: false };
      return send(200, { consent: this.consent });
    }
    if (req.method === "GET" && url.pathname === "/v1/tafs") return send(200, { tafs: [{ id: this.tafId, name: this.taf, kiff_agent: this.agent }] });
    // The account's Coach: a taf of its own, which the panel's Coach chat sends to.
    if (req.method === "GET" && url.pathname === "/v1/setup") return send(200, { setup: { id: this.coachId } });
    const taf = /^\/v1\/tafs\/([^/]+)\/(messages|conversation)$/.exec(url.pathname);
    if (taf && taf[1] === this.coachId && taf[2] === "conversation" && req.method === "GET") return send(200, { messages: [], working: null });
    if (taf && taf[1] === this.coachId && taf[2] === "messages" && req.method === "POST") {
      const run = randomId("agr_");
      this.coachSends.push({ text: body.text, page: body.page || null, run });
      return send(201, { message: { id: randomId("msg_") }, run });
    }
    if (taf && taf[1] === this.tafId && taf[2] === "conversation" && req.method === "GET") return send(200, { messages: this.thread, working: null, ...(this.failed ? { failed: this.failed } : {}) });
    if (taf && taf[1] === this.tafId && taf[2] === "messages" && req.method === "POST") {
      // The agent run this message starts: what the panel's mark and planque's steps name.
      const run = randomId("agr_");
      const id = run + ".in";
      this.sends.push({ text: body.text, page: body.page || null, run, clientID: body.client_message_id || null });
      this.thread.push({ id, from: "owner", kind: "message", text: body.text, at: new Date().toISOString(), ...(body.page ? { reading: new URL(body.page.url).hostname } : {}) });
      this.failed = this.failNext ? { ...CONTRACT_FAILED, ...this.failNext, run } : null;
      this.failNext = null;
      return send(201, { message: { id }, run });
    }
    return send(404, { error: "not_found" });
  }
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

/**
 * The server's side of one WebSocket (RFC 6455), as much as the relay needs: text messages, fragmented or not, ping, pong
 * and close. Node has a WebSocket client and no server; this keeps the harness free of packages beyond Playwright.
 */
class Socket extends EventEmitter {
  constructor(req, sock) {
    super();
    this.sock = sock;
    this.buf = Buffer.alloc(0);
    this.parts = [];
    this.closed = false;
    const accept = createHash("sha1").update(req.headers["sec-websocket-key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").digest("base64");
    sock.setNoDelay(true);
    sock.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n");
    sock.on("data", (d) => {
      this.buf = Buffer.concat([this.buf, d]);
      this.frames();
    });
    sock.on("close", () => this.gone());
    sock.on("error", () => this.gone());
  }

  gone() {
    if (this.closed) return;
    this.closed = true;
    this.emit("close");
  }

  frames() {
    for (;;) {
      const b = this.buf;
      if (b.length < 2) return;
      const fin = (b[0] & 0x80) !== 0;
      const opcode = b[0] & 0x0f;
      const masked = (b[1] & 0x80) !== 0;
      let len = b[1] & 0x7f;
      let at = 2;
      if (len === 126) {
        if (b.length < 4) return;
        len = b.readUInt16BE(2);
        at = 4;
      } else if (len === 127) {
        if (b.length < 10) return;
        len = Number(b.readBigUInt64BE(2));
        at = 10;
      }
      const maskAt = at;
      if (masked) at += 4;
      if (b.length < at + len) return;
      const payload = Buffer.from(b.subarray(at, at + len));
      if (masked) for (let i = 0; i < len; i++) payload[i] ^= b[maskAt + (i % 4)];
      this.buf = b.subarray(at + len);
      if (opcode === 0x8) {
        this.write(0x8, payload.subarray(0, 2));
        this.sock.end();
        this.gone();
        return;
      }
      if (opcode === 0x9) this.write(0xa, payload);
      if (opcode === 0x1 || opcode === 0x0) {
        this.parts.push(payload);
        if (fin) {
          const text = Buffer.concat(this.parts).toString("utf8");
          this.parts = [];
          this.emit("message", text);
        }
      }
    }
  }

  write(opcode, payload) {
    if (this.closed) return;
    const len = payload.length;
    const head = len < 126 ? Buffer.from([0x80 | opcode, len]) : len < 65536 ? Buffer.from([0x80 | opcode, 126, len >> 8, len & 0xff]) : Buffer.concat([Buffer.from([0x80 | opcode, 127]), (() => {
      const l = Buffer.alloc(8);
      l.writeBigUInt64BE(BigInt(len));
      return l;
    })()]);
    this.sock.write(Buffer.concat([head, payload]));
  }

  send(text) {
    this.write(0x1, Buffer.from(text, "utf8"));
  }

  close(code = 1000) {
    const p = Buffer.alloc(2);
    p.writeUInt16BE(code);
    this.write(0x8, p);
    this.sock.end();
    this.gone();
  }

  destroy() {
    this.sock.destroy();
    this.gone();
  }
}
