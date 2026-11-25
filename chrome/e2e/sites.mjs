// The local websites the end-to-end tests drive: one HTTPS server on 127.0.0.1 that answers by host name, with a self-signed
// certificate made at the start (the `openssl` command) and thrown away after. Chromium reaches `*.test` here through
// `--host-resolver-rules` (harness.mjs), and the URLs carry the server's port: a site's rules read the host alone.
//
//   news.test   an article to read, and a long page whose text comes in windows
//   shop.test   a form (text, select, checkbox, password), a page that asks confirm(), a page that links off the run's sites,
//               the other ways a page leaves for another site (a redirect, a form that posts there, a refresh, a script, a link
//               to a new tab, a frame), and a page that holds secrets in frames
//               and a page of the person's own (also on other.test), open before a run joins their tab
//   other.test  a site no grant lists
//
// A test can also answer another host on the same server (`serve`): the fake tafs cloud and KIFF (planque.mjs).

import { createServer } from "node:https";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

/** The password the form's field holds: a read or a result that carries it has leaked it. */
export const PASSWORD = "hunter2-e2e-Zq7";

const page = (title, body) => `<!doctype html><html><head><meta charset="utf-8"><title>${title}</title></head><body>${body}</body></html>`;

const ARTICLE = page("Tide tables", `<h1>Tide tables</h1>
<p>The harbour floods twice a day. High water today is at 06:12 and 18:40.</p>
<p>Boats leave from the north quay.</p>`);

const LONG = page("A long page", `<h1>A long page</h1>` + Array.from({ length: 600 }, (_, i) => `<p>Line ${i + 1} of the long page, with enough words to fill it.</p>`).join("\n") + `<p id="end">The last line.</p>`);

const FORM = page("Delivery details", `<h1>Delivery details</h1>
<form id="details" onsubmit="event.preventDefault()">
  <label for="name">Full name</label><input id="name" type="text">
  <label for="size">Size</label><select id="size"><option>Small</option><option>Medium</option><option>Large</option></select>
  <label for="news">Send me news</label><input id="news" type="checkbox">
  <label for="pw">Password</label><input id="pw" type="password" value="${PASSWORD}">
  <button type="submit" id="save">Save details</button>
</form>`);

const CONFIRM = page("Drafts", `<h1>Drafts</h1>
<p id="out">untouched</p>
<button id="delete" onclick="document.getElementById('out').textContent = confirm('Delete every draft?') ? 'accepted' : 'refused'">Delete drafts</button>`);

/** A page of the run's sites with a link on them, a link and a form off them: where a joined tab is acted on. */
const HUB = (port) => page("Harbour news", `<h1>Harbour news</h1>
<a id="long" href="/long?case=hub">Read the long page</a>
<a id="off" href="https://other.test:${port}/?from=hub">Somewhere else</a>
<form id="ask" method="get" action="https://other.test:${port}/?from=hub-form"><label for="q">Question</label><input id="q" name="q" type="text"><button type="submit">Ask elsewhere</button></form>`);

const LINKS = (port) => page("Links", `<h1>Links</h1>
<a id="off" href="https://other.test:${port}/">Somewhere else</a>`);

const OTHER = page("Other", `<h1>Not on the run's sites</h1>`);

/** What only the person's own page holds, before a run joins their tab: a read or a result that carries it has read their page. */
export const PERSON_WORDS = "Private notes the person had open";
export const PERSON_VALUE = "person-value-e2e-Jt3";

/** The person's own page, open in their tab before the run (on the run's sites or not): a form that posts to its own site. */
const ACCOUNT = page("Your account", `<h1>Your account</h1>
<p>${PERSON_WORDS}.</p>
<form id="account" method="post" action="/account?from=account">
  <label for="notes">Notes</label><input id="notes" name="notes" type="text" value="${PERSON_VALUE}">
  <button type="submit" id="keep">Save account</button>
</form>`);

/** The password in a frame of the same site, and the one in a frame of another of the run's sites. */
export const FRAME_PASSWORD = "frame-pw-e2e-Kx4";
export const CROSS_FRAME_PASSWORD = "cross-frame-pw-e2e-Vb9";
/** Words only a frame holds: a read that has them read the frame. */
export const FRAME_WORDS = "Words inside the frame";

// Every other way a page of the run's sites can leave for other.test, each named by `from` in what other.test would be asked.
const LEAVE = {
  post: (port) => page("Send a note", `<h1>Send a note</h1>
<form id="note" method="post" action="https://other.test:${port}/collect?from=post">
  <label for="words">Note</label><input id="words" name="words" type="text">
  <button type="submit" id="send">Send the note</button>
</form>`),
  refresh: (port) => `<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="refresh" content="0; url=https://other.test:${port}/?from=refresh"><title>Moving</title></head><body><h1>Moving</h1></body></html>`,
  assign: (port) => page("A button", `<h1>A button</h1>
<button id="go" type="button" onclick="location.assign('https://other.test:${port}/?from=assign')">Go elsewhere</button>`),
  blank: (port) => page("A new tab", `<h1>A new tab</h1>
<a id="new" target="_blank" href="https://other.test:${port}/?from=blank">Open it in a new tab</a>`),
  frame: (port) => page("A frame", `<h1>A frame from elsewhere</h1>
<iframe id="away" src="https://other.test:${port}/?from=frame" width="300" height="100"></iframe>`),
};

const FRAMES = (port) => page("Frames", `<h1>Frames</h1>
<p>The page around the frames.</p>
<iframe id="same" src="/inner" width="400" height="160"></iframe>
<iframe id="cross" src="https://news.test:${port}/inner" width="400" height="160"></iframe>`);

const INNER = (password) => page("Inner", `<p>${FRAME_WORDS}</p>
<label for="secret">Frame password</label><input id="secret" type="password" value="${password}">`);

export class FixtureSites {
  async start() {
    this.dir = mkdtempSync(join(tmpdir(), "tafs-e2e-tls-"));
    const key = join(this.dir, "key.pem");
    const cert = join(this.dir, "cert.pem");
    execFileSync("openssl", ["req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes", "-keyout", key, "-out", cert, "-days", "1", "-subj", "/CN=shop.test", "-addext", "subjectAltName=DNS:shop.test,DNS:news.test,DNS:other.test"], { stdio: "ignore" });
    this.hits = []; // every request, as "host path?query"
    this.posts = []; // every POST, as "host path?query"
    this.hosts = new Map(); // a host answered by a test's own handler (serve)
    this.server = createServer({ key: readFileSync(key), cert: readFileSync(cert) }, (req, res) => this.answer(req, res));
    await new Promise((resolve) => this.server.listen(0, "127.0.0.1", resolve));
    this.port = this.server.address().port;
    return this;
  }

  /** The URL of `path` on `host` (`shop.test`, …), with this server's port. */
  url(host, path = "/") {
    return `https://${host}:${this.port}${path}`;
  }

  /** Answers every request for `host` with `handler(req, res)`, on this server, instead of a fixture page. */
  serve(host, handler) {
    this.hosts.set(host, handler);
  }

  answer(req, res) {
    const host = String(req.headers.host || "").replace(/:\d+$/, "");
    if (this.hosts.has(host)) return this.hosts.get(host)(req, res);
    const path = new URL(req.url, "https://x").pathname;
    this.hits.push(host + " " + req.url);
    if (req.method === "POST") this.posts.push(host + " " + req.url);
    // A redirect off the run's sites: the page answers 302 to other.test.
    if (host === "shop.test" && path === "/redirect") {
      res.writeHead(302, { location: this.url("other.test", "/?from=redirect"), "cache-control": "no-store" }).end();
      return;
    }
    const pages = {
      "news.test": { "/article": ARTICLE, "/long": LONG, "/inner": INNER(CROSS_FRAME_PASSWORD), "/hub": HUB(this.port) },
      "shop.test": {
        "/form": FORM, "/confirm": CONFIRM, "/account": ACCOUNT, "/links": LINKS(this.port), "/frames": FRAMES(this.port), "/inner": INNER(FRAME_PASSWORD),
        ...Object.fromEntries(Object.entries(LEAVE).map(([name, make]) => ["/leave-" + name, make(this.port)])),
      },
      "other.test": { "/": OTHER, "/collect": OTHER, "/account": ACCOUNT },
    };
    const body = (pages[host] || {})[path];
    if (!body) {
      res.writeHead(404, { "content-type": "text/plain" }).end("not found");
      return;
    }
    // `?bfcache=1`: a page Chrome may keep in its back/forward cache (no `no-store`), so a Back to it commits with no request.
    const cacheable = new URL(req.url, "https://x").searchParams.get("bfcache") === "1";
    res.writeHead(200, { "content-type": "text/html; charset=utf-8", ...(cacheable ? {} : { "cache-control": "no-store" }) }).end(body);
  }

  /** Whether `host` was asked for anything since the start. */
  reached(host) {
    return this.hits.some((h) => h.startsWith(host + " "));
  }

  async stop() {
    this.server.closeAllConnections();
    await new Promise((resolve) => this.server.close(resolve));
    rmSync(this.dir, { recursive: true, force: true });
  }
}
