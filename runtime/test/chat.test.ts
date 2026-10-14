import { describe, expect, it } from "vitest";
import type Anthropic from "@anthropic-ai/sdk";
import { CHAT, handle, type HTTPEvent, type Names, type Slots } from "../src/cloud/handler.js";

const ME = { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] };
const VIEWER = { ...ME, capabilities: ["read"] };
const NOW = new Date("2026-10-14T12:00:00Z");
const minutesAgo = (m: number) => new Date(NOW.getTime() - m * 60_000).toISOString();

function kiff(byToken: Record<string, object>) {
  return (async (_url: string, init?: RequestInit) => {
    const token = String((init?.headers as Record<string, string>).authorization).replace("Bearer ", "");
    const me = byToken[token];
    return me ? new Response(JSON.stringify(me), { status: 200 }) : new Response("{}", { status: 401 });
  }) as typeof fetch;
}

const names: Names = { claim: async () => true, release: async () => {} };

/** Slots claimed atomically, like the DynamoDB conditional write. */
function memorySlots(): Slots & { held: Set<string> } {
  const held = new Set<string>();
  return { held, async claim(key) { if (held.has(key)) return false; held.add(key); return true; } };
}

interface FakeSession {
  id: string;
  created_at: string;
  updated_at: string;
  status: string;
  deployment_id?: string | null;
  metadata: Record<string, string>;
  agent: { id: string };
}
interface FakeEvent { type: string; processed_at?: string | null; content: Array<{ type: string; text: string }> }

/** A stateful fake of the parts of the Anthropic client chat uses. */
function fakeAnthropic() {
  const calls: string[] = [];
  const deployment = (id: string, tenant: string) => ({
    id, name: "Rae", status: "active",
    agent: { id: "agent_refunds", version: 3, type: "agent" },
    environment_id: "env_1", vault_ids: [`vlt_${id}`],
    budget: { type: "limit", max_list_cost: { amount: "200", currency: "USD" } },
    metadata: { tafs_tenant: tenant, tafs_taf: "Rae", tafs_job: "refunds" },
    schedule: null,
  });
  const deployments = [deployment("depl_acme", "t_acme"), deployment("depl_other", "t_other")];
  const sessions: FakeSession[] = [];
  const events: Record<string, FakeEvent[]> = {};
  let n = 0;
  const say = (type: string, text: string, at: string | null) => ({ type, processed_at: at, content: [{ type: "text", text }] });
  const client = {
    beta: {
      deployments: {
        list: async function* () { yield* deployments; },
        retrieve: async (id: string) => { const d = deployments.find((x) => x.id === id); if (!d) throw new Error("404"); return d; },
      },
      sessions: {
        list: async function* (p: { agent_id: string; order: string }) {
          calls.push(`sessions.list ${p.agent_id} ${p.order}`);
          yield* [...sessions].filter((s) => s.agent.id === p.agent_id).sort((a, b) => b.created_at.localeCompare(a.created_at));
        },
        create: async (p: Record<string, unknown>) => {
          calls.push(`sessions.create ${JSON.stringify(p)}`);
          const s = { id: `sesn_chat${++n}`, created_at: NOW.toISOString(), updated_at: NOW.toISOString(), status: "running", metadata: p.metadata as Record<string, string>, agent: { id: "agent_refunds" } };
          sessions.push(s);
          events[s.id] = (p.initial_events as FakeEvent[]).map((e) => ({ ...e, processed_at: NOW.toISOString() }));
          return s;
        },
        events: {
          list: async function* (id: string) { yield* events[id] ?? []; },
          send: async (id: string, p: { events: FakeEvent[] }) => {
            calls.push(`events.send ${id} ${p.events[0]?.content[0]?.text}`);
            (events[id] ??= []).push(...p.events.map((e) => ({ ...e, processed_at: null })));
            return {};
          },
        },
      },
    },
  } as unknown as Anthropic;
  const addSession = (s: Partial<FakeSession> & { id: string; created_at: string }, evs: FakeEvent[]) => {
    sessions.push({ updated_at: s.created_at, status: "idle", metadata: {}, agent: { id: "agent_refunds" }, ...s });
    events[s.id] = evs;
  };
  return { client, calls, addSession, say, sessions };
}

function call(fake: ReturnType<typeof fakeAnthropic>, method: string, path: string, opts: { token?: string; body?: unknown; slots?: Slots; now?: Date } = {}) {
  const event: HTTPEvent = {
    rawPath: path,
    requestContext: { http: { method } },
    headers: { Authorization: `Bearer ${opts.token ?? "ana"}` },
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
  };
  return handle(event, {
    anthropic: async () => fake.client,
    setup: async () => ({ environmentID: "env_1", agents: {} }),
    names,
    slots: opts.slots ?? memorySlots(),
    fetch: kiff({ ana: ME, viewer: VIEWER }),
    now: () => opts.now ?? NOW,
  }).then((r) => ({ status: r.statusCode, body: JSON.parse(r.body) }));
}

describe("conversation", () => {
  it("reads the taf's runs and chats, oldest first, with the run's kickoff marked", async () => {
    const f = fakeAnthropic();
    f.addSession({ id: "sesn_run1", created_at: minutesAgo(120), deployment_id: "depl_acme" }, [
      f.say("user.message", "Work the refund queue.", minutesAgo(120)),
      f.say("agent.message", "Refunded 3 orders; 1 waits on you.", minutesAgo(118)),
    ]);
    f.addSession({ id: "sesn_chat0", created_at: minutesAgo(60), metadata: { tafs_chat_for: "depl_acme", tafs_tenant: "t_acme" } }, [
      f.say("user.message", "Why is order 1042 waiting?", minutesAgo(60)),
      f.say("agent.message", "It's $240, over the $150 a refund may be.", minutesAgo(59)),
    ]);
    // Another taf's run on the same agent, and an unmarked session: not in this thread.
    f.addSession({ id: "sesn_other", created_at: minutesAgo(30), deployment_id: "depl_other" }, [f.say("agent.message", "not yours", minutesAgo(30))]);
    f.addSession({ id: "sesn_loose", created_at: minutesAgo(20) }, [f.say("agent.message", "not a taf's", minutesAgo(20))]);

    const r = await call(f, "GET", "/v1/tafs/depl_acme/conversation");
    expect(r.status).toBe(200);
    expect(r.body.messages).toEqual([
      { at: minutesAgo(120), from: "owner", kind: "kickoff", text: "Work the refund queue.", session: "sesn_run1" },
      { at: minutesAgo(118), from: "taf", kind: "message", text: "Refunded 3 orders; 1 waits on you.", session: "sesn_run1" },
      { at: minutesAgo(60), from: "owner", kind: "message", text: "Why is order 1042 waiting?", session: "sesn_chat0" },
      { at: minutesAgo(59), from: "taf", kind: "message", text: "It's $240, over the $150 a refund may be.", session: "sesn_chat0" },
    ]);
    expect(f.calls).toContain("sessions.list agent_refunds desc");
  });

  it("reads only the latest five runs", async () => {
    const f = fakeAnthropic();
    for (let i = 0; i < 8; i++) {
      f.addSession({ id: `sesn_run${i}`, created_at: minutesAgo(500 - i * 10), deployment_id: "depl_acme" },
        [f.say("agent.message", `run ${i}`, minutesAgo(500 - i * 10))]);
    }
    const r = await call(f, "GET", "/v1/tafs/depl_acme/conversation");
    expect(r.body.messages.map((m: { text: string }) => m.text)).toEqual(["run 3", "run 4", "run 5", "run 6", "run 7"]);
  });

  it("caps the thread to its newest messages", async () => {
    const f = fakeAnthropic();
    f.addSession({ id: "sesn_run1", created_at: minutesAgo(300), deployment_id: "depl_acme" },
      Array.from({ length: 150 }, (_, k) => f.say("agent.message", `note ${k}`, minutesAgo(300 - k))));
    const r = await call(f, "GET", "/v1/tafs/depl_acme/conversation");
    expect(r.body.messages).toHaveLength(CHAT.maxThread);
    expect(r.body.messages[0].text).toBe("note 50");
    expect(r.body.messages.at(-1).text).toBe("note 149");
  });

  it("answers another account's taf like a missing one", async () => {
    const f = fakeAnthropic();
    expect((await call(f, "GET", "/v1/tafs/depl_other/conversation")).status).toBe(404);
    expect((await call(f, "POST", "/v1/tafs/depl_other/messages", { body: { text: "hi" } })).status).toBe(404);
    expect(f.calls).toEqual([]);
  });
});

describe("messages", () => {
  it("needs the right to manage Cards, and 1 to 2000 characters", async () => {
    const f = fakeAnthropic();
    expect((await call(f, "POST", "/v1/tafs/depl_acme/messages", { token: "viewer", body: { text: "hi" } })).status).toBe(403);
    expect((await call(f, "POST", "/v1/tafs/depl_acme/messages", { body: { text: "   " } })).status).toBe(400);
    expect((await call(f, "POST", "/v1/tafs/depl_acme/messages", { body: { text: "x".repeat(2001) } })).status).toBe(400);
    expect((await call(f, "POST", "/v1/tafs/depl_acme/messages", { body: {} })).status).toBe(400);
    expect(f.calls.filter((c) => c.startsWith("sessions.create") || c.startsWith("events.send"))).toEqual([]);
  });

  it("goes into the latest session while it is live", async () => {
    const f = fakeAnthropic();
    f.addSession({ id: "sesn_run1", created_at: minutesAgo(10), updated_at: minutesAgo(5), deployment_id: "depl_acme", status: "idle" },
      [f.say("user.message", "Work the queue.", minutesAgo(10))]);
    const r = await call(f, "POST", "/v1/tafs/depl_acme/messages", { body: { text: "  Hold refunds over $100 today.  " } });
    expect(r.body).toEqual({ session: "sesn_run1", new_session: false });
    expect(f.calls).toContain("events.send sesn_run1 Hold refunds over $100 today.");
  });

  it("starts a chat session with the taf's agent, vault and budget when nothing is live", async () => {
    const f = fakeAnthropic();
    f.addSession({ id: "sesn_old", created_at: minutesAgo(90), updated_at: minutesAgo(80), deployment_id: "depl_acme", status: "idle" }, []);
    const r = await call(f, "POST", "/v1/tafs/depl_acme/messages", { body: { text: "What's waiting on me?" } });
    expect(r.body).toEqual({ session: "sesn_chat1", new_session: true });
    const created = JSON.parse(f.calls.find((c) => c.startsWith("sessions.create"))!.slice("sessions.create ".length));
    expect(created).toEqual({
      agent: { type: "agent", id: "agent_refunds", version: 3 },
      environment_id: "env_1",
      vault_ids: ["vlt_depl_acme"],
      budget: { type: "limit", max_list_cost: { amount: "200", currency: "USD" } },
      title: "Rae: chat",
      metadata: { tafs_chat_for: "depl_acme", tafs_tenant: "t_acme", tafs_taf: "Rae" },
      initial_events: [{ type: "user.message", content: [{ type: "text", text: "What's waiting on me?" }] }],
    });
    // The new chat is part of the thread from then on.
    const thread = await call(f, "GET", "/v1/tafs/depl_acme/conversation");
    expect(thread.body.messages.at(-1)).toMatchObject({ from: "owner", kind: "message", text: "What's waiting on me?", session: "sesn_chat1" });
  });

  it("starts a new session when the live one refuses the message", async () => {
    const f = fakeAnthropic();
    f.addSession({ id: "sesn_run1", created_at: minutesAgo(5), deployment_id: "depl_acme", status: "running" }, []);
    const sessions = f.sessions;
    // As a session paused at its budget does: it refuses new work.
    (f.client.beta.sessions.events as unknown as { send: (id: string) => Promise<never> }).send = async (id: string) => {
      f.calls.push(`events.send ${id} refused`);
      throw Object.assign(new Error("only settle events while paused at its budget"), { status: 400 });
    };
    const r = await call(f, "POST", "/v1/tafs/depl_acme/messages", { body: { text: "Go on." } });
    expect(r.body.new_session).toBe(true);
    expect(sessions.map((s) => s.id)).toContain("sesn_chat1");
  });

  it("refuses an eleventh message in ten minutes, however many sessions they start", async () => {
    const f = fakeAnthropic();
    const slots = memorySlots();
    // Nothing is live, so each message starts its own chat session, and the thread
    // keeps only the latest few: the guard must not depend on what it shows.
    (f.client.beta.sessions as unknown as { list: () => AsyncGenerator<never> }).list = async function* () {};
    for (let i = 0; i < 10; i++) {
      const r = await call(f, "POST", "/v1/tafs/depl_acme/messages", { body: { text: `go ${i}` }, slots });
      expect(r.status, `message ${i}`).toBe(200);
      expect(r.body.new_session).toBe(true);
    }
    const eleventh = await call(f, "POST", "/v1/tafs/depl_acme/messages", { body: { text: "one more" }, slots });
    expect(eleventh.status).toBe(429);
    expect(f.calls.filter((c) => c.startsWith("sessions.create"))).toHaveLength(10);

    // The next window has room again; another taf has its own.
    const later = new Date(NOW.getTime() + CHAT.floodMinutes * 60_000);
    expect((await call(f, "POST", "/v1/tafs/depl_acme/messages", { body: { text: "later" }, slots, now: later })).status).toBe(200);
    expect([...slots.held].every((k) => k.startsWith("rate#t_acme#depl_acme#"))).toBe(true);
  });
});
