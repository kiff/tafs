// The side panel: pairing by the phone's code, and while a taf works, what it's doing, with Pause, Resume and Stop. It
// shows only what the service worker reports; it decides nothing.

import { FRESH, chosen, sendOptions, afterSend, chatTitle, pollDelay, idleDelay, tafOfTab, runOfTab, tafsAtWork, switchDraft } from "../src/coachchat.js";

const t = (key, ...subs) => chrome.i18n.getMessage(key, subs) || key;

for (const el of document.querySelectorAll("[data-m]")) el.textContent = t(el.dataset.m);
for (const el of document.querySelectorAll("[data-m-placeholder]")) el.placeholder = t(el.dataset.mPlaceholder);

const $ = (id) => document.getElementById(id);

function send(message) {
  return chrome.runtime.sendMessage(message);
}

/** A step's site for its line, or "" when its address can't be read: a line never throws, and the panel always draws. */
function hostOf(url) {
  try {
    return url ? new URL(url).hostname : "";
  } catch {
    return "";
  }
}

// Why Show me pointed to no field, in the panel's words (runner.showHand's `why`).
const SHOW_LINE = { page_changed: "hand_show_changed", ref_unknown: "hand_show_hidden", page_unresponsive: "hand_show_unanswered", no_ref: "hand_show_no_field" };
const showSaid = new Map(); // run id → {hand, why}: the last Show me's line for that run's open hand-over
let lastState = {};

const STEP_WORDS = {
  open: (s) => t("step_open", hostOf(s.url)),
  read: () => t("step_read"),
  act: (s) => t("step_act_" + (s.kind || "click")),
  navigate: (s) => t("step_navigate", hostOf(s.url)),
  pause: (s) => t(s.by === "failures" ? "step_paused_failures" : s.by === "no_progress" ? "step_paused_no_progress" : "step_paused"),
  resume: () => t("step_resumed"),
  stop: () => t("step_stopped"),
  // Handed, then the person's answer: each its own line, so Done never reads as a second "Your turn".
  hand: (s) => t(s.kind === "done" ? "step_hand_done" : s.kind === "declined" ? "step_hand_declined" : "step_handed"),
  close: () => t("step_closed"),
};

function stepLine(s) {
  const words = (STEP_WORDS[s.op] || (() => s.op))(s);
  return s.error ? words + " · " + t("step_refused", s.error) : words;
}

let lastConnections = null;
let lastRuns = null;

function draw(state) {
  lastState = state;
  $("conn").textContent = state.paired ? t(state.connected ? "connected" : "waiting") : "";
  const connections = JSON.stringify(state.connections || []);
  if (state.paired && connections !== lastConnections) {
    lastConnections = connections;
    loadTafs();
  }
  const runsSeen = JSON.stringify((state.runs || []).map((r) => [r.id, (r.steps || []).length, r.stopped]));
  if (state.paired && lastRuns !== null && runsSeen !== lastRuns && document.visibilityState === "visible") readTaf();
  lastRuns = runsSeen;
  $("pair").hidden = !!state.paired;
  $("paired").hidden = !state.paired;
  if (!state.paired) return;
  $("pairedName").textContent = t("paired_title", state.name || "");
  const live = (state.runs || []).filter((r) => !r.stopped);
  // D2: the run of the tab in front first, so the card under the chat is the taf's whose tab this is.
  const here = runOfTab(activeTab, live);
  const runs = here ? [here, ...live.filter((r) => r !== here)] : live;
  drawAtWork();
  follow();
  $("idle").hidden = runs.length > 0;
  const box = $("runs");
  box.textContent = "";
  for (const run of runs) {
    const node = $("runTemplate").content.cloneNode(true);
    // The template's words: a template's content isn't part of the document, so the page-wide pass above doesn't reach them.
    for (const el of node.querySelectorAll("[data-m]")) el.textContent = t(el.dataset.m);
    node.querySelector(".taf").textContent = t("working", run.taf || "taf");
    node.querySelector(".where").textContent = run.site || "";
    const paused = node.querySelector(".paused");
    if (run.paused) {
      paused.hidden = false;
      paused.textContent = t(run.paused === "failures" ? "paused_failures" : run.paused === "no_progress" ? "paused_no_progress" : "paused_owner");
    }
    // A step the taf handed to the person: its words as the taf's (plain text, never KIFF's or the extension's line), and three buttons.
    const hand = node.querySelector(".hand");
    if (run.hand) {
      hand.hidden = false;
      node.querySelector(".handWords").textContent = run.hand.words;
      // Show me says what it did when it couldn't point to the field: kept per hand-over, so a redraw keeps the line.
      const shown = node.querySelector(".handShown");
      const said = showSaid.get(run.id);
      shown.hidden = !(said && said.hand === run.hand.id);
      if (!shown.hidden) shown.textContent = t(SHOW_LINE[said.why]);
      node.querySelector(".handShow").onclick = async () => {
        const out = await send({ type: "hand_show", run: run.id }).catch(() => null);
        if (out && out.why && SHOW_LINE[out.why]) showSaid.set(run.id, { hand: run.hand.id, why: out.why });
        else showSaid.delete(run.id);
        draw(lastState);
      };
      node.querySelector(".handDone").onclick = () => send({ type: "hand_answer", run: run.id, outcome: "done" });
      node.querySelector(".handNo").onclick = () => send({ type: "hand_answer", run: run.id, outcome: "declined" });
    }
    const list = node.querySelector(".steps");
    for (const s of run.steps.slice(-12)) {
      const li = document.createElement("li");
      li.textContent = stepLine(s);
      if (s.error) li.className = "refused";
      list.append(li);
    }
    node.querySelector(".pause").hidden = !!run.paused;
    node.querySelector(".resume").hidden = !run.paused;
    node.querySelector(".pause").onclick = () => send({ type: "pause", run: run.id });
    node.querySelector(".resume").onclick = () => send({ type: "resume", run: run.id });
    node.querySelector(".stop").onclick = () => send({ type: "stop", run: run.id });
    box.append(node);
  }
}

chrome.runtime.onMessage.addListener((m) => {
  if (m && m.type === "state") draw(m.state);
});

// D2, several tafs, several tabs (docs/chrome-panel.md): the panel follows the tab in front of its own window. On a tab a
// taf is working in, it shows that taf's chat and its run first; on any other tab, the person's own pick stays.
let activeTab = null;
let panelWindow = null;

function followTab(tabId) {
  activeTab = typeof tabId === "number" ? tabId : null;
  drawReads();
  // Only once the service worker's state is in: an empty one would draw the pairing screen for a moment.
  if (lastState && "paired" in lastState) draw(lastState);
}

chrome.windows.getCurrent().then((w) => {
  panelWindow = w.id;
  return chrome.tabs.query({ active: true, windowId: w.id });
}).then(([tab]) => tab && followTab(tab.id)).catch(() => {});

chrome.tabs.onActivated.addListener(({ tabId, windowId }) => {
  if (panelWindow === null || windowId === panelWindow) followTab(tabId);
});

/** The taf whose tab is in front, picked for the chat: only when it changes, so the person's own pick holds elsewhere. */
let followed = null;
function follow() {
  const taf = tafOfTab(activeTab, (lastState && lastState.runs) || [], tafs);
  const id = taf ? taf.id : null;
  if (id === followed) return;
  followed = id;
  if (!id || id === tafId) return;
  switchTaf(id);
  $("tafPick").value = id;
  $("tafName").textContent = tafName();
  $("tafWords").placeholder = t("taf_placeholder", tafName());
  $("tafThread").textContent = "";
  tafIdle = 0;
  readTaf();
}

/** The panel's head: each taf at work in this Chrome, once; a click brings its tab to the front. */
function drawAtWork() {
  const working = tafsAtWork((lastState && lastState.runs) || [], tafs);
  const list = $("atWorkList");
  list.textContent = "";
  $("atWork").hidden = working.length === 0;
  const hereTaf = tafOfTab(activeTab, (lastState && lastState.runs) || [], tafs);
  for (const w of working) {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "chip quiet" + (w.waiting ? " waiting" : "") + (hereTaf && hereTaf.id === w.id ? " here" : "");
    b.textContent = w.name;
    b.setAttribute("aria-label", t("go_to_taf", w.name));
    b.onclick = () => send({ type: "go_to_run", run: w.run });
    list.append(b);
  }
}

$("pairForm").onsubmit = async (e) => {
  e.preventDefault();
  const status = $("pairStatus");
  status.textContent = t("pair_sending");
  const sent = await send({ type: "pair", code: $("code").value });
  if (sent.error) {
    status.textContent = t("pair_error_" + sent.error);
    return;
  }
  status.textContent = t("pair_waiting");
  for (let i = 0; i < 200; i++) {
    await new Promise((r) => setTimeout(r, 3000));
    const r = await send({ type: "poll" });
    if (r.paired) {
      status.textContent = "";
      return;
    }
    if (r.error) {
      status.textContent = t("pair_error_" + r.error);
      return;
    }
  }
  status.textContent = t("pair_error_expired");
};

$("unpair").onclick = async () => {
  if (!confirm(t("unpair_confirm"))) return;
  await send({ type: "unpair" });
};

chrome.storage.session.get("state").then(({ state }) => state && draw(state));
send({ type: "state" });

// Coach (docs/chrome.md, "Coach in the side panel"): the same chat as the apps. Approvals stay on the phone. The panel opens on
// a fresh conversation, as the phone's New conversation does; the earlier ones are under Previous conversations.
let view = FRESH;

// The chat is read while the panel is seen: often while Coach is at work or the owner just wrote, seldom otherwise, never on a
// fresh conversation (docs/chrome.md, "Coach in the side panel"). Each read sets the next.
let working = false;
let sentAt = 0;
let pollTimer = null;

function schedule() {
  clearTimeout(pollTimer);
  const delay = pollDelay({ view, working, sentAt, now: Date.now() });
  // Coach is behind its button now ("Set up a new taf for this site"): read only while it's shown.
  if (delay == null || document.visibilityState !== "visible" || $("coach").hidden) return;
  pollTimer = setTimeout(drawCoach, delay);
}

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible") {
    if (!$("coach").hidden) drawCoach();
    readTaf();
  } else {
    clearTimeout(pollTimer);
    clearTimeout(tafTimer);
  }
});

async function drawCoach() {
  working = false;
  try {
    await renderCoach();
  } finally {
    schedule();
  }
}

async function renderCoach() {
  const note = $("coachNote");
  note.textContent = "";
  // Which conversation is on show: a new one says so, an earlier one its title.
  $("coachOpen").textContent = view.fresh ? t("coach_new") : openTitle || t("coach_untitled");
  $("coachOpen").hidden = false;
  const list = $("coachThread");
  if (view.fresh) {
    // Nothing to read: a fresh conversation has no chat until its first message. Whether Coach can be reached at all is
    // what the list of chats says.
    const r = await send({ type: "coach_chats" });
    if (r.error) {
      note.textContent = t(r.error === "no_session" ? "unpaired" : "coach_error");
      return;
    }
    list.textContent = "";
    $("coachEmpty").hidden = false;
    $("coachPhone").hidden = true;
    return;
  }
  const r = await send({ type: "coach_thread", session: view.session });
  if (r.error) {
    note.textContent = t(r.error === "no_session" ? "unpaired" : "coach_error");
    return;
  }
  if (!r.thread) {
    note.textContent = t("coach_not_started");
    return;
  }
  list.textContent = "";
  $("coachEmpty").hidden = r.thread.messages.length > 0;
  for (const m of r.thread.messages.slice(-20)) {
    const li = document.createElement("li");
    li.className = m.from;
    li.textContent = m.text;
    // The page that went with this message, by its host alone, as tafs cloud recorded it: never silent.
    if (m.reading) {
      const line = document.createElement("small");
      line.className = "reading";
      line.textContent = t("aside_reading", m.reading);
      li.append(line);
    }
    list.append(li);
  }
  $("coachPhone").hidden = !r.thread.waitsOnPhone;
  working = !!r.thread.working;
  if (working) note.textContent = t("coach_working");
}

/**
 * One list of the person's chats with Coach: each opens on a tap, and has its own Archive (or, in the archived list, Put
 * back). `archived` says which list; `none` and `failed` are its words for an empty list and for one that couldn't be read.
 */
async function drawChats({ archived, list, note, none, failed }) {
  list.textContent = "";
  note.hidden = true;
  const r = await send({ type: archived ? "coach_archived" : "coach_chats" });
  if (r.error || !Array.isArray(r.chats)) {
    note.textContent = t(failed);
    note.hidden = false;
    return;
  }
  if (!r.chats.length) {
    note.textContent = t(none);
    note.hidden = false;
    return;
  }
  for (const c of r.chats) {
    const title = chatTitle(c, t("coach_untitled"));
    const li = document.createElement("li");
    if (!view.fresh && view.session === c.session) li.className = "open";
    const b = document.createElement("button");
    b.className = "quiet chat";
    b.textContent = title;
    b.onclick = () => {
      view = chosen(c.session);
      openTitle = title;
      $("coachArchived").open = false;
      drawCoach();
      drawPast();
    };
    // Archive takes it out of the list (kept, never deleted); Put back returns it. Neither is signed: they give nothing.
    const a = document.createElement("button");
    a.className = "quiet small";
    a.textContent = t(archived ? "coach_unarchive" : "coach_archive");
    a.setAttribute("aria-label", t(archived ? "coach_unarchive_named" : "coach_archive_named", title));
    a.onclick = async () => {
      a.disabled = true;
      const done = await send({ type: "coach_archive", session: c.session, archived: !archived });
      if (done.error) {
        a.disabled = false;
        note.textContent = t("coach_archive_error");
        note.hidden = false;
        return;
      }
      // The chat on show was archived: a new conversation takes its place, so nothing goes on in an archived one.
      if (!archived && !view.fresh && view.session === c.session) startFresh();
      drawPast();
      if ($("coachArchived").open) drawArchived();
    };
    li.append(b, a);
    list.append(li);
  }
}

/** The person's conversations with Coach, newest first, always on show in Coach's view. */
function drawPast() {
  return drawChats({ archived: false, list: $("coachChats"), note: $("coachPastNote"), none: "coach_previous_none", failed: "coach_previous_error" });
}

/** The ones they archived, read when that list is opened. */
function drawArchived() {
  return drawChats({ archived: true, list: $("coachArchivedChats"), note: $("coachArchivedNote"), none: "coach_archived_none", failed: "coach_previous_error" });
}

$("coachArchived").addEventListener("toggle", () => $("coachArchived").open && drawArchived());

// The title of the conversation on show, above its messages; a new one says so.
let openTitle = "";

/** A new conversation with Coach: nothing of the one before is shown or sent into. */
function startFresh() {
  view = FRESH;
  openTitle = "";
  $("coachWords").value = "";
  $("coachArchived").open = false;
  $("coachThread").textContent = "";
  drawCoach();
}

/**
 * Coach in its own view: the taf's chat goes out of sight (not under it), and the runs stay, since Pause, Stop and a
 * hand-over must never be hidden. Back returns to the taf.
 */
function showCoach(on) {
  $("coach").hidden = !on;
  $("tafView").hidden = on;
  if (!on) {
    clearTimeout(pollTimer);
    readTaf();
    return;
  }
  clearTimeout(tafTimer);
  drawPast();
}

$("coachNew").onclick = () => {
  startFresh();
  drawPast();
};

async function sendCoach(alone) {
  const words = $("coachWords").value.trim();
  if (!words) return;
  // The page in front of this panel's window goes with the words only with the person's page consent and if the gate lets
  // it (sw.js, pageconsent.js): only on this send. "Send without the page" sends the words alone, whatever the consent.
  const r = await send({ type: "coach_send", words, alone, clientID: crypto.randomUUID(), tabId: activeTab, windowId: panelWindow, ...sendOptions(view) });
  if (r.error) {
    $("coachNote").textContent = t("coach_error");
    return;
  }
  view = afterSend(view, r.sent);
  sentAt = Date.now();
  $("coachWords").value = "";
  drawCoach();
}

$("coachForm").onsubmit = (e) => {
  e.preventDefault();
  sendCoach(false);
};
$("coachSendAlone").onclick = () => sendCoach(true);

// The taf in this Chrome (docs/chrome-panel.md): the panel is for watching it work and talking to it. Which tafs come from
// planque's list of this Chrome's connections (display only); the chat is the taf's own, as on the phone. It's read at once on
// each event (shown again, a taf picked, a send, a step seen), then every 4 s while it works or a minute after a send, and
// otherwise less and less (60 s to 5 minutes), never while the panel is hidden.
let tafs = [];
let tafId = null;
let tafWorking = false;
let tafSentAt = 0;
let tafIdle = 0;
let tafTimer = null;

const tafName = () => (tafs.find((x) => x.id === tafId) || {}).name || "";

async function loadTafs() {
  const r = await send({ type: "taf_list" });
  tafs = r.error ? [] : r.tafs || [];
  const pick = $("tafPick");
  pick.textContent = "";
  for (const x of tafs) {
    const o = document.createElement("option");
    o.value = x.id;
    o.textContent = x.name;
    pick.append(o);
  }
  if (!tafs.some((x) => x.id === tafId)) switchTaf(tafs.length ? tafs[0].id : null);
  pick.value = tafId || "";
  $("tafPickRow").hidden = tafs.length < 2;
  $("noTaf").hidden = tafs.length > 0 || !!r.error;
  $("tafForm").hidden = !tafId;
  $("tafRemove").hidden = !tafId;
  $("tafRemoveWhy").hidden = true;
  $("tafName").textContent = tafName();
  $("tafWords").placeholder = tafId ? t("taf_placeholder", tafName()) : "";
  $("tafNote").textContent = r.error ? t(r.error === "no_session" ? "unpaired" : "tafs_not_read") : "";
  if (!tafId) $("tafThread").textContent = "";
  tafIdle = 0;
  drawAtWork();
  followed = null;
  follow();
  readTaf();
}

async function readTaf() {
  clearTimeout(tafTimer);
  if (!tafId) return;
  const id = tafId;
  const r = await send({ type: "taf_thread", taf: id });
  if (id !== tafId) return; // another taf was picked meanwhile
  const note = $("tafNote");
  note.textContent = "";
  if (r.error) {
    note.textContent = t(r.error === "no_session" ? "unpaired" : "taf_error", tafName());
  } else {
    const list = $("tafThread");
    list.textContent = "";
    for (const m of r.thread.messages.slice(-20)) {
      const li = document.createElement("li");
      li.className = m.from;
      li.textContent = m.text;
      list.append(li);
    }
    $("tafPhone").hidden = !r.thread.waitsOnPhone;
    tafWorking = !!r.thread.working;
    if (tafWorking) note.textContent = t("working", tafName());
  }
  scheduleTaf();
}

function scheduleTaf() {
  clearTimeout(tafTimer);
  if (!tafId || document.visibilityState !== "visible") return;
  const busy = tafWorking || Date.now() - tafSentAt < 60_000;
  if (busy) tafIdle = 0;
  tafTimer = setTimeout(readTaf, busy ? 4000 : idleDelay(tafIdle++));
}

// What's typed for each taf, kept while another's chat is on show (switchDraft): never sent to another taf.
let drafts = new Map();

/** Shows taf [id]'s chat: what was typed for the one before is kept for it, and the new one's own draft comes back. */
function switchTaf(id) {
  const next = id || null;
  if (next === tafId) return;
  const out = switchDraft(drafts, tafId, next, $("tafWords").value);
  drafts = out.drafts;
  $("tafWords").value = out.text;
  tafId = next;
}

$("tafPick").onchange = () => {
  switchTaf($("tafPick").value || null);
  $("tafName").textContent = tafName();
  $("tafWords").placeholder = tafId ? t("taf_placeholder", tafName()) : "";
  tafIdle = 0;
  readTaf();
};

$("tafForm").onsubmit = async (e) => {
  e.preventDefault();
  const words = $("tafWords").value.trim();
  const to = tafId;
  if (!words || !to) return;
  const r = await send({ type: "taf_send", taf: to, words, clientID: crypto.randomUUID() });
  if (r.error) {
    if (tafId === to) $("tafNote").textContent = t("taf_error", tafName());
    return;
  }
  tafSentAt = Date.now();
  // Sent: those words are gone from the taf they went to, and from nowhere else (the chat may have switched meanwhile).
  if (tafId === to) $("tafWords").value = "";
  else drafts.delete(to);
  readTaf();
};

// Coach sets up a new taf; it's a button now, not the panel's chat.
// "Set up a new taf for this site" always opens a new conversation with Coach, in Coach's own view: never the one already
// open, and never under the taf's chat. Back returns to the taf.
$("setupTaf").onclick = () => {
  showCoach(true);
  startFresh();
};
$("coachBack").onclick = () => showCoach(false);

// A taf is removed on the phone (tafs cloud takes a removal only with the phone's approval key or a computer's passkey,
// which this Chrome has neither of): the button says where, and removes nothing.
$("tafRemoveBtn").onclick = () => {
  $("tafRemoveWhy").hidden = !$("tafRemoveWhy").hidden;
};

// Coach reads the page you're on (docs/chrome.md): above Coach's box, what a send will read, and the person's own switch.
// It's the page in front of this window, read only when they send; a sensitive site or one they switched off is never read.
const READ_WHY = { sensitive: "aside_sensitive", off: "aside_off_line" };
let readsHost = "";

async function drawReads() {
  const line = $("coachReads");
  if (!line) return;
  const r = await send({ type: "aside_status", tabId: activeTab, windowId: panelWindow }).catch(() => null);
  const sw = $("coachReadsSwitch");
  readsHost = (r && r.host) || "";
  // Off until the person allows it (pageconsent.js): the line says so and offers the screen; nothing of a tab is read.
  const consent = Boolean(r && r.consent);
  $("pageConsentOff").hidden = consent;
  $("pageConsentStop").hidden = !consent;
  $("coachSendAlone").hidden = !consent || !readsHost || Boolean(r && r.why);
  if (!consent) {
    line.hidden = true;
    return;
  }
  $("pageConsent").hidden = true;
  if (!r || !readsHost || (r.why && !READ_WHY[r.why])) {
    // Chrome's own pages, an incognito tab, a page with no https address: nothing to read, nothing to say.
    line.hidden = true;
    return;
  }
  line.hidden = false;
  $("coachReadsWords").textContent = r.why ? t(READ_WHY[r.why], readsHost) : t("aside_will", readsHost);
  // The switch: off for a host it reads, back on for one switched off; a sensitive site has none (it's never read).
  sw.hidden = r.why === "sensitive";
  sw.textContent = t(r.why === "off" ? "aside_on" : "aside_off");
  sw.dataset.off = r.why === "off" ? "0" : "1";
}

// The page consent's own screen: what goes to Anthropic, when, and what never does; Allow, or Not now. Withdrawn from the
// line above Coach's box at any time.
$("pageConsentOpen").onclick = () => {
  $("pageConsent").hidden = false;
};
$("pageConsentNotNow").onclick = () => {
  $("pageConsent").hidden = true;
};
$("pageConsentAllow").onclick = async () => {
  const r = await send({ type: "page_allow" }).catch(() => null);
  if (!r || r.error || !r.allowed) $("coachNote").textContent = t("coach_error");
  drawReads();
};
$("pageConsentStop").onclick = async () => {
  await send({ type: "page_withdraw" }).catch(() => null);
  drawReads();
};

$("coachReadsSwitch").onclick = async () => {
  if (!readsHost) return;
  await send({ type: "aside_site", host: readsHost, off: $("coachReadsSwitch").dataset.off === "1" }).catch(() => null);
  drawReads();
};

chrome.tabs.onUpdated.addListener((id, change) => {
  if (id === activeTab && change.url) drawReads();
});
