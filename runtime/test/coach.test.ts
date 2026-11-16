import { generateKeyPairSync, sign } from "node:crypto";
import { LANGUAGES, wordsIn } from "../src/cloud/words/index.js";
import { describe, expect, it, vi } from "vitest";
import { COACH_CARDS, COACH_TOOL_GROUPS, coachCardsDraft, coachToolsStep, OP_ARG, SESSION_ARG, TOOLS } from "../src/cloud/coach.js";
import { startDrive } from "../src/cloud/drive.js";
import { AgentsError } from "../src/cloud/agents.js";
import { CHAT, type Deps, handle, type HTTPEvent, SETUP_JOB, type Taf } from "../src/cloud/handler.js";
import { sha256 } from "../src/cloud/jcs.js";
import { readLabels, saveLabels } from "../src/cloud/labels.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryCoach, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

const T = "t_acme";
const ISSUER = "https://kiff.test";
const HOST = "tafs.test";
const AUD = `https://${HOST}/v1/mcp`;
const RAE = "taf-aaaa0000000000000000";
const SETUP = "taf-5e700000000000000000";
const { publicKey, privateKey } = generateKeyPairSync("ed25519");
const ME = { tenant_id: T, subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] };

function world() {
  const agents = fakeAgents();
  const tafs = memoryTafs();
  const coach = memoryCoach();
  for (const taf of [
    { id: RAE, name: "Refunds", job: "refunds", kiffAgent: "agent_rae", nameClaim: "c1", created: "2026-10-15T09:00:00Z" },
    { id: SETUP, name: "Setup", job: SETUP_JOB, kiffAgent: "agent_coach", nameClaim: "c2", created: "2026-10-15T09:00:00Z" },
  ] as Taf[]) {
    tafs.held.set(`${T}#${taf.id}`, taf);
    void agents.api.putAgent(T, taf.id, {} as never);
  }
  void agents.api.createSchedule(T, { id: RAE, agent: RAE, cron: "*/30 * * * *", input: "Work the refund queue.", enabled: true });
  agents.versions.set(`${T}|${RAE}`, [
    { n: 1, note: "As it was set up", by: "u_ana", created: "2026-10-15T09:00:00Z", digest: "d1", live: true },
    { n: 2, note: "Asks before refunds over 50", by: "setup", created: "2026-10-16T09:00:00Z", digest: "d2", live: false, draft: "drf_2" },
  ]);
  agents.calls.length = 0;
  let jti = 0;
  /** What was pushed to the account's one phone. */
  const pushed: Array<{ token: string; payload: Record<string, unknown> }> = [];
  const phone = { token: "a".repeat(64), environment: "production" as const, subject: "u_ana", answers: true, at: "" };
  const deps: Deps = {
    agents: agents.api, keys: memoryKeys(), accounts: memoryAccounts(), tafs, names: memoryNames(), slots: memorySlots(), claims: memoryClaims(),
    removals: memoryRemovals(), archived: memoryArchived(),
    push: { get: async () => ({ devices: [phone], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async (d, n) => { pushed.push({ token: d.token, payload: n.payload }); return "sent" as const; } },
    forward: async () => undefined,
    coach, permitKeys: async (kid) => { if (kid !== "k1") throw new Error("unknown"); return publicKey; },
    kiffBase: ISSUER, now: () => new Date("2026-10-16T12:00:00Z"),
    fetch: (async (url: string, init?: RequestInit) => {
      if (url.endsWith("/v1/me")) return new Response(JSON.stringify(ME));
      if (url.endsWith("/v1/coach/act")) {
        // By default the person uses and manages Refunds, with a run grant for it.
        const body = JSON.parse(String(init?.body)) as { permit: string; agent?: string };
        const act = JSON.parse(Buffer.from(body.permit.split(".")[1]!, "base64url").toString()).act.sub as string;
        const may = { use: true, oversee: false, manage: true, grant: `krg_${act}` };
        return new Response(JSON.stringify(body.agent ? may : { agents: { agent_rae: may } }));
      }
      return new Response("{}", { status: 404 });
    }) as unknown as typeof fetch,
  };
  /** KIFF's permit for exactly this call, from `sub`. */
  const permit = (tool: string, args: Record<string, unknown>, sub = "agent_coach", tenant = T, act?: string) => {
    const enc = (v: unknown) => Buffer.from(JSON.stringify(v)).toString("base64url");
    const iat = Math.floor(Date.parse("2026-10-16T12:00:00Z") / 1000);
    const h = enc({ alg: "Ed25519", typ: "kiff-permit+jwt", kid: "k1" });
    const c = enc({
      iss: ISSUER, aud: AUD, sub, iat, exp: iat + 60, jti: `jti_${++jti}`, ...(act ? { act: { sub: act } } : {}),
      authorization_details: [{ type: "https://kiff.dev/permits/tool-call", tenant, tool, args_sha256: sha256(args), op: args[OP_ARG] }],
    });
    return `${h}.${c}.${sign(null, Buffer.from(`${h}.${c}`), privateKey).toString("base64url")}`;
  };
  let id = 0;
  const rpc = async (method: string, params?: unknown, token?: string) => {
    const event: HTTPEvent = {
      rawPath: "/v1/mcp", requestContext: { http: { method: "POST" } },
      headers: { host: HOST, ...(token ? { "KIFF-Permit": token } : {}) },
      body: JSON.stringify({ jsonrpc: "2.0", id: ++id, method, ...(params ? { params } : {}) }),
    };
    const r = await handle(event, deps);
    return JSON.parse(r.body) as { result?: { content?: Array<{ text: string }>; isError?: boolean; tools?: Array<{ name: string; inputSchema: { required: string[] } }> } };
  };
  /** A tool call as KIFF forwards it: its operation id in the arguments, and the permit for exactly them. */
  const tool = async (name: string, args: Record<string, unknown>, opts: { op?: string; sub?: string; tenant?: string; token?: string; act?: string } = {}) => {
    const full = { ...args, [OP_ARG]: opts.op ?? `op_${name}_${++id}` };
    const r = await rpc("tools/call", { name, arguments: full }, opts.token ?? permit(name, full, opts.sub, opts.tenant, opts.act));
    const text = r.result?.content?.[0]?.text ?? "";
    return { isError: r.result?.isError === true, text, json: (() => { try { return JSON.parse(text); } catch { return undefined; } })() };
  };
  const send = async (method: string, path: string, body?: unknown, token?: string) => {
    const r = await handle({ rawPath: path, requestContext: { http: { method } }, headers: { host: HOST, ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: body === undefined ? undefined : JSON.stringify(body) }, deps);
    return { status: r.statusCode, body: r.body ? JSON.parse(r.body) : undefined };
  };
  return { agents, tafs, coach, deps, permit, rpc, tool, send, pushed };
}

describe("a drive's tools are told the chat (kiff/tafs#544)", () => {
  it("declare it to kiff/agents only on app_look and app_act, never required, and refuse another chat's call", async () => {
    const w = world();
    const list = await w.rpc("tools/list");
    const tools = (list.result?.tools ?? []) as Array<{ name: string; inputSchema: { properties: Record<string, unknown>; required: string[] } }>;
    for (const t of tools) {
      const asks = t.name === "app_look" || t.name === "app_act";
      expect(SESSION_ARG in t.inputSchema.properties, t.name).toBe(asks);
      expect(t.inputSchema.required).not.toContain(SESSION_ARG);
    }
    let clock = new Date("2026-10-16T12:00:00Z");
    w.deps.now = () => clock;
    w.deps.sleep = async (ms) => { clock = new Date(clock.getTime() + ms); };
    await startDrive(w.coach, T, "u_ana", "s_drive_chat", "Run Refunds", clock);
    // Another chat of the same person: refused, nothing sent, whatever runs are at work.
    const other = await w.tool("app_look", { [SESSION_ARG]: "s_other_chat" }, { act: "u_ana" });
    expect(other).toMatchObject({ isError: true, text: expect.stringContaining("approved in another chat") });
    // The drive's own chat goes through to the phone (which doesn't answer here).
    const own = await w.tool("app_look", { [SESSION_ARG]: "s_drive_chat" }, { act: "u_ana" });
    expect(own.text).toContain("didn't answer in 20 seconds");
  });
});

// Labels and the business description (docs/labels.md): words only, which change nothing a taf may do.
describe("labels and the business description", () => {
  const OTHER = { id: "taf-bbbb0000000000000000", name: "Bills", job: "custom", kiffAgent: "agent_bills", nameClaim: "c3", created: "2026-10-15T09:00:00Z" } as Taf;

  it("taf_labels sets a taf's whole list, makes a new label, takes a category's name in any language as one label, and drops one no taf has", async () => {
    const w = world();
    const set = await w.tool("taf_labels", { action: "set", taf: "Refunds", labels: ["Geld", "VIP"] });
    expect(set.isError).toBe(false);
    expect(set.json.labels.map((l: { name: string }) => l.name)).toEqual(["Geld", "VIP"]);
    const kept = await readLabels(w.coach, T);
    expect(kept.version).toBe(1);
    expect(kept.labels[0]).toMatchObject({ name: "Geld", key: "money", color: "money" });
    expect((await w.tafs.get(T, RAE))!.labels).toEqual(kept.labels.map((l) => l.id));
    // "money" is Geld's category: the same label, never a second one. VIP is then on no taf, and dropped.
    const again = await w.tool("taf_labels", { action: "set", taf: RAE, labels: ["money"] });
    expect(again.json.labels).toEqual([{ id: kept.labels[0]!.id, name: "Geld" }]);
    expect((await readLabels(w.coach, T)).labels.map((l) => l.name)).toEqual(["Geld"]);
    const state = await w.tool("tafs_state", {});
    expect(state.json.tafs.find((t: { id: string }) => t.id === RAE).labels).toEqual([{ id: kept.labels[0]!.id, name: "Geld" }]);
  });

  it("refuses a Card, tool or dial field, a 4th label on a taf, a 13th on the account and another account's taf, and changes nothing else of the taf", async () => {
    const w = world();
    const before = structuredClone(await w.tafs.get(T, RAE))!;
    for (const extra of [{ card: "crd_1" }, { tools: ["refund"] }, { wakes: [] }, { limit: 5 }]) {
      const r = await w.tool("taf_labels", { action: "set", taf: RAE, labels: ["Money"], ...extra });
      expect(r.isError).toBe(true);
      expect(r.text).toContain("a label changes no Card, tool or dial");
    }
    expect((await w.tool("taf_labels", { action: "set", taf: RAE, labels: ["A", "B", "C", "D"] })).text).toContain("a taf has at most 3 labels");
    // Twelve labels on four other tafs: a new name on Refunds would be the 13th.
    const twelve = Array.from({ length: 12 }, (_, i) => ({ id: `lbl_${i}`, name: `L${i}`, color: "errands" }));
    await saveLabels(w.coach, T, { labels: [], version: 0 }, twelve, new Date("2026-10-16T12:00:00Z"));
    for (let i = 0; i < 4; i++) w.tafs.held.set(`${T}#taf-${i}`, { ...OTHER, id: `taf-${i}`, name: `T${i}`, labels: [0, 1, 2].map((j) => `lbl_${i * 3 + j}`) });
    const thirteenth = await w.tool("taf_labels", { action: "set", taf: RAE, labels: ["New"] });
    expect(thirteenth.text).toContain("an account has at most 12 labels");
    // One the account has is fine.
    expect((await w.tool("taf_labels", { action: "set", taf: RAE, labels: ["L4"] })).isError).toBe(false);
    w.tafs.held.set(`t_other#${OTHER.id}`, OTHER);
    expect((await w.tool("taf_labels", { action: "set", taf: OTHER.id, labels: ["Money"] })).text).toContain("there is no taf");
    const { labels, ...after } = (await w.tafs.get(T, RAE))!;
    expect(labels).toEqual(["lbl_4"]);
    expect(after).toEqual(before);
  });

  it("rename keeps the label's id, remove takes it off every taf and leaves the rest as it was, and a person labels only tafs they manage", async () => {
    const w = world();
    await w.tool("taf_labels", { action: "set", taf: RAE, labels: ["Customers"] });
    const id = (await readLabels(w.coach, T)).labels[0]!.id;
    const renamed = await w.tool("taf_labels", { action: "rename", label: "Customers", name: "Shop", color: "errands" });
    expect(renamed.json.label).toEqual({ id, name: "Shop", color: "errands" });
    expect((await readLabels(w.coach, T)).labels[0]!.key).toBeUndefined();
    expect((await w.tool("taf_labels", { action: "rename", label: id, color: "purple" })).text).toContain("color is one of money, customers, operations, errands");
    const schedule = await w.agents.api.getSchedule(T, RAE);
    const removed = await w.tool("taf_labels", { action: "remove", label: id });
    expect(removed.json).toMatchObject({ removed: "Shop", from: ["Refunds"] });
    expect((await w.tafs.get(T, RAE))!.labels).toEqual([]);
    expect((await readLabels(w.coach, T)).labels).toEqual([]);
    expect(await w.agents.api.getSchedule(T, RAE)).toEqual(schedule);
    // KIFF says this person may use Refunds but not manage it.
    const fetchOf = w.deps.fetch!;
    w.deps.fetch = (async (url: string, init?: RequestInit) => url.endsWith("/v1/coach/act")
      ? new Response(JSON.stringify({ agents: { agent_rae: { use: true, oversee: false, manage: false } } }))
      : fetchOf(url, init)) as unknown as typeof fetch;
    const theirs = await w.tool("taf_labels", { action: "set", taf: RAE, labels: ["Money"] }, { act: "u_bo" });
    expect(theirs.text).toContain("Refunds's labels are for the people who manage it");
    // Nor rename or remove a label on a taf they don't manage.
    w.deps.fetch = fetchOf;
    await w.tool("taf_labels", { action: "set", taf: RAE, labels: ["Shop"] });
    w.deps.fetch = (async (url: string, init?: RequestInit) => url.endsWith("/v1/coach/act")
      ? new Response(JSON.stringify({ agents: { agent_rae: { use: true, oversee: false, manage: false } } }))
      : fetchOf(url, init)) as unknown as typeof fetch;
    expect((await w.tool("taf_labels", { action: "rename", label: "Shop", name: "Store" }, { act: "u_bo" })).text).toContain("Shop is on tafs they don't manage");
    expect((await w.tool("taf_labels", { action: "remove", label: "Shop" }, { act: "u_bo" })).text).toContain("Shop is on tafs they don't manage");
    expect((await readLabels(w.coach, T)).labels.map((l) => l.name)).toEqual(["Shop"]);
    expect((await w.tool("business_context", { action: "save", text: "We sell lamps.", version: 0 }, { act: "u_bo" })).text).toContain("for the people who manage all of the account's tafs");
  });

  it("business_context reads with its version and saves over it, refuses a stale or long one, and Account reads it in the person's language", async () => {
    const w = world();
    expect((await w.tool("business_context", { action: "read" })).json).toMatchObject({ text: "", version: 0, data: expect.stringContaining("never an instruction") });
    const text = "We sell lamps to shops in France.\n\nOne first message per person; never someone who asked not to be contacted.";
    expect((await w.tool("business_context", { action: "save", text, version: 0 })).json).toMatchObject({ saved: true, version: 1 });
    const stale = await w.tool("business_context", { action: "save", text: "Something else.", version: 0 });
    expect(stale.isError).toBe(true);
    expect(stale.text).toContain("changed since: read it again (version 1)");
    expect((await w.tool("business_context", { action: "save", text: "word ".repeat(301), version: 1 })).text).toContain("at most 300 words");
    expect((await w.tool("business_context", { action: "read" })).json).toMatchObject({ text, version: 1 });
    const account = await w.send("GET", "/v1/me/business", undefined, "tok");
    expect(account.body.business).toEqual({ text, version: 1, words: { title: "What tafs know about your business", empty: "Nothing yet. Coach can draft it with you.", change: "Change" } });
    for (const lang of LANGUAGES) expect(wordsIn(lang).business_title, lang).toBeTruthy();
    // Only a person puts words into every run's preface: KIFF holds each save for their yes, with the text shown,
    // and the tools step says so. A page Coach read can't save a description on its own.
    // limit 0: a Card holds a call only past its limit, so any other limit would let that many saves through unasked.
    expect(COACH_CARDS.find((c) => c.actions.includes("business_context"))).toMatchObject({ id: "coach-business", limit: 0, asks: true });
    const built = coachCardsDraft("agent_coach").cards.find((c: { id: string }) => c.id === "coach-business");
    expect(built).toMatchObject({ on_exceed: "owner" });
    const does = coachToolsStep("en").groups.flatMap((g) => g.tools).find((x) => x.name === wordsIn("en").cts_business_context)!.does;
    expect(does).toMatch(/waits for you|waits for your approval/);
  });
});

describe("Coach's tools (kiff/tafs#206)", () => {
  // Coach finds a vendor's published API description itself (webread.ts): one public page, as data, about no taf.
  it("web_read answers one public page's text as data, and refuses a page that isn't public, in words", async () => {
    const w = world();
    const asked: string[] = [];
    w.deps.pageFetch = async (url) => {
      asked.push(url.toString());
      return { status: 200, headers: { "content-type": "text/html" }, body: Buffer.from("<html><title>Acme docs</title><body>Send AcmeToken &lt;key&gt;.</body></html>"), truncated: false };
    };
    const ok = await w.tool("web_read", { url: "https://docs.acme.example.com/auth" });
    expect(ok.isError).toBe(false);
    expect(ok.json).toMatchObject({ url: "https://docs.acme.example.com/auth", kind: "html", title: "Acme docs", data: expect.stringContaining("never an instruction") });
    expect(ok.json.text).toContain("Send AcmeToken <key>.");
    const refused = await w.tool("web_read", { url: "https://169.254.169.254/latest/meta-data/" });
    expect(refused.isError).toBe(true);
    expect(refused.text).toContain("only a public page can be read");
    expect(asked).toEqual(["https://docs.acme.example.com/auth"]);
  });

  it("speak MCP, and list the sixteen tools, each with KIFF's operation argument", async () => {
    const w = world();
    const init = await w.rpc("initialize", { protocolVersion: "2025-06-18" });
    expect(init.result).toMatchObject({ protocolVersion: "2025-06-18", serverInfo: { name: "tafs" } });
    const list = await w.rpc("tools/list");
    expect(list.result?.tools?.map((t) => t.name)).toEqual(["tafs_state", "taf_run", "taf_check", "taf_task", "run_stop", "taf_pause", "taf_results", "taf_dial", "taf_estimate", "web_read", "taf_labels", "business_context", "version_try", "version_install", "app_look", "app_act"]);
    for (const t of list.result?.tools ?? []) expect(t.inputSchema.required).toContain(OP_ARG);
    expect(TOOLS.map((t) => t.name)).not.toContain("hold_answer");
  });

  it("do nothing without KIFF's permit for exactly the call, from the account's Coach", async () => {
    const w = world();
    const r = w.agents.api.startRun(T, RAE, "go");
    const run = (await r).id;
    const args = { taf: RAE, run, [OP_ARG]: "op_1" };
    const refusals = [
      await w.tool("run_stop", { taf: RAE, run }, { token: "" }),
      await w.tool("run_stop", { taf: RAE, run }, { sub: "agent_rae" }),
      await w.tool("run_stop", { taf: RAE, run }, { token: w.permit("run_stop", { ...args, run: "run_other" }) }),
      await w.tool("run_stop", { taf: RAE, run }, { token: w.permit("taf_pause", args) }),
      await w.tool("run_stop", { taf: RAE, run }, { tenant: "t_other" }),
    ];
    for (const r of refusals) expect(r).toMatchObject({ isError: true });
    expect(refusals[0]!.text).toMatch(/^Refused by tafs: no KIFF permit/);
    expect(refusals[1]!.text).toContain("only the account's Coach");
    expect(w.agents.calls).not.toContain(`cancelRun ${RAE} ${run}`);
  });

  it("take each permit once, and answer a retried operation as the first time, doing it once", async () => {
    const w = world();
    const args = { taf: RAE, text: "Refund order 42", [OP_ARG]: "op_task" };
    const token = w.permit("taf_task", args);
    const first = await w.rpc("tools/call", { name: "taf_task", arguments: args }, token);
    const again = await w.rpc("tools/call", { name: "taf_task", arguments: args }, w.permit("taf_task", args));
    expect(again.result).toEqual(first.result);
    expect(w.agents.calls.filter((c) => c.startsWith(`startRun ${RAE}`))).toHaveLength(1);
    // The same permit for a new operation is refused: it named the first one.
    const other = await w.rpc("tools/call", { name: "taf_task", arguments: { ...args, [OP_ARG]: "op_other" } }, token);
    expect(other.result?.isError).toBe(true);
  });

  it("answers a retried operation only in the chat that asked it", async () => {
    const w = world();
    const args = { taf: RAE, text: "Refund order 42", [OP_ARG]: "op_mine" };
    const first = await w.rpc("tools/call", { name: "taf_task", arguments: args }, w.permit("taf_task", args, "agent_coach", T, "u_ana"));
    expect(first.result?.isError).toBeFalsy();
    const again = await w.rpc("tools/call", { name: "taf_task", arguments: args }, w.permit("taf_task", args, "agent_coach", T, "u_ana"));
    expect(again.result).toEqual(first.result);
    // The same operation from another person's Coach chat isn't given Ana's answer.
    const other = await w.rpc("tools/call", { name: "taf_task", arguments: args }, w.permit("taf_task", args, "agent_coach", T, "u_bo"));
    expect(other.result).not.toEqual(first.result);
  });

  it("tafs_state shows each taf, its KIFF agent, versions and schedule, and what its open runs wait on, and never Setup or Cards", async () => {
    const w = world();
    const run = await w.agents.api.startRun(T, RAE, "Refund order 42");
    Object.assign(w.agents.runs.find((r) => r.id === run.id)!, {
      status: "PAUSED",
      requirements: [{ call_id: "c1", tool: "stripe_refund", input: { amount: 9000, currency: "eur" }, operation: "op", exception: "exc_1", expires: "2026-10-16T13:00:00Z" }],
    });
    const r = await w.tool("tafs_state", {});
    expect(r.isError).toBe(false);
    expect(r.json.tafs).toHaveLength(1);
    expect(r.json.tafs[0]).toMatchObject({
      id: RAE, name: "Refunds", kiff_agent: "agent_rae", live_version: 1, schedule: { cron: "*/30 * * * *", on: true },
      versions: [{ n: 1, live: true }, { n: 2, live: false }],
      open_runs: [{ run: run.id, status: "PAUSED", waits_on: [{ tool: "stripe_refund", arguments: { amount: 9000, currency: "eur" }, deadline: "2026-10-16T13:00:00.000Z" }] }],
    });
    // Coach reads a taf's Cards itself, by its KIFF agent (kiff/tafs#214, item 4).
    expect(r.json.cards).toContain("kiff_card {agent: its kiff_agent}");
    expect(r.json.tafs[0].runs_on).toBeUndefined();
  });

  it("tafs_state says a taf with no numbered version live runs the definition it was made with, and how to read a schedule", async () => {
    const w = world();
    w.agents.versions.set(`${T}|${RAE}`, []);
    const r = await w.tool("tafs_state", {});
    expect(r.json.tafs[0]).toMatchObject({ live_version: null, runs_on: expect.stringContaining("the definition it was made with"), schedule: { on: true } });
    // Not "no version installed": a schedule that's on is working, though nothing runs this minute.
    expect(r.json.reading).toContain("schedule.on true is working on its schedule");
    expect(r.json.reading).toContain("open_runs lists only what runs this minute");
  });

  it("taf_task starts a run announced back to tafs cloud, under the taf's own agent", async () => {
    const w = world();
    const r = await w.tool("taf_task", { taf: "refunds", text: "Refund order 42", chat: "new" });
    expect(r.json).toMatchObject({ taf: "Refunds", status: "PENDING" });
    const notify = w.agents.notified.get(r.json.run);
    expect(notify?.url).toBe(`https://${HOST}/v1/runs/ended`);
    expect(notify?.ref.length).toBeGreaterThanOrEqual(16);
    expect([...w.coach.held.keys()].some((k) => k.startsWith(`${T}#coach-task#`))).toBe(true);
  });

  it("answers under its public address when it has one, whatever host the request reached", async () => {
    const w = world();
    const behind = { ...w.deps, publicURL: `https://${HOST}` };
    const event = (args: Record<string, unknown>) => ({
      rawPath: "/v1/mcp", requestContext: { http: { method: "POST" } },
      headers: { host: "abc.lambda-url.eu-west-1.on.aws", "KIFF-Permit": w.permit("taf_task", args) },
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: "taf_task", arguments: args } }),
    });
    const args = { taf: "refunds", text: "Refund order 42", chat: "new", [OP_ARG]: "op_public" };
    const r = JSON.parse((await handle(event(args), behind)).body) as { result: { isError?: boolean; content: Array<{ text: string }> } };
    expect(r.result.isError).not.toBe(true);
    const run = JSON.parse(r.result.content[0]!.text).run as string;
    expect(w.agents.notified.get(run)?.url).toBe(`https://${HOST}/v1/runs/ended`);
    // Without it, a permit for the public address doesn't fit the function's own host.
    const direct = JSON.parse((await handle(event({ ...args, [OP_ARG]: "op_direct" }), w.deps)).body) as { result: { isError?: boolean } };
    expect(direct.result.isError).toBe(true);
  });

  it("taf_task with a run steers it; one that ended is refused in words", async () => {
    const w = world();
    // Coach's own task: it may write into it.
    const run = await w.agents.api.startRun(T, RAE, "go", { requester: { kind: "agent", id: "agent_coach" } });
    expect((await w.tool("taf_task", { taf: RAE, text: "Skip order 7", run: run.id })).json).toMatchObject({ run: run.id });
    w.agents.runs.find((x) => x.id === run.id)!.status = "COMPLETED";
    expect(await w.tool("taf_task", { taf: RAE, text: "And 8", run: run.id })).toMatchObject({ isError: true, text: expect.stringContaining("ended") });
  });

  it("taf_results sets where a taf's results go, all at once, and only to known places", async () => {
    const w = world();
    expect((await w.tool("taf_results", { taf: RAE, places: ["owner_email", "door_reply"] })).json).toEqual({ taf: "Refunds", results: ["door_reply", "owner_email"] });
    expect((await w.tool("tafs_state", {})).json).toMatchObject({ tafs: [expect.objectContaining({ id: RAE, results: ["door_reply", "owner_email"] })] });
    expect(await w.tool("taf_results", { taf: RAE, places: [] })).toMatchObject({ isError: true, text: expect.stringContaining("places is one or more of") });
    expect(await w.tool("taf_results", { taf: RAE, places: ["sms"] })).toMatchObject({ isError: true });
  });

  it("taf_dial drafts the dial as the taf's next version, checked like a tap, and runs nothing", async () => {
    const w = world();
    w.agents.versions.set(`${T}|${RAE}`, []);
    expect(await w.tool("taf_dial", { taf: RAE, wakes: [{ kind: "schedule", form: "every", every_minutes: 25 }] }))
      .toMatchObject({ isError: true, text: expect.stringContaining("the nearest is every 20 minutes") });
    const made = (await w.tool("taf_dial", { taf: RAE, wakes: [{ kind: "schedule", form: "weekdays", at: "09:00" }], when: { busy: "latest" } })).json as { version: number; words: string[] };
    expect(made.words).toEqual(["Weekdays at 09:00", "If busy, runs the latest once it's free", "Replies to whoever wrote"]);
    expect(w.tafs.held.get(`${T}#${RAE}`)!.knobs?.[String(made.version)]).toEqual({ wakes: [{ kind: "schedule", form: "weekdays", at: "09:00" }], busy: "latest", results: ["door_reply"] });
    expect(w.agents.calls.filter((c: string) => /(create|update)Schedule/.test(c))).toEqual([]);
    // Coach can draft any connected app's event too.
    const ev = (await w.tool("taf_dial", { taf: RAE, wakes: [{ kind: "app_event", grant: "grt_hub", event: "HUBSPOT_DEAL_WON", name: "HubSpot · deal won" }] })).json as { version: number; words: string[] };
    expect(ev.words[0]).toBe("Wakes on HubSpot · deal won");
    expect(w.tafs.held.get(`${T}#${RAE}`)!.knobs?.[String(ev.version)]?.wakes).toEqual([{ kind: "app_event", grant: "grt_hub", event: "HUBSPOT_DEAL_WON", name: "HubSpot · deal won" }]);
    // Coach's recipe and Why go with the version.
    const withRecipe = (await w.tool("taf_dial", { taf: RAE, wakes: [{ kind: "door" }], why: "Refund double charges", what: [{ label: "Check", words: "Looks up the charge" }] })).json as { version: number };
    expect(w.tafs.held.get(`${T}#${RAE}`)!.knobs?.[String(withRecipe.version)]).toMatchObject({ why: "Refund double charges", what: [{ label: "Check", words: "Looks up the charge" }] });
  });

  // A taf from before, whose schedule wakes the AI at every firing, gets the read it checks first at its next dial change.
  it("taf_dial adds a check to a taf from before: the next version carries it, and a checked taf keeps one timetable", async () => {
    const w = world();
    w.agents.versions.set(`${T}|${RAE}`, []);
    const check = { tool: "list_requests_k3f9", arguments: { status: "open" }, items: "data", cursor: "created", first: "all" };
    const made = (await w.tool("taf_dial", { taf: RAE, wakes: [{ kind: "schedule", form: "weekdays", at: "09:00" }], check })).json as { version: number; words: string[] };
    expect(made.words[0]).toBe("Weekdays at 09:00 · checks for new work first");
    expect(w.tafs.held.get(`${T}#${RAE}`)!.knobs?.[String(made.version)]?.check).toEqual(check);
    // Nothing reaches the scheduler, nor the taf's record, until the version goes live.
    expect(w.agents.calls.filter((c: string) => /(create|update)Schedule/.test(c))).toEqual([]);
    expect(w.tafs.held.get(`${T}#${RAE}`)!.check).toBeUndefined();
    expect(await w.tool("taf_dial", { taf: RAE, wakes: [{ kind: "schedule", form: "times_a_day", times: ["09:00", "13:30"] }] }))
      .toMatchObject({ isError: true, text: expect.stringContaining("the nearest is one list of times on the same minute: every day at 09:00 and 13:00") });
    expect(await w.tool("taf_dial", { taf: RAE, wakes: [{ kind: "schedule", form: "weekdays", at: "09:00" }], check: { ...check, cursor: "" } }))
      .toMatchObject({ isError: true, text: expect.stringContaining("check.cursor") });
    // Once the taf's tools are known (as Coach's results named them), the read is one of them.
    w.tafs.held.set(`${T}#${RAE}`, { ...w.tafs.held.get(`${T}#${RAE}`)!, tools: ["list_requests_k3f9", "refund_k3f9"] });
    expect(await w.tool("taf_dial", { taf: RAE, wakes: [{ kind: "schedule", form: "weekdays", at: "09:00" }], check: { ...check, tool: "list_orders_x1" } }))
      .toMatchObject({ isError: true, text: expect.stringContaining("check.tool names list_orders_x1, which isn't among the taf's tools (list_requests_k3f9, refund_k3f9)") });
    expect((await w.tool("taf_dial", { taf: RAE, wakes: [{ kind: "schedule", form: "weekdays", at: "09:00" }], check })).isError).toBeFalsy();
  });

  it("run_stop and taf_pause act through kiff/agents; an unknown taf is refused", async () => {
    const w = world();
    const run = await w.agents.api.startRun(T, RAE, "go");
    expect((await w.tool("run_stop", { taf: RAE, run: run.id })).json).toMatchObject({ status: "CANCELLED" });
    expect((await w.tool("taf_pause", { taf: RAE, action: "pause" })).json).toMatchObject({ schedule: "paused" });
    expect(await w.tool("taf_pause", { taf: "nobody", action: "pause" })).toMatchObject({ isError: true, text: expect.stringContaining("no taf") });
  });

  it("log one line per call: the tool, how it ended, and the taf's id only when it was named by id", async () => {
    const w = world();
    const lines: Array<Record<string, unknown>> = [];
    const spy = vi.spyOn(console, "log").mockImplementation((s: unknown) => {
      try { const l = JSON.parse(String(s)); if (l.event === "coach_tool") lines.push(l); } catch { /* another line */ }
    });
    try {
      await w.tool("taf_pause", { taf: RAE, action: "pause" });
      await w.tool("taf_pause", { taf: "Rae the refunds taf", action: "pause" });
      await w.tool("tafs_state", {});
      await w.tool("taf_pause", { taf: RAE, action: "pause" }, { token: "not-a-permit" });
      const args = { taf: RAE, text: "Refund order 42 for ana@example.com", [OP_ARG]: "op_logged" };
      await w.rpc("tools/call", { name: "taf_task", arguments: args }, w.permit("taf_task", args));
      await w.rpc("tools/call", { name: "taf_task", arguments: args }, w.permit("taf_task", args));
    } finally {
      spy.mockRestore();
    }
    expect(lines).toEqual([
      { event: "coach_tool", tool: "taf_pause", outcome: "done", taf: RAE },
      { event: "coach_tool", tool: "taf_pause", outcome: "refused_by_tool" },
      { event: "coach_tool", tool: "tafs_state", outcome: "done" },
      { event: "coach_tool", tool: "taf_pause", outcome: "refused", taf: RAE },
      { event: "coach_tool", tool: "taf_task", outcome: "done", taf: RAE },
      { event: "coach_tool", tool: "taf_task", outcome: "repeat", taf: RAE },
    ]);
    // Never what was asked or answered: no name, no address, no text.
    expect(JSON.stringify(lines)).not.toMatch(/Rae the refunds|ana@|order 42/);
  });

  it("log an answer marked as an error, a failure and a call already under way as such", async () => {
    const w = world();
    const lines: Array<Record<string, unknown>> = [];
    const spy = vi.spyOn(console, "log").mockImplementation((s: unknown) => {
      try { const l = JSON.parse(String(s)); if (l.event === "coach_tool") lines.push(l); } catch { /* another line */ }
    });
    const quiet = vi.spyOn(console, "error").mockImplementation(() => {});
    try {
      // A drive's look from Coach's own chat: answered, as an error, in the tool's words.
      await w.tool("app_look", {});
      // kiff/agents fails under a task: tafs cloud didn't expect that.
      w.agents.fail.set("startRun", new Error("kiff/agents is down"));
      await w.tool("taf_task", { taf: RAE, text: "Refund order 42" });
      w.agents.fail.delete("startRun");
      // The same operation while the first still holds its claim.
      const args = { taf: RAE, action: "pause", [OP_ARG]: "op_busy" };
      await w.coach.once(`${T}#coach-op2##op_busy#claim`, new Date(Date.parse("2026-10-16T13:00:00Z")));
      await w.rpc("tools/call", { name: "taf_pause", arguments: args }, w.permit("taf_pause", args));
    } finally {
      spy.mockRestore(); quiet.mockRestore();
    }
    expect(lines).toEqual([
      { event: "coach_tool", tool: "app_look", outcome: "done_with_error" },
      { event: "coach_tool", tool: "taf_task", outcome: "failed", taf: RAE },
      { event: "coach_tool", tool: "taf_pause", outcome: "busy", taf: RAE },
    ]);
  });

  it("hands a task over as Coach itself, in Coach's own chat with the taf", async () => {
    const w = world();
    const first = await w.tool("taf_task", { taf: RAE, text: "Refund order 42" });
    const second = await w.tool("taf_task", { taf: RAE, text: "Refund order 43" });
    const fresh = await w.tool("taf_task", { taf: RAE, text: "Refund order 44", chat: "new" });
    const runs = [first.json.run, second.json.run, fresh.json.run].map((id) => w.agents.runs.find((r) => r.id === id)!);
    expect(runs.map((r) => r.requester?.kind)).toEqual(["agent", "agent", "agent"]);
    expect(runs[1]!.session).toBe(runs[0]!.session);
    expect(runs[2]!.session).not.toBe(runs[0]!.session);
  });

  it("in a person's chat, does only what KIFF says they may, and hands tasks over as theirs", async () => {
    const w = world();
    // KIFF's answers, by person: Ana uses Refunds, Max oversees it, Bo has nothing.
    const may: Record<string, Record<string, unknown>> = {
      u_ana: { use: true, oversee: false, manage: false, grant: "krg_u_ana" },
      u_max: { use: false, oversee: true, manage: false },
      u_bo: { use: false, oversee: false, manage: false },
    };
    const asked: Array<Record<string, unknown>> = [];
    const me = w.deps.fetch!;
    w.deps.fetch = (async (url: string, init?: RequestInit) => {
      if (!url.endsWith("/v1/coach/act")) return me(url, init);
      const body = JSON.parse(String(init?.body)) as { permit: string; agent?: string };
      asked.push(body);
      const act = JSON.parse(Buffer.from(body.permit.split(".")[1]!, "base64url").toString()).act.sub as string;
      return new Response(JSON.stringify(body.agent ? may[act] : { agents: { agent_rae: may[act] } }));
    }) as unknown as typeof fetch;

    // Ana's task is hers: her own chat with the taf, under her grant.
    const task = await w.tool("taf_task", { taf: RAE, text: "Refund order 42" }, { act: "u_ana" });
    expect(task.isError).toBe(false);
    const run = w.agents.runs.find((r) => r.id === task.json.run)!;
    expect(run.requester).toEqual({ kind: "member", id: "u_ana" });
    expect(asked[0]).toMatchObject({ agent: "agent_rae", permit: expect.any(String) });
    // Bo may not use it: nothing starts, and he lists no tafs.
    const bo = await w.tool("taf_task", { taf: RAE, text: "Refund order 43" }, { act: "u_bo" });
    expect(bo).toMatchObject({ isError: true, text: expect.stringContaining("isn't shared with them") });
    expect((await w.tool("tafs_state", {}, { act: "u_bo" })).json.tafs).toEqual([]);
    // Max oversees it, but no one reads or writes into another person's run: only stops it.
    expect(await w.tool("taf_run", { taf: RAE, run: run.id }, { act: "u_max" })).toMatchObject({ isError: true, text: expect.stringContaining("isn't theirs to read") });
    expect(await w.tool("taf_task", { taf: RAE, text: "and refund 43 too", run: run.id }, { act: "u_max" })).toMatchObject({ isError: true, text: expect.stringContaining("isn't theirs to write into") });
    expect((await w.tool("taf_run", { taf: RAE, run: run.id }, { act: "u_ana" })).isError).toBe(false);
    // Bo can't stop Ana's run; Max, who oversees, can.
    expect(await w.tool("run_stop", { taf: RAE, run: run.id }, { act: "u_bo" })).toMatchObject({ isError: true, text: expect.stringContaining("isn't theirs to stop") });
    expect((await w.tool("run_stop", { taf: RAE, run: run.id }, { act: "u_max" })).json).toMatchObject({ status: "CANCELLED" });
    // Changing versions takes manage, which no one here has.
    expect(await w.tool("version_try", { taf: RAE, n: 2 }, { act: "u_max" })).toMatchObject({ isError: true, text: expect.stringContaining("people who manage it") });
  });

  it("steers a run a person started in the app from their own Coach chat, never from anyone else's", async () => {
    const w = world();
    const ana = { requester: { kind: "member" as const, id: "u_ana" } };
    const run = await w.agents.api.startRun(T, RAE, "Work the queue", ana);
    expect((await w.tool("taf_task", { taf: RAE, text: "only the €20 one", run: run.id }, { act: "u_ana" })).json).toMatchObject({ run: run.id });
    expect(await w.tool("taf_task", { taf: RAE, text: "and the €30 one", run: run.id }, { act: "u_bo" })).toMatchObject({ isError: true, text: expect.stringContaining("isn't theirs to write into") });
    expect(await w.tool("taf_task", { taf: RAE, text: "and the €40 one", run: run.id })).toMatchObject({ isError: true, text: expect.stringContaining("isn't theirs to write into") });
    expect((await w.tool("run_stop", { taf: RAE, run: run.id })).json).toMatchObject({ status: "CANCELLED" });
  });

  it("tafs_state shows another person's runs by how they stand only, never what they said or used", async () => {
    const w = world();
    const bo = await w.agents.api.startRun(T, RAE, "Refund order 7 for Ivo", { requester: { kind: "member", id: "u_bo" } });
    Object.assign(w.agents.runs.find((r) => r.id === bo.id)!, {
      status: "PAUSED", output: "Ivo's card ends 4242",
      requirements: [{ call_id: "c1", tool: "stripe_refund", input: { customer: "ivo@example.com" }, operation: "op", exception: "exc_1", expires: "2026-10-16T13:00:00Z" }],
    });
    const mine = await w.agents.api.startRun(T, RAE, "Refund order 8", { requester: { kind: "member", id: "u_ana" } });
    Object.assign(w.agents.runs.find((r) => r.id === mine.id)!, { status: "PAUSED", output: "Order 8 is 20 EUR" });
    const r = await w.tool("tafs_state", {}, { act: "u_ana" });
    const runs = r.json.tafs[0].open_runs as Array<Record<string, unknown>>;
    const theirs = runs.find((x) => x.run === bo.id)!;
    expect(Object.keys(theirs).sort()).toEqual(["run", "started", "status", "trigger", "updated"]);
    expect(JSON.stringify(r.json)).not.toContain("ivo@example.com");
    expect(JSON.stringify(r.json)).not.toContain("4242");
    expect(runs.find((x) => x.run === mine.id)).toMatchObject({ answer: "Order 8 is 20 EUR" });
  });

  it("version_try starts a trial announced back; version_install only says where the owner's install stands", async () => {
    const w = world();
    const t = await w.tool("version_try", { taf: RAE, n: 2 });
    expect(w.agents.notified.get(t.json.trial)?.url).toBe(`https://${HOST}/v1/runs/ended`);
    expect((await w.tool("version_install", { taf: RAE, n: 2 })).json).toMatchObject({ installed: false, say: expect.stringContaining("Open Refunds in tafs") });
    w.agents.switching.set(`${T}|${RAE}`, { n: 2, draft: "drf_2", at: "2026-10-16T12:00:00Z" });
    expect((await w.tool("version_install", { taf: RAE, n: 2 })).json).toMatchObject({ installed: false, waiting_for: expect.stringContaining("confirm its Card") });
    expect((await w.tool("version_install", { taf: RAE, n: 1 })).json).toMatchObject({ installed: true });
    expect(w.agents.calls.some((c) => c.startsWith("beginSwitch") || c.startsWith("switchVersion"))).toBe(false);
  });

  it("a task's end reaches Coach's run at work, read back from kiff/agents; an unknown ref is dropped", async () => {
    const w = world();
    const coachRun = await w.agents.api.startRun(T, SETUP, "Have Refunds refund order 42", { requester: { kind: "member", id: "u_ana" } });
    w.agents.runs.find((r) => r.id === coachRun.id)!.status = "RUNNING";
    // Another person's Coach run at work too: it never hears of Ana's task.
    const boRun = await w.agents.api.startRun(T, SETUP, "Something else", { requester: { kind: "member", id: "u_bo" } });
    w.agents.runs.find((r) => r.id === boRun.id)!.status = "RUNNING";
    const task = await w.tool("taf_task", { taf: RAE, text: "Refund order 42" }, { act: "u_ana" });
    const ref = w.agents.notified.get(task.json.run)!.ref;
    Object.assign(w.agents.runs.find((r) => r.id === task.json.run)!, { status: "COMPLETED", output: "Refunded order 42. It was 30 EUR. Nothing else was due." });
    expect((await w.send("POST", "/v1/runs/ended", { run: task.json.run, agent: RAE, status: "COMPLETED", ref: "bm8.unknown" })).body).toEqual({ taken: false });
    expect((await w.send("POST", "/v1/runs/ended", { run: task.json.run, agent: RAE, status: "COMPLETED", ref })).body).toEqual({ taken: true });
    const steered = w.agents.runs.find((r) => r.id === coachRun.id)!.steering!;
    expect(steered.at(-1)!.text).toBe(`[taf Refunds run ${task.json.run} ended: Refund order 42] It finished. answer: "Refunded order 42. It was 30 EUR."`);
    expect(w.agents.runs.find((r) => r.id === boRun.id)!.steering ?? []).toEqual([]);
    // Once ended, the ref is spent.
    expect((await w.send("POST", "/v1/runs/ended", { run: task.json.run, agent: RAE, status: "COMPLETED", ref })).body).toEqual({ taken: false });
  });

  it("says a run kiff/agents stopped with a code in its own words, whole, and names the code for Coach", async () => {
    const w = world();
    const task = await w.tool("taf_task", { taf: RAE, text: "Refund order 42" }, { act: "u_ana" });
    const ref = w.agents.notified.get(task.json.run)!.ref;
    const words = "Our AI provider isn't serving us right now, so this stopped. That's on our side, not yours: none of your credits were used. Try again a little later.";
    Object.assign(w.agents.runs.find((r) => r.id === task.json.run)!, { status: "ERROR", error: words, code: "ai_unavailable" });
    await w.send("POST", "/v1/runs/ended", { run: task.json.run, agent: RAE, status: "ERROR", ref });
    const before = await w.send("GET", `/v1/tafs/${SETUP}/conversation`, undefined, "ana");
    expect(before.body.coach_lines).toMatchObject([{ taf: "Refunds", said: wordsIn("en").code_ai_unavailable }]);
    await w.send("POST", `/v1/tafs/${SETUP}/messages`, { text: "How did it go?" }, "ana");
    const sent = w.agents.events.get(w.agents.runs[0]!.session)!.at(-1)!.body as { text: string };
    // tafs cloud words a code it knows from its own catalog (docs/languages.md), whatever kiff/agents sent.
    expect(sent.text).toContain(`ended: Refund order 42] It failed (ai_unavailable). error: ${JSON.stringify(wordsIn("en").code_ai_unavailable)}`);
  });

  it("never says a run cut off at its turn or cost cap finished: not on the phone, not in Coach's chat, not to Coach", async () => {
    const said = { turn_cap: "It stopped before it finished: it reached its limit of steps for one run.", cost_cap: "It stopped before it finished: it reached its cost limit for one run." };
    for (const stop of ["turn_cap", "cost_cap"] as const) {
      const w = world();
      const task = await w.tool("taf_task", { taf: RAE, text: "Refund order 42" }, { act: "u_ana" });
      const ref = w.agents.notified.get(task.json.run)!.ref;
      Object.assign(w.agents.runs.find((r) => r.id === task.json.run)!, { status: "COMPLETED", stop, output: "Now let me check order 43." });
      (w.deps.slots as ReturnType<typeof memorySlots>).taken.add(`present#${T}`);
      await w.send("POST", "/v1/runs/ended", { run: task.json.run, agent: RAE, status: "COMPLETED", ref });
      const body = (w.pushed[0]!.payload as { aps: { alert: { body: string } } }).aps.alert.body;
      expect(body).toBe(said[stop]);
      // Negative: neither the push nor Coach's chat says it finished or was done.
      expect(body).not.toMatch(/finished\.|\bDone\b/);
      const lines = (await w.send("GET", `/v1/tafs/${SETUP}/conversation`, undefined, "ana")).body.coach_lines;
      expect(lines).toEqual([{ at: "2026-10-16T12:00:00.000Z", taf: "Refunds", said: said[stop] }]);
      await w.send("POST", `/v1/tafs/${SETUP}/messages`, { text: "How did it go?" }, "ana");
      const sent = w.agents.events.get(w.agents.runs[0]!.session)!.at(-1)!.body as { text: string };
      expect(sent.text).toContain(`ended: Refund order 42] It stopped before it finished (${stop}). last words: "Now let me check order 43."`);
      expect(sent.text).not.toContain("It finished");
    }
  });

  it("still says an ordinary run that answered finished, on the phone and in Coach's chat", async () => {
    const w = world();
    const task = await w.tool("taf_task", { taf: RAE, text: "Refund order 42" }, { act: "u_ana" });
    const ref = w.agents.notified.get(task.json.run)!.ref;
    Object.assign(w.agents.runs.find((r) => r.id === task.json.run)!, { status: "COMPLETED", stop: "answered", output: "Refunded order 42." });
    (w.deps.slots as ReturnType<typeof memorySlots>).taken.add(`present#${T}`);
    await w.send("POST", "/v1/runs/ended", { run: task.json.run, agent: RAE, status: "COMPLETED", ref });
    expect((w.pushed[0]!.payload as { aps: { alert: { body: string } } }).aps.alert.body).toBe("It finished. Open Coach mode to read how it went.");
    expect((await w.send("GET", `/v1/tafs/${SETUP}/conversation`, undefined, "ana")).body.coach_lines).toMatchObject([{ said: "Refunded order 42." }]);
  });

  it("with Coach's run over, the line waits for its next turn, shown in its chat meanwhile and never in the owner's words", async () => {
    const w = world();
    const task = await w.tool("taf_task", { taf: RAE, text: "Refund order 42" }, { act: "u_ana" });
    const ref = w.agents.notified.get(task.json.run)!.ref;
    Object.assign(w.agents.runs.find((r) => r.id === task.json.run)!, { status: "ERROR", error: "Stripe said no." });
    // The owner has tafs open: told quietly, and the line isn't kept for later (kiff/tafs#210).
    (w.deps.slots as ReturnType<typeof memorySlots>).taken.add(`present#${T}`);
    await w.send("POST", "/v1/runs/ended", { run: task.json.run, agent: RAE, status: "ERROR", ref });
    expect(w.pushed).toEqual([{ token: "a".repeat(64), payload: {
      aps: { alert: { title: "Refunds", body: "It failed. Open Coach mode to see why." }, "thread-id": "coach", "interruption-level": "passive" }, open: "coach",
    } }]);
    const before = await w.send("GET", `/v1/tafs/${SETUP}/conversation`, undefined, "ana");
    expect(before.body.coach_lines).toEqual([{ at: "2026-10-16T12:00:00.000Z", taf: "Refunds", said: "It failed: Stripe said no." }]);
    await w.send("POST", `/v1/tafs/${SETUP}/messages`, { text: "How did it go?" }, "ana");
    const sent = w.agents.events.get(w.agents.runs[0]!.session)!.at(-1)!.body as { text: string };
    expect(sent.text).toBe(`[tafs coach lines]\n[taf Refunds run ${task.json.run} ended: Refund order 42] It failed. error: "Stripe said no."\nThe owner says: How did it go?`);
    const after = await w.send("GET", `/v1/tafs/${SETUP}/conversation`, undefined, "ana");
    expect(after.body.coach_lines).toBeUndefined();
    expect(after.body.messages.at(-1)).toMatchObject({ from: "owner", text: "How did it go?" });
  });

  // A phone test: words to a Coach run still at work (paused on an approval) go as a steer, and Coach's line
  // stayed under every later message, never read by Coach.
  it("a steer to Coach's run at work carries Coach's lines, and one never read leaves them for the next message", async () => {
    const w = world();
    await w.send("POST", `/v1/tafs/${SETUP}/messages`, { text: "Connect my calendar" }, "ana");
    const coachRun = w.agents.runs.find((r) => r.agent === SETUP)!;
    coachRun.status = "PAUSED";
    const line = (n: number) => ({ at: "2026-10-16T12:00:00.000Z", text: `[taf Refunds version ${n} live] The owner installed version ${n}, which you didn't make; it runs now.`,
      taf: "Refunds", said: `Version ${n} is live.` });
    await w.coach.append(`${T}#coach-notes#u_ana`, line(2), new Date("2026-10-17T12:00:00Z"));
    expect((await w.send("POST", `/v1/tafs/${SETUP}/steer`, { run: coachRun.id, text: "And the shared one" }, "ana")).status).toBe(200);
    expect(coachRun.steering?.at(-1)?.text).toBe(`[tafs coach lines]\n${line(2).text}\nThe owner says: And the shared one`);
    expect((await w.send("GET", `/v1/tafs/${SETUP}/conversation`, undefined, "ana")).body.coach_lines).toBeUndefined();
    // The run ended first: the steer is refused, and the line waits for the message the app sends instead.
    await w.coach.append(`${T}#coach-notes#u_ana`, line(3), new Date("2026-10-17T12:00:00Z"));
    coachRun.status = "COMPLETED";
    expect((await w.send("POST", `/v1/tafs/${SETUP}/steer`, { run: coachRun.id, text: "Still there?" }, "ana")).status).toBe(409);
    expect((await w.send("GET", `/v1/tafs/${SETUP}/conversation`, undefined, "ana")).body.coach_lines).toEqual([{ at: line(3).at, taf: "Refunds", said: "Version 3 is live." }]);
  });

  it("a run that ends while the owner is away pushes nothing, and its line waits for \"While you were away\" (kiff/tafs#210)", async () => {
    const w = world();
    const task = await w.tool("taf_task", { taf: RAE, text: "Refund order 42" }, { act: "u_ana" });
    const ref = w.agents.notified.get(task.json.run)!.ref;
    Object.assign(w.agents.runs.find((r) => r.id === task.json.run)!, { status: "COMPLETED", output: "Refunded order 42." });
    await w.send("POST", "/v1/runs/ended", { run: task.json.run, agent: RAE, status: "COMPLETED", ref });
    expect(w.pushed).toEqual([]);
    const lines = (await w.send("GET", `/v1/tafs/${SETUP}/conversation`, undefined, "ana")).body.coach_lines;
    expect(lines).toEqual([{ at: "2026-10-16T12:00:00.000Z", taf: "Refunds", said: "Refunded order 42.", away: true }]);
    // How it went is Ana's alone: kept under her, never for the whole account.
    expect([...w.coach.held.keys()].filter((k) => k.includes("#coach-notes"))).toEqual([`${T}#coach-notes#u_ana`]);
  });

  it("a paused task's run is a hold: KIFF's alert interrupts, so tafs cloud pushes nothing for it", async () => {
    const w = world();
    (w.deps.slots as ReturnType<typeof memorySlots>).taken.add(`present#${T}`);
    const task = await w.tool("taf_task", { taf: RAE, text: "Refund order 42" });
    const ref = w.agents.notified.get(task.json.run)!.ref;
    Object.assign(w.agents.runs.find((r) => r.id === task.json.run)!, {
      status: "PAUSED", requirements: [{ call_id: "c1", tool: "stripe_refund", input: {}, operation: "op", expires: "2026-10-16T13:00:00Z" }],
    });
    await w.send("POST", "/v1/runs/ended", { run: task.json.run, agent: RAE, status: "PAUSED", ref });
    expect(w.pushed).toEqual([]);
  });

  it("a message that doesn't start leaves Coach's lines for the next one", async () => {
    const w = world();
    const task = await w.tool("taf_task", { taf: RAE, text: "Refund order 42" }, { act: "u_ana" });
    const ref = w.agents.notified.get(task.json.run)!.ref;
    Object.assign(w.agents.runs.find((r) => r.id === task.json.run)!, { status: "ERROR", error: "Stripe said no." });
    await w.send("POST", "/v1/runs/ended", { run: task.json.run, agent: RAE, status: "ERROR", ref });
    // Past the person's flood guard in kiff/agents: the owner's message is refused.
    w.agents.fail.set("startRun", new AgentsError(429, "that's 10 messages in 10 minutes; wait a little before writing again"));
    expect((await w.send("POST", `/v1/tafs/${SETUP}/messages`, { text: "How did it go?" }, "ana")).status).toBe(429);
    expect((await w.send("GET", `/v1/tafs/${SETUP}/conversation`, undefined, "ana")).body.coach_lines).toHaveLength(1);
    // kiff/agents failing keeps them too.
    w.agents.fail.set("startRun", new Error("kiff/agents is down"));
    expect((await w.send("POST", `/v1/tafs/${SETUP}/messages`, { text: "How did it go?" }, "ana")).status).toBe(502);
    expect((await w.send("GET", `/v1/tafs/${SETUP}/conversation`, undefined, "ana")).body.coach_lines).toHaveLength(1);
  });

  it("a tool that fails frees its operation, and doesn't say nothing changed", async () => {
    const w = world();
    w.agents.fail.set("startRun", new Error("kiff/agents is down"));
    const failed = await w.tool("taf_task", { taf: RAE, text: "Refund order 42" }, { op: "op_retry" });
    expect(failed).toMatchObject({ isError: true, text: expect.stringContaining("may have started") });
    expect(failed.text).not.toContain("Nothing was changed");
    const again = await w.tool("taf_task", { taf: RAE, text: "Refund order 42" }, { op: "op_retry" });
    expect(again.isError).toBe(false);
    expect(again.json).toMatchObject({ taf: "Refunds" });
  });

  it("a paused task tells Coach it waits for the owner, and keeps its ref for the end", async () => {
    const w = world();
    const task = await w.tool("taf_task", { taf: RAE, text: "Refund order 42" }, { act: "u_ana" });
    const ref = w.agents.notified.get(task.json.run)!.ref;
    Object.assign(w.agents.runs.find((r) => r.id === task.json.run)!, {
      status: "PAUSED", requirements: [{ call_id: "c1", tool: "stripe_refund", input: {}, operation: "op", expires: "2026-10-16T13:00:00Z" }],
    });
    expect((await w.send("POST", "/v1/runs/ended", { run: task.json.run, agent: RAE, status: "PAUSED", ref })).body).toEqual({ taken: true });
    const lines = (await w.send("GET", `/v1/tafs/${SETUP}/conversation`, undefined, "ana")).body.coach_lines;
    expect(lines[0]).toMatchObject({ taf: "Refunds", said: "Waits for your answer." });
    expect([...w.coach.held.keys()].some((k) => k.startsWith(`${T}#coach-task#`))).toBe(true);
  });
});

describe("Coach's tools step, at the end of a first Start", () => {
  it("groups every tool once, in four groups, with each Card's limit in its line in every language", () => {
    const grouped = COACH_TOOL_GROUPS.flatMap((g) => g.tools);
    expect([...grouped].sort()).toEqual(TOOLS.map((t) => t.name).sort());
    for (const lang of LANGUAGES) {
      const step = coachToolsStep(lang);
      expect(step.groups).toHaveLength(4);
      for (const k of ["title", "intro", "details", "give", "not_now", "row"] as const) expect(step[k].trim()).not.toBe("");
      const n = (x: number) => new Intl.NumberFormat(lang === "en" ? "en-GB" : lang).format(x);
      COACH_TOOL_GROUPS.forEach((g, gi) => {
        const group = step.groups[gi]!;
        expect(group.name && group.line).toBeTruthy();
        g.tools.forEach((tool, ti) => {
          const line = group.tools[ti]!;
          expect(line.name && line.does && line.why).toBeTruthy();
          const card = COACH_CARDS.find((c) => c.actions.includes(tool))!;
          if (card.limit > 0) expect(line.does, `${lang} ${tool}`).toContain(n(card.limit));
        });
      });
    }
  });

  it("says a tool waits for you only where its Card holds, and only the limit where it refuses", () => {
    const step = coachToolsStep("en");
    const does = Object.fromEntries(COACH_TOOL_GROUPS.flatMap((g, gi) => g.tools.map((t, ti) => [t, step.groups[gi]!.tools[ti]!.does])));
    for (const card of COACH_CARDS) {
      for (const tool of card.actions) {
        expect(/waits for you|waits for your approval/.test(does[tool]!), tool).toBe(card.asks);
      }
    }
    expect(does.tafs_state).toBe("Read each taf, its versions and its open runs. Up to 500 a day, counted with “Read a run”.");
    expect(does.taf_task).toBe("Hand a taf a task or a message. The taf works under its own Card. Up to 50 a day; after that, each one waits for you.");
    expect(does.version_install).toBe("Put a version live. Each one waits for your approval.");
    // The row shows while any tool is still to give, some given or none: it never says Coach has none.
    expect(step.row).toBe("Coach's tools are waiting for you · Give them");
    expect(does.app_look).toContain("Up to 2,000 a day.");
    expect(does.app_act).toContain("Up to 1,000 a day.");
    expect(coachToolsStep("de").groups[3]!.tools[0]!.does).toContain("2.000");
    // No biometric named and no count of confirmations, in any language: the phone knows what it asks for, and it asks once.
    for (const lang of LANGUAGES) expect(JSON.stringify(coachToolsStep(lang)).toLowerCase(), lang).not.toMatch(/fingerprint|face id|fingerabdruck|empreinte|huella|impronta|impressão digital|vingerafdruck|vingerafdruk|odcisk|twice|zweimal|deux fois|dos veces|due volte|duas vezes|twee keer|dwa razy/);
  });
});

// The owner removed a Qonto taf, then a new Coach chat asked whether "no client invoices" on that account was
// normal (owner's phone, 13 Nov): its last result was still queued for Coach, and Coach's notes still named it.
// Removing a taf forgets it for Coach too, for every person on the account.
describe("a removed taf is forgotten by Coach", () => {
  it("drops its queued lines for every person, by its id or an older line's name, and forgets Coach's notes naming it", async () => {
    const w = world();
    const CHB = "taf_chb";
    w.tafs.held.set(`${T}#${CHB}`, { id: CHB, name: "Chargebacks", job: "chargebacks", kiffAgent: "agent_chb", nameClaim: "c3", created: "2026-10-15T09:00:00Z" } as Taf);
    const later = new Date("2026-10-17T12:00:00Z");
    const line = (taf: string, taf_id: string | undefined, said: string) =>
      ({ at: "2026-10-16T12:00:00.000Z", text: `[taf ${taf} run r1 ended] It finished. answer: ${JSON.stringify(said)}`, taf, ...(taf_id ? { taf_id } : {}), said });
    await w.coach.append(`${T}#coach-notes#u_ana`, line("Refunds", RAE, "No client invoices on this account."), later);
    await w.coach.append(`${T}#coach-notes#u_ana`, line("Refunds", undefined, "An older line, by name only."), later);
    await w.coach.append(`${T}#coach-notes#u_ana`, line("Chargebacks", CHB, "One dispute answered."), later);
    await w.coach.append(`${T}#coach-notes#u_bo`, line("Refunds", RAE, "No client invoices on this account."), later);
    w.agents.notes.set(`${T}|${SETUP}`, [
      { agent: SETUP, name: "refunds-account", text: "Refunds' Qonto account has no client invoices.", updated: "2026-10-16T12:00:00Z" },
      { agent: SETUP, scope: "member:u_ana", name: "ana-waiting", text: `${RAE} waits for Ana's approval.`, updated: "2026-10-16T12:00:00Z" },
      { agent: SETUP, name: "chargebacks", text: "Chargebacks answers disputes on Fridays.", updated: "2026-10-16T12:00:00Z" },
      // "Refunds" inside another word isn't the taf.
      { agent: SETUP, name: "policy", text: "RefundsPolicy is 14 days.", updated: "2026-10-16T12:00:00Z" },
      // Nor is the plain word, in a note about another taf (agentgabo on #835).
      { agent: SETUP, name: "disputes", text: "Disputes come in by email; refunds are handled elsewhere.", updated: "2026-10-16T12:00:00Z" },
      // A note about the removed taf and one still there goes too: removed means forgotten.
      { agent: SETUP, name: "both", text: "Refunds' Qonto account has no client invoices; Chargebacks uses Stripe.", updated: "2026-10-16T12:00:00Z" },
    ]);
    // Ana manages Refunds, as KIFF says.
    const kiff = w.deps.fetch!;
    // Bo is another person on the account, with lines of their own.
    w.deps.fetch = (async (url: string, init?: RequestInit) => {
      const bo = String((init?.headers as Record<string, string> | undefined)?.authorization ?? (init?.headers as Record<string, string> | undefined)?.Authorization ?? "").endsWith(" bo");
      if (url.endsWith("/v1/me") && bo) return new Response(JSON.stringify({ ...ME, subject: "u_bo" }));
      if (url.endsWith("/v1/me/access")) return new Response(JSON.stringify({ agents: { agent_rae: { use: true, oversee: true, manage: true } } }));
      return kiff(url, init);
    }) as typeof fetch;
    const removed = await w.send("DELETE", `/v1/tafs/${RAE}`, undefined, "ana");
    expect(removed.body, JSON.stringify(removed)).toMatchObject({ done: true });
    // Coach's chat shows only the taf that's still there.
    const chat = await w.send("GET", `/v1/tafs/${SETUP}/conversation`, undefined, "ana");
    expect((chat.body.coach_lines as Array<{ taf: string }>).map((l) => l.taf)).toEqual(["Chargebacks"]);
    // The owner's next words to Coach carry nothing of the removed taf, and still carry the other's line.
    await w.send("POST", `/v1/tafs/${SETUP}/messages`, { text: "Every Monday at 9, list my overdue client invoices." }, "ana");
    const sent = (w.agents.events.get(w.agents.runs.find((r) => r.agent === SETUP)!.session)!.at(-1)!.body as { text: string }).text;
    expect(sent).toContain("One dispute answered.");
    expect(sent).not.toMatch(/Refunds|client invoices on this account|older line/);
    // Another person's list loses it too: Bo's Coach chat shows none of it.
    const boChat = await w.send("GET", `/v1/tafs/${SETUP}/conversation`, undefined, "bo");
    expect(boChat.status).toBe(200);
    expect(boChat.body.coach_lines).toBeUndefined();
    expect(w.tafs.held.has(`${T}#${RAE}`)).toBe(false);
    // Coach's notes naming it, shared or a person's own, are forgotten; the others stay.
    expect((w.agents.notes.get(`${T}|${SETUP}`) ?? []).map((n) => n.name)).toEqual(["chargebacks", "policy", "disputes"]);
  });
});
