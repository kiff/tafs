// End to end: the side panel's chat drawn by the phones' rules (docs/chat.md; docs/chrome.md, "The taf in the side panel",
// 0.2.24). In the real side panel, with the fake tafs cloud: a header where a day starts and each message's time, an answer's
// work folded into one line that opens to its steps, its Markdown, Copy, Report this answer with the chat's session, and the
// person's own message's Send again (once, however fast it's pressed) and Edit and send.

import { test } from "node:test";
import assert from "node:assert/strict";
import { playwright, start, until } from "./harness.mjs";

const skip = !playwright;

/** The text of the panel's element `selector`, or null when it isn't there or is hidden. */
function shown(h, selector) {
  return h.inPanel(`(() => { const el = document.querySelector(${JSON.stringify(selector)}); return el && !el.closest("[hidden]") ? el.textContent : null; })()`);
}

/** Clicks the panel's element `selector`, `times` times in a row, as a quick double click does. */
function press(h, selector, times = 1) {
  return h.inPanel(`(() => { const el = document.querySelector(${JSON.stringify(selector)}); for (let i = 0; i < ${times}; i++) el.click(); return true; })()`);
}

test("a taf's chat: days, times, the work folded, Markdown, Copy, Report, Send again once, and Edit and send", { skip }, async () => {
  const h = await start({ cloud: true });
  try {
    const yesterday = new Date(Date.now() - 24 * 3600_000);
    yesterday.setHours(9, 5, 0, 0);
    const today = new Date();
    h.cloud.thread.push(
      { id: "agr_1.in", from: "owner", kind: "message", text: "List the refunds", at: yesterday.toISOString(), session: h.cloud.session },
      {
        id: "agr_1.2", from: "taf", kind: "message", at: today.toISOString(), session: h.cloud.session,
        text: "Two **refunds**:\n\n| Who | Amount |\n|---|---|\n| Rae | 20.00 |\n\n```\nraw <b>x</b>\n```",
        work: { ms: 4200, steps: [
          { tool: "kiff_list", words: "Listing refunds…" },
          { tool: "kiff_read", words: "Reading a refund…", error: true, failed: true, why: "No such refund" },
          { tool: "kiff_read", words: "Reading a refund…" },
        ] },
      },
    );
    await until(() => h.cloud.claimed, "the KIFF device's claim", 15_000);
    await until(async () => (await h.inPanel(`document.querySelectorAll("#tafThread li.taf").length`)) === 1, "the answer");

    // A header where each day starts, and each message's time under it.
    assert.deepEqual(await h.inPanel(`[...document.querySelectorAll("#tafThread li.day")].map((x) => x.textContent)`), ["Yesterday", "Today"]);
    const time = await shown(h, "#tafThread li.owner small.time");
    assert.match(time, /^9:05\s?AM$/, "in the panel's language");

    // The work, folded into one line, opens to its steps in tafs cloud's words; the failed try is folded into the one that worked.
    assert.equal(await shown(h, "#tafThread li.taf .workLine"), "▸ Worked 4.2 s · 3 steps, 1 failed");
    assert.equal(await h.inPanel(`document.querySelector("#tafThread li.taf .workSteps").hidden`), true);
    await press(h, "#tafThread li.taf .workLine");
    assert.deepEqual(await h.inPanel(`[...document.querySelectorAll("#tafThread .workSteps li")].map((x) => x.textContent)`), ["✓Listing refunds", "✓Reading a refund · 2 tries"]);
    assert.equal(await h.inPanel(`document.querySelector("#tafThread li.taf .workLine").getAttribute("aria-expanded")`), "true");

    // Its Markdown, as the phones draw it: a table and a code block, as text.
    assert.equal(await shown(h, "#tafThread li.taf .words td.num"), "20.00");
    assert.equal(await shown(h, "#tafThread li.taf .words pre code"), "raw <b>x</b>");
    assert.equal(await h.inPanel(`document.querySelectorAll("#tafThread li.taf .words b").length`), 0, "markup stays words");

    // Copy: the answer's words, whole, to the clipboard; a browser that refuses selects them instead and says so.
    await h.inPanel(`(() => { window.copied = []; navigator.clipboard.writeText = async (x) => { window.copied.push(x); }; return true; })()`);
    await press(h, "#tafThread li.taf .actions button.act");
    await until(async () => (await h.inPanel("window.copied.length")) === 1, "copied");
    assert.equal(await h.inPanel("window.copied[0]"), h.cloud.thread[1].text);
    assert.equal(await shown(h, "#tafThread li.taf .actions button.act"), "Copied");
    await h.inPanel(`(() => { navigator.clipboard.writeText = async () => { throw new Error("refused"); }; return true; })()`);
    await until(async () => (await shown(h, "#tafThread li.taf .actions button.act")) === "Copy", "back to Copy", 5_000);
    await press(h, "#tafThread li.taf .actions button.act");
    await until(async () => (await shown(h, "#tafThread li.taf .actions button.act")) === "Selected: copy it with your keyboard.", "said");
    assert.equal(await h.inPanel("String(window.getSelection())").then((x) => x.includes("Two refunds")), true, "the words selected");

    // Report this answer: behind ⋯, with tafs cloud's reasons, sent with the chat's session; again, "already reported".
    await press(h, "#tafThread li.taf .more > button.act");
    assert.equal(await shown(h, "#tafThread li.taf .moreItems button"), "Report this answer");
    await press(h, "#tafThread li.taf .moreItems button");
    await until(async () => (await shown(h, "#report")) !== null, "the report screen");
    assert.equal(await h.inPanel(`document.activeElement.id`), "reportTitle", "the focus on its heading");
    assert.equal(await h.inPanel(`document.getElementById("reportSend").disabled`), true, "Send waits for a reason");
    await h.inPanel(`(() => { document.querySelector('#reportForm input[value="wrong"]').click(); document.getElementById("reportNote").value = "  The total is off  "; document.getElementById("reportNote").dispatchEvent(new Event("input", { bubbles: true })); return true; })()`);
    await press(h, "#reportSend", 2);
    await until(async () => (await shown(h, "#reportDoneWords")) !== null, "sent");
    assert.equal(await shown(h, "#reportDoneWords"), "Thanks. Someone at KIFF will read your report.");
    assert.equal(h.cloud.reports.length, 1, "one report for a double press");
    assert.deepEqual(h.cloud.reports[0], { message: "agr_1.2", body: { reason: "wrong", note: "The total is off", session: h.cloud.session } });
    await press(h, "#reportClose");
    assert.equal(await shown(h, "#report"), null);
    await press(h, "#tafThread li.taf .more > button.act");
    await press(h, "#tafThread li.taf .moreItems button");
    await h.inPanel(`(() => { document.querySelector('#reportForm input[value="other"]').click(); return true; })()`);
    await press(h, "#reportSend");
    await until(async () => (await shown(h, "#reportDoneWords")) === "You've already reported this answer.", "already");
    await press(h, "#reportClose");

    // The person's own message: ⋯ holds Send again, Edit and send and Copy. Send again sends once, however fast it's pressed.
    await press(h, "#tafThread li.owner .more > button.act");
    assert.deepEqual(await h.inPanel(`[...document.querySelectorAll("#tafThread li.owner .moreItems button")].map((b) => b.textContent)`), ["Send again", "Edit and send", "Copy"]);
    await press(h, "#tafThread li.owner .moreItems button", 3);
    await until(() => h.cloud.sends.length === 1, "sent again");
    await new Promise((done) => setTimeout(done, 1500));
    assert.equal(h.cloud.sends.length, 1, "once");
    assert.equal(h.cloud.sends[0].text, "List the refunds");
    assert.equal(await h.inPanel(`document.getElementById("tafWords").value`), "", "the box as it was");
    // Edit and send: the words back in the box, nothing sent.
    await until(async () => (await h.inPanel(`document.querySelectorAll("#tafThread li.owner:not(.pending)").length`)) === 2, "the chat read again");
    await press(h, "#tafThread li.owner .more > button.act");
    await press(h, "#tafThread li.owner .moreItems button:nth-child(2)");
    assert.equal(await h.inPanel(`document.getElementById("tafWords").value`), "List the refunds");
    assert.equal(h.cloud.sends.length, 1, "nothing more sent");
  } finally {
    await h.close();
  }
});

test("Coach's chats: Send again and Edit and send only in one begun in this Chrome; a run's chat shown here has Copy only", { skip }, async () => {
  const h = await start({ cloud: true });
  try {
    // A chat the panel sees only because a run worked in this Chrome: its turn, read-only to the panel.
    h.cloud.coachChats.set("ses_run", { began: false, title: "Check the parcels", messages: [
      { id: "agr_9.in", from: "owner", kind: "message", text: "Check the parcels", at: new Date().toISOString(), session: "ses_run" },
      { id: "agr_9.2", from: "taf", kind: "message", text: "All delivered.", at: new Date().toISOString(), session: "ses_run" },
    ] });
    await until(() => h.cloud.claimed, "the KIFF device's claim", 15_000);
    await until(async () => (await shown(h, "#menuBtn")) !== null, "the menu");
    await press(h, "#menuChats");
    await until(async () => (await shown(h, "#coachChats .chat")) !== null, "the conversations");
    await press(h, "#coachChats .chat");
    await until(async () => (await h.inPanel(`document.querySelectorAll("#coachThread li.owner").length`)) === 1, "the run's chat");
    await press(h, "#coachThread li.owner .more > button.act");
    assert.deepEqual(await h.inPanel(`[...document.querySelectorAll("#coachThread li.owner .moreItems button")].map((b) => b.textContent)`), ["Copy"], "Copy only");
    // Its answer can still be reported, with its chat's session.
    assert.equal(await h.inPanel(`!!document.querySelector("#coachThread li.taf .more")`), true);

    // A new conversation, begun here: its message has all three.
    await press(h, "#coachNew");
    await h.inPanel(`(() => { document.getElementById("coachWords").value = "Set up a taf for this site"; document.getElementById("coachForm").requestSubmit(); return true; })()`);
    await until(() => h.cloud.coachSends.length === 1, "sent");
    const session = h.cloud.coachSends[0].session;
    assert.ok(session && h.cloud.coachChats.get(session).began);
    await until(async () => (await h.inPanel(`document.querySelectorAll("#coachThread li.owner:not(.pending) .more").length`)) === 1, "the chat read");
    await press(h, "#coachThread li.owner .more > button.act");
    assert.deepEqual(await h.inPanel(`[...document.querySelectorAll("#coachThread li.owner .moreItems button")].map((b) => b.textContent)`), ["Send again", "Edit and send", "Copy"]);
    await press(h, "#coachThread li.owner .moreItems button", 2);
    await until(() => h.cloud.coachSends.length === 2, "sent again");
    await new Promise((done) => setTimeout(done, 1000));
    assert.deepEqual(h.cloud.coachSends.map((x) => [x.text, x.session]), [["Set up a taf for this site", session], ["Set up a taf for this site", session]], "once, into the same chat");
  } finally {
    await h.close();
  }
});
