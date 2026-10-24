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
async function stream(w: World, path: string, opts: { token?: string; body?: unknown } = {}) {
  const event: HTTPEvent = {
    rawPath: path,
    requestContext: { http: { method: "POST" } },
    headers: opts.token ? { Authorization: `Bearer ${opts.token}` } : {},
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
  };
  const r = await handleStream(event, {
    agents: w.agents.api, keys: memoryKeys(), accounts: memoryAccounts(), tafs: w.tafs,
    names: memoryNames(), slots: w.slots, claims: memoryClaims(), removals: memoryRemovals(), archived: w.archived,
    push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async () => "sent" as const },
    forward: async () => undefined,
    fetch: kiff({ ana: ME, viewer: VIEWER }),
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
    expect(r.events.map((e) => e.event)).toEqual(["session", "text", "text", "activity", "component", "end"]);
    expect(r.events[0]!.data).toMatchObject({ new_session: true });
    expect(r.events.filter((e) => e.event === "text").map((e) => e.data.text).join("")).toBe("I'll connect **Stripe**.");
    expect(r.events[3]!.data).toEqual({ tool: "planque", step: "Reading what your provider offers…" });
    expect(r.events.at(-1)!.data).toEqual({ status: "COMPLETED", stop: "answered" });
    // What tools were given and what they answered never reaches the phone.
    expect(r.raw).not.toContain("grt_secret");
    expect(r.raw).not.toContain("acct_123");
    expect(w.agents.calls).toEqual(["streamRun " + RAE + " new"]);
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
      { tool: "planque", step: "Preparing the connection to Stripe…" },
      { tool: "planque", step: "Checking your Stripe connection…" },
      { tool: "kiff_draft", step: "Drafting the Card…" },
      { tool: "show" },
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
    expect(r.events.map((e) => e.event)).toEqual(["session", "text", "activity", "held", "activity", "end"]);
    expect(r.events[3]!.data).toEqual({ exception: "exc_1", invocation: "tc-1" });
    expect(r.raw).not.toContain("ch_secret");
    expect(r.raw).not.toContain("op_1");
  });

  it("goes on in a live chat, or takes a new one when kiff/agents refuses it", async () => {
    const w = world();
    const first = await stream(w, PATH, { token: "ana", body: { text: "hi" } });
    const session = first.events[0]!.data.session;
    const again = await stream(w, PATH, { token: "ana", body: { text: "and then?" } });
    expect(again.events[0]!.data).toEqual({ session, new_session: false, run: expect.any(String) });
    w.agents.fail.set("streamRun", new AgentsError(409, "the chat is busy"));
    const busy = await stream(w, PATH, { token: "ana", body: { text: "one more" } });
    expect(busy.status).toBe(200);
    expect(busy.events[0]!.data).toMatchObject({ new_session: true });
  });

  it("checks what messages checks, before any run", async () => {
    const w = world();
    expect((await stream(w, PATH, { body: { text: "hi" } })).status).toBe(401);
    expect((await stream(w, PATH, { token: "viewer", body: { text: "hi" } })).status).toBe(403);
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
