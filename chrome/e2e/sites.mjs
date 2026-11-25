// The local websites the end-to-end tests drive: one HTTPS server on 127.0.0.1 that answers by host name, with a self-signed
// certificate made at the start (the `openssl` command) and thrown away after. Chromium reaches `*.test` here through
// `--host-resolver-rules` (harness.mjs), and the URLs carry the server's port: a site's rules read the host alone.
//
//   news.test   an article to read, and a long page whose text comes in windows
//   shop.test   a form (text, select, checkbox, password), a page that asks confirm(), a page that links off the run's sites
//   other.test  a site no grant lists

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

const LINKS = (port) => page("Links", `<h1>Links</h1>
<a id="off" href="https://other.test:${port}/">Somewhere else</a>`);

const OTHER = page("Other", `<h1>Not on the run's sites</h1>`);

export class FixtureSites {
  async start() {
    this.dir = mkdtempSync(join(tmpdir(), "tafs-e2e-tls-"));
    const key = join(this.dir, "key.pem");
    const cert = join(this.dir, "cert.pem");
    execFileSync("openssl", ["req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes", "-keyout", key, "-out", cert, "-days", "1", "-subj", "/CN=shop.test", "-addext", "subjectAltName=DNS:shop.test,DNS:news.test,DNS:other.test"], { stdio: "ignore" });
    this.hits = []; // every request, as "host path?query"
    this.server = createServer({ key: readFileSync(key), cert: readFileSync(cert) }, (req, res) => this.answer(req, res));
    await new Promise((resolve) => this.server.listen(0, "127.0.0.1", resolve));
    this.port = this.server.address().port;
    return this;
  }

  /** The URL of `path` on `host` (`shop.test`, …), with this server's port. */
  url(host, path = "/") {
    return `https://${host}:${this.port}${path}`;
  }

  answer(req, res) {
    const host = String(req.headers.host || "").replace(/:\d+$/, "");
    const path = new URL(req.url, "https://x").pathname;
    this.hits.push(host + " " + req.url);
    const pages = {
      "news.test": { "/article": ARTICLE, "/long": LONG },
      "shop.test": { "/form": FORM, "/confirm": CONFIRM, "/links": LINKS(this.port) },
      "other.test": { "/": OTHER },
    };
    const body = (pages[host] || {})[path];
    if (!body) {
      res.writeHead(404, { "content-type": "text/plain" }).end("not found");
      return;
    }
    res.writeHead(200, { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" }).end(body);
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
