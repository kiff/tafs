// The side panel: pairing by the phone's code, and while a taf works, what it's doing, with Pause, Resume and Stop. It
// shows only what the service worker reports; it decides nothing.

import { FRESH, chosen, sendOptions, afterSend, chatTitle, pollDelay, idleDelay } from "../src/coachchat.js";

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

const STEP_WORDS = {
  open: (s) => t("step_open", hostOf(s.url)),
  read: () => t("step_read"),
  act: (s) => t("step_act_" + (s.kind || "click")),
  navigate: (s) => t("step_navigate", hostOf(s.url)),
  pause: (s) => t(s.by === "failures" ? "step_paused_failures" : s.by === "no_progress" ? "step_paused_no_progress" : "step_paused"),
  resume: () => t("step_resumed"),
  stop: () => t("step_stopped"),
  hand: (s) => t("hand_title") + (s.kind === "declined" ? " · " + t("hand_declined") : ""),
  close: () => t("step_closed"),
};

function stepLine(s) {
  const words = (STEP_WORDS[s.op] || (() => s.op))(s);
  return s.error ? words + " · " + t("step_refused", s.error) : words;
}

let lastConnections = null;
let lastRuns = null;

function draw(state) {
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
  const runs = (state.runs || []).filter((r) => !r.stopped);
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
      node.querySelector(".handShow").onclick = () => send({ type: "hand_show", run: run.id });
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

// Saved sign-ins (sealed): the panel shows each site and whether a username is saved, never a value. A saved sign-in is
// only ever typed into that exact site, and only after you approve that fill on your phone.
async function drawVault() {
  const { entries = [] } = await send({ type: "vault_list" });
  const list = $("vaultList");
  list.textContent = "";
  for (const e of entries) {
    const li = document.createElement("li");
    li.textContent = new URL(e.origin).hostname + (e.hasUsername ? " · " + t("vault_with_username") : "");
    const b = document.createElement("button");
    b.className = "quiet";
    b.textContent = t("vault_remove");
    b.onclick = async () => {
      await send({ type: "vault_remove", id: e.id });
      drawVault();
    };
    li.append(" ", b);
    list.append(li);
  }
}

$("vaultForm").onsubmit = async (e) => {
  e.preventDefault();
  const r = await send({ type: "vault_save", origin: $("vaultSite").value, username: $("vaultUser").value, password: $("vaultPass").value });
  $("vaultPass").value = "";
  $("vaultStatus").textContent = r.error ? t("vault_error") : t("vault_saved");
  drawVault();
};

$("vault").addEventListener("toggle", () => $("vault").open && drawVault());

// Coach (docs/chrome.md, "Coach in the side panel"): the same chat as the apps. Approvals stay on the phone. The panel opens on
// a fresh conversation, as the phone's New conversation does; the earlier ones are under Previous conversations.
let shared = null;
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
    list.append(li);
  }
  $("coachPhone").hidden = !r.thread.waitsOnPhone;
  working = !!r.thread.working;
  if (working) note.textContent = t("coach_working");
}

/** The earlier chats, newest first; a tap shows one. */
async function drawPast() {
  const note = $("coachPastNote");
  const list = $("coachChats");
  list.textContent = "";
  note.hidden = true;
  const r = await send({ type: "coach_chats" });
  if (r.error || !Array.isArray(r.chats)) {
    note.textContent = t("coach_previous_error");
    note.hidden = false;
    return;
  }
  if (!r.chats.length) {
    note.textContent = t("coach_previous_none");
    note.hidden = false;
    return;
  }
  for (const c of r.chats) {
    const li = document.createElement("li");
    const b = document.createElement("button");
    b.className = "quiet";
    b.textContent = chatTitle(c, t("coach_untitled"));
    b.onclick = () => {
      view = chosen(c.session);
      $("coachPast").open = false;
      drawCoach();
    };
    li.append(b);
    list.append(li);
  }
}

$("coachPast").addEventListener("toggle", () => $("coachPast").open && drawPast());

$("coachNew").onclick = () => {
  view = FRESH;
  shared = null;
  $("sharePreview").hidden = true;
  $("coachWords").value = "";
  $("coachPast").open = false;
  $("coachThread").textContent = "";
  drawCoach();
};

$("share").onclick = async () => {
  const target = await send({ type: "share_target" });
  if (target.error) {
    $("coachNote").textContent = chrome.i18n.getMessage("share_error_" + target.error) || t("share_unavailable");
    return;
  }
  const read = await send({ type: "share_read", tabId: target.tabId });
  if (read.error) {
    $("coachNote").textContent = t("share_unavailable");
    return;
  }
  shared = read.page;
  $("shareWhat").textContent = shared.title + " · " + new URL(shared.url).hostname + " · " + t("share_chars", String(shared.text.length));
  $("sharePreview").hidden = false;
};

$("shareRemove").onclick = () => {
  shared = null;
  $("sharePreview").hidden = true;
};

$("coachForm").onsubmit = async (e) => {
  e.preventDefault();
  const words = $("coachWords").value.trim();
  if (!words) return;
  const r = await send({ type: "coach_send", words, page: shared, clientID: crypto.randomUUID(), ...sendOptions(view) });
  if (r.error) {
    $("coachNote").textContent = t("coach_error");
    return;
  }
  view = afterSend(view, r.sent);
  sentAt = Date.now();
  $("coachWords").value = "";
  shared = null;
  $("sharePreview").hidden = true;
  drawCoach();
};

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
  if (!tafs.some((x) => x.id === tafId)) tafId = tafs.length ? tafs[0].id : null;
  pick.value = tafId || "";
  $("tafPickRow").hidden = tafs.length < 2;
  $("noTaf").hidden = tafs.length > 0 || !!r.error;
  $("tafForm").hidden = !tafId;
  $("tafName").textContent = tafName();
  $("tafWords").placeholder = tafId ? t("taf_placeholder", tafName()) : "";
  $("tafNote").textContent = r.error ? t(r.error === "no_session" ? "unpaired" : "tafs_not_read") : "";
  if (!tafId) $("tafThread").textContent = "";
  tafIdle = 0;
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

$("tafPick").onchange = () => {
  tafId = $("tafPick").value || null;
  $("tafName").textContent = tafName();
  $("tafWords").placeholder = tafId ? t("taf_placeholder", tafName()) : "";
  tafIdle = 0;
  readTaf();
};

$("tafForm").onsubmit = async (e) => {
  e.preventDefault();
  const words = $("tafWords").value.trim();
  if (!words || !tafId) return;
  const r = await send({ type: "taf_send", taf: tafId, words, clientID: crypto.randomUUID() });
  if (r.error) {
    $("tafNote").textContent = t("taf_error", tafName());
    return;
  }
  tafSentAt = Date.now();
  $("tafWords").value = "";
  readTaf();
};

// Coach sets up a new taf; it's a button now, not the panel's chat.
$("setupTaf").onclick = () => {
  $("coach").hidden = !$("coach").hidden;
  if (!$("coach").hidden) drawCoach();
  else clearTimeout(pollTimer);
};
