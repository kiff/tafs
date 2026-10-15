import { describe, expect, it } from "vitest";
import type Anthropic from "@anthropic-ai/sdk";
import { MEMORY_INSTRUCTIONS } from "../src/config.js";
import { CHAT, handle, type HTTPEvent, MEMORY, type Names, type Slots } from "../src/cloud/handler.js";

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
function memorySlots(): Slots & { taken: Set<string> } {
  const taken = new Set<string>();
  return {
    taken,
    async claim(key) { if (taken.has(key)) return false; taken.add(key); return true; },
    async release(key) { taken.delete(key); },
    async keep() {},
    async held(key) { return taken.has(key); },
  };
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
    metadata: { tafs_tenant: tenant, tafs_taf: "Rae", tafs_job: "refunds", tafs_memory: `memstore_${id}` } as Record<string, string>,
    resources: [] as unknown[],
    schedule: null,
  });
  const deployments = [deployment("depl_acme", "t_acme"), deployment("depl_other", "t_other")];
  // Notes per memory store, as the API lists them with view=full.
  const notes: Record<string, Array<{ type: string; id: string; path: string; content: string; updated_at: string }>> = {
    memstore_depl_acme: [
      { type: "memory_prefix", id: "", path: "/customers/", content: "", updated_at: "" },
      { type: "memory", id: "mem_old", path: "/refunds.md", content: "Offer store credit first.", updated_at: minutesAgo(600) },
      { type: "memory", id: "mem_new", path: "/customers/acme.md", content: "Acme buys wholesale: ask first.", updated_at: minutesAgo(60) },
    ],
    memstore_depl_other: [{ type: "memory", id: "mem_theirs", path: "/x.md", content: "not yours", updated_at: minutesAgo(5) }],
  };
  // Every version of a note: the store keeps them after a delete.
  const versions: Array<{ id: string; memory_store_id: string; memory_id: string; operation: string; redacted_at: string | null }> = [
    { id: "memver_1", memory_store_id: "memstore_depl_acme", memory_id: "mem_old", operation: "created", redacted_at: null },
    { id: "memver_2", memory_store_id: "memstore_depl_acme", memory_id: "mem_old", operation: "modified", redacted_at: null },
    { id: "memver_3", memory_store_id: "memstore_depl_acme", memory_id: "mem_new", operation: "created", redacted_at: null },
  ];
  const sessions: FakeSession[] = [];
  const events: Record<string, FakeEvent[]> = {};
  let n = 0;
  const say = (type: string, text: string, at: string | null) => ({ type, processed_at: at, content: [{ type: "text", text }] });
  const client = {
    beta: {
      deployments: {
        list: async function* () { yield* deployments; },
        retrieve: async (id: string) => { const d = deployments.find((x) => x.id === id); if (!d) throw new Error("404"); return d; },
        update: async (id: string, p: { metadata?: Record<string, string>; resources?: unknown[] }) => {
          calls.push(`deployments.update ${id} ${JSON.stringify(p)}`);
          const d = deployments.find((x) => x.id === id)!;
          Object.assign(d.metadata, p.metadata ?? {});
          if (p.resources) d.resources = p.resources;
          return d;
        },
      },
      memoryStores: {
        create: async (p: { name: string }) => { calls.push(`memoryStores.create ${p.name}`); notes.memstore_made = []; return { id: "memstore_made" }; },
        memories: {
          list: async function* (store: string, p: { view: string }) {
            calls.push(`memories.list ${store} ${p.view}`);
            yield* notes[store] ?? [];
          },
          delete: async (id: string, p: { memory_store_id: string }) => {
            calls.push(`memories.delete ${id} ${p.memory_store_id}`);
            const list = notes[p.memory_store_id] ?? [];
            const i = list.findIndex((m) => m.id === id);
            if (i < 0) throw Object.assign(new Error("not found"), { status: 404 });
            list.splice(i, 1);
            versions.push({ id: `memver_del_${id}`, memory_store_id: p.memory_store_id, memory_id: id, operation: "deleted", redacted_at: null });
            return { id, type: "memory_deleted" };
          },
        },
        memoryVersions: {
          list: async function* (store: string, p: { memory_id: string }) {
            yield* versions.filter((v) => v.memory_store_id === store && v.memory_id === p.memory_id);
          },
          redact: async (id: string, p: { memory_store_id: string }) => {
            const v = versions.find((x) => x.id === id && x.memory_store_id === p.memory_store_id)!;
            calls.push(`versions.redact ${id}`);
            v.redacted_at = NOW.toISOString();
            return v;
          },
        },
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
  return { client, calls, addSession, say, sessions, deployments, notes };
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
      resources: [{ type: "memory_store", memory_store_id: "memstore_depl_acme", access: "read_write", instructions: MEMORY_INSTRUCTIONS }],
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
    expect([...slots.taken].every((k) => k.startsWith("rate#t_acme#depl_acme#"))).toBe(true);
  });
});

describe("memory", () => {
  it("lists the taf's notes, newest first, without folders", async () => {
    const f = fakeAnthropic();
    const r = await call(f, "GET", "/v1/tafs/depl_acme/memory", { token: "viewer" });
    expect(r.status).toBe(200);
    expect(r.body.notes).toEqual([
      { id: "mem_new", path: "/customers/acme.md", content: "Acme buys wholesale: ask first.", updated_at: minutesAgo(60) },
      { id: "mem_old", path: "/refunds.md", content: "Offer store credit first.", updated_at: minutesAgo(600) },
    ]);
    expect(f.calls).toContain("memories.list memstore_depl_acme full");
  });

  it("shows the newest notes when there are more than it shows, whatever order the API lists them in", async () => {
    const f = fakeAnthropic();
    const many = Array.from({ length: MEMORY.shown + 5 }, (_, i) => ({
      type: "memory", id: `mem_${i}`, path: `/n${i}.md`, content: `note ${i}`, updated_at: minutesAgo(1000 - i),
    }));
    f.notes.memstore_depl_acme = [...many.slice(1), many[0]!].reverse(); // the newest listed last
    const r = await call(f, "GET", "/v1/tafs/depl_acme/memory");
    const ids = r.body.notes.map((n: { id: string }) => n.id);
    expect(ids).toHaveLength(MEMORY.shown);
    expect(ids[0]).toBe(`mem_${MEMORY.shown + 4}`);
    expect(ids).not.toContain("mem_0");
  });

  it("shows no notes for a taf without memory yet, and makes none to show them", async () => {
    const f = fakeAnthropic();
    delete f.deployments[0]!.metadata.tafs_memory;
    const r = await call(f, "GET", "/v1/tafs/depl_acme/memory");
    expect(r.body).toEqual({ notes: [] });
    expect(f.calls).toEqual([]);
  });

  it("forgets a note, for someone who can manage Cards", async () => {
    const f = fakeAnthropic();
    expect((await call(f, "DELETE", "/v1/tafs/depl_acme/memory/mem_old", { token: "viewer" })).status).toBe(403);
    const r = await call(f, "DELETE", "/v1/tafs/depl_acme/memory/mem_old");
    expect(r).toEqual({ status: 200, body: { forgotten: "mem_old" } });
    expect(f.calls).toContain("memories.delete mem_old memstore_depl_acme");
    const after = await call(f, "GET", "/v1/tafs/depl_acme/memory");
    expect(after.body.notes.map((n: { id: string }) => n.id)).toEqual(["mem_new"]);
  });

  it("redacts every earlier version of a note it forgets, and forgetting again finishes the job", async () => {
    const f = fakeAnthropic();
    expect((await call(f, "DELETE", "/v1/tafs/depl_acme/memory/mem_old")).status).toBe(200);
    // The note's own versions only; the deletion marker has no text to redact.
    expect(f.calls.filter((c) => c.startsWith("versions.redact"))).toEqual(["versions.redact memver_1", "versions.redact memver_2"]);
    // Again (say the answer was lost): already deleted, nothing left to redact, still done.
    expect((await call(f, "DELETE", "/v1/tafs/depl_acme/memory/mem_old")).status).toBe(200);
    expect(f.calls.filter((c) => c.startsWith("versions.redact"))).toHaveLength(2);
    // A note that never was is still missing.
    expect((await call(f, "DELETE", "/v1/tafs/depl_acme/memory/mem_nope")).status).toBe(404);
  });

  it("can't forget another taf's note, or another account's", async () => {
    const f = fakeAnthropic();
    // Named through my taf, the note is looked up only in my taf's store.
    expect((await call(f, "DELETE", "/v1/tafs/depl_acme/memory/mem_theirs")).status).toBe(404);
    expect((await call(f, "DELETE", "/v1/tafs/depl_other/memory/mem_theirs")).status).toBe(404);
    expect((await call(f, "GET", "/v1/tafs/depl_other/memory")).status).toBe(404);
    expect((await call(f, "DELETE", "/v1/tafs/depl_acme/memory/..%2Fx")).status).toBe(404);
    expect(f.calls.filter((c) => c.startsWith("memories.delete"))).toEqual(["memories.delete mem_theirs memstore_depl_acme"]);
  });

  it("gives a taf started before memory its store when the owner first writes", async () => {
    const f = fakeAnthropic();
    delete f.deployments[0]!.metadata.tafs_memory;
    const r = await call(f, "POST", "/v1/tafs/depl_acme/messages", { body: { text: "Remember: Acme is wholesale." } });
    expect(r.body).toEqual({ session: "sesn_chat1", new_session: true });
    expect(f.calls).toContain("memoryStores.create tafs-rae");
    expect(f.deployments[0]!.metadata.tafs_memory).toBe("memstore_made");
    expect(f.deployments[0]!.resources).toEqual([
      { type: "memory_store", memory_store_id: "memstore_made", access: "read_write", instructions: MEMORY_INSTRUCTIONS },
    ]);
    const created = JSON.parse(f.calls.find((c) => c.startsWith("sessions.create"))!.slice("sessions.create ".length));
    expect(created.resources[0].memory_store_id).toBe("memstore_made");
  });

  it("makes one store when two first chats race: the one without the lock starts without memory", async () => {
    const f = fakeAnthropic();
    delete f.deployments[0]!.metadata.tafs_memory;
    const slots = memorySlots();
    slots.taken.add("memory#depl_acme"); // another chat is giving the taf its store right now
    const r = await call(f, "POST", "/v1/tafs/depl_acme/messages", { body: { text: "Hello" }, slots });
    expect(r.status).toBe(200);
    expect(f.calls.filter((c) => c.startsWith("memoryStores.create"))).toEqual([]);
    const created = JSON.parse(f.calls.find((c) => c.startsWith("sessions.create"))!.slice("sessions.create ".length));
    expect(created.resources).toBeUndefined();
  });
});
