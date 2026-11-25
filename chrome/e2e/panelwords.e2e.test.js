// End to end: the side panel says why a step wasn't done in plain words (docs/chrome.md, "The side panel", 0.2.18), never by its
// code. Each of the seven refusals the panel has a line for (`step_refused_<code>`) is made to happen in a real run, and the run's
// card shows "<the step> · <its line>", in English and in French (Chromium started in each language), with no code in it.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { playwright, start, newRun, ref, until, runState, fillSessionRules, unfillSessionRules } from "./harness.mjs";
import { joining } from "./joining.mjs";

const skip = !playwright;
const root = join(dirname(fileURLToPath(import.meta.url)), "..");

/** The codes the panel has a plain line for: one `step_refused_<code>` each in every locale. */
const CODES = ["given_back", "page_changed", "no_back", "site_not_listed", "paused_by_owner", "no_page", "browser_unavailable"];

/** The extension's words in `locale` (its _locales file), with `$1` given as `sub`, as chrome.i18n gives them. */
function words(locale) {
  const m = JSON.parse(readFileSync(join(root, "_locales", locale, "messages.json"), "utf8"));
  return (key, sub = "") => {
    const e = m[key];
    assert.ok(e, `${locale} has ${key}`);
    let text = e.message;
    for (const [name, p] of Object.entries(e.placeholders || {})) if (p.content === "$1") text = text.split("$" + name.toUpperCase() + "$").join(sub);
    return text;
  };
}

test("every locale has a plain line for each of the seven codes, with no code in it", { skip }, () => {
  for (const locale of ["en", "fr"]) {
    const t = words(locale);
    for (const code of CODES) {
      const line = t("step_refused_" + code);
      assert.ok(line.length > 0);
      assert.ok(!line.includes(code), `${locale} step_refused_${code} names its code: ${line}`);
    }
  }
});

/**
 * The last refused line the panel's one run card shows: the person's Pause and Resume are lines of their own, after the step
 * they held, and aren't refusals.
 */
function lastLine(h) {
  return h.inPanel(`(() => { const li = [...document.querySelectorAll("#runs article.run .steps li.refused")].at(-1); return li ? li.textContent : null; })()`);
}

/**
 * In Chromium started in `lang`, one run of the person's (joined, so it can need a further tab) meets each of the seven codes; after
 * each, the panel's last line is the step's words and the code's plain line, and no line of the card names a code.
 */
async function sevenInPanel(lang, locale) {
  const h = await start({ cloud: true, lang });
  try {
    const j = await joining(h);
    const t = words(locale);
    const seen = [];
    const expect = async (fields, code, stepWords) => {
      const r = await j.step(fields);
      assert.equal(r.ok, false, `${code}: the step was done`);
      assert.equal(r.error.code, code);
      const line = stepWords + " · " + t("step_refused_" + code);
      await until(async () => (await lastLine(h)) === line, `${lang}: "${line}" (last: "${await lastLine(h)}")`);
      seen.push(code);
    };

    const hub = h.sites.url("news.test", "/hub?case=words-" + locale);
    const { agentRun } = await j.sendFromPanel(hub);
    const run = newRun("words-" + locale);
    const as = { run, agent_run: agentRun };

    // no_page: a read before the run has a page.
    await expect({ ...as, op: "read" }, "no_page", t("step_read"));
    const opened = await j.step({ ...as, op: "open", url: hub });
    assert.equal(opened.ok, true, JSON.stringify(opened.error));
    // page_changed: an act on a read the tab no longer shows.
    await expect({ ...as, op: "act", read_id: "rd_000000000000000000000000", act: { kind: "fill", ref: ref(opened.page, "Question"), value: "x" } }, "page_changed", t("step_act_fill"));
    // no_back: the taf's back in the person's tab.
    await expect({ ...as, op: "act", read_id: opened.page.read_id, act: { kind: "back" } }, "no_back", t("step_act_back"));
    assert.equal((await j.step({ ...as, op: "read" })).ok, true, "a read in between: the failures start again");
    // site_not_listed: an open off the run's sites.
    await expect({ ...as, op: "open", url: h.sites.url("other.test", "/?case=words") }, "site_not_listed", t("step_open", "other.test"));
    // browser_unavailable: a further tab whose fence Chrome refuses (its limit on session rules reached).
    await fillSessionRules(h);
    try {
      await expect({ ...as, op: "open", url: h.sites.url("shop.test", "/form?case=words") }, "browser_unavailable", t("step_open", "shop.test"));
    } finally {
      await unfillSessionRules(h);
    }
    // paused_by_owner: the person's Pause while the taf shows where it's about to act (its outline): the act is held and recorded.
    // A step that arrives already paused is refused before it's recorded, so the card says "Paused" and draws no line for it.
    const read = await j.step({ ...as, op: "read" });
    const held = expect({ ...as, op: "act", read_id: read.page.read_id, act: { kind: "fill", ref: ref(read.page, "Question"), value: "x" } }, "paused_by_owner", t("step_act_fill"));
    await until(() => j.owner.evaluate(() => !!document.querySelector("[data-tafs-outline]")), "the taf's outline in the page", 5000);
    await h.fromPanel({ type: "pause", run });
    await held;
    await h.fromPanel({ type: "resume", run });
    // given_back: the person types another address in their tab.
    await j.owner.goto(h.sites.url("shop.test", "/account?case=words-" + locale));
    await until(async () => !(await runState(h, run)).tabs.length || !(await runState(h, run)).tabs.includes((await j.front()).tabId), "their tab given back");
    await expect({ ...as, op: "read" }, "given_back", t("step_read"));

    assert.deepEqual(seen.sort(), [...CODES].sort(), "all seven");
    const all = await h.inPanel(`[...document.querySelectorAll("#runs article.run .steps li")].map((li) => li.textContent)`);
    for (const code of CODES) assert.ok(!all.some((l) => l.includes(code)), `${lang}: a line names ${code}: ${JSON.stringify(all)}`);
    await h.fromPanel({ type: "stop", run });
  } finally {
    await h.close();
  }
}

test("in English, each of the seven refusals shows in the panel as its plain line, never its code", { skip }, () => sevenInPanel("en-US", "en"));

test("in French, each of the seven refusals shows in the panel as its plain line, never its code", { skip }, () => sevenInPanel("fr", "fr"));
