// The side panel: pairing by the phone's code, and while a taf works, what it's doing, with Pause, Resume and Stop. It
// shows only what the service worker reports; it decides nothing.

const t = (key, ...subs) => chrome.i18n.getMessage(key, subs) || key;

for (const el of document.querySelectorAll("[data-m]")) el.textContent = t(el.dataset.m);

const $ = (id) => document.getElementById(id);

function send(message) {
  return chrome.runtime.sendMessage(message);
}

const STEP_WORDS = {
  open: (s) => t("step_open", s.url ? new URL(s.url).hostname : ""),
  read: () => t("step_read"),
  act: (s) => t("step_act_" + (s.kind || "click")),
  navigate: (s) => t("step_navigate", s.url ? new URL(s.url).hostname : ""),
  ask: (s) => t("step_ask", s.site || ""),
  pause: (s) => t(s.by === "failures" ? "step_paused_failures" : "step_paused"),
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
      paused.textContent = t(run.paused === "failures" ? "paused_failures" : "paused_owner");
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
