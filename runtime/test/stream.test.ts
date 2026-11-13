import { wordsIn } from "../src/cloud/words/index.js";
import { describe, expect, it, vi } from "vitest";
import { AgentsError, readEvents } from "../src/cloud/agents.js";
import { handleStream, heldLine, type HTTPEvent, MAX_NOTE, noteWords, preToolLines, SETUP_JOB, type Taf, waitsOf, withLine } from "../src/cloud/handler.js";
import { stepWhyKey } from "../src/cloud/steps.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryCoach, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

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
  return { agents, tafs, slots: memorySlots(), archived: memoryArchived(), coach: memoryCoach(() => clock), at: (d: Date) => { clock = d; } };
}
type World = ReturnType<typeof world>;

/** Streams a message; the status, and the events the phone gets, parsed. */
async function stream(w: World, path: string, opts: { token?: string; body?: unknown; method?: string; publicURL?: string; fetch?: typeof fetch; language?: string } = {}) {
  const [rawPath, rawQueryString] = path.split("?");
  const event: HTTPEvent = {
    rawPath: rawPath!,
    rawQueryString,
    requestContext: { http: { method: opts.method ?? "POST" } },
    headers: { ...(opts.token ? { Authorization: `Bearer ${opts.token}` } : {}), ...(opts.language ? { "Accept-Language": opts.language } : {}) },
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
  };
  const r = await handleStream(event, {
    agents: w.agents.api, keys: memoryKeys(), accounts: memoryAccounts(), tafs: w.tafs,
    names: memoryNames(), slots: w.slots, claims: memoryClaims(), removals: memoryRemovals(), archived: w.archived, coach: w.coach,
    push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async () => "sent" as const },
    forward: async () => undefined,
    fetch: opts.fetch ?? kiff({ ana: ME, viewer: VIEWER, luis: { ...ME, subject: "u_luis" } }),
    now: () => NOW,
    ...(opts.publicURL ? { publicURL: opts.publicURL } : {}),
  });
  let raw = "";
  for await (const chunk of r.body) raw += chunk;
  if (r.statusCode !== 200) return { status: r.statusCode, events: [], states: [], error: JSON.parse(raw).error as string, raw };
  const events: Array<{ event: string; data: Record<string, unknown> }> = [];
  // The paced `state` line is read apart (its own tests): the rest is the stream as it was.
  const states: Array<Record<string, unknown>> = [];
  for await (const e of readEvents(new Response(raw).body!)) {
    const ev = e as { event: string; data: Record<string, unknown> };
    if (ev.event === "state") states.push(ev.data);
    else events.push(ev);
  }
  return { status: r.statusCode, events, states, error: "", raw };
}

const PATH = `/v1/tafs/${RAE}/messages/stream`;

describe("a message streamed (kiff/tafs#60)", () => {
  it("sends one calm line: thinking, the step in its words, waiting for you, done; never the thinking text", async () => {
    const w = world();
    w.agents.streamed.push(
      { event: "delta", data: { kind: "thinking", text: "Let me look at the charge first" } },
      { event: "delta", data: { kind: "text", text: "Checking." } },
      { event: "delta", data: { kind: "tool_call", call_id: "c1", tool: "show", input: { type: "approval" } } },
      { event: "delta", data: { kind: "component", component: { type: "approval", id: "a1", grant: "g_1", waits: "owner" } } },
    );
    const r = await stream(w, PATH, { token: "ana", body: { text: "Refund order 42" } });
    const lines = r.states.map((s) => s.state);
    expect(lines[0]).toBe("thinking");
    // Waiting for the person and the end go at once, whatever the hold.
    expect(lines).toContain("waiting_for_you");
    expect(lines.at(-1)).toBe("done");
    // Sent only on a change, and the model's thinking text is never in the line.
    expect(lines.every((l, i) => i === 0 || l !== lines[i - 1])).toBe(true);
    expect(JSON.stringify(r.states)).not.toContain("Let me look");
  });

  // The latency work: where a message's wait goes, one log line, times only.
  it("logs where the message's wait went, step by step, with its first words and end, and nothing of the chat", async () => {
    const logged: string[] = [];
    const spy = vi.spyOn(console, "log").mockImplementation((line: unknown) => { logged.push(String(line)); });
    try {
      const w = world();
      w.agents.streamed.push(
        { event: "delta", data: { kind: "update", text: "Checking the charge…" } },
        { event: "delta", data: { kind: "text", text: "Refunded order 42." } },
      );
      await stream(w, PATH, { token: "ana", body: { text: "Refund order 42" } });
    } finally {
      spy.mockRestore();
    }
    const lines = logged.filter((l) => l.includes('"event":"stream"'));
    expect(lines).toHaveLength(1);
    const line = JSON.parse(lines[0]!) as Record<string, unknown>;
    expect(Object.keys(line)).toEqual(["event", "cold", "person_ms", "taf_ms", "answer_ms", "lines_ms", "chat_ms", "skills_ms", "first_event_ms", "first_words_ms", "end_ms", "whole"]);
    expect(typeof line.first_words_ms).toBe("number");
    expect(line.whole).toBe(true);
    for (const word of ["Refund", "order", "42", "ana", RAE]) expect(lines[0]).not.toContain(word);
  });

  // kiff/tafs#568: which card waits for the person is kiff/agents' to say, on the card (`waits`), once for
  // tafs cloud and both apps. A drive card waits for them; a result, which tafs cloud answers, and a plan don't.
  it("waits for the person only on a card kiff/agents marks as theirs", async () => {
    const waiting = async (component: Record<string, unknown>) => {
      const w = world();
      w.agents.streamed.push(
        { event: "delta", data: { kind: "text", text: "One moment." } },
        { event: "delta", data: { kind: "component", component } },
      );
      const r = await stream(w, PATH, { token: "ana", body: { text: "Show me in the app" } });
      return r.states.some((s) => s.state === "waiting_for_you");
    };
    expect(await waiting({ type: "drive", id: "d1", text: "Open Refunds", waits: "owner" })).toBe(true);
    expect(await waiting({ type: "result", id: "r1", waits: "service" })).toBe(false);
    expect(await waiting({ type: "steps", id: "plan", steps: [{ label: "Connect", status: "active" }] })).toBe(false);
    // A card with no mark at all is from before kiff/agents marked them (kiff/agents#351): read by its type, as its
    // lists were then, so the line still waits on an approval while that change isn't deployed (qa-kiff on #569).
    expect(await waiting({ type: "approval", id: "a1", grant: "g_1" })).toBe(true);
    expect(await waiting({ type: "notice", id: "n1", text: "Connected" })).toBe(false);
  });

  it("gives every card its mark, the ones from before kiff/agents marked them too", () => {
    expect(withLine({ type: "approval", id: "a1" })).toMatchObject({ waits: "owner" });
    expect(withLine({ type: "drive", id: "d1" })).toMatchObject({ waits: "owner" });
    expect(withLine({ type: "result", id: "r1" })).toMatchObject({ waits: "service" });
    expect(withLine({ type: "notice", id: "n1" })).not.toHaveProperty("waits");
    // kiff/agents' own mark is kept as it says.
    expect(withLine({ type: "approval", id: "a1", waits: "service" })).toMatchObject({ waits: "service" });
    expect(waitsOf({ type: "steps", steps: [] })).toBe("");
  });

  // On Claude Opus 5.5 Coach's line before a tool call comes back as a progress note, not as text, and
  // Android draws no thinking: first words were a median 12.5 s with the note at a few (the Eval, 5 Nov).
  it("puts the model's progress note on the line at once, in place of thinking, as words with no closing mark", async () => {
    const w = world();
    w.agents.streamed.push(
      { event: "delta", data: { kind: "update", text: "Miro las últimas ejecuciones de Reembolsos…" } },
      { event: "delta", data: { kind: "tool_call", call_id: "c1", tool: "show", input: { type: "approval" } } },
      { event: "delta", data: { kind: "component", component: { type: "approval", id: "a1", grant: "g_1" } } },
    );
    const r = await stream(w, PATH, { token: "ana", body: { text: "¿Cómo van los reembolsos?" } });
    // The note replaces "thinking" whatever the hold; the rest of the line goes as before.
    expect(r.states.slice(0, 2)).toEqual([{ state: "thinking" }, { state: "working", words: "Miro las últimas ejecuciones de Reembolsos" }]);
    expect(r.states.at(-1)).toEqual({ state: "done" });
    // The note is the line's only: never an event of its own, never the answer's text.
    expect(r.events.map((e) => e.event)).toEqual(["session", "activity", "component", "end"]);
  });

  // On Opus 5.5 and Sonnet 5.5 Coach's line before its tools comes as a note or as text, run to run: the owner
  // sees one shape either way.
  it("puts a turn's one-line text before its tool call on the live line, never as the answer", async () => {
    const w = world();
    w.agents.streamed.push(
      { event: "delta", data: { kind: "text", text: "Miro las últimas " } },
      { event: "delta", data: { kind: "text", text: "ejecuciones de Reembolsos…" } },
      { event: "delta", data: { kind: "tool_call", call_id: "c1", tool: "get_charge" } },
      { event: "delta", data: { kind: "tool_result", call_id: "c1", result: { text: "ok" } } },
      { event: "delta", data: { kind: "text", text: "Todo en orden." } },
    );
    const r = await stream(w, PATH, { token: "ana", body: { text: "¿Cómo va?" } });
    // The line shows as it's written, growing at once, whatever the hold.
    expect(r.states.slice(1, 3)).toEqual([{ state: "working", words: "Miro las últimas" }, { state: "working", words: "Miro las últimas ejecuciones de Reembolsos" }]);
    // The answer after the tools is held as one line too, then sent at the end, the line going to thinking first.
    expect(r.events.filter((e) => e.event === "text").map((e) => e.data.text)).toEqual(["Todo en orden."]);
    expect(r.raw).not.toContain("Miro las últimas ejecuciones de Reembolsos…");
  });

  it("sends held words as the answer with the line at thinking first, at once, so they never show twice", async () => {
    const w = world();
    w.agents.streamed.push(
      { event: "delta", data: { kind: "text", text: "Reembolsos está lista." } },
      { event: "delta", data: { kind: "component", component: { type: "approval", id: "a1", grant: "g_1" } } },
    );
    const r = await stream(w, PATH, { token: "ana", body: { text: "¿Lista?" } });
    // The live line had the words; just before they go as text, it says thinking, with nothing between.
    const at = r.raw.indexOf("event: text\n");
    expect(r.raw.slice(0, at)).toMatch(/event: state\ndata: \{"state":"thinking"\}\n\n$/);
    expect(r.states).toContainEqual({ state: "working", words: "Reembolsos está lista" });
  });

  it("streams an answer past its first line word by word, holding only that line", async () => {
    const w = world();
    w.agents.streamed.push(
      { event: "delta", data: { kind: "text", text: "Hecho:" } },
      { event: "delta", data: { kind: "text", text: "\n- 3 reembolsos" } },
      { event: "delta", data: { kind: "text", text: "\n- 0 fallos" } },
    );
    const r = await stream(w, PATH, { token: "ana", body: { text: "¿Qué hiciste?" } });
    expect(r.events.filter((e) => e.event === "text").map((e) => e.data.text)).toEqual(["Hecho:\n- 3 reembolsos", "\n- 0 fallos"]);
  });

  it("knows a turn's line: one short line, which a long or broken text isn't", () => {
    expect(heldLine("Miro las últimas ejecuciones de Reembolsos…\n\n")).toBe(true);
    expect(heldLine("Hecho:\n- 3 reembolsos")).toBe(false);
    expect(heldLine("a".repeat(MAX_NOTE + 1))).toBe(false);
  });

  it("leaves a run's notes before its tool calls out of the thread, however long, when anything follows its calls", () => {
    const ev = (run: string, seq: number, kind: string, text?: string) => ({ run, seq, kind, ...(text === undefined ? {} : { body: { text } }) });
    const got = preToolLines([
      ev("r1", 1, "owner_message", "¿Cómo va?"),
      ev("r1", 2, "agent_message", "Miro las ejecuciones…"), ev("r1", 3, "usage"), ev("r1", 4, "tool_call"),
      ev("r1", 5, "agent_message", "Primero:\nreviso los pagos."), ev("r1", 6, "tool_call"),
      ev("r2", 7, "agent_message", "Otro run."), ev("r1", 8, "agent_message", "Todo en orden."), ev("r2", 9, "tool_call"),
      // A card shown with no call between: the text was the run's words before it, not a note.
      ev("r3", 10, "agent_message", "Aquí tienes la tarjeta."), ev("r3", 11, "component"), ev("r3", 12, "tool_call"),
    ]);
    // r2's text: its call came, and the run wrote nothing after it, so it was the run's last words.
    expect([...got].sort()).toEqual(["r1.2", "r1.5"]);
  });

  // Coach's self-talk reached the person before an approval card ("Wait — the owner wrote in English…", "…so I'm
  // asking again with each one marked read only"): a note before a card is a note, as the stream showed it.
  it("leaves a note out before a card, a pause, or the owner's answer: only the run's final words reach the chat", () => {
    const ev = (seq: number, kind: string, body?: unknown) => ({ run: "r1", seq, kind, ...(body === undefined ? {} : { body }) });
    const say = (seq: number, text: string) => ev(seq, "agent_message", { text });
    // The run ends with nothing after its call: its last note is its message.
    expect([...preToolLines([say(1, "Falta que lo apruebes para seguir."), ev(2, "tool_call")])]).toEqual([]);
    expect([...preToolLines([say(1, "Wait — the owner wrote in English, so I'm asking again."), ev(2, "tool_call"), ev(3, "component")])]).toEqual(["r1.1"]);
    expect([...preToolLines([say(1, "Te preparo el plan…"), ev(2, "tool_call"), ev(3, "component"), say(4, "Listo.")])]).toEqual(["r1.1"]);
    const resumed = ev(3, "usage", { timing: { ms: 900, resumed: true } });
    expect([...preToolLines([say(1, "Falta que lo apruebes para seguir."), ev(2, "tool_call"), resumed, say(4, "Conectado.")])]).toEqual(["r1.1"]);
    expect([...preToolLines([say(1, "Miro la cuenta…"), ev(2, "tool_call"), ev(3, "usage", { timing: { ms: 900 } }), say(4, "Conectado.")])]).toEqual(["r1.1"]);
    const answered = ev(4, "answered", { id: "conectar", type: "approval" });
    expect([...preToolLines([say(1, "Falta que apruebes la conexión."), ev(2, "tool_call"), ev(3, "tool_result"), answered, say(6, "Falta que elijas el modo.")])]).toEqual(["r1.1"]);
    // Two turns' notes and then the end: only the last is the run's message.
    expect([...preToolLines([say(1, "Primero miro."), ev(2, "tool_call"), say(3, "…so I'm asking again."), ev(4, "tool_call")])]).toEqual(["r1.1"]);
  });

  it("sends a one-line text its call follows as the answer when the run ends with nothing after it", async () => {
    const w = world();
    w.agents.streamed.push(
      { event: "delta", data: { kind: "text", text: "Falta que lo apruebes para seguir." } },
      { event: "delta", data: { kind: "tool_call", call_id: "c1", tool: "planque", input: { action: "request" } } },
      { event: "delta", data: { kind: "tool_result", call_id: "c1", result: { text: "approval asked" } } },
    );
    const r = await stream(w, PATH, { token: "ana", body: { text: "Conéctalo" } });
    expect(r.events.filter((e) => e.event === "text").map((e) => e.data.text)).toEqual(["Falta que lo apruebes para seguir."]);
    // The live line had it while the call ran; it goes to thinking, at once, just before the words.
    expect(r.states).toContainEqual({ state: "working", words: "Falta que lo apruebes para seguir" });
    const at = r.raw.indexOf("event: text\n");
    expect(r.raw.slice(0, at)).toMatch(/event: state\ndata: \{"state":"thinking"\}\n\n$/);
  });

  it("never sends a text its call follows before the card the run then shows: the card speaks for itself", async () => {
    const w = world();
    w.agents.streamed.push(
      { event: "delta", data: { kind: "text", text: "Te preparo el plan de Reembolsos." } },
      { event: "delta", data: { kind: "tool_call", call_id: "c1", tool: "show", input: { type: "steps" } } },
      { event: "delta", data: { kind: "component", component: { type: "steps", id: "s1" } } },
    );
    const r = await stream(w, PATH, { token: "ana", body: { text: "Hazlo" } });
    // The note stayed on the live line; the card goes alone, as the stored thread has it.
    expect(r.events.map((e) => e.event)).toEqual(["session", "activity", "component", "end"]);
  });

  it("shows a note as one short line: its closing mark gone, cut at a word, and nothing for an empty one", () => {
    expect(noteWords("Checking your Stripe account first.")).toBe("Checking your Stripe account first");
    expect(noteWords("  Reviso los pagos...  ")).toBe("Reviso los pagos");
    expect(noteWords("Listo!?")).toBe("Listo");
    expect(noteWords("Two\nlines,\u0007 one line…")).toBe("Two lines, one line");
    expect(noteWords(" … ")).toBeUndefined();
    const long = noteWords("Reading ".repeat(30))!;
    expect([...long].length).toBeLessThanOrEqual(MAX_NOTE);
    expect(long.endsWith("Reading")).toBe(true);
  });

  it("relays the answer as it's written, the tools by name only, and where it stopped", async () => {
    const w = world();
    w.agents.streamed.push(
      { event: "delta", data: { kind: "text", text: "I'll connect " } },
      { event: "delta", data: { kind: "text", text: "**Stripe**.\nYou approve it on your phone." } },
      { event: "delta", data: { kind: "tool_call", call_id: "c1", tool: "planque", input: { action: "catalog", grant: "grt_secret" } } },
      { event: "delta", data: { kind: "tool_result", call_id: "c1", result: { text: "acct_123 tools…" } } },
      { event: "event", data: { kind: "tool_call", body: { input: "grt_secret" } } },
      { event: "delta", data: { kind: "component", component: { type: "approval", id: "connect", grant: "g_1" } } },
    );
    const r = await stream(w, PATH, { token: "ana", body: { text: "Make sure customers can get their refunds" } });
    expect(r.status).toBe(200);
    // Held while it was one short line, then sent as written once it was more: the answer, not a line.
    expect(r.events.map((e) => e.event)).toEqual(["session", "text", "activity", "step", "component", "end"]);
    expect(r.events[0]!.data).toMatchObject({ new_session: true });
    expect(r.events.filter((e) => e.event === "text").map((e) => e.data.text).join("")).toBe("I'll connect **Stripe**.\nYou approve it on your phone.");
    expect(r.events[2]!.data).toEqual({ tool: "planque", step: "Reading what your provider offers…", call_id: "c1" });
    expect(r.events[3]!.data).toEqual({ call_id: "c1", status: "done" });
    expect(r.events.at(-1)!.data).toEqual({ status: "COMPLETED", stop: "answered" });
    // What tools were given and what they answered never reaches the phone.
    expect(r.raw).not.toContain("grt_secret");
    expect(r.raw).not.toContain("acct_123");
    expect(w.agents.calls).toEqual(["streamRun " + RAE + " live"]);
  });

  it("holds a failed step while Coach may retry it: a retry that goes through is never shown failed; one given up on is said at the end", async () => {
    const w = world();
    w.agents.streamed.push(
      { event: "delta", data: { kind: "tool_call", call_id: "c1", tool: "kiff_draft", input: { action: "submit" } } },
      { event: "delta", data: { kind: "tool_result", call_id: "c1", result: { text: "KIFF refused: the draft names a tool twice.", is_error: true } } },
      { event: "delta", data: { kind: "tool_call", call_id: "c2", tool: "kiff_draft", input: { action: "submit" } } },
      { event: "delta", data: { kind: "tool_result", call_id: "c2", result: { text: "draft drf_1 submitted" } } },
      { event: "delta", data: { kind: "tool_call", call_id: "c3", tool: "planque", input: { action: "status", grant: "grt_1" } } },
      { event: "delta", data: { kind: "tool_result", call_id: "c3", result: { text: "The connection service is down.", is_error: true } } },
    );
    const r = await stream(w, PATH, { token: "ana", body: { text: "Set up refunds" } });
    const steps = r.events.filter((e) => e.event === "step").map((e) => e.data);
    // The retried draft never reads as failed; the status read Coach gave up on is said before the end, in
    // tafs cloud's words: a connection call's refusal is about Coach's request, never shown as the server said it.
    expect(steps).toEqual([{ call_id: "c2", status: "done" }, { call_id: "c3", status: "error", failed: true, why: "Coach couldn't connect the app yet" }]);
    expect(r.events.map((e) => e.event).slice(-2)).toEqual(["step", "end"]);
    expect(r.raw).not.toContain("names a tool twice");
    expect(r.raw).not.toContain("The connection service is down");
  });

  it("never shows a connection request Coach corrected as failed, nor the connection server's refusal (owner's phone, 12 Nov)", async () => {
    const w = world();
    const refused = { text: "planque answered 400: the body must be a JSON object with the documented fields", is_error: true };
    const api = { url: "https://thirdparty.qonto.com/openapi.json", base: "https://thirdparty.qonto.com/v2" };
    w.agents.streamed.push(
      { event: "delta", data: { kind: "tool_call", call_id: "c1", tool: "planque", input: { action: "request", api, name: "overdue", extra: 1 } } },
      { event: "delta", data: { kind: "tool_result", call_id: "c1", result: refused } },
      { event: "delta", data: { kind: "tool_call", call_id: "c2", tool: "planque", input: { action: "request", api, name: "overdue", other: 2 } } },
      { event: "delta", data: { kind: "tool_result", call_id: "c2", result: refused } },
      { event: "delta", data: { kind: "tool_call", call_id: "c3", tool: "planque", input: { action: "request", api, name: "overdue" } } },
      { event: "delta", data: { kind: "tool_result", call_id: "c3", result: { text: "grant grt_Qonto12345 pending; agent overdue-k3f9" } } },
    );
    const r = await stream(w, PATH, { token: "ana", body: { text: "Every Monday, tell me overdue invoices" } });
    // Coach's own request, corrected twice: one step, done; neither refusal is shown, nor its words.
    expect(r.events.filter((e) => e.event === "step").map((e) => e.data)).toEqual([{ call_id: "c3", status: "done" }]);
    expect(r.raw).not.toContain("documented fields");
    // A request it gave up on says so in tafs cloud's words, naming the app by its host when that's all it has.
    const w2 = world();
    w2.agents.streamed.push(
      { event: "delta", data: { kind: "tool_call", call_id: "c1", tool: "planque", input: { action: "request", api, name: "overdue" } } },
      { event: "delta", data: { kind: "tool_result", call_id: "c1", result: refused } },
    );
    const r2 = await stream(w2, PATH, { token: "ana", body: { text: "Every Monday, tell me overdue invoices" } });
    expect(r2.events.filter((e) => e.event === "step").map((e) => e.data)).toEqual([{ call_id: "c1", status: "error", failed: true, why: "Coach couldn't connect Qonto yet" }]);
    expect(r2.raw).not.toContain("documented fields");
    // The server's reason isn't lost (owner's phone, 13 Nov: asked what the setup said, Coach no longer had it): it's
    // kept beside the step, scrubbed, and goes before the person's next words to Coach. The app still gets only `why`.
    await new Promise((f) => setTimeout(f, 0));
    const run = r2.events[0]!.data.run as string;
    const refusal = "the body must be a JSON object with the documented fields";
    expect(await w2.coach.get(stepWhyKey("t_acme", run))).toEqual([{ call: "c1", tool: "planque", why: "Coach couldn't connect Qonto yet", refusal,
      // The person's two lines, tafs cloud's words, for the thread's message (`refused`): the reason and what to do next.
      refused: { vendor: "Qonto", reason: `Why: ${refusal}`, next: "Next: ask Coach to connect Qonto with its key instead, or to use another app." } }]);
    expect(await w2.coach.get("t_acme#coach-notes#u_ana")).toEqual([expect.objectContaining({
      text: `[connection refused] Coach couldn't connect Qonto yet. The connection server said: ${JSON.stringify(refusal)}` })]);
  });

  it("marks a refused connection past once a request for the same app goes through in the run, and not for another app", async () => {
    const w = world();
    const refused = { text: "planque answered 409: managed sign-in isn't set up for this app", is_error: true };
    const qonto = { url: "https://thirdparty.qonto.com/openapi.json" };
    const shippo = { url: "https://api.goshippo.com/openapi.yaml" };
    w.agents.streamed.push(
      { event: "delta", data: { kind: "tool_call", call_id: "c1", tool: "planque", input: { action: "request", api: shippo, name: "labels" } } },
      { event: "delta", data: { kind: "tool_result", call_id: "c1", result: refused } },
      { event: "delta", data: { kind: "tool_call", call_id: "c2", tool: "planque", input: { action: "request", api: qonto, name: "invoices" } } },
      { event: "delta", data: { kind: "tool_result", call_id: "c2", result: refused } },
      // A read that went through connects nothing: Qonto's refusal stands.
      { event: "delta", data: { kind: "tool_call", call_id: "c3", tool: "planque", input: { action: "catalog" } } },
      { event: "delta", data: { kind: "tool_result", call_id: "c3", result: { text: "catalog: 12 apps" } } },
      // Shippo by its key: through, so Shippo's refusal is past.
      { event: "delta", data: { kind: "tool_call", call_id: "c4", tool: "planque", input: { action: "request", api: shippo, name: "labels", key: true } } },
      { event: "delta", data: { kind: "tool_result", call_id: "c4", result: { text: "grant grt_Shippo1234 pending; agent labels-k3f9" } } },
    );
    const r = await stream(w, PATH, { token: "ana", body: { text: "Get my unused labels refunded" } });
    await new Promise((f) => setTimeout(f, 0));
    const kept = await w.coach.get(stepWhyKey("t_acme", r.events[0]!.data.run as string)) as Array<{ call: string; resolved?: true }>;
    expect(kept.map((k) => [k.call, k.resolved ?? false])).toEqual([["c1", true], ["c2", false]]);
    // The stream still sends only the step's own words: the lines are for the thread's message.
    expect(r.raw).not.toContain("Next: ask Coach");
  });

  it("keeps a failed step when the same tool is called again with other input: that is a new call, not a retry", async () => {
    const w = world();
    w.agents.streamed.push(
      { event: "delta", data: { kind: "tool_call", call_id: "c1", tool: "send_reminder", input: { to: "A" } } },
      { event: "delta", data: { kind: "tool_result", call_id: "c1", result: { text: "The mailbox for A is full.", is_error: true } } },
      { event: "delta", data: { kind: "tool_call", call_id: "c2", tool: "send_reminder", input: { to: "B" } } },
      { event: "delta", data: { kind: "tool_result", call_id: "c2", result: { text: "sent" } } },
    );
    const r = await stream(w, PATH, { token: "ana", body: { text: "Remind A and B" } });
    const steps = r.events.filter((e) => e.event === "step").map((e) => e.data);
    // B's reminder went; A's still shows failed, with its why, before the end.
    expect(steps).toEqual([{ call_id: "c2", status: "done" }, { call_id: "c1", status: "error", failed: true, why: "The mailbox for A is full." }]);
    expect(r.events.map((e) => e.event).slice(-2)).toEqual(["step", "end"]);
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
      { tool: "show", step: "Showing you a card…", call_id: "c4" },
    ]);
    expect(r.raw).not.toContain("grt_AbC123xyz");
    expect(r.raw).not.toContain("refunds-k3f9");
    expect(r.raw).not.toContain("mcp.stripe.com");
  });

  it("says a taf's tool through KIFF by KIFF's title for it, and nothing for one without a title, never its name", async () => {
    const w = world();
    w.agents.streamed.push(
      { event: "delta", data: { kind: "tool_call", call_id: "c1", tool: "list_charges_ijkp", input: { limit: 5 } } },
      { event: "delta", data: { kind: "tool_call", call_id: "c2", tool: "get_charge_ijkp", input: { charge: "ch_1" } } },
    );
    const people = kiff({ ana: ME });
    const withCards = (async (url: string, init?: RequestInit) =>
      String(url).endsWith("/v1/me/mandates")
        ? new Response(JSON.stringify({ mandates: [{ id: "m1", subject: "rae", active: true, grants: [{ action: "list_charges_ijkp", title: "List your charges" }] }] }), { status: 200 })
        : people(url, init)) as typeof fetch;
    const r = await stream(w, PATH, { token: "ana", body: { text: "Refund ch_1" }, fetch: withCards });
    expect(r.events.filter((e) => e.event === "activity").map((e) => e.data)).toEqual([
      { tool: "list_charges_ijkp", step: "List your charges…", call_id: "c1" },
      { tool: "get_charge_ijkp", call_id: "c2" },
    ]);
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
    // "Refunding $20." before its calls, and nothing written after them: the run's message, sent at its end.
    expect(r.events.map((e) => e.event)).toEqual(["session", "activity", "held", "activity", "step", "text", "end"]);
    expect(r.events[2]!.data).toEqual({ exception: "exc_1", invocation: "tc-1" });
    expect(r.events[5]!.data).toEqual({ text: "Refunding $20." });
    expect(r.raw).not.toContain("ch_secret");
    expect(r.raw).not.toContain("op_1");
  });

  it("goes on in the person's live chat, as kiff/agents picks it, or a new one they ask for", async () => {
    const w = world();
    const first = await stream(w, PATH, { token: "ana", body: { text: "hi" } });
    const session = first.events[0]!.data.session;
    expect(first.events[0]!.data).toMatchObject({ new_session: true });
    const again = await stream(w, PATH, { token: "ana", body: { text: "and then?" } });
    expect(again.events[0]!.data).toEqual({ session, new_session: false, run: expect.any(String), message: { id: expect.any(String), at: expect.any(String) } });
    // The thread names the owner's message by that same id, so the app matches its send without guessing.
    expect(again.events[0]!.data.message.id).toBe(`${again.events[0]!.data.run}.in`);
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
    // The look-up failed and was retried with the same tool: the failure is never shown, the retry's outcome is.
    expect(r.events.map((e) => e.event)).toEqual(["session", "thinking", "thinking", "activity", "activity", "step", "text", "end"]);
    expect(r.events.filter((e) => e.event === "thinking").map((e) => e.data.text).join("")).toBe("The order is paid, so a refund fits.");
    expect(r.events.filter((e) => e.event === "step").map((e) => e.data)).toEqual([{ call_id: "c2", status: "done" }]);
    expect(r.raw).not.toContain("ord_secret");
  });

  it("says why a step failed, in one line without ids or codes, and keeps it for the thread (kiff/tafs#476, gap 9)", async () => {
    const w = world();
    w.agents.streamed.push(
      { event: "delta", data: { kind: "tool_call", call_id: "c1", tool: "append_row_ijkp", input: { sheet: "sh_secret" } } },
      { event: "delta", data: { kind: "tool_result", call_id: "c1", result: { text: "planque answered 403: Google Sheets refused the write: the sheet \"Orders\" was renamed (grant grt_Abc123xyz, code sheet_not_found).\nMore lines the owner never sees.", is_error: true } } },
      { event: "delta", data: { kind: "tool_call", call_id: "c2", tool: "get_charge_ijkp", input: { charge: "ch_secret" } } },
      { event: "delta", data: { kind: "tool_result", call_id: "c2", result: { text: "ch_1AbCdEfGhIjKlMnOp 0123456789abcdef0123", is_error: true } } },
      { event: "delta", data: { kind: "text", text: "I couldn't write the row." } },
    );
    const r = await stream(w, PATH, { token: "ana", body: { text: "Add the order to the sheet" } });
    expect(r.events.filter((e) => e.event === "step").map((e) => e.data)).toEqual([
      { call_id: "c1", status: "error", failed: true, why: "Google Sheets refused the write: the sheet \"Orders\" was renamed." },
      { call_id: "c2", status: "error", failed: true }, // nothing readable: only that it failed
    ]);
    for (const secret of ["grt_Abc123xyz", "sheet_not_found", "More lines", "ch_1AbCdEfGhIjKlMnOp", "0123456789abcdef"]) expect(r.raw).not.toContain(secret);
    // Kept under the run, with the call and its tool, so the stored thread can say it (chat.test.ts).
    const run = r.events[0]!.data.run as string;
    await new Promise((f) => setTimeout(f, 0));
    expect(await w.coach.get(stepWhyKey("t_acme", run))).toEqual([{ call: "c1", tool: "append_row_ijkp", why: "Google Sheets refused the write: the sheet \"Orders\" was renamed." }]);
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

  it("ends a run kiff/agents stopped with a code with that code and its words, for the apps to show as they are", async () => {
    const w = world();
    const started = await stream(w, PATH, { token: "ana", body: { text: "Refund T-3001" } });
    const run = String(started.events[0]!.data.run);
    const stored = w.agents.runs.find((x) => x.id === run)!;
    const words = "Our AI provider isn't serving us right now, so this stopped. That's on our side, not yours: none of your credits were used. Try again a little later.";
    Object.assign(stored, { status: "ERROR", error: words, code: "ai_unavailable" });
    const r = await stream(w, `/v1/tafs/${RAE}/runs/${run}/stream?after=0`, { token: "ana", method: "GET" });
    // A code tafs cloud knows is worded from its own catalog, whole, in the person's language (docs/languages.md).
    expect(r.events.at(-1)).toEqual({ event: "end", data: { status: "ERROR", stop: "", done: [], code: "ai_unavailable", words: wordsIn("en").code_ai_unavailable } });
    const german = await stream(w, `/v1/tafs/${RAE}/runs/${run}/stream?after=0`, { token: "ana", method: "GET", language: "de-AT,de;q=0.9,en;q=0.5" });
    expect(german.events.at(-1)!.data).toMatchObject({ code: "ai_unavailable", words: wordsIn("de").code_ai_unavailable });
    // One it doesn't know passes kiff/agents' words through, whole: all three sentences, never cut to two.
    Object.assign(stored, { code: "new_code" });
    const unknown = await stream(w, `/v1/tafs/${RAE}/runs/${run}/stream?after=0`, { token: "ana", method: "GET", language: "de" });
    expect(unknown.events.at(-1)!.data).toMatchObject({ code: "new_code", words });
    // A failure with no code ends as before: kiff/agents' error text isn't passed on as words.
    Object.assign(stored, { error: "anthropic: 500 internal", code: undefined });
    const plain = await stream(w, `/v1/tafs/${RAE}/runs/${run}/stream?after=0`, { token: "ana", method: "GET" });
    expect(plain.events.at(-1)).toEqual({ event: "end", data: { status: "ERROR", stop: "", done: [] } });
    // A code that isn't one (spaces, capitals, too long) isn't passed on either.
    for (const bad of ["AI Unavailable", "x".repeat(41), ""]) {
      Object.assign(stored, { error: words, code: bad });
      const odd = await stream(w, `/v1/tafs/${RAE}/runs/${run}/stream?after=0`, { token: "ana", method: "GET" });
      expect(odd.events.at(-1)!.data).toEqual({ status: "ERROR", stop: "", done: [] });
    }
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

describe("Coach's runs announce their pauses", () => {
  it("a Coach message's run is announced to tafs cloud, a taf's isn't", async () => {
    const w = world();
    const COACH = "taf-c0ac4000000000000000";
    w.tafs.held.set(`t_acme#${COACH}`, { id: COACH, name: "Coach", job: SETUP_JOB, kiffAgent: "setup-acme", nameClaim: "c", created: "2026-10-15T09:00:00Z" });
    void w.agents.api.putAgent("t_acme", COACH, {} as never);
    const coach = await stream(w, `/v1/tafs/${COACH}/messages/stream`, { token: "ana", body: { text: "Set up refunds" }, publicURL: "https://cloud.test" });
    expect(coach.error).toBe("");
    const coachRun = w.agents.runs.find((r) => r.agent === COACH)!;
    expect(w.agents.notified.get(coachRun.id)?.url).toBe("https://cloud.test/v1/runs/ended");
    const taf = await stream(w, `/v1/tafs/${RAE}/messages/stream`, { token: "ana", body: { text: "Refund order 42" }, publicURL: "https://cloud.test" });
    expect(taf.error).toBe("");
    expect(w.agents.notified.has(w.agents.runs.find((r) => r.agent === RAE)!.id)).toBe(false);
  });
});
