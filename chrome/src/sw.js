// The service worker (docs/chrome.md): it keeps the pairing, connects to planque when paired, and runs what planque sends
// through the runner. It wakes on an alarm while it sleeps, connects, and lets the connection drop when no run is on.

import { config } from "./config.js";
import { indexedStore } from "./store.js";
import { newKeyPair, sha256, b64u, sign, LABEL } from "./crypto.js";
import { parseCode, offer, checkConfirmation } from "./pairing.js";
import { Runner } from "./runner.js";
import { Relay } from "./relay.js";

const WAKE = "tafs-wake";
const IDLE_CLOSE_MS = 60_000;

let relay = null;
let lastActive = 0;

const runner = new Runner({ chrome, onChange: (runs) => publish({ runs }) });

function publish(extra = {}) {
  const state = { paired: !!kept.pairing, name: kept.pairing ? kept.pairing.name : null, connected: !!(relay && relay.connected), runs: runner.state(), ...extra };
  chrome.storage.session.set({ state }).catch(() => {});
  chrome.runtime.sendMessage({ type: "state", state }).catch(() => {});
  chrome.action.setBadgeText({ text: runner.active ? "ON" : "" }).catch(() => {});
  chrome.action.setBadgeBackgroundColor({ color: "#F29A2E" }).catch(() => {});
}

const kept = { keys: null, pairing: null };

async function load() {
  kept.keys = (await indexedStore.get("keys")) || null;
  kept.pairing = (await indexedStore.get("pairing")) || null;
}

async function manifestHash() {
  const raw = await (await fetch(chrome.runtime.getURL("manifest.json"))).arrayBuffer();
  return b64u(await sha256(new Uint8Array(raw)));
}

/** Unpaired, from the phone (planque closes with 4410) or here: everything kept is wiped and every run closed. */
async function wipe() {
  if (relay) relay.close();
  relay = null;
  await runner.stopAll();
  await indexedStore.wipe();
  await chrome.storage.session.clear();
  kept.keys = null;
  kept.pairing = null;
  publish();
}

async function connect() {
  await load();
  const cfg = config();
  if (!kept.pairing || !cfg.planqueKey) return;
  if (!relay) {
    relay = new Relay({ config: { ...cfg, manifestHash: await manifestHash() }, kept, runner, onUnpaired: wipe, onChange: () => publish() });
  }
  relay.connect();
  lastActive = Date.now();
}

chrome.alarms.create(WAKE, { periodInMinutes: 1 });
chrome.alarms.onAlarm.addListener(async (a) => {
  if (a.name !== WAKE) return;
  if (runner.active) lastActive = Date.now();
  if (relay && relay.connected && !runner.active && Date.now() - lastActive > IDLE_CLOSE_MS) {
    relay.close();
    return;
  }
  await connect();
});
chrome.runtime.onStartup.addListener(connect);
chrome.runtime.onInstalled.addListener(connect);
chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true }).catch(() => {});

async function pairWith(text) {
  const code = parseCode(text);
  if (!code) return { error: "code" };
  const cfg = config();
  if (!cfg.planqueKey) return { error: "not_released" };
  const keys = await newKeyPair();
  const ua = navigator.userAgentData;
  const body = await offer(code.secret, keys, {
    version: cfg.version,
    manifestHash: await manifestHash(),
    browser: "Chrome " + (ua && ua.brands ? (ua.brands.find((b) => b.brand === "Google Chrome") || {}).version || "" : ""),
    os: ua ? ua.platform : "",
  });
  const sent = await fetch(cfg.api + "/v1/extension/pairings/" + code.lookup, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
  // 404: no pairing has that code, or it expired; 409: another Chrome used it; 403: not a released build.
  if (!sent.ok) return { error: sent.status === 404 || sent.status === 409 ? "code" : sent.status === 403 ? "not_released" : "planque" };
  // The secret waits in memory only, while the owner confirms on the phone.
  await chrome.storage.session.set({ pending: { lookup: code.lookup, secret: code.secret, key: body.key } });
  await indexedStore.set("pending_keys", keys);
  return { waiting: true };
}

async function pollPairing() {
  const { pending } = await chrome.storage.session.get("pending");
  const pendingKeys = await indexedStore.get("pending_keys");
  if (!pending || !pendingKeys) return { error: "none" };
  const cfg = config();
  const res = await fetch(cfg.api + "/v1/extension/pairings/" + pending.lookup + "?key=" + encodeURIComponent(pending.key));
  if (res.status === 202) return { waiting: true };
  if (!res.ok) return { error: res.status === 404 || res.status === 410 ? "expired" : "planque" };
  const checked = await checkConfirmation(pending.secret, pendingKeys, await res.json());
  await chrome.storage.session.remove("pending");
  await indexedStore.set("pending_keys", null);
  if (!checked) return { error: "proof" };
  await indexedStore.set("keys", pendingKeys);
  await indexedStore.set("pairing", { ...checked, pairedAt: Date.now() });
  await connect();
  publish();
  return { paired: true, name: checked.name };
}

async function unpairHere() {
  await load();
  if (kept.pairing && kept.keys) {
    const body = { chrome_id: kept.pairing.chromeId, at: Date.now() };
    const sig = await sign(kept.keys.privateKey, LABEL.unpair, body);
    await fetch(config().api + "/v1/extension/unpair", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ ...body, sig }) }).catch(() => {});
  }
  await wipe();
}

chrome.runtime.onMessage.addListener((m, sender, reply) => {
  if (!m || typeof m.type !== "string") return;
  // Only the extension's own pages speak to it, apart from a content script's "owner_input".
  if (m.type === "owner_input") {
    if (sender.tab) runner.ownerInput(sender.tab.id);
    return;
  }
  if (sender.id !== chrome.runtime.id || sender.tab) return;
  const handle = {
    pair: () => pairWith(m.code),
    poll: () => pollPairing(),
    unpair: () => unpairHere(),
    pause: async () => runner.pause(m.run),
    resume: async () => runner.resume(m.run),
    stop: async () => runner.stop(m.run),
    state: async () => {
      await load();
      publish();
    },
  }[m.type];
  if (!handle) return;
  handle().then((r) => reply(r || {}), (e) => reply({ error: "failed", detail: String(e && e.message || e) }));
  return true;
});

connect().catch(() => {});
