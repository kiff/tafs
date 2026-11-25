// End to end: what a page of the run's sites sends by itself (docs/chrome.md, "The fence"). The fence is Chrome's rules for a run's
// tab, and they cover navigations only: a move of the page, or of a frame in it, to a host that isn't one of the run's sites is
// blocked. What an allowed site's own script sends by a request of its own (a fetch, an XHR, a beacon, an image, a WebSocket) is that
// site's, as when the person types there themselves; the extension never sends a request elsewhere itself. Each case is pinned here,
// after a taf's fill, in a tab of the run's own and in a tab of the person's the run joined, so that neither can change silently.

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { playwright, start, newRun, ref, until } from "./harness.mjs";
import { joining } from "./joining.mjs";

const skip = !playwright;
let h;
let j;

before(async () => {
  if (skip) return;
  h = await start({ cloud: true });
  j = await joining(h);
});

after(async () => {
  if (h) await h.close();
});

/** Every request other.test was asked for that carries `value` (its query, or a WebSocket's opening request). */
const carrying = (value) => h.sites.hits.filter((x) => x.startsWith("other.test ") && x.includes(encodeURIComponent(value)));

/** How long a blocked case is given to send before its absence counts: the page's script looks every 100 ms. */
const SETTLE = 2500;

/**
 * The taf fills the page's field with `value` in a run's tab at `url`: a tab of its own, or (`joined`) the person's tab, which
 * shows the page before the run joins it. Gives the run, to stop.
 */
async function fill(url, value, joined) {
  const run = newRun("requests");
  let agentRun;
  if (joined) ({ agentRun } = await j.sendFromPanel(url));
  const opened = await j.step({ op: "open", run, url, ...(joined ? { agent_run: agentRun } : {}) });
  assert.equal(opened.ok, true, JSON.stringify(opened.error));
  const filled = await j.step({ op: "act", run, read_id: opened.page.read_id, act: { kind: "fill", ref: ref(opened.page, "Your note"), value }, ...(joined ? { agent_run: agentRun } : {}) });
  assert.equal(filled.ok, true, JSON.stringify(filled.error));
  return run;
}

/** Whether other.test got `value` within SETTLE. */
async function reached(value) {
  return until(() => carrying(value).length > 0, value, SETTLE).then(() => true, () => false);
}

const CASES = [
  // The site's own requests: not navigations, so not the fence's. other.test gets the value, in either kind of tab.
  { via: "fetch", own: true, joined: true },
  { via: "xhr", own: true, joined: true },
  { via: "beacon", own: true, joined: true },
  { via: "img", own: true, joined: true },
  { via: "websocket", own: true, joined: true },
  // A frame's navigation: the fence's. A form posted into a frame never reaches other.test.
  { via: "form-iframe", own: false, joined: false },
  // A message to an off-site frame: in a tab of the run's own the frame was fenced before it loaded, so there's nobody to tell; in
  // the person's tab it loaded before the run joined (unfenced then, as all of their browsing is), and it sends what it's told home
  // by a fetch of its own.
  { via: "postmessage", own: false, joined: true },
];

for (const { via, own, joined } of CASES) {
  for (const kind of ["own", "joined"]) {
    const expected = kind === "own" ? own : joined;
    const says = expected ? "is the site's own: other.test gets the value" : "is fenced: other.test gets nothing";
    test(`after the taf's fill, in ${kind === "own" ? "a tab of the run's own" : "the person's tab the run joined"}, a page's ${via} ${says}`, { skip }, async () => {
      const url = h.sites.url("shop.test", `/leak-${via}?case=${kind}`);
      const value = `filled-${via}-${kind}-${process.pid}`;
      const run = await fill(url, value, kind === "joined");
      const got = await reached(value);
      await h.fromPanel({ type: "stop", run });
      assert.equal(got, expected, `${via}, ${kind}: other.test was asked for ${JSON.stringify(carrying(value))}`);
      // Whatever the page did, the extension sent nothing of its own: no navigation of the tab to other.test carries the value.
      assert.ok(!carrying(value).some((x) => x.startsWith("other.test /?") || x.startsWith("other.test /account")), "a navigation carried it");
    });
  }
}

test("with no run, the same pages send the value by every way, the fenced ones included: the cases above aren't empty", { skip }, async () => {
  const page = await h.context.newPage();
  try {
    for (const via of ["form-iframe", "postmessage", "fetch"]) {
      const value = `typed-${via}-norun-${process.pid}`;
      await page.goto(h.sites.url("shop.test", `/leak-${via}?case=norun`));
      await page.fill("#note", value);
      assert.equal(await reached(value), true, `${via} sends it with no run`);
    }
  } finally {
    await page.close();
  }
});
