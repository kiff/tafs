import { describe, expect, it } from "vitest";
import { AgentsError, readEvents } from "../src/cloud/agents.js";
import { handleStream, type HTTPEvent, type Taf } from "../src/cloud/handler.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

const ME = { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] };
const VIEWER = { ...ME, capabilities: ["read"] };
const NOW = new Date("2026-10-17T12:00:00Z");
const RAE = "taf-aaaa0000000000000000";
const MAX = "taf-bbbb0000000000000000";

function kiff(byToken: Record<string, object>) {
  return (async (_url: string, init?: RequestInit) => {
    const token = String((init?.headers as Record<string, string>).authorization).replace("Bearer ", "");
    const me = byToken[token];
    return me ? new Response(JSON.stringify(me), { status: 200 }) : new Response("{}", { status: 401 });
  }) as typeof fetch;
}

function world() {
  let clock = NOW;
  const agents = fakeAgents(() => clock);
  const tafs = memoryTafs();
  for (const [tenant, id, name] of [["t_acme", RAE, "Rae"], ["t_other", MAX, "Max"]] as const) {
    const taf: Taf = { id, name, job: "refunds", kiffAgent: name.toLowerCase(), nameClaim: "c", created: "2026-10-15T09:00:00Z" };
    tafs.held.set(`${tenant}#${id}`, taf);
    void agents.api.putAgent(tenant, id, {} as never);
  }
  agents.calls.length = 0;
  return { agents, tafs, slots: memorySlots(), archived: memoryArchived(), at: (d: Date) => { clock = d; } };
}
type World = ReturnType<typeof world>;

/** Streams a message; the status, and the events the phone gets, parsed. */
async function stream(w: World, path: string, opts: { token?: string; body?: unknown; method?: string } = {}) {
  const [rawPath, rawQueryString] = path.split("?");
  const event: HTTPEvent = {
    rawPath: rawPath!,
    rawQueryString,
    requestContext: { http: { method: opts.method ?? "POST" } },
    headers: opts.token ? { Authorization: `Bearer ${opts.token}` } : {},
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
  };
  const r = await handleStream(event, {
    agents: w.agents.api, keys: memoryKeys(), accounts: memoryAccounts(), tafs: w.tafs,
    names: memoryNames(), slots: w.slots, claims: memoryClaims(), removals: memoryRemovals(), archived: w.archived,
    push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async () => "sent" as const },
    forward: async () => undefined,
    fetch: kiff({ ana: ME, viewer: VIEWER, luis: { ...ME, subject: "u_luis" } }),
    now: () => NOW,
  });
  let raw = "";
  for await (const chunk of r.body) raw += chunk;
  if (r.statusCode !== 200) return { status: r.statusCode, events: [], error: JSON.parse(raw).error as string, raw };
  const events: Array<{ event: string; data: Record<string, unknown> }> = [];
  for await (const e of readEvents(new Response(raw).body!)) events.push(e as { event: string; data: Record<string, unknown> });
  return { status: r.statusCode, events, error: "", raw };
}

const PATH = `/v1/tafs/${RAE}/messages/stream`;

describe("a message streamed (kiff/tafs#60)", () => {
  it("relays the answer as it's written, the tools by name only, and where it stopped", async () => {
    const w = world();
    w.agents.streamed.push(
      { event: "delta", data: { kind: "text", text: "I'll connect " } },
      { event: "delta", data: { kind: "text", text: "**Stripe**." } },
      { event: "delta", data: { kind: "tool_call", call_id: "c1", tool: "planque", input: { action: "catalog", grant: "grt_secret" } } },
      { event: "delta", data: { kind: "tool_result", call_id: "c1", result: { text: "acct_123 tools…" } } },
      { event: "event", data: { kind: "tool_call", body: { input: "grt_secret" } } },
      { event: "delta", data: { kind: "component", component: { type: "approval", id: "connect", grant: "g_1" } } },
    );
    const r = await stream(w, PATH, { token: "ana", body: { text: "Make sure customers can get their refunds" } });
    expect(r.status).toBe(200);
    expect(r.events.map((e) => e.event)).toEqual(["session", "text", "text", "activity", "step", "component", "end"]);
    expect(r.events[0]!.data).toMatchObject({ new_session: true });
    expect(r.events.filter((e) => e.event === "text").map((e) => e.data.text).join("")).toBe("I'll connect **Stripe**.");
    expect(r.events[3]!.data).toEqual({ tool: "planque", step: "Reading what your provider offers…", call_id: "c1" });
    expect(r.events[4]!.data).toEqual({ call_id: "c1", status: "done" });
    expect(r.events.at(-1)!.data).toEqual({ status: "COMPLETED", stop: "answered" });
    // What tools were given and what they answered never reaches the phone.
    expect(r.raw).not.toContain("grt_secret");
    expect(r.raw).not.toContain("acct_123");
    expect(w.agents.calls).toEqual(["streamRun " + RAE + " live"]);
  });

  it("says each step in a few words, naming the vendor once its grant is known, never the arguments (kiff/tafs#214)", async () => {
    const w = world();
    w.agents.streamed.push(
      { event: "delta", data: { kind: "tool_call", call_id: "c1", tool: "planque", input: { action: "request", server: "https://mcp.stripe.com/v1", name: "refunds" } } },
      { event: "delta", data: { kind: "tool_result", call_id: "c1", result: { text: "grant grt_AbC123xyz pending; agent refunds-k3f9" } } },
      { event: "delta", data: { kind: "tool_call", call_id: "c2", tool: "planque", input: { action: "status", grant: "grt_AbC123xyz" } } },
      { event: "delta", data: { kind: "tool_call", call_id: "c3", tool: "kiff_draft", input: { action: "submit", agent_id: "refunds-k3f9", cards: [] } } },
      { event: "delta", data: { kind: "tool_call", call_id: "c4", tool: "show", input: { type: "approval" } } },
    );
    const r = await stream(w, PATH, { token: "ana", body: { text: "Set up refunds" } });
    expect(r.events.filter((e) => e.event === "activity").map((e) => e.data)).toEqual([
      { tool: "planque", step: "Preparing the connection to Stripe…", call_id: "c1" },
      { tool: "planque", step: "Checking your Stripe connection…", call_id: "c2" },
      { tool: "kiff_draft", step: "Drafting the Card…", call_id: "c3" },
      { tool: "show", call_id: "c4" },
    ]);
    expect(r.raw).not.toContain("grt_AbC123xyz");
    expect(r.raw).not.toContain("refunds-k3f9");
    expect(r.raw).not.toContain("mcp.stripe.com");
  });

  it("says where KIFF held a call, by KIFF's keys only (kiff/tafs#206)", async () => {
    const w = world();
    w.agents.streamed.push(
      { event: "delta", data: { kind: "text", text: "Refunding $20." } },
      { event: "delta", data: { kind: "tool_call", call_id: "c1", tool: "create_refund", input: { charge: "ch_secret" } } },
      { event: "delta", data: { kind: "tool_result", call_id: "c1", result: { text: "held: ch_secret over the day's total", state: "held", hold: { operation: "op_1", exception: "exc_1", expires: "2026-10-17T13:00:00Z" }, invocation: "tc-1" } } },
      { event: "delta", data: { kind: "tool_call", call_id: "c2", tool: "get_charge" } },
      { event: "delta", data: { kind: "tool_result", call_id: "c2", result: { text: "ch_secret paid" } } },
    );
    const r = await stream(w, PATH, { token: "ana", body: { text: "Refund $20 of T-3002" } });
    expect(r.events.map((e) => e.event)).toEqual(["session", "text", "activity", "held", "activity", "step", "end"]);
    expect(r.events[3]!.data).toEqual({ exception: "exc_1", invocation: "tc-1" });
    expect(r.raw).not.toContain("ch_secret");
    expect(r.raw).not.toContain("op_1");
  });

  it("goes on in the person's live chat, as kiff/agents picks it, or a new one they ask for", async () => {
    const w = world();
    const first = await stream(w, PATH, { token: "ana", body: { text: "hi" } });
    const session = first.events[0]!.data.session;
    expect(first.events[0]!.data).toMatchObject({ new_session: true });
    const again = await stream(w, PATH, { token: "ana", body: { text: "and then?" } });
    expect(again.events[0]!.data).toEqual({ session, new_session: false, run: expect.any(String) });
    const fresh = await stream(w, PATH, { token: "ana", body: { text: "another thing", new_chat: true } });
    expect(fresh.events[0]!.data).toMatchObject({ new_session: true });
    expect(fresh.events[0]!.data.session).not.toBe(session);
    expect(w.agents.runs.every((r) => r.requester?.kind === "member" && r.requester.id === "u_ana")).toBe(true);
  });

  it("streams what the taf is thinking, and how each step ended, never its result (kiff/tafs#285)", async () => {
    const w = world();
    w.agents.streamed.push(
      { event: "delta", data: { kind: "thinking", text: "The order is paid, " } },
      { event: "delta", data: { kind: "thinking", text: "so a refund fits." } },
      { event: "delta", data: { kind: "tool_call", call_id: "c1", tool: "get_order", input: { order: "ord_secret" } } },
      { event: "delta", data: { kind: "tool_result", call_id: "c1", result: { text: "ord_secret not found", is_error: true } } },
      { event: "delta", data: { kind: "tool_call", call_id: "c2", tool: "get_order", input: { order: "ord_secret" } } },
      { event: "delta", data: { kind: "tool_result", call_id: "c2", result: { text: "ord_secret paid" } } },
      { event: "delta", data: { kind: "text", text: "Refunded." } },
    );
    const r = await stream(w, PATH, { token: "ana", body: { text: "Refund the last order" } });
    expect(r.events.map((e) => e.event)).toEqual(["session", "thinking", "thinking", "activity", "step", "activity", "step", "text", "end"]);
    expect(r.events.filter((e) => e.event === "thinking").map((e) => e.data.text).join("")).toBe("The order is paid, so a refund fits.");
    expect(r.events.filter((e) => e.event === "step").map((e) => e.data)).toEqual([
      { call_id: "c1", status: "error" }, { call_id: "c2", status: "done" },
    ]);
    expect(r.raw).not.toContain("ord_secret");
  });

  it("picks the person's own run back up from its record, steps only (kiff/tafs#285)", async () => {
    const w = world();
    w.agents.streamed.push({ event: "delta", data: { kind: "text", text: "On it." } });
    const started = await stream(w, PATH, { token: "ana", body: { text: "Refund the last order" } });
    const run = String(started.events[0]!.data.run);
    w.agents.resumed.push(
      { event: "event", data: { kind: "owner_message", body: { text: "Refund the last order" } } },
      { event: "event", data: { kind: "tool_call", body: { call_id: "c1", tool: "create_refund", input: { charge: "ch_secret" } } } },
      { event: "event", data: { kind: "tool_result", body: { call_id: "c1", tool: "create_refund", text: "held ch_secret", state: "held", hold: { exception: "exc_1" }, invocation: "tc-1" } } },
      { event: "event", data: { kind: "agent_message", body: { text: "The refund of ch_secret waits for you." } } },
      { event: "delta", data: { kind: "text", text: "never relayed on a resume" } },
    );
    const resume = (path: string, token = "ana") => stream(w, path, { token, method: "GET" });
    const r = await resume(`/v1/tafs/${RAE}/runs/${run}/stream?after=0`);
    expect(r.status).toBe(200);
    expect(r.events.map((e) => e.event)).toEqual(["activity", "held", "said", "end"]);
    expect(r.events[0]!.data).toEqual({ tool: "create_refund", call_id: "c1" });
    expect(r.events[1]!.data).toEqual({ exception: "exc_1", invocation: "tc-1" });
    expect(r.raw).not.toContain("ch_secret");
    expect(r.raw).not.toContain("never relayed");
    expect(w.agents.calls).toContain(`resumeRun ${RAE} ${run} 0`);
    // Another person's run, another account's taf, a bad cursor: never.
    expect((await resume(`/v1/tafs/${RAE}/runs/${run}/stream`, "luis")).status).toBe(404);
    expect((await resume(`/v1/tafs/${MAX}/runs/${run}/stream`)).status).toBe(404);
    expect((await resume(`/v1/tafs/${RAE}/runs/${run}/stream?after=-1`)).status).toBe(400);
    expect((await resume(`/v1/tafs/${RAE}/runs/${run}/stream`, "")).status).toBe(401);
  });

  it("says what a failed run did before it stopped, by tool, what it acted on and KIFF's key, never the input (kiff/agents#249)", async () => {
    const w = world();
    const started = await stream(w, PATH, { token: "ana", body: { text: "Refund T-3001" } });
    expect(started.events.at(-1)!.data).toEqual({ status: "COMPLETED", stop: "answered" }); // a run that answered says nothing of it
    const run = String(started.events[0]!.data.run);
    const stored = w.agents.runs.find((x) => x.id === run)!;
    Object.assign(stored, {
      status: "ERROR",
      done: [
        { call_id: "c1", tool: "create_refund", input: { charge: "ch_secret", amount: 500 }, resource: "ch_3001", invocation: "tc-1" },
        { call_id: "c2", tool: "send_email", input: { body: "secret words" } },
      ],
    });
    const r = await stream(w, `/v1/tafs/${RAE}/runs/${run}/stream?after=0`, { token: "ana", method: "GET" });
    expect(r.events.at(-1)).toEqual({ event: "end", data: { status: "ERROR", stop: "", done: [{ tool: "create_refund", resource: "ch_3001", invocation: "tc-1" }, { tool: "send_email" }] } });
    expect(r.raw).not.toContain("ch_secret");
    expect(r.raw).not.toContain("secret words");
  });

  it("marks where a resumed run went on after a held call the owner answered (the 859 test)", async () => {
    const w = world();
    w.agents.streamed.push({ event: "delta", data: { kind: "text", text: "On it." } });
    const started = await stream(w, PATH, { token: "ana", body: { text: "Refund T-5005" } });
    const run = String(started.events[0]!.data.run);
    const usage = (time: string, ms: number, waited: object[]) =>
      ({ event: "event", data: { kind: "usage", time, body: { cost_micro_usd: 1, timing: { ms, waited } } } });
    w.agents.resumed.push(
      usage("2026-10-27T17:00:02Z", 2000, []),
      { event: "event", data: { kind: "tool_call", body: { call_id: "c1", tool: "list_charges", input: {} } } },
      { event: "event", data: { kind: "tool_result", body: { call_id: "c1", tool: "list_charges", text: "[]" } } },
      usage("2026-10-27T17:00:09Z", 3000, [{ tool: "list_charges", ms: 400 }]),
      { event: "event", data: { kind: "agent_message", body: { text: "The refund waits for you." } } },
      { event: "event", data: { kind: "tool_call", body: { call_id: "c2", tool: "create_refund", input: {} } } },
      { event: "event", data: { kind: "tool_result", body: { call_id: "c2", tool: "create_refund", text: "", state: "held", hold: { exception: "exc_1" }, invocation: "tc-1" } } },
      // The owner approves; the run goes on: the held call's result, then a turn that waited on nothing.
      { event: "event", data: { kind: "tool_result", body: { call_id: "c2", tool: "create_refund", text: "done" } } },
      usage("2026-10-27T17:05:04Z", 1500, []),
      { event: "event", data: { kind: "tool_call", body: { call_id: "c3", tool: "get_charge", input: {} } } },
      // A summary's usage has no timing: not a turn.
      { event: "event", data: { kind: "usage", time: "2026-10-27T17:05:05Z", body: { cost_micro_usd: 1 } } },
    );
    const r = await stream(w, `/v1/tafs/${RAE}/runs/${run}/stream?after=0`, { token: "ana", method: "GET" });
    expect(r.events.map((e) => e.event)).toEqual(["activity", "step", "said", "activity", "held", "step", "part", "activity", "end"]);
    expect(r.events.find((e) => e.event === "part")!.data).toEqual({ at: "2026-10-27T17:05:02.500Z" });
  });

  it("marks a part where kiff/agents says the turn resumed, and a call refused back unmade as not made", async () => {
    const w = world();
    w.agents.streamed.push(
      { event: "delta", data: { kind: "tool_call", call_id: "c1", tool: "create_refund", input: {} } },
      { event: "delta", data: { kind: "tool_result", call_id: "c1", not_made: true, result: { text: "Read first", is_error: true } } },
    );
    const live = await stream(w, PATH, { token: "ana", body: { text: "Refund T-5005" } });
    expect(live.events.filter((e) => e.event === "step").map((e) => e.data)).toEqual([{ call_id: "c1", status: "not_made" }]);
    const run = String(live.events[0]!.data.run);
    const usage = (time: string, ms: number, timing: object) =>
      ({ event: "event", data: { kind: "usage", time, body: { cost_micro_usd: 1, timing: { ms, ...timing } } } });
    w.agents.resumed.push(
      usage("2026-10-27T21:00:02Z", 2000, { waited: [] }),
      { event: "event", data: { kind: "tool_call", body: { call_id: "c1", tool: "create_refund", input: {} } } },
      { event: "event", data: { kind: "tool_result", body: { call_id: "c1", tool: "create_refund", text: "Read first", is_error: true, not_made: true } } },
      usage("2026-10-27T21:00:05Z", 1000, { waited: [] }), // waited on nothing, but not resumed
      { event: "event", data: { kind: "tool_call", body: { call_id: "c2", tool: "create_refund", input: {} } } },
      { event: "event", data: { kind: "tool_result", body: { call_id: "c2", tool: "create_refund", text: "", state: "held", hold: { exception: "exc_1" }, invocation: "tc-1" } } },
      { event: "event", data: { kind: "tool_result", body: { call_id: "c2", tool: "create_refund", text: "done" } } },
      usage("2026-10-27T21:05:04Z", 1500, { resumed: true, waited: [{ tool: "create_refund", ms: 900 }] }),
    );
    const r = await stream(w, `/v1/tafs/${RAE}/runs/${run}/stream?after=0`, { token: "ana", method: "GET" });
    expect(r.events.map((e) => e.event)).toEqual(["activity", "step", "activity", "held", "step", "part", "end"]);
    expect(r.events[1]!.data).toEqual({ call_id: "c1", status: "not_made" });
    expect(r.events.find((e) => e.event === "part")!.data).toEqual({ at: "2026-10-27T21:05:02.500Z" });
  });

  it("carries the files the person attached, with words or alone (kiff/tafs#209)", async () => {
    const w = world();
    const { attachment } = await w.agents.api.createAttachment("t_acme", RAE, "member:u_ana", { media_type: "application/pdf", name: "policy.pdf", size: 900, sha256: "b".repeat(64) });
    const r = await stream(w, PATH, { token: "ana", body: { text: "", attachments: [attachment.id] } });
    expect(r.status).toBe(200);
    const session = String(r.events[0]!.data.session);
    const read = await w.agents.api.session("t_acme", session, "member:u_ana");
    expect(read.events[0]!.body).toEqual({ text: "", attachments: [{ id: attachment.id, kind: "pdf", media_type: "application/pdf", name: "policy.pdf", size: 900 }] });
    // Luis can't send Ana's file: kiff/agents takes only the requester's own.
    const theirs = await w.agents.api.createAttachment("t_acme", RAE, "member:u_ana", { media_type: "text/plain", name: "a.txt", size: 5, sha256: "c".repeat(64) });
    expect((await stream(w, PATH, { token: "luis", body: { text: "hi", attachments: [theirs.attachment.id] } })).status).toBe(404);
  });

  it("checks what messages checks, before any run", async () => {
    const w = world();
    expect((await stream(w, PATH, { body: { text: "hi" } })).status).toBe(401);
    expect((await stream(w, `/v1/tafs/${MAX}/messages/stream`, { token: "ana", body: { text: "hi" } })).status).toBe(404);
    expect((await stream(w, PATH, { token: "ana", body: { text: " " } })).status).toBe(400);
    expect((await stream(w, `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "hi" } })).status).toBe(404);
    expect(w.agents.calls).toEqual([]);
  });

  it("counts toward the same flood guard as messages", async () => {
    const w = world();
    for (let i = 0; i < 10; i++) expect((await stream(w, PATH, { token: "ana", body: { text: `m${i}` } })).status).toBe(200);
    const r = await stream(w, PATH, { token: "ana", body: { text: "one too many" } });
    expect(r.status).toBe(429);
  });

  it("says when the stream broke, so the phone reads the conversation", async () => {
    const w = world();
    w.agents.streamed.push({ event: "delta", data: { kind: "text", text: "Half" } }, new Error("socket closed"));
    const r = await stream(w, PATH, { token: "ana", body: { text: "hi" } });
    expect(r.events.map((e) => e.event)).toEqual(["session", "text", "error"]);
  });

  it("answers kiff/agents being down as tafs cloud's 502", async () => {
    const w = world();
    w.agents.fail.set("streamRun", new AgentsError(503, "down"));
    expect((await stream(w, PATH, { token: "ana", body: { text: "hi" } })).status).toBe(502);
  });
});

describe("reading kiff/agents' run stream", () => {
  it("reads each event whole, however the bytes arrive", async () => {
    const text = 'event: run\ndata: {"id":"r1"}\n\nevent: delta\ndata: {"kind":"text","text":"a\\nb"}\n\n: ping\n\nevent: end\ndata: {"status":"COMPLETED"}\n\n';
    const bytes = new TextEncoder().encode(text);
    const body = new ReadableStream<Uint8Array>({
      start(c) { for (let i = 0; i < bytes.length; i += 7) c.enqueue(bytes.slice(i, i + 7)); c.close(); },
    });
    const got = [];
    for await (const e of readEvents(body)) got.push(e);
    expect(got).toEqual([
      { event: "run", data: { id: "r1" } },
      { event: "delta", data: { kind: "text", text: "a\nb" } },
      { event: "end", data: { status: "COMPLETED" } },
    ]);
  });
});

describe("steering in the stream", () => {
  it("names the run first, and says when the run read a message the owner wrote while it went", async () => {
    const w = world();
    w.agents.streamed.push({ event: "delta", data: { kind: "text", text: "Refunding." } }, { event: "delta", data: { kind: "owner_message", text: "only the €20 one" } });
    const r = await stream(w, PATH, { token: "ana", body: { text: "refund 4502" } });
    expect(r.status).toBe(200);
    expect(r.events.map((e) => e.event)).toEqual(["session", "text", "taken", "end"]);
    expect(r.events[0]!.data.run).toBe(w.agents.runs.at(-1)!.id);
    expect(r.events[2]!.data).toEqual({});
    expect(r.raw).not.toContain("only the €20 one");
  });
});

describe("a new chat, streamed (kiff/tafs#73)", () => {
  it("starts one when asked, though the latest chat is live", async () => {
    const w = world();
    const first = await stream(w, PATH, { token: "ana", body: { text: "First topic" } });
    const session = first.events[0]!.data.session;
    const again = await stream(w, PATH, { token: "ana", body: { text: "A new topic", new_chat: true } });
    expect(again.events[0]!.data).toMatchObject({ new_session: true });
    expect(again.events[0]!.data.session).not.toBe(session);
    const back = await stream(w, PATH, { token: "ana", body: { text: "Back", session } });
    expect(back.events[0]!.data).toMatchObject({ session, new_session: false });
  });
});

describe("a card answered from the old form (kiff/agents' cards wait for their answer)", () => {
  it("answers the waiting card, then streams its run going on, never starting another", async () => {
    const w = world();
    const run = await w.agents.api.startRun("t_acme", RAE, "Connect my Stripe", { requester: { kind: "member", id: "u_ana" } });
    w.agents.ask(run, { type: "confirm", id: "which", options: [{ value: "test", label: "Test mode" }] });
    w.agents.resumed.push({ event: "event", data: { kind: "agent_message", body: { text: "Test mode it is." } } });
    w.agents.calls.length = 0;
    const r = await stream(w, PATH, { token: "ana", body: { text: "[confirm which] test" } });
    expect(r.status).toBe(200);
    expect(w.agents.calls).toEqual([`answerCard ${RAE} ${run.id} which "test"`, `resumeRun ${RAE} ${run.id} 0`]);
    expect(r.events.map((e) => e.event)).toEqual(["session", "taken", "said", "end"]);
    expect(r.events[0]!.data).toEqual({ session: run.session, new_session: false, run: run.id });
  });

  it("streams it as a message when no card by that id waits", async () => {
    const w = world();
    await w.agents.api.startRun("t_acme", RAE, "Connect my Stripe", { requester: { kind: "member", id: "u_ana" } });
    w.agents.calls.length = 0;
    const r = await stream(w, PATH, { token: "ana", body: { text: "[confirm which] test" } });
    expect(r.status).toBe(200);
    expect(w.agents.calls.some((c) => c.startsWith("answerCard"))).toBe(false);
    expect(r.events[0]!.event).toBe("session");
  });
});
