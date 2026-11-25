// The side panel: pairing by the phone's code, and while a taf works, what it's doing, with Pause, Resume and Stop. It
// shows only what the service worker reports; it decides nothing.

import { FRESH, chosen, sendOptions, afterSend, chatTitle, pollDelay, idleDelay, tafOfTab, runOfTab, tafsAtWork, switchDraft, turnOver, stillPending, lapsed, turnFailed, retryOf, failedKey, whyLine } from "../src/coachchat.js";
import { render as renderWords } from "../src/chatmd.js";
import { ACCEPT, prepare, upload, readyIDs, uploading, counted, afterSent } from "../src/attach.js";
import { startsDay, timeLabel, dayLabel, workLine, workRows, pluralKey, canReport, reportBody, reportOutcome, ownerActions, cardShown, REPORT_NOTE_MAX } from "../src/chatrow.js";

const t = (key, ...subs) => chrome.i18n.getMessage(key, subs) || key;
// The panel's language, for each message's time and a day's header (chatrow.js), as its words are.
const LANG = chrome.i18n.getUILanguage();

/**
 * A message's words, in their own box: the person's as they typed them; a taf's or Coach's with their Markdown drawn, opening
 * no link, under its run's work folded into one line when it carries it (the phones' `WorkSummary`).
 */
function words(li, m) {
  if (m.from === "taf" && m.work) li.append(workFold(m));
  const box = document.createElement("div");
  box.className = "words";
  if (m.from === "taf") box.append(renderWords(document, m.text));
  else box.textContent = m.text;
  li.append(box);
}

for (const el of document.querySelectorAll("[data-m]")) el.textContent = t(el.dataset.m);
for (const el of document.querySelectorAll("[data-m-placeholder]")) el.placeholder = t(el.dataset.mPlaceholder);
// A button drawn as a sign (the composer's ↑): its words are its accessible name and its tooltip.
for (const el of document.querySelectorAll("[data-m-label]")) {
  el.setAttribute("aria-label", t(el.dataset.mLabel));
  el.title = t(el.dataset.mLabel);
}
// The composers, as the app's chat: Enter sends, Shift+Enter is a new line, and nothing sends while an input method is
// still composing a word.
for (const form of document.querySelectorAll("form.composer")) {
  form.querySelector("textarea").addEventListener("keydown", (e) => {
    if (e.key !== "Enter" || e.shiftKey || e.isComposing || e.keyCode === 229) return;
    e.preventDefault();
    form.requestSubmit();
  });
}

const $ = (id) => document.getElementById(id);

function send(message) {
  return chrome.runtime.sendMessage(message);
}

/**
 * A message whose answer nobody waits for (a press, the first state): a worker that isn't there yet (stopped, reloaded) is no
 * error to leave unhandled in chrome://extensions. The press does what it did; its failure says nothing, as before.
 */
function quietly(message) {
  return send(message).catch(() => null);
}

/**
 * The page that went with a message (Coach's or a taf's), by its host alone, as tafs cloud recorded it: never silent.
 */
function reading(li, m) {
  if (!m.reading) return;
  const line = document.createElement("small");
  line.className = "reading";
  line.textContent = t("aside_reading", m.reading);
  li.append(line);
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

/**
 * Why a step wasn't done, in words: the code's own line when there is one (`step_refused_<code>`, "not done: the tab moved
 * without the taf, so it's yours now"), else the code as it is ("not done (<code>)"). Only a plain code names a key.
 */
function stepRefusedWords(code) {
  const own = /^[a-z_]{1,40}$/.test(String(code)) ? chrome.i18n.getMessage("step_refused_" + code) : "";
  return own || t("step_refused", code);
}

function stepLine(s) {
  const words = (STEP_WORDS[s.op] || (() => s.op))(s);
  return s.error ? words + " · " + stepRefusedWords(s.error) : words;
}

let lastConnections = null;
let lastRuns = null;
// The runs whose turn tafs cloud said was over (coachchat.js `turnOver`), each with how many steps it had then: its card gives
// way to the answer's folded work until a step comes after (chatrow.js `cardShown`).
const overRuns = new Map();

function draw(state) {
  lastState = state;
  $("conn").textContent = state.paired ? t(state.connected ? "connected" : "waiting") : "";
  $("menuBtn").hidden = !state.paired;
  if (!state.paired) closeMenu();
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
  // A run whose turn tafs cloud says is over shows as its answer's folded work, not as a card too (chatrow.js `cardShown`).
  for (const id of overRuns.keys()) if (!live.some((r) => r.id === id)) overRuns.delete(id);
  const runs = (here ? [here, ...live.filter((r) => r !== here)] : live).filter((r) => cardShown(r, overRuns));
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
      node.querySelector(".handDone").onclick = () => quietly({ type: "hand_answer", run: run.id, outcome: "done" });
      node.querySelector(".handNo").onclick = () => quietly({ type: "hand_answer", run: run.id, outcome: "declined" });
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
    node.querySelector(".pause").onclick = () => quietly({ type: "pause", run: run.id });
    node.querySelector(".resume").onclick = () => quietly({ type: "resume", run: run.id });
    node.querySelector(".stop").onclick = () => quietly({ type: "stop", run: run.id });
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
  openHere();
  return chrome.tabs.query({ active: true, windowId: w.id });
}).then(([tab]) => tab && followTab(tab.id)).catch(() => {});

// The service worker knows this panel is open, in this window, for as long as this port is (panels.js): a run the person starts
// from here works in the tab in front, and Chrome follows it only while the panel is open. The worker may stop and drop the port;
// the panel connects again. A connect that found no worker ("Receiving end does not exist") ends in the same disconnect, its
// error read there: Chrome logs an error nobody reads as "Unchecked runtime.lastError".
function openHere() {
  let port;
  try {
    port = chrome.runtime.connect({ name: "panel" });
  } catch {
    return; // the extension was reloaded: this panel is gone with it
  }
  port.onDisconnect.addListener(() => {
    void chrome.runtime.lastError; // read, so it's checked: the panel connects again either way
    setTimeout(openHere, 1000);
  });
  try {
    port.postMessage({ windowId: panelWindow });
  } catch {
    // Already disconnected: the listener above connects again.
  }
}

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
    b.onclick = () => quietly({ type: "go_to_run", run: w.run });
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
  await quietly({ type: "unpair" });
};

chrome.storage.session.get("state").then(({ state }) => state && draw(state)).catch(() => {});
quietly({ type: "state" });

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
  const atEnd = nearEnd();
  const focused = keyInFocus(list);
  list.textContent = "";
  $("coachEmpty").hidden = r.thread.messages.length > 0 || !!coachPending;
  // Send again goes as Try again does; Edit and send puts the words back in the box. Both only in a chat begun in this Chrome
  // (the worker's `began`): a run's chat shown here is read-only to the panel.
  const session = view.session;
  drawMessages(list, r.thread, {
    retry: (again) => sendCoach(false, again),
    again: (again) => sendCoach(false, again),
    edit: (text) => editIn($("coachWords"), text),
    began: r.thread.began === true,
    session,
    taf: null,
  });
  $("coachPhone").hidden = !r.thread.waitsOnPhone;
  // A failed turn ends the turn: no working line under it, whatever the chat still says.
  working = !!r.thread.working && !turnFailed(r.thread);
  if (coachPending && !stillPending(coachPending, r.thread.messages)) {
    if (lapsed(coachPending, r.thread.messages)) coachLapsed = true;
    coachPending = null;
  }
  // A message that never reached the chat in 2 minutes is said, never taken away with nothing said.
  if (coachLapsed) note.textContent = t("coach_error");
  // What was just sent stays on show until the chat has it, and Coach's working line goes under the last message, as in the app.
  if (coachPending) showPending(list, coachPending.words);
  if (coachPending || working) list.append(workingLine(t("coach_working")));
  refocus(list, focused);
  if (atEnd) toEnd();
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
    const name = document.createElement("span");
    name.className = "chatTitle";
    name.textContent = title;
    b.append(name);
    const when = whenShown(c.updated);
    if (when) {
      const date = document.createElement("span");
      date.className = "chatWhen";
      date.textContent = when;
      b.append(date);
    }
    b.onclick = () => {
      view = chosen(c.session);
      openTitle = title;
      $("coachArchived").open = false;
      showView("coach");
      drawCoach();
    };
    // Archive takes it out of the list (kept, never deleted); Put back returns it. Neither is signed: they give nothing.
    // It sits behind the row's ⋯, so the list reads as conversations, not as a column of buttons.
    const more = document.createElement("button");
    more.type = "button";
    more.className = "quiet iconbtn small";
    more.textContent = "⋯";
    more.setAttribute("aria-label", t("coach_row_menu", title));
    more.setAttribute("aria-expanded", "false");
    const a = document.createElement("button");
    a.hidden = true;
    a.className = "quiet small";
    a.textContent = t(archived ? "coach_unarchive" : "coach_archive");
    a.setAttribute("aria-label", t(archived ? "coach_unarchive_named" : "coach_archive_named", title));
    more.onclick = () => {
      a.hidden = !a.hidden;
      more.setAttribute("aria-expanded", String(!a.hidden));
      if (!a.hidden) a.focus();
    };
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
    li.append(b, more, a);
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
  coachLapsed = false;
  openTitle = "";
  $("coachWords").value = "";
  $("coachArchived").open = false;
  $("coachThread").textContent = "";
  drawCoach();
}

/**
 * One view at a time (docs/chrome.md, "The side panel"): the taf's chat, Coach's chat, the conversations with Coach, or
 * Settings. The runs stay below whichever is on show, since Pause, Stop and a hand-over must never be hidden.
 */
const VIEWS = ["tafView", "coach", "chats", "settings"];
let current = "tafView";
let backTo = "tafView"; // where Back goes from the conversations or Settings: the chat it was opened from

function showView(name) {
  if (current === "coach" && name !== "coach") clearTimeout(pollTimer);
  if (current === "tafView" && name !== "tafView") clearTimeout(tafTimer);
  for (const v of VIEWS) $(v).hidden = v !== name;
  current = name;
  // The pairing's lines show on the taf's own view; Coach, its chats and Settings get the room (panel.css).
  $("paired").dataset.view = name;
  closeMenu();
  if (name === "tafView") readTaf();
  if (name === "coach" || name === "tafView") drawReads();
  if (name === "chats") drawPast();
  if (name === "settings") drawSettings();
}

/** Coach in its own view, or back to the taf. */
function showCoach(on) {
  showView(on ? "coach" : "tafView");
}

/** A chat to come back to from the conversations or Settings. */
function opensFrom() {
  return current === "coach" || current === "tafView" ? current : backTo;
}

// The ⋮ menu (top right): set up a new taf for this site, the conversations with Coach, and Settings. The chat screens
// hold only the chat.
function closeMenu() {
  $("menu").hidden = true;
  $("menuBtn").setAttribute("aria-expanded", "false");
}
$("menuBtn").setAttribute("aria-label", t("menu_label"));
$("menuBtn").onclick = (e) => {
  e.stopPropagation();
  const open = $("menu").hidden;
  $("menu").hidden = !open;
  $("menuBtn").setAttribute("aria-expanded", String(open));
  if (open) $("menu").querySelector("button").focus();
};
document.addEventListener("click", (e) => {
  if (!$("menu").hidden && !$("menu").contains(e.target)) closeMenu();
});
document.addEventListener("keydown", (e) => {
  if (e.key !== "Escape") return;
  if (!$("report").hidden) return closeReport();
  if (!$("pageConsent").hidden) return closeConsent();
  if (!$("menu").hidden) {
    closeMenu();
    $("menuBtn").focus();
  }
});
// "Set up a new taf for this site" always opens a new conversation with Coach, in Coach's own view: never the one already
// open, and never under the taf's chat. Back returns to the taf.
$("menuSetup").onclick = () => {
  showView("coach");
  startFresh();
};
$("menuChats").onclick = () => {
  backTo = opensFrom();
  showView("chats");
};
$("menuSettings").onclick = () => {
  backTo = opensFrom();
  showView("settings");
};
$("chatsBack").onclick = () => showView(backTo);
$("settingsBack").onclick = () => showView(backTo);

/** When a conversation was last written, in the panel's language: the time today, else the day. */
function whenShown(at) {
  const d = new Date(at);
  if (!at || Number.isNaN(d.getTime())) return "";
  const lang = chrome.i18n.getUILanguage();
  const today = new Date().toDateString() === d.toDateString();
  return today ? d.toLocaleTimeString(lang, { hour: "2-digit", minute: "2-digit" }) : d.toLocaleDateString(lang, { day: "numeric", month: "short" });
}

$("coachNew").onclick = () => {
  startFresh();
  drawPast();
};

/**
 * Sends what's in Coach's box, or [again], the words of a failed turn's Retry: those go as a new message on their own, with no
 * file, and leave the box and its files as they are.
 */
async function sendCoach(alone, again = null) {
  const words = again != null ? String(again).trim() : $("coachWords").value.trim();
  // Send waits while a file is still going up; a message may be words, files, or both.
  if (again == null && uploading(trayOf(COACH_TRAY))) return;
  const files = again != null ? [] : readyIDs(trayOf(COACH_TRAY));
  if (!words && !files.length) return;
  coachLapsed = false;
  // Shown at once, as in the app: the person's message at the bottom right and Coach's working line under it, before tafs
  // cloud has answered. The box is cleared now; on a failure the words come back to it, never lost.
  coachPending = { words: words || fileNames(COACH_TRAY), id: null, at: Date.now() };
  if (again == null) $("coachWords").value = "";
  $("coachNote").textContent = "";
  $("coachEmpty").hidden = true;
  const list = $("coachThread");
  showPending(list, words || fileNames(COACH_TRAY));
  list.append(workingLine(t("coach_working")));
  toEnd();
  // The page in front of this panel's window goes with the words only with the person's page consent and if the gate lets
  // it (sw.js, pageconsent.js): only on this send. "Send without the page" sends the words alone, whatever the consent.
  const r = await send({ type: "coach_send", words, alone, attachments: files, clientID: crypto.randomUUID(), tabId: activeTab, windowId: panelWindow, ...sendOptions(view) }).catch(() => ({ error: "failed" }));
  if (r.error) {
    coachPending = null;
    for (const el of list.querySelectorAll(".pending, .working")) el.remove();
    if (!$("coachWords").value) $("coachWords").value = words;
    $("coachNote").textContent = t("coach_error");
    return;
  }
  // From now on it's matched by the id tafs cloud gave it; an answer with none leaves the chat as tafs cloud reads it.
  const id = sentID(r.sent);
  if (coachPending && coachPending.words === (words || coachPending.words)) coachPending = id ? { ...coachPending, id } : null;
  // The page in front didn't go: the message says why, under it (never silent).
  const why = id && whyLine(r.notReading, r.host);
  if (why) notRead.set(id, why);
  // The files it carried are gone from the tray; any that didn't go up stay, with why.
  setTray(COACH_TRAY, afterSent(trayOf(COACH_TRAY), files));
  view = afterSend(view, r.sent);
  sentAt = Date.now();
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
// The messages sent from here whose page in front didn't go, and why (coachchat.js `whyLine`): message id → {key, host?, consent?}.
const notRead = new Map();
// The failed turns whose Retry was pressed (coachchat.js `failedKey`): pressed once, it never comes back.
const retried = new Set();
// A message that never reached the chat in 2 minutes (coachchat.js `lapsed`): Coach's, or the taf it went to.
let coachLapsed = false;
let tafLapsed = null;

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
    const atEnd = nearEnd();
    const focused = keyInFocus(list);
    list.textContent = "";
    // A taf's chat in the panel is always one this Chrome began (tafs cloud reads it its own newest), so Send again and Edit and
    // send are there; each answer names its chat for a report.
    drawMessages(list, r.thread, {
      retry: (again) => sendTaf(id, again, []),
      again: (again) => sendTaf(id, again, []),
      edit: (text) => id === tafId && editIn($("tafWords"), text),
      began: true,
      session: null,
      taf: id,
    });
    $("tafPhone").hidden = !r.thread.waitsOnPhone;
    // A failed turn ends the turn: no working line under it, whatever the chat still says.
    tafWorking = !!r.thread.working && !turnFailed(r.thread);
    if (tafPending && tafPending.taf === id && !stillPending(tafPending, r.thread.messages)) {
      if (lapsed(tafPending, r.thread.messages)) tafLapsed = id;
      tafPending = null;
    }
    // A message that never reached the chat in 2 minutes is said, never taken away with nothing said.
    if (tafLapsed === id) note.textContent = t("taf_error", tafName());
    const pending = tafPending && tafPending.taf === id ? tafPending.words : null;
    if (pending) showPending(list, pending);
    if (pending || tafWorking) list.append(workingLine(t("working", tafName())));
    refocus(list, focused);
    if (atEnd) toEnd();
    // The taf's turn is over, in tafs cloud's word: its run here stops saying it works, rather than until the idle sweep, and its
    // card gives way to the answer's folded work (`cardShown`) unless a step comes after.
    const over = turnOver(id, r.thread, (lastState && lastState.runs) || [], tafs);
    let hidden = false;
    for (const run of over) {
      const steps = (((lastState && lastState.runs) || []).find((x) => x.id === run) || {}).steps || [];
      if (overRuns.get(run) !== steps.length) hidden = true;
      overRuns.set(run, steps.length);
    }
    if (hidden && lastState && lastState.paired) draw(lastState);
    for (const run of over) {
      quietly({ type: "turn_over", run }).then((out) => {
        // Its last step was a moment ago: the chat is read again once the run has been quiet long enough.
        if (out && out.retryIn > 0 && id === tafId) setTimeout(() => id === tafId && readTaf(), out.retryIn + 500);
      });
    }
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
  drawTray(next);
  if (next) drawReads();
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
  if (!to || uploading(trayOf(to))) return;
  const files = readyIDs(trayOf(to));
  if (!words && !files.length) return;
  $("tafWords").value = "";
  await sendTaf(to, words, files);
};

/**
 * Sends [words] and [files] to taf [to]: what was typed in its box (cleared by the caller), or a failed turn's Retry, whose
 * words go as a new message with no file, leaving the box as it is.
 */
async function sendTaf(to, words, files) {
  words = String(words || "").trim();
  if (!to || (!words && !files.length)) return;
  const said = words || fileNames(to);
  if (tafLapsed === to) tafLapsed = null;
  // Shown at once, as Coach's are: the message, and the taf's working line under it; its words come back to the box on a
  // failure.
  tafPending = { taf: to, words: said, id: null, at: Date.now() };
  $("tafNote").textContent = "";
  showPending($("tafThread"), said);
  $("tafThread").append(workingLine(t("working", tafName())));
  toEnd();
  const r = await send({ type: "taf_send", taf: to, words, attachments: files, clientID: crypto.randomUUID(), tabId: activeTab, windowId: panelWindow }).catch(() => ({ error: "failed" }));
  if (r.error) {
    tafPending = null;
    if (tafId === to) {
      for (const el of $("tafThread").querySelectorAll(".pending, .working")) el.remove();
      if (!$("tafWords").value) $("tafWords").value = words;
      $("tafNote").textContent = t("taf_error", tafName());
    } else if (words && !drafts.get(to)) drafts.set(to, words);
    return;
  }
  tafSentAt = Date.now();
  const sent = sentID(r.sent);
  // The page in front didn't go (no consent, a sensitive site, one switched off, off the taf's sites): the message says why,
  // under it (never silent).
  const why = sent && whyLine(r.notReading, r.host);
  if (why) notRead.set(sent, why);
  if (tafPending && tafPending.taf === to && tafPending.words === said) tafPending = sent ? { ...tafPending, id: sent } : null;
  setTray(to, afterSent(trayOf(to), files));
  // Sent: those words are gone from the taf they went to, and from nowhere else (the chat may have switched meanwhile).
  if (tafId !== to) drafts.delete(to);
  readTaf();
}

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
  // On the taf's view, the page is checked against that taf's own sites too: off them, it couldn't go.
  const forTaf = current === "tafView" && tafId ? tafId : null;
  const r = await send({ type: "aside_status", tabId: activeTab, windowId: panelWindow, ...(forTaf ? { taf: forTaf } : {}) }).catch(() => null);
  // Another view or taf on show meanwhile: its own read, already asked, draws the banner.
  if ((current === "tafView" && tafId ? tafId : null) !== forTaf) return;
  const sw = $("coachReadsSwitch");
  readsHost = (r && r.host) || "";
  // Off until the person allows it (pageconsent.js): while a page that could go is in front, a banner right above the box
  // says so, before anything is sent, with the button to the consent's screen; it goes once consent is given. Coach's and the
  // taf's are the same banner; the taf's counts only the taf's own sites. Nothing of a tab is read.
  const consent = Boolean(r && r.consent);
  const ask = Boolean(r) && !consent && Boolean(readsHost) && !r.why;
  $("pageConsentOff").hidden = !ask;
  $("tafConsentOff").hidden = !ask || !forTaf;
  $("coachSendAlone").hidden = !consent || !readsHost || Boolean(r && r.why);
  if (!consent) {
    line.hidden = true;
    // The first time Coach could read a page (a page it may read is in front, and Coach is on show), the consent screen
    // comes up by itself, once per panel; Not now keeps the banner above the box for later. A taf's chat has the banner
    // only: the screen never comes up over a taf's work by itself.
    if (current === "coach" && ask && !askedOnce()) openConsent();
    return;
  }
  closeConsent(false);
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

// The page consent's own screen, over the whole panel: what goes to the AI model, when, and what never does; Allow, or Not now.
// It opens by itself once, and from the banner above Coach's chat or Settings; it's withdrawn in Settings.
let consentFrom = null; // what had the focus when it opened, to give it back

function askedOnce() {
  try {
    if (sessionStorage.getItem("pageConsentAsked")) return true;
    sessionStorage.setItem("pageConsentAsked", "1");
  } catch {
    // no storage: it may come up again, which asks, never reads
  }
  return false;
}

function openConsent() {
  if (!$("pageConsent").hidden) return;
  consentFrom = document.activeElement;
  closeMenu();
  $("pageConsent").hidden = false;
  // The screen's heading takes the focus, never Allow: it may open by itself while the person types in Coach's box, and an
  // Enter or a space there must not give consent. Allowing is a deliberate press.
  $("pageConsentTitle").focus();
}

function closeConsent(giveFocus = true) {
  if ($("pageConsent").hidden) return;
  $("pageConsent").hidden = true;
  if (giveFocus && consentFrom && consentFrom.isConnected) consentFrom.focus();
  consentFrom = null;
}

/** Settings: whether Coach reads the page in front, with its switch; and this Chrome, with Unpair. */
async function drawSettings() {
  const r = await send({ type: "aside_status", tabId: activeTab, windowId: panelWindow }).catch(() => null);
  const consent = Boolean(r && r.consent);
  $("settingsReadsState").textContent = t(consent ? "settings_reads_on" : "page_consent_off");
  $("settingsReadsAllow").hidden = consent;
  $("pageConsentStop").hidden = !consent;
}

$("pageConsentOpen").onclick = () => openConsent();
$("tafConsentOpen").onclick = () => openConsent();
$("settingsReadsAllow").onclick = () => openConsent();
$("pageConsentNotNow").onclick = () => closeConsent();
$("pageConsentAllow").onclick = async () => {
  const r = await send({ type: "page_allow" }).catch(() => null);
  if (!r || r.error || !r.allowed) $("coachNote").textContent = t("coach_error");
  closeConsent();
  drawReads();
  if (current === "settings") drawSettings();
};
/** Withdrawing, as easy as allowing: one tap from the chat (beside what a send reads), and in Settings. */
async function withdrawPage() {
  await send({ type: "page_withdraw" }).catch(() => null);
  drawReads();
  if (current === "settings") drawSettings();
}
$("pageConsentStop").onclick = withdrawPage;
$("coachReadsStop").onclick = withdrawPage;

$("coachReadsSwitch").onclick = async () => {
  if (!readsHost) return;
  await send({ type: "aside_site", host: readsHost, off: $("coachReadsSwitch").dataset.off === "1" }).catch(() => null);
  drawReads();
};

chrome.tabs.onUpdated.addListener((id, change) => {
  if (id === activeTab && change.url) drawReads();
});

// The message just sent to Coach ({words, id, at}), or to a taf (with its taf), on show until the chat as tafs cloud reads it has
// it by its id (stillPending).
let coachPending = null;
let tafPending = null;

/**
 * A chat's last 20 messages, as the apps draw them (docs/chat.md): a header where a day starts, each message with its time
 * under it, an answer's Copy and ⋯ (Report this answer), the person's own message's ⋯ (Copy, and Send again and Edit and send
 * in a chat begun here); then its failed run, if any, as its last line: tafs cloud's words, and Try again when it offers one.
 * [chat] is the chat's own: `retry`, `again` and `edit` (each given the words), whether this Chrome `began` it, its `session`
 * (else each message's own), and the `taf` it is (null for Coach).
 */
function drawMessages(list, thread, chat) {
  const shown = thread.messages.slice(-20);
  shown.forEach((m, i) => {
    if (startsDay(shown, i)) list.append(dayHeader(m.at));
    const li = document.createElement("li");
    li.className = m.from + (m.kind === "kickoff" ? " kickoff" : "");
    words(li, m);
    reading(li, m);
    whyUnder(li, m);
    under(li, m, chat);
    list.append(li);
  });
  if (thread.failed) list.append(failedLine(thread.failed, thread.messages, chat.retry));
}

/** The header where a day starts: Today, Yesterday, else the date (chatrow.js `dayLabel`). */
function dayHeader(at) {
  const li = document.createElement("li");
  li.className = "day";
  li.textContent = dayLabel(at, { lang: LANG, today: t("chat_today"), yesterday: t("chat_yesterday") });
  return li;
}

// What's open in the chat (a run's steps, a message's ⋯), by key, so a read of the chat every few seconds keeps it open.
const opened = new Set();

/** A small action under a message, named by its words; `key` lets a redraw give it back the focus. */
function actButton(text, key) {
  const b = document.createElement("button");
  b.type = "button";
  b.className = "act";
  b.textContent = text;
  if (key) b.dataset.key = key;
  return b;
}

/**
 * What goes under a message, as on the phones: under an answer, Copy and, where it can be reported, ⋯ with Report this answer;
 * under the person's own, ⋯ with Copy, and Send again and Edit and send only in a chat begun here; then its time, small.
 */
function under(li, m, chat) {
  const id = m.id || "";
  // What a redraw keeps open or focused is keyed by the message's id; a message with none keeps nothing.
  const key = (what) => (id ? what + ":" + id : null);
  if (m.from === "taf" && (m.kind || "message") === "message" && m.text.trim()) {
    const row = document.createElement("div");
    row.className = "actions";
    const copy = copyButton(m.text, li, key("copy"));
    copy.setAttribute("aria-label", t("chat_copy_answer"));
    row.append(copy);
    const session = m.session || chat.session;
    if (canReport(m, session)) {
      const report = actButton(t("report_menu"), key("report"));
      report.onclick = () => openReport({ taf: chat.taf, message: m.id, session });
      row.append(moreMenu(key("more"), [report]));
    }
    li.append(row);
  }
  if (m.from === "owner") {
    const can = ownerActions(m, chat.began);
    const items = [];
    if (can.again) {
      const again = actButton(t("chat_send_again"), key("again"));
      // Pressed once: a double click, or a press while the first send is still on its way, sends nothing more.
      again.onclick = () => {
        if (again.disabled || resending) return;
        again.disabled = true;
        resending = true;
        closeMore(again);
        Promise.resolve(chat.again(m.text)).finally(() => {
          resending = false;
        });
      };
      items.push(again);
    }
    if (can.edit) {
      const edit = actButton(t("chat_edit_and_send"), key("edit"));
      edit.onclick = () => {
        closeMore(edit);
        chat.edit(m.text);
      };
      items.push(edit);
    }
    if (can.copy) items.push(copyButton(m.text, li, key("copy")));
    if (items.length) {
      const row = document.createElement("div");
      row.className = "actions";
      row.append(moreMenu(key("more"), items));
      li.append(row);
    }
  }
  const when = timeLabel(m.at, LANG);
  if (when) {
    const time = document.createElement("small");
    time.className = "time";
    time.textContent = when;
    li.append(time);
  }
}

// A Send again on its way: another press waits for it, so the same words never go twice from one press and its echo.
let resending = false;

/**
 * ⋯ and the actions it holds, shown beside it when pressed, as the conversations' rows have theirs. Opening it sends
 * nothing; it stays open across the chat's next read.
 */
function moreMenu(key, items) {
  const box = document.createElement("span");
  box.className = "more";
  const more = actButton("⋯", key);
  more.setAttribute("aria-label", t("chat_more"));
  more.title = t("chat_more");
  const menu = document.createElement("span");
  menu.className = "moreItems";
  menu.append(...items);
  const set = (open) => {
    menu.hidden = !open;
    more.setAttribute("aria-expanded", String(open));
    if (key && open) opened.add(key);
    else if (key) opened.delete(key);
  };
  set(!!key && opened.has(key));
  more.onclick = () => {
    set(menu.hidden);
    if (!menu.hidden) items[0].focus();
  };
  menu.close = () => {
    set(false);
    more.focus();
  };
  box.append(more, menu);
  return box;
}

/** Closes the ⋯ an action was pressed in, the focus back on ⋯. */
function closeMore(item) {
  const menu = item.closest(".moreItems");
  if (menu && menu.close) menu.close();
}

/**
 * Copy: the message's words, whole, to the clipboard, from this press (no permission asked for: a press in the panel may
 * write it), saying "Copied" for a moment. Where the browser refuses, the words are selected instead, and it says to copy
 * them with the keyboard: a problem is always shown.
 */
function copyButton(text, li, key) {
  const b = actButton(t("chat_copy"), key);
  b.onclick = async () => {
    try {
      await navigator.clipboard.writeText(text);
      b.textContent = t("chat_copied");
    } catch {
      const words = li.querySelector(".words");
      if (words) {
        const range = document.createRange();
        range.selectNodeContents(words);
        const sel = window.getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
      }
      b.textContent = t("chat_copy_selected");
    }
    setTimeout(() => {
      if (b.isConnected) b.textContent = t("chat_copy");
    }, 2000);
  };
  return b;
}

/**
 * A run's work, folded into one line ("Worked 12 s · 4 steps", chatrow.js `workLine`), which opens to its steps in tafs
 * cloud's words (`workRows`): a failed one in the failure colour with its reason under it, a step not made marked so, tries
 * of the same step as one row. A line with no step to show opens nothing.
 */
function workFold(m) {
  const box = document.createElement("div");
  box.className = "work";
  const rows = workRows(m.work);
  const key = m.id ? "work:" + m.id : null;
  const line = workLine(m.work, LANG, t);
  if (!rows.length) {
    const p = document.createElement("span");
    p.className = "workLine";
    p.textContent = line;
    box.append(p);
    return box;
  }
  const b = actButton("", key);
  b.classList.add("workLine");
  const list = document.createElement("ol");
  list.className = "workSteps";
  for (const r of rows) {
    const li = document.createElement("li");
    li.className = r.status;
    const mark = document.createElement("span");
    mark.className = "mark";
    mark.setAttribute("aria-hidden", "true");
    mark.textContent = r.status === "failed" ? "!" : r.status === "not_made" ? "\u21A9" : "\u2713";
    li.append(mark, r.words);
    const more = r.status === "not_made" ? t("chat_work_not_made") : r.tries > 1 ? t(pluralKey("chat_work_tries", r.tries, LANG), new Intl.NumberFormat(LANG).format(r.tries)) : "";
    if (more) {
      const s = document.createElement("span");
      s.className = "tries";
      s.textContent = " · " + more;
      li.append(s);
    }
    if (r.why) {
      const why = document.createElement("small");
      why.className = "why";
      why.textContent = r.why;
      li.append(why);
    }
    list.append(li);
  }
  const set = (open) => {
    list.hidden = !open;
    b.textContent = (open ? "\u25BE " : "\u25B8 ") + line;
    b.setAttribute("aria-expanded", String(open));
    if (key && open) opened.add(key);
    else if (key) opened.delete(key);
  };
  set(!!key && opened.has(key));
  b.onclick = () => set(list.hidden);
  box.append(b, list);
  return box;
}

/** Edit and send: the message's words back in the box, to change and send as a new message. */
function editIn(box, text) {
  box.value = text;
  box.focus();
  box.setSelectionRange(text.length, text.length);
}

/** What in the chat has the focus, by its key, so the chat's next read can give it back. */
function keyInFocus(list) {
  const el = document.activeElement;
  return el && list.contains(el) && el.dataset ? el.dataset.key || null : null;
}

function refocus(list, key) {
  if (!key) return;
  const el = [...list.querySelectorAll("[data-key]")].find((x) => x.dataset.key === key);
  if (el && !el.hidden) el.focus({ preventScroll: true });
}

// Report this answer (docs/chat.md, "Reporting an answer"), as the phones' sheet: tafs cloud's four reasons, a note of at most
// 1,000 characters, and Send; the chat's session always goes with it, since tafs cloud takes the panel's report only for an
// answer in a chat it may show. Sent, it thanks, or says it was reported already; not sent, it says why and stays to send again.
let reporting = null; // {taf, message, session}
let reportSending = false;
let reportFrom = null; // what had the focus, to give it back

function openReport(answer) {
  reporting = answer;
  reportFrom = document.activeElement;
  closeMenu();
  $("reportForm").reset();
  $("reportForm").hidden = false;
  $("reportDone").hidden = true;
  $("reportFailed").hidden = true;
  reportReady();
  $("report").hidden = false;
  $("reportTitle").focus();
}

function closeReport() {
  if ($("report").hidden) return;
  $("report").hidden = true;
  reporting = null;
  if (reportFrom && reportFrom.isConnected) reportFrom.focus();
  reportFrom = null;
}

/** Send waits for a reason, and for a note no longer than tafs cloud takes, which is said when it is. */
function reportReady() {
  const reason = $("reportForm").elements.reason.value;
  const long = $("reportNote").value.trim().length > REPORT_NOTE_MAX;
  $("reportNoteLong").hidden = !long;
  $("reportSend").disabled = reportSending || !reason || long;
}

$("reportForm").addEventListener("input", reportReady);
$("reportForm").addEventListener("change", reportReady);
$("reportCancel").onclick = () => closeReport();
$("reportClose").onclick = () => closeReport();
$("reportForm").onsubmit = async (e) => {
  e.preventDefault();
  const answer = reporting;
  const body = answer && reportBody($("reportForm").elements.reason.value, $("reportNote").value, answer.session);
  if (!body || reportSending) return;
  reportSending = true;
  reportReady();
  $("reportFailed").hidden = true;
  const r = await send({ type: "report", taf: answer.taf, message: answer.message, ...body }).catch(() => null);
  reportSending = false;
  if (reporting !== answer) return; // closed meanwhile
  const out = reportOutcome(r);
  if (out.state === "failed") {
    $("reportFailed").textContent = t("report_not_sent_why", out.words || t(out.key));
    $("reportFailed").hidden = false;
    reportReady();
    return;
  }
  $("reportForm").hidden = true;
  $("reportDoneWords").textContent = t(out.state === "already" ? "report_already" : "report_thanks");
  $("reportDone").hidden = false;
  $("reportClose").focus();
};

/**
 * The failed run's line: tafs cloud's words, as text, whatever its code; Try again only when it offers one (coachchat.js
 * `retryOf`), in its own words (`retry_label`, else the extension's). It's pressed once: it goes at the first press, so a
 * double click never sends twice, and it doesn't come back when the chat is drawn again.
 */
function failedLine(f, messages, retry) {
  const li = document.createElement("li");
  li.className = "taf failed";
  const p = document.createElement("p");
  p.textContent = f.words;
  li.append(p);
  const again = retryOf(f, messages);
  const label = f.retryLabel || chrome.i18n.getMessage("turn_failed_retry");
  const key = failedKey(f);
  if (again && label && !retried.has(key)) {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "quiet small retry";
    b.textContent = label;
    b.onclick = () => {
      if (retried.has(key)) return;
      retried.add(key);
      b.disabled = true;
      b.remove();
      retry(again);
    };
    li.append(b);
  }
  return li;
}

/**
 * Under the person's message, why the page in front didn't go with it (coachchat.js `whyLine`), as it was sent from here; for
 * no page consent, with the button that opens its screen.
 */
function whyUnder(li, m) {
  if (m.from !== "owner" || m.reading || !m.id || !notRead.has(m.id)) return;
  const why = notRead.get(m.id);
  const line = document.createElement("small");
  line.className = "reading";
  line.textContent = why.host ? t(why.key, why.host) : t(why.key);
  if (why.consent) {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "quiet small";
    b.textContent = t("page_consent_turn_on");
    b.onclick = () => openConsent();
    line.append(" ", b);
  }
  li.append(line);
}

/** The person's message as it was sent, at the bottom right, until the chat has it. */
function showPending(list, words) {
  const li = document.createElement("li");
  li.className = "owner pending";
  const box = document.createElement("div");
  box.className = "words";
  box.textContent = words;
  li.append(box);
  list.append(li);
}

/** The working line under the last message ("Coach travaille…"), as in the app; read out when it appears. */
function workingLine(text) {
  const li = document.createElement("li");
  li.className = "working";
  li.setAttribute("role", "status");
  li.textContent = text;
  return li;
}

/** Whether the person is at the end of the chat: a new message then keeps it in view, and one scrolled up isn't moved. */
function nearEnd() {
  const doc = document.documentElement;
  return window.innerHeight + window.scrollY >= doc.scrollHeight - 80;
}

function toEnd() {
  window.scrollTo({ top: document.documentElement.scrollHeight });
}

/** The id tafs cloud gave a message it took (`{message: {id}}`), or null. */
function sentID(sent) {
  const id = sent && sent.message && sent.message.id;
  return typeof id === "string" && id ? id : null;
}

/*
 * Files on a message (docs/chrome.md, "Files on a message"), as in the apps: picked with Chrome's own file picker only (the
 * + in the composer), at most 5, each made ready (attach.js: its type and limit checked, an image redrawn as a JPEG), declared
 * to tafs cloud through the service worker and uploaded by the panel straight to the address it gives. Each shows as a chip
 * above the box until Send, with a × to remove it. Coach's chat has one tray, each taf's chat its own: a file declared on one
 * taf never goes to another.
 */
const COACH_TRAY = "coach";
const trays = new Map();
let trayKeys = 0;

function trayOf(target) {
  return trays.get(target) || [];
}

function setTray(target, tray) {
  if (tray.length) trays.set(target, tray);
  else trays.delete(target);
  drawTray(target);
}

/** Changes one file of a tray, found by its key: a file removed while it went up is left removed. */
function updateFile(target, key, change) {
  const tray = trayOf(target);
  if (!tray.some((f) => f.key === key)) return;
  setTray(target, tray.map((f) => (f.key === key ? { ...f, ...change } : f)));
}

/** The names of the files ready to go, for the message shown at once when it has no words. */
function fileNames(target) {
  return trayOf(target).filter((f) => f.state === "ready").map((f) => f.name).join(", ");
}

function trayList(target) {
  return target === COACH_TRAY ? $("coachFiles") : $("tafFiles");
}

/** Draws a tray's chips: each file's name, where it stands, and × to remove it. Only the chat on show draws its tray. */
function drawTray(target) {
  if (target !== COACH_TRAY && target !== tafId) return;
  const list = trayList(target);
  list.textContent = "";
  for (const f of trayOf(target)) {
    const li = document.createElement("li");
    li.className = f.state;
    const name = document.createElement("span");
    name.className = "fileName";
    name.textContent = f.name;
    li.append(name);
    const state = f.state === "uploading" ? t("attach_uploading") : f.state === "refused" ? f.why : f.state === "failed" ? t("attach_failed") : "";
    if (state) {
      const s = document.createElement("span");
      s.className = "fileState";
      s.textContent = state;
      li.append(s);
    }
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "remove";
    remove.textContent = "×";
    remove.setAttribute("aria-label", t("attach_remove", f.name));
    remove.title = t("attach_remove", f.name);
    remove.onclick = () => setTray(target, trayOf(target).filter((x) => x.key !== f.key));
    li.append(remove);
    list.append(li);
  }
  list.hidden = !list.children.length;
}

/** Why a file can't go, in the panel's words. */
function refusedWords(refused) {
  if (refused.code === "too_many") return t("attach_too_many");
  if (refused.code === "too_big") return t("attach_too_big", refused.size);
  return t("attach_type");
}

/** The files picked for target's next message: each shown at once, made ready, declared, and uploaded. */
async function attachFiles(target, picked) {
  for (const file of picked) {
    const key = ++trayKeys;
    const refusedNow = counted(trayOf(target)) >= 5 ? { code: "too_many" } : null;
    setTray(target, [...trayOf(target), { key, name: file.name, state: refusedNow ? "refused" : "uploading", ...(refusedNow ? { why: refusedWords(refusedNow) } : {}) }]);
    if (refusedNow) continue;
    // Counted before this one was added: the tray already holds it, going up.
    const ready = await prepare(file, { count: counted(trayOf(target)) - 1 });
    if (ready.refused) {
      updateFile(target, key, { state: "refused", why: refusedWords(ready.refused) });
      continue;
    }
    updateFile(target, key, { name: ready.name });
    try {
      const declare = async (f) => {
        const out = await send({ type: "attach", taf: target === COACH_TRAY ? null : target, file: f });
        if (!out || out.error) throw Object.assign(new Error("not declared"), { code: "failed" });
        return out;
      };
      const id = await upload(ready, { declare });
      updateFile(target, key, { state: "ready", id });
    } catch {
      updateFile(target, key, { state: "failed" });
    }
  }
}

for (const [button, input, target] of [["coachAttach", "coachFile", () => COACH_TRAY], ["tafAttach", "tafFile", () => tafId]]) {
  $(input).accept = ACCEPT;
  $(button).onclick = () => $(input).click();
  $(input).onchange = () => {
    const to = target();
    const picked = [...($(input).files || [])];
    $(input).value = "";
    if (to && picked.length) attachFiles(to, picked);
  };
}
