// The side panel: pairing by the phone's code, and while a taf works, what it's doing, with Pause, Resume and Stop. It
// shows only what the service worker reports; it decides nothing.

import { FRESH, chosen, sendOptions, afterSend, chatTitle, pollDelay } from "../src/coachchat.js";

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
  ask: (s) => t("step_ask", s.site || ""),
  pause: (s) => t(s.by === "failures" ? "step_paused_failures" : s.by === "no_progress" ? "step_paused_no_progress" : "step_paused"),
  resume: () => t("step_resumed"),
  stop: () => t("step_stopped"),
  close: () => t("step_closed"),
};

function stepLine(s) {
  const words = (STEP_WORDS[s.op] || (() => s.op))(s);
  return s.error ? words + " · " + t("step_refused", s.error) : words;
}

function draw(state) {
  $("conn").textContent = state.paired ? t(state.connected ? "connected" : "waiting") : "";
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
    node.querySelector(".taf").textContent = t("working", run.taf || "taf");
    node.querySelector(".where").textContent = run.site || "";
    const paused = node.querySelector(".paused");
    if (run.paused) {
      paused.hidden = false;
      paused.textContent = t(run.paused === "failures" ? "paused_failures" : run.paused === "no_progress" ? "paused_no_progress" : "paused_owner");
    }
    // A site the grant lists that Chrome hasn't let the extension into yet: the owner allows it here, once.
    const asks = node.querySelector(".asks");
    for (const site of run.asks || []) {
      const b = document.createElement("button");
      b.textContent = t("allow_site", site);
      b.onclick = () => chrome.permissions.request({ origins: ["https://" + site + "/*", "https://*." + site + "/*"] }).then(() => send({ type: "state" }));
      asks.append(b);
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
  if (delay == null || document.visibilityState !== "visible") return;
  pollTimer = setTimeout(drawCoach, delay);
}

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible") drawCoach();
  else clearTimeout(pollTimer);
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
  // Chrome asks the owner once for this site: the extension reads nothing it wasn't let into.
  const ok = await chrome.permissions.request({ origins: [target.origin + "/*"] });
  if (!ok) {
    $("coachNote").textContent = t("share_declined");
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

$("share").title = t("share_permission");

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

drawCoach();
