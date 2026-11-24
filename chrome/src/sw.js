// The service worker (docs/chrome.md): it keeps the pairing, connects to planque when paired, and runs what planque sends
// through the runner. It wakes on an alarm while it sleeps, connects, and lets the connection drop when no run is on.

import { asideTab, readForCoach } from "./asideread.js";
import { allowedHere, pageForSend, PAGE_CONSENT_VERSION } from "./pageconsent.js";
import { config } from "./config.js";
import { indexedStore, profileId, forgetSavedSignIns } from "./store.js";
import { actionBadge, actionTitle } from "./coachchat.js";
import { newKeyPair, sha256, b64u, sign, LABEL, manifestHashOf } from "./crypto.js";
import { parseCode, offer, checkConfirmation, postOffer } from "./pairing.js";
import { Runner } from "./runner.js";
import { Relay, MAX_RESULT_BYTES } from "./relay.js";
import { tafsHere } from "./coachchat.js";
import { Coach, reusing } from "./coach.js";
import { ensureSubscription, subscriptionOf, pushMessage } from "./push.js";
import { HandAnswers, handMessage } from "./hand.js";
import { RunStates, runStateMessage } from "./runstate.js";
import { Shots } from "./shot.js";
import { kiffOffer, claimProof, claim, logout as kiffLogout } from "./kiff.js";

const WAKE = "tafs-wake";

let relay = null;

// The picture kept of each act: only in a build that has it on (config.js), never by default.
const shots = config().screenshots
  ? new Shots({ chrome, inTab: (tabId, func, args) => chrome.scripting.executeScript({ target: { tabId }, func, args, world: "ISOLATED" }).then((r) => r && r[0] && r[0].result), keys: () => kept.keys, pairing: () => kept.pairing, base: config().api })
  : null;
// The person's answers to a hand-over, kept (in memory Chrome keeps for the session) until planque says it has them, and sent again on
// each welcome: the relay may be down when the person clicks.
const handAnswers = new HandAnswers({
  get: async () => (await chrome.storage.session.get("hands")).hands,
  set: (hands) => chrome.storage.session.set({ hands }),
});

async function sendHands(only) {
  if (!relay || !relay.connected || !kept.keys) return;
  for (const a of await handAnswers.all()) {
    if (typeof only === "string" && a.hand !== only) continue; // one answer sent again at once, not every one that waits
    // A page over the relay's cap goes as Done with no page: the taf reads again.
    // Bytes, as the relay counts them (gap 4): a page's length in UTF-16 units undercounts Japanese three times over.
    const answer = a.page && new TextEncoder().encode(JSON.stringify(a.page)).length > MAX_RESULT_BYTES ? { ...a, page: null } : a;
    relay.send(await handMessage(kept.keys.privateKey, answer));
  }
}

// Each run's newest Pause, Resume or Stop, kept until planque says it has it, and sent again on each welcome: the taf waits on
// it (runstate.js).
const runStates = new RunStates({
  get: async () => (await chrome.storage.session.get("runStates")).runStates,
  set: (runStates) => chrome.storage.session.set({ runStates }),
});

async function sendRunStates(only) {
  if (!relay || !relay.connected || !kept.keys) return;
  for (const s of await runStates.all()) {
    if (typeof only === "string" && s.run !== only) continue;
    runStates.sending(s);
    relay.send(await runStateMessage(kept.keys.privateKey, s));
  }
}

const doneStore = {
  get: async () => (await chrome.storage.session.get("doneTabs")).doneTabs || {},
  set: (done) => chrome.storage.session.set({ doneTabs: done }),
};
const runner = new Runner({ chrome, onChange: (runs) => publish({ runs }), paired: () => kept.pairing, doneStore, shots, onHandAnswer: (a) => handAnswers.add(a).then(() => sendHands(a.hand)).catch(() => {}), onRunState: (s) => runStates.add(s).then((added) => added && sendRunStates(s.run)).catch(() => {}) });

function publish(extra = {}) {
  const state = { paired: !!kept.pairing, name: kept.pairing ? kept.pairing.name : null, connected: !!(relay && relay.connected), runs: runner.state(),
    connections: (relay && relay.connections) || [], ...extra };
  chrome.storage.session.set({ state }).catch(() => {});
  chrome.runtime.sendMessage({ type: "state", state }).catch(() => {});
  const live = runner.state().filter((r) => !r.stopped);
  chrome.action.setBadgeText({ text: actionBadge(live) }).catch(() => {});
  chrome.action.setBadgeBackgroundColor({ color: "#F29A2E" }).catch(() => {});
  // The toolbar says who is at work, in the extension's words ("Rae is working"); idle, its name, as the manifest has it.
  chrome.action.setTitle({ title: actionTitle(live, (key, sub) => chrome.i18n.getMessage(key, sub)) }).catch(() => {});
}

const kept = { keys: null, pairing: null };

async function load() {
  // The extension keeps no sign-in (docs/chrome.md, "What's kept"): what an earlier version saved is deleted on every start.
  // A delete that fails (the database busy) is tried again on the next load(), which every entry into the worker goes
  // through; nothing reads those keys meanwhile.
  await forgetSavedSignIns(indexedStore).catch(() => {});
  kept.keys = (await indexedStore.get("keys")) || null;
  kept.pairing = (await indexedStore.get("pairing")) || null;
}

async function manifestHash() {
  const raw = await (await fetch(chrome.runtime.getURL("manifest.json"))).arrayBuffer();
  return manifestHashOf(new Uint8Array(raw));
}

/** Unpaired, from the phone (planque closes with 4410) or here: everything kept is wiped and every run closed. */
async function wipe() {
  const session = await indexedStore.get("kiff_session").catch(() => null);
  if (session && session.token) await kiffLogout({ fetch, token: session.token }).catch(() => {});
  if (relay) relay.close();
  relay = null;
  await runner.stopAll();
  await indexedStore.wipe();
  await chrome.storage.session.clear();
  kept.keys = null;
  kept.pairing = null;
  // The wake-up goes with the pairing.
  self.registration.pushManager.getSubscription().then((s) => s && s.unsubscribe()).catch(() => {});
  publish();
}

/** Gives planque this Chrome's push subscription, so it can wake this worker (push.js). Each connect, it is a few hundred bytes. */
async function announcePush() {
  const sub = subscriptionOf(await ensureSubscription(self.registration.pushManager, config().pushKey));
  if (!sub || !relay || !kept.keys || !kept.pairing) return;
  relay.send(await pushMessage(kept.keys.privateKey, sub));
  // planque answers `push_kept` once it has it, and only then does the idle worker stop being kept awake by pings (relay.js).
}

// planque's silent push (no data): a taf needs this Chrome. The worker is awake now; connect.
self.addEventListener("push", (event) => event.waitUntil(connect()));

async function connect() {
  await load();
  const cfg = config();
  if (!kept.pairing || !cfg.planqueKey) return;
  if (!relay) {
    // The steps' nonces survive MV3 stopping this worker: kept in the session's memory store, never on disk.
    const nonceStore = {
      get: async () => (await chrome.storage.session.get("nonces")).nonces || [],
      set: (entries) => chrome.storage.session.set({ nonces: entries }),
    };
    relay = new Relay({ config: { ...cfg, manifestHash: await manifestHash() }, kept, runner, onUnpaired: wipe, onChange: () => publish(), onReady: async () => { await announcePush(); await sendHands(); await sendRunStates(); }, onHandMessage: (m) => handAnswers.heard(m).then((again) => again && sendHands(again)), onRunStateMessage: (m) => runStates.heard(m), nonceStore });
  }
  relay.connect();
}

chrome.alarms.create(WAKE, { periodInMinutes: 1 });
chrome.alarms.onAlarm.addListener(async (a) => {
  if (a.name !== WAKE) return;
  // A sweep that throws (a window Chrome already closed) mustn't skip the idle close below.
  await runner.sweep().catch(() => {});
  // Never closed for being idle: planque can only reach a connected extension (docs/chrome.md, "Waking the extension").
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
  // The KIFF device rides the same pairing: its own key, its view-only offer inside ours, covered by our proof.
  const kiffKeys = await newKeyPair();
  const kiff = await kiffOffer(kiffKeys, { name: "tafs for Chrome" });
  const ua = navigator.userAgentData;
  const body = await offer(code.secret, keys, {
    kiffOffer: kiff.text,
    profileId: await profileId(chrome.storage.local),
    version: cfg.version,
    manifestHash: await manifestHash(),
    browser: "Chrome " + (ua && ua.brands ? (ua.brands.find((b) => b.brand === "Google Chrome") || {}).version || "" : ""),
    os: ua ? ua.platform : "",
  });
  const sent = await postOffer(fetch, cfg.api + "/v1/extension/pairings/" + code.lookup, body);
  // 404: no pairing has that code, or it expired; 409: another Chrome used it; 403: not a released build.
  if (!sent.ok) return { error: sent.status === 404 || sent.status === 409 ? "code" : sent.status === 403 ? "not_released" : "planque" };
  // The secret waits in memory only, while the owner confirms on the phone.
  await chrome.storage.session.set({ pending: { lookup: code.lookup, secret: code.secret, key: body.key } });
  await indexedStore.set("pending_keys", keys);
  await indexedStore.set("pending_kiff", { keys: kiffKeys, nonce: kiff.nonce, pub: kiff.pub, proof: await claimProof(kiffKeys.privateKey, kiff.claimMessage), until: Date.now() + CLAIM_FOR_MS });
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
  claimKiff().catch(() => {});
  await connect();
  publish();
  return { paired: true, name: checked.name };
}

/** How long the KIFF offer is claimed for: 90 tries, 3 s apart, as an iPad polls. */
const CLAIM_FOR_MS = 270_000;
let claiming = null;

/**
 * Collects the KIFF device's session once the phone approved it, polling as an iPad does: every 3 s while the offer
 * lives. Only a view-only session is kept (kiff.claim ends any other); it's wiped with the pairing. MV3 can stop the
 * worker mid-wait, so the claim picks up again when the worker starts and when the panel reads Coach, until its time
 * is up; one claim runs at a time.
 */
function claimKiff() {
  if (!claiming) claiming = claimKiffNow().finally(() => (claiming = null));
  return claiming;
}

async function claimKiffNow() {
  const pending = await indexedStore.get("pending_kiff");
  if (!pending) return;
  const until = pending.until || Date.now() + CLAIM_FOR_MS;
  while (Date.now() < until) {
    const r = await claim({ fetch, nonce: pending.nonce, pub: pending.pub, proof: pending.proof });
    if (r.token) {
      await indexedStore.set("kiff_session", { token: r.token, tenant: r.tenant, subject: r.subject, keys: pending.keys });
      await indexedStore.set("pending_kiff", null);
      publish();
      return;
    }
    if (r.error) break;
    await new Promise((res) => setTimeout(res, 3000));
  }
  await indexedStore.set("pending_kiff", null);
}

async function unpairHere() {
  await load();
  // The KIFF session ends with its own bearer, and the planque key below: one action ends both.
  const session = await indexedStore.get("kiff_session");
  if (session && session.token) await kiffLogout({ fetch, token: session.token }).catch(() => {});
  if (kept.pairing && kept.keys) {
    const body = { chrome_id: kept.pairing.chromeId, at: Date.now() };
    const sig = await sign(kept.keys.privateKey, LABEL.unpair, body);
    await fetch(config().api + "/v1/extension/unpair", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ ...body, sig }) }).catch(() => {});
  }
  await wipe();
}

// Coach in the side panel (docs/chrome.md). The session is the view-only KIFF device's, kept with the pairing and wiped
// with it; without one, the panel says Coach is on the phone.
// One Coach for the session, so the account's Coach id is read once, not on every read of the chat.
const coachFor = reusing((token) => new Coach({ token, lang: chrome.i18n.getUILanguage() }));
async function coach() {
  const session = await indexedStore.get("kiff_session");
  if (!session || !session.token) throw Object.assign(new Error("no session"), { code: "no_session" });
  return coachFor(session.token);
}

/** This Chrome's connections that are the person's tafs (`tafsHere`), the tafs read once per change of the connections. */
async function tafsOfThisChrome(fresh = false, working = null) {
  const connections = (relay && relay.connections) || [];
  const tafs = await (await coach()).keptTafs(JSON.stringify(connections), fresh);
  return tafsHere(connections, tafs, working);
}

async function onlyHere(taf) {
  if (!(await tafsOfThisChrome()).some((t) => t.id === taf)) throw Object.assign(new Error("not a taf of this Chrome"), { code: "not_here" });
}

// The floating bar follows the tab in front of each window, and a page that loads again draws it again.
chrome.tabs.onActivated.addListener(() => runner.queueBars());
chrome.windows.onFocusChanged.addListener(() => runner.queueBars());
chrome.tabs.onUpdated.addListener((id, change, tab) => {
  if (change.status === "complete" && tab && tab.active) runner.queueBars();
});

// Coach reads the page in front (docs/chrome.md, "Coach reads the page you're on"): on the person's send, the tab in front
// of the panel's own window, once, through aside.js; never on a timer, never another tab, never a site the gate refuses.
const ASIDE_OFF = "aside_off";

async function asideOff() {
  return ((await chrome.storage.local.get(ASIDE_OFF))[ASIDE_OFF] || []).filter((h) => typeof h === "string");
}

async function asideStatus(tabId, windowId) {
  const { host, why } = await asideTab(chrome, tabId, windowId, await asideOff());
  return { host, why };
}

async function asideRead(tabId, windowId) {
  return readForCoach(chrome, tabId, windowId, await asideOff());
}

// Coach reads the page in front only with the person's page consent (pageconsent.js), checked before the tab is touched.
const PAGE_CONSENT = "page_consent";

async function pageConsentReady() {
  const session = await indexedStore.get("kiff_session").catch(() => null);
  if (!allowedHere(session, await indexedStore.get(PAGE_CONSENT).catch(() => null))) return false;
  try {
    const c = await (await coach()).pageConsent();
    return c.given === true && c.version === PAGE_CONSENT_VERSION;
  } catch {
    return false;
  }
}

async function pageAllow() {
  const session = await indexedStore.get("kiff_session");
  if (!session || !session.token) throw Object.assign(new Error("no session"), { code: "no_session" });
  const c = await (await coach()).allowPage(PAGE_CONSENT_VERSION);
  if (c.given !== true) return { allowed: false };
  await indexedStore.set(PAGE_CONSENT, { tenant: session.tenant, subject: session.subject, version: PAGE_CONSENT_VERSION, at: Date.now() });
  return { allowed: true };
}

/** Withdrawn: off here at once, whatever tafs cloud answers, then at tafs cloud. */
async function pageWithdraw() {
  await indexedStore.set(PAGE_CONSENT, null);
  await (await coach()).withdrawPage().catch(() => {});
  return { allowed: false };
}

/** "Not on this site": the person's own list of hosts Coach never reads, kept in this Chrome only. */
async function asideSwitch(host, off) {
  if (!host) return { error: "no_host" };
  const now = await asideOff();
  const next = off ? [...new Set([...now, host])] : now.filter((h) => h !== host);
  await chrome.storage.local.set({ [ASIDE_OFF]: next });
  return { off };
}

chrome.runtime.onMessage.addListener((m, sender, reply) => {
  if (!m || typeof m.type !== "string") return;
  // Only the extension's own pages speak to it, apart from a content script's "owner_input".
  if (m.type === "owner_input") {
    if (sender.tab) runner.ownerInput(sender.tab.id);
    return;
  }
  // A press on the floating bar (bar.js): only from the extension's own content script in a tab, and only for a run it holds.
  if (m.type === "bar") {
    if (sender.tab && sender.id === chrome.runtime.id && typeof m.run === "string") runner.barPress(m.run, String(m.act || ""), sender.tab.id).catch(() => {});
    return;
  }
  if (sender.id !== chrome.runtime.id || sender.tab) return;
  const handle = {
    pair: () => pairWith(m.code),
    poll: () => pollPairing(),
    unpair: () => unpairHere(),
    pause: async () => runner.pause(m.run),
    resume: async () => runner.resume(m.run),
    // The panel's Stop is the person's, as the bar's is: the run's group says done at once.
    stop: async () => runner.stop(m.run, false, "person"),
    // tafs cloud says the taf's turn is over (the panel read its chat): its run here stops saying it works (runner.endTurn).
    turn_over: async () => runner.endTurn(String(m.run)),
    go_to_run: async () => runner.goTo(String(m.run)),
    coach_thread: async () => {
      claimKiff().catch(() => {});
      return { thread: await (await coach()).thread(typeof m.session === "string" ? m.session : null) };
    },
    coach_chats: async () => ({ chats: await (await coach()).chats() }),
    coach_archived: async () => ({ chats: await (await coach()).archivedChats() }),
    // Archive one of the person's chats with Coach, or put it back: kept either way, never deleted, and unsigned, as on the phone.
    coach_archive: async () => ({ done: await (await coach()).archive(String(m.session || ""), m.archived !== false) }),
    // The person's answer to a step handed to them, from the panel's card (the extension never acts for them).
    hand_answer: async () => runner.answerHand(String(m.run), m.outcome),
    hand_show: async () => runner.showHand(String(m.run)),
    // The tafs that work in this Chrome (planque's list, display only), as the person's own tafs. The panel asks when it's
    // shown and when the connections change, and that's when the person's tafs are read; a chat's reads and sends reuse them.
    taf_list: async () => {
      const working = runner.state().find((r) => !r.stopped);
      return { tafs: await tafsOfThisChrome(true, working ? working.taf : null) };
    },
    // A taf's own chat: only one this Chrome offers, so the panel reads nothing else.
    taf_thread: async () => {
      await onlyHere(m.taf);
      return { thread: await (await coach()).tafThread(m.taf) };
    },
    taf_send: async () => {
      await onlyHere(m.taf);
      return { sent: await (await coach()).sendToTaf(m.taf, m.words, m.clientID) };
    },
    coach_send: async () => {
      // The page goes only with the person's page consent, here and at tafs cloud, checked before the tab is touched; and
      // never when they chose to send the words alone.
      const page = await pageForSend({ alone: m.alone === true, consent: pageConsentReady, read: () => asideRead(m.tabId, m.windowId) });
      return {
        sent: await (await coach()).send(m.words, { clientID: m.clientID, session: typeof m.session === "string" ? m.session : null, newChat: m.newChat === true, page: page.page }),
        reading: page.page ? page.host : null,
        notReading: page.why || null,
      };
    },
    aside_status: async () => ({ ...(await asideStatus(m.tabId, m.windowId)), consent: await pageConsentReady() }),
    page_allow: async () => pageAllow(),
    page_withdraw: async () => pageWithdraw(),
    aside_site: async () => asideSwitch(String(m.host || ""), m.off === true),
    state: async () => {
      await load();
      publish();
    },
  }[m.type];
  if (!handle) return;
  handle().then((r) => reply(r || {}), (e) => reply({ error: (e && e.code) || "failed", detail: String(e && e.message || e) }));
  return true;
});

connect().catch(() => {});
// A KIFF claim the worker was stopped in the middle of picks up again.
claimKiff().catch(() => {});
