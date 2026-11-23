// The Chrome Web Store screenshots. Renders the extension's own side panel (chrome/sidepanel) with a stub `chrome` and synthetic state, next to a
// synthetic page, at 1280x800: the Chrome Web Store's screenshot size. No real account, site or person.
const { chromium } = require("playwright");
const http = require("http");
const fs = require("fs");
const path = require("path");

// node chrome/store/screenshots.cjs, with Playwright and its Chromium installed: writes chrome/store/screenshots/*.png.
const ROOT = path.resolve(__dirname, "..");
const OUT = path.join(__dirname, "screenshots");
const messages = JSON.parse(fs.readFileSync(path.join(ROOT, "_locales/en/messages.json"), "utf8"));
const TYPES = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".png": "image/png", ".svg": "image/svg+xml", ".json": "application/json" };

const server = http.createServer((req, res) => {
  const p = path.join(ROOT, decodeURIComponent(new URL(req.url, "http://x").pathname));
  if (!p.startsWith(ROOT) || !fs.existsSync(p) || fs.statSync(p).isDirectory()) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { "content-type": TYPES[path.extname(p)] || "application/octet-stream" });
  fs.createReadStream(p).pipe(res);
});

const steps = (...s) => s;
const SCENES = {
  "1-pair": { state: { paired: false } },
  "2-working": {
    state: { paired: true, connected: true, name: "Work laptop", connections: [{ taf: "Price watch" }],
      runs: [{ id: "r1", taf: "Price watch", site: "books.example", steps: steps(
        { op: "open", url: "https://books.example/" }, { op: "read" }, { op: "act", kind: "click" }, { op: "read" }) }] },
    tafs: [{ id: "t1", name: "Price watch" }],
    thread: { messages: [
      { from: "owner", text: "Each morning, check the price of the new world atlas and tell me if it drops." },
      { from: "taf", text: "I opened books.example and read the atlas page: €42.00 today, the same as yesterday. I'll look again tomorrow morning." }] },
    page: "atlas",
  },
  "3-your-turn": {
    state: { paired: true, connected: true, name: "Work laptop", connections: [{ taf: "Book finder" }],
      runs: [{ id: "r2", taf: "Book finder", site: "books.example", hand: { words: "Type the title you're looking for in the search box, then tap Done." },
        steps: steps({ op: "open", url: "https://books.example/" }, { op: "read" }, { op: "hand" }) }] },
    tafs: [{ id: "t2", name: "Book finder" }],
    thread: { messages: [
      { from: "owner", text: "Open books.example and hand me the search box. I'll type the title myself, then tell me the first result." },
      { from: "taf", text: "The search box is yours: type the title, then tap Done in the side panel. I never see what you type." }] },
    page: "search",
  },
};

function stub({ scene, messages }) {
  if (!location.pathname.endsWith("/sidepanel/index.html")) return;
  const get = (key, subs = []) => {
    const m = messages[key];
    if (!m) return "";
    let s = m.message;
    for (const [name, ph] of Object.entries(m.placeholders || {})) s = s.split("$" + name.toUpperCase() + "$").join(ph.content);
    return s.replace(/\$(\d)/g, (_, n) => subs[n - 1] ?? "");
  };
  const answer = (m) => {
    switch (m.type) {
      case "state": return { ok: true };
      case "taf_list": return { tafs: scene.tafs || [] };
      case "taf_thread": return { thread: { ...(scene.thread || { messages: [] }), working: false, waitsOnPhone: false } };
      case "vault_list": return { entries: [] };
      case "coach_chats": return { chats: [] };
      default: return {};
    }
  };
  window.chrome = {
    i18n: { getMessage: (k, subs) => get(k, Array.isArray(subs) ? subs : subs == null ? [] : [subs]), getUILanguage: () => "en" },
    runtime: { sendMessage: async (m) => answer(m), onMessage: { addListener() {} }, getURL: (p) => "/" + p },
    storage: { session: { get: async () => ({ state: scene.state }) } },
  };
}

const PAGE = (kind) => `<!doctype html><meta charset="utf-8"><style>
 body{margin:0;font:15px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif;color:#1f2328;background:#fff}
 .bar{height:56px;display:flex;align-items:center;gap:16px;padding:0 28px;border-bottom:1px solid #e5e7eb}
 .logo{font-weight:700;font-size:20px;color:#7c3aed}.url{font-size:13px;color:#6b7280}
 .wrap{padding:32px 28px}h1{font-size:28px;margin:0 0 12px}.muted{color:#6b7280}
 .search{display:flex;gap:8px;margin:24px 0}.search input{flex:1;font:inherit;padding:12px 14px;border:1px solid #d1d5db;border-radius:10px}
 .search button{font:inherit;padding:12px 18px;border:0;border-radius:10px;background:#7c3aed;color:#fff}
 .outlined{outline:3px solid #f59e0b;outline-offset:3px}
 .card{display:flex;gap:24px;margin-top:24px}.cover{width:180px;height:240px;border-radius:8px;background:linear-gradient(135deg,#0ea5e9,#22c55e)}
 .price{font-size:26px;font-weight:700;margin:12px 0}.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:16px;margin-top:8px}
 .tile{height:150px;border-radius:8px;background:#f3f4f6}
</style>
<div class="bar"><span class="logo">Example Books</span><span class="url">books.example</span></div>
<div class="wrap">${kind === "atlas" ? `
 <div class="card"><div class="cover"></div><div><h1>The New World Atlas</h1><p class="muted">Hardcover · 320 pages</p>
 <p class="price">€42.00</p><p class="muted">In stock. Ships in 2 days.</p></div></div>` : `
 <h1>Find your next book</h1><p class="muted">Search by title, author or subject.</p>
 <form class="search" onsubmit="return false"><input class="${kind === "search" ? "outlined" : ""}" placeholder="Search books"><button>Search</button></form>
 <div class="grid"><div class="tile"></div><div class="tile"></div><div class="tile"></div></div>`}
</div>`;

(async () => {
  await new Promise((r) => server.listen(0, r));
  const port = server.address().port;
  const browser = await chromium.launch();
  for (const [name, scene] of Object.entries(SCENES)) {
    const page = await browser.newPage({ viewport: { width: 1280, height: 800 }, deviceScaleFactor: 1 });
    await page.addInitScript(stub, { scene, messages });
    await page.route("**/__site.html", (r) => r.fulfill({ contentType: "text/html", body: PAGE(scene.page) }));
    await page.route("**/__shot.html", (r) => r.fulfill({ contentType: "text/html", body: `<!doctype html><meta charset="utf-8">
      <style>html,body{margin:0;height:100%;background:#f3f4f6}.row{display:flex;height:800px}
      .site{flex:1;border:0;height:100%}.panel{width:380px;border:0;border-left:1px solid #d1d5db;height:100%}</style>
      <div class="row"><iframe class="site" src="/__site.html"></iframe><iframe class="panel" src="/sidepanel/index.html"></iframe></div>` }));
    page.on("pageerror", (e) => { console.error(name + ": " + e.message); process.exitCode = 1; });
    await page.goto(`http://127.0.0.1:${port}/__shot.html`);
    await page.waitForTimeout(1200);
    // The hand-over card in full: the panel scrolls to it, as a person would.
    if (scene.state.runs && scene.state.runs.some((r) => r.hand)) {
      const panel = page.frames().find((f) => f.url().endsWith("/sidepanel/index.html"));
      await panel.evaluate(() => document.querySelector(".run .hand").closest(".run").scrollIntoView({ block: "end" }));
      await page.waitForTimeout(200);
    }
    await page.screenshot({ path: path.join(OUT, `chrome-store-${name}.png`) });
    await page.close();
  }
  await browser.close();
  server.close();
})().catch((e) => { console.error(e); process.exit(1); });
