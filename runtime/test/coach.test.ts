import { generateKeyPairSync, sign } from "node:crypto";
import { describe, expect, it } from "vitest";
import { OP_ARG, TOOLS } from "../src/cloud/coach.js";
import { AgentsError } from "../src/cloud/agents.js";
import { CHAT, type Deps, handle, type HTTPEvent, SETUP_JOB, type Taf } from "../src/cloud/handler.js";
import { sha256 } from "../src/cloud/jcs.js";
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

describe("Coach's tools (kiff/tafs#206)", () => {
  it("speak MCP, and list the eight tools, each with KIFF's operation argument", async () => {
    const w = world();
    const init = await w.rpc("initialize", { protocolVersion: "2025-06-18" });
    expect(init.result).toMatchObject({ protocolVersion: "2025-06-18", serverInfo: { name: "tafs" } });
    const list = await w.rpc("tools/list");
    expect(list.result?.tools?.map((t) => t.name)).toEqual(["tafs_state", "taf_run", "taf_task", "run_stop", "taf_pause", "taf_results", "version_try", "version_install"]);
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

  it("run_stop and taf_pause act through kiff/agents; an unknown taf is refused", async () => {
    const w = world();
    const run = await w.agents.api.startRun(T, RAE, "go");
    expect((await w.tool("run_stop", { taf: RAE, run: run.id })).json).toMatchObject({ status: "CANCELLED" });
    expect((await w.tool("taf_pause", { taf: RAE, action: "pause" })).json).toMatchObject({ schedule: "paused" });
    expect(await w.tool("taf_pause", { taf: "nobody", action: "pause" })).toMatchObject({ isError: true, text: expect.stringContaining("no taf") });
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
