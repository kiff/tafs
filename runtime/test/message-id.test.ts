import { describe, expect, it } from "vitest";
import { AgentsError, readEvents } from "../src/cloud/agents.js";
import { handle, handleStream, type HTTPEvent, SETUP_JOB, type Taf } from "../src/cloud/handler.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryCoach, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

const ME = { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] };
const NOW = new Date("2026-11-17T10:00:00Z");
const RAE = "taf-aaaa0000000000000000";
const COACH = "taf-c0ac4000000000000000";
const ID = "0b6f1d3e-8f42-4c1a-9a57-3d2e8b1c7f00";
const NOTES = "t_acme#coach-notes#u_ana";

function kiff(byToken: Record<string, object>) {
  return (async (_url: string, init?: RequestInit) => {
    const token = String((init?.headers as Record<string, string>).authorization).replace("Bearer ", "");
    const me = byToken[token];
    return me ? new Response(JSON.stringify(me), { status: 200 }) : new Response("{}", { status: 401 });
  }) as typeof fetch;
}

/** Rae and Coach on kiff/agents, with one Coach line queued for Ana. */
async function world() {
  const clock = NOW;
  const agents = fakeAgents(() => clock);
  const tafs = memoryTafs();
  const coach = memoryCoach(() => clock);
  tafs.held.set(`t_acme#${RAE}`, { id: RAE, name: "Rae", job: "refunds", kiffAgent: "rae", nameClaim: "c", created: "2026-10-15T09:00:00Z" } satisfies Taf);
  tafs.held.set(`t_acme#${COACH}`, { id: COACH, name: "Coach", job: SETUP_JOB, kiffAgent: "setup-acme", nameClaim: "c", created: "2026-10-15T09:00:00Z" } satisfies Taf);
  await agents.api.putAgent("t_acme", RAE, {} as never);
  await agents.api.putAgent("t_acme", COACH, {} as never);
  await coach.append(NOTES, { at: NOW.toISOString(), text: "Rae finished its first run.", taf_id: RAE }, new Date(NOW.getTime() + 86_400_000));
  agents.calls.length = 0;
  return { agents, tafs, coach, slots: memorySlots(), archived: memoryArchived() };
}
type World = Awaited<ReturnType<typeof world>>;

function deps(w: World) {
  return {
    agents: w.agents.api, keys: memoryKeys(), accounts: memoryAccounts(), tafs: w.tafs,
    names: memoryNames(), slots: w.slots, claims: memoryClaims(), removals: memoryRemovals(), archived: w.archived, coach: w.coach,
    push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async () => "sent" as const },
    forward: async () => undefined,
    fetch: kiff({ ana: ME }),
    now: () => NOW,
  };
}

function event(path: string, body: unknown): HTTPEvent {
  return { rawPath: path, requestContext: { http: { method: "POST" } }, headers: { Authorization: "Bearer ana" }, body: JSON.stringify(body) };
}

/** The plain send: POST /v1/tafs/{id}/messages. */
async function send(w: World, taf: string, body: unknown) {
  const r = await handle(event(`/v1/tafs/${taf}/messages`, body), deps(w));
  return { status: r.statusCode, body: JSON.parse(r.body) as Record<string, unknown> };
}

/** The stream: POST /v1/tafs/{id}/messages/stream, its events parsed. */
async function stream(w: World, taf: string, body: unknown) {
  const r = await handleStream(event(`/v1/tafs/${taf}/messages/stream`, body), deps(w));
  let raw = "";
  for await (const chunk of r.body) raw += chunk;
  if (r.statusCode !== 200) return { status: r.statusCode, events: [], body: JSON.parse(raw) as Record<string, unknown> };
  const events: Array<{ event: string; data: Record<string, unknown> }> = [];
  for await (const e of readEvents(new Response(raw).body!)) {
    const ev = e as { event: string; data: Record<string, unknown> };
    if (ev.event !== "state") events.push(ev);
  }
  return { status: r.statusCode, events, body: {} as Record<string, unknown> };
}

const runsOf = (w: World, taf: string) => w.agents.runs.filter((r) => r.agent === taf);
const queued = async (w: World) => ((await w.coach.get(NOTES)) as unknown[] | undefined) ?? [];

describe("an app's message sent again with its client_message_id", () => {
  it("a stream that broke after kiff/agents made the run, then the plain send: one run, and Coach's line goes once", async () => {
    const w = await world();
    w.agents.breakFirst.err = new Error("socket closed before the first event");
    const first = await stream(w, COACH, { text: "Set up refunds", client_message_id: ID });
    expect(first.status).toBe(500);
    expect(runsOf(w, COACH)).toHaveLength(1);
    const made = runsOf(w, COACH)[0]!;
    // The run has the line as its input: it isn't put back for the next message.
    expect(w.agents.byKey.get(`t_acme#${COACH}#msg:u_ana:${ID}`)!.input).toContain("Rae finished its first run.");
    expect(await queued(w)).toEqual([]);

    const again = await send(w, COACH, { text: "Set up refunds", client_message_id: ID });
    expect(again.status).toBe(200);
    expect(again.body).toMatchObject({ run: made.id, repeat: true });
    expect(runsOf(w, COACH)).toHaveLength(1);
    expect(await queued(w)).toEqual([]);
    // No second stream, no fresh start: only kiff/agents' own answer to the key, handing the untaken run on.
    expect(w.agents.calls.filter((c) => c.startsWith("streamRun"))).toHaveLength(1);
  });

  it("a stream kiff/agents refused before making anything puts Coach's line back", async () => {
    const w = await world();
    w.agents.fail.set("streamRun", new AgentsError(503, "down"));
    const first = await stream(w, COACH, { text: "Set up refunds", client_message_id: ID });
    expect(first.status).toBe(502);
    expect(runsOf(w, COACH)).toHaveLength(0);
    expect(await queued(w)).toHaveLength(1);
    // Sent again, the message starts with its line, once.
    const again = await send(w, COACH, { text: "Set up refunds", client_message_id: ID });
    expect(again.status).toBe(200);
    expect(runsOf(w, COACH)).toHaveLength(1);
    expect(await queued(w)).toEqual([]);
  });

  it("the plain send sent twice is one run, answered as the first was", async () => {
    const w = await world();
    const first = await send(w, RAE, { text: "Refund order 42", client_message_id: ID });
    expect(first.status).toBe(200);
    w.agents.runs.find((r) => r.id === first.body.run)!.status = "COMPLETED";
    const again = await send(w, RAE, { text: "Refund order 42", client_message_id: ID });
    expect(again.status).toBe(200);
    expect(again.body).toMatchObject({ run: first.body.run, session: first.body.session, message: first.body.message, repeat: true });
    expect(runsOf(w, RAE)).toHaveLength(1);
    expect(w.agents.calls.filter((c) => c.startsWith("startRun"))).toHaveLength(1);
  });

  it("a stream sent again gets the first run's stream from its start, never a second run", async () => {
    const w = await world();
    const first = await stream(w, RAE, { text: "Refund order 42", client_message_id: ID });
    expect(first.events[0]!.event).toBe("session");
    const run = first.events[0]!.data.run as string;
    const again = await stream(w, RAE, { text: "Refund order 42", client_message_id: ID });
    expect(again.status).toBe(200);
    expect(again.events[0]).toEqual({ event: "session", data: expect.objectContaining({ run, repeat: true }) });
    expect(again.events.at(-1)!.event).toBe("end");
    expect(runsOf(w, RAE)).toHaveLength(1);
    expect(w.agents.calls).toContain(`resumeRun ${RAE} ${run} 0`);
  });

  it("a message steered into an approval's run, sent again, is steered once, even once the approval is over", async () => {
    const w = await world();
    const paused = await w.agents.api.startRun("t_acme", COACH, "Connect Stripe", { requester: { kind: "member", id: "u_ana" } });
    w.agents.runs.find((r) => r.id === paused.id)!.status = "PAUSED";
    const wait = `t_acme#${COACH}#u_ana#approval-waiting`;
    await w.coach.put(wait, { session: paused.session, run: paused.id, component: "ap1" }, new Date(NOW.getTime() + 600_000));
    w.agents.calls.length = 0;

    const first = await send(w, COACH, { text: "Use the test account", client_message_id: ID });
    expect(first.body).toMatchObject({ run: paused.id, held: "approval" });
    expect(w.agents.runs.find((r) => r.id === paused.id)!.steering).toHaveLength(1);

    // The approval ends before the app sends it again: still the one steer, and no new run.
    await w.coach.remove(wait);
    const again = await send(w, COACH, { text: "Use the test account", client_message_id: ID });
    expect(again.body).toMatchObject({ run: paused.id, held: "approval", repeat: true });
    expect(w.agents.runs.find((r) => r.id === paused.id)!.steering).toHaveLength(1);
    expect(runsOf(w, COACH)).toHaveLength(1);
    expect(w.agents.calls.filter((c) => c.startsWith("steerRun"))).toHaveLength(1);
    expect(await queued(w)).toEqual([]);
  });

  it("a steer kiff/agents took whose answer was lost, sent again: the same text with the same lines, taken once", async () => {
    const w = await world();
    const paused = await w.agents.api.startRun("t_acme", COACH, "Connect Stripe", { requester: { kind: "member", id: "u_ana" } });
    w.agents.runs.find((r) => r.id === paused.id)!.status = "PAUSED";
    await w.coach.put(`t_acme#${COACH}#u_ana#approval-waiting`, { session: paused.session, run: paused.id, component: "ap1" }, new Date(NOW.getTime() + 600_000));
    w.agents.steerLost.err = new Error("connection reset after the steer was taken");
    const first = await send(w, COACH, { text: "Use the test account", client_message_id: ID });
    expect(first.status).toBeGreaterThanOrEqual(500);
    // It may have gone through: Coach's line stays with this message, not the queue.
    expect(await queued(w)).toEqual([]);
    const again = await send(w, COACH, { text: "Use the test account", client_message_id: ID });
    expect(again.status).toBe(200);
    expect(again.body).toMatchObject({ run: paused.id, held: "approval" });
    const steering = w.agents.runs.find((r) => r.id === paused.id)!.steering!;
    expect(steering).toHaveLength(1);
    expect(steering[0]!.text).toContain("Rae finished its first run.");
    expect(runsOf(w, COACH)).toHaveLength(1);
  });

  it("a steer that failed before kiff/agents took it keeps Coach's line with the message, and the resend carries it", async () => {
    const w = await world();
    const paused = await w.agents.api.startRun("t_acme", COACH, "Connect Stripe", { requester: { kind: "member", id: "u_ana" } });
    w.agents.runs.find((r) => r.id === paused.id)!.status = "PAUSED";
    await w.coach.put(`t_acme#${COACH}#u_ana#approval-waiting`, { session: paused.session, run: paused.id, component: "ap1" }, new Date(NOW.getTime() + 600_000));
    // A 5xx may or may not have been taken: the line stays with this message, not the queue.
    w.agents.fail.set("steerRun", new AgentsError(503, "down"));
    expect((await send(w, COACH, { text: "Use the test account", client_message_id: ID })).status).toBeGreaterThanOrEqual(500);
    expect(await queued(w)).toEqual([]);
    const again = await send(w, COACH, { text: "Use the test account", client_message_id: ID });
    expect(again.body).toMatchObject({ run: paused.id, held: "approval" });
    const steering = w.agents.runs.find((r) => r.id === paused.id)!.steering!;
    expect(steering).toHaveLength(1);
    expect(steering[0]!.text).toContain("Rae finished its first run.");
  });

  it("a steer kiff/agents no longer finds by its key (past the runs it reads) is still answered from tafs cloud's record", async () => {
    const w = await world();
    const paused = await w.agents.api.startRun("t_acme", COACH, "Connect Stripe", { requester: { kind: "member", id: "u_ana" } });
    w.agents.runs.find((r) => r.id === paused.id)!.status = "PAUSED";
    const wait = `t_acme#${COACH}#u_ana#approval-waiting`;
    await w.coach.put(wait, { session: paused.session, run: paused.id, component: "ap1" }, new Date(NOW.getTime() + 600_000));
    await send(w, COACH, { text: "Use the test account", client_message_id: ID });
    w.agents.steeredKeys.clear();
    await w.coach.remove(wait);
    const again = await send(w, COACH, { text: "Use the test account", client_message_id: ID });
    expect(again.body).toMatchObject({ run: paused.id, held: "approval", repeat: true });
    expect(runsOf(w, COACH)).toHaveLength(1);
  });

  it("another message under an id already used is 409 dedup_key_conflict, on the plain send and the stream", async () => {
    const w = await world();
    await send(w, RAE, { text: "Refund order 42", client_message_id: ID });
    const plain = await send(w, RAE, { text: "Refund order 43", client_message_id: ID });
    expect(plain.status).toBe(409);
    expect(plain.body.code).toBe("dedup_key_conflict");
    const streamed = await stream(w, RAE, { text: "Refund order 43", client_message_id: ID });
    expect(streamed.status).toBe(409);
    expect(streamed.body.code).toBe("dedup_key_conflict");
    expect(runsOf(w, RAE)).toHaveLength(1);
  });

  it("refuses an id that isn't a UUID with 400, and starts nothing", async () => {
    const w = await world();
    for (const id of ["42", "not-a-uuid", 7, "0b6f1d3e8f424c1a9a573d2e8b1c7f00"]) {
      expect((await send(w, RAE, { text: "Refund order 42", client_message_id: id })).status).toBe(400);
      expect((await stream(w, RAE, { text: "Refund order 42", client_message_id: id })).status).toBe(400);
    }
    expect(runsOf(w, RAE)).toHaveLength(0);
  });

  it("without an id, nothing changes: two sends are two runs, and no key goes to kiff/agents", async () => {
    const w = await world();
    await send(w, RAE, { text: "Refund order 42" });
    await stream(w, RAE, { text: "Refund order 42" });
    expect(runsOf(w, RAE)).toHaveLength(2);
    expect(w.agents.byKey.size).toBe(0);
    expect(w.agents.calls.some((c) => c.startsWith("runByKey"))).toBe(false);
  });

  it("the same id from another person is another message", async () => {
    const w = await world();
    const ana = await send(w, RAE, { text: "Refund order 42", client_message_id: ID });
    const other = await handle({ ...event(`/v1/tafs/${RAE}/messages`, { text: "Refund order 42", client_message_id: ID }), headers: { Authorization: "Bearer luis" } },
      { ...deps(w), fetch: kiff({ ana: ME, luis: { ...ME, subject: "u_luis" } }) });
    expect(other.statusCode).toBe(200);
    expect(JSON.parse(other.body).run).not.toBe(ana.body.run);
    expect(runsOf(w, RAE)).toHaveLength(2);
  });
});

describe("kiff/agents' lookup of a key", () => {
  it("a lookup that fails is 503 on every route, and nothing is taken: no run, no steer, Coach's line still queued", async () => {
    const w = await world();
    const paused = await w.agents.api.startRun("t_acme", COACH, "Connect Stripe", { requester: { kind: "member", id: "u_ana" } });
    w.agents.runs.find((r) => r.id === paused.id)!.status = "RUNNING";
    w.agents.calls.length = 0;
    for (const go of [
      () => send(w, COACH, { text: "Set up refunds", client_message_id: ID }),
      () => stream(w, COACH, { text: "Set up refunds", client_message_id: ID }),
      async () => {
        const r = await handle(event(`/v1/tafs/${COACH}/steer`, { run: paused.id, text: "Set up refunds", client_message_id: ID }), deps(w));
        return { status: r.statusCode, body: JSON.parse(r.body) as Record<string, unknown> };
      },
    ]) {
      w.agents.fail.set("runByKey", new AgentsError(500, "kiff/agents' store didn't answer"));
      expect((await go()).status).toBe(503);
    }
    w.agents.fail.set("runByKey", new TypeError("fetch failed"));
    expect((await send(w, COACH, { text: "Set up refunds", client_message_id: ID })).status).toBe(503);
    expect(runsOf(w, COACH)).toHaveLength(1);
    expect(w.agents.runs.find((r) => r.id === paused.id)!.steering ?? []).toHaveLength(0);
    expect(w.agents.calls.filter((c) => /^(startRun|streamRun|steerRun)/.test(c))).toEqual([]);
    expect(await queued(w)).toHaveLength(1);
    // The key was never taken as used: the next send acts.
    expect((await send(w, COACH, { text: "Set up refunds", client_message_id: ID })).status).toBe(200);
    expect(runsOf(w, COACH)).toHaveLength(2);
  });

  it("with tafs cloud's own record gone, kiff/agents' digest tells the same message from another", async () => {
    const w = await world();
    const first = await send(w, RAE, { text: "Refund order 42", client_message_id: ID });
    await w.coach.remove(`t_acme#msg-key#msg:u_ana:${ID}`);
    const other = await send(w, RAE, { text: "Refund order 43", client_message_id: ID });
    expect(other.status).toBe(409);
    expect(other.body.code).toBe("dedup_key_conflict");
    const same = await send(w, RAE, { text: "Refund order 42", client_message_id: ID });
    expect(same.body).toMatchObject({ run: first.body.run, repeat: true });
    expect(runsOf(w, RAE)).toHaveLength(1);
  });
});

describe("writing to a run at work (POST …/steer) with a client_message_id", () => {
  async function atWork(w: World) {
    const r = await w.agents.api.startRun("t_acme", RAE, "Work the refund queue", { requester: { kind: "member", id: "u_ana" } });
    w.agents.runs.find((x) => x.id === r.id)!.status = "RUNNING";
    w.agents.calls.length = 0;
    return r;
  }
  const steer = async (w: World, body: unknown) => {
    const r = await handle(event(`/v1/tafs/${RAE}/steer`, body), deps(w));
    return { status: r.statusCode, body: JSON.parse(r.body) as Record<string, unknown> };
  };

  it("written again, the run takes it once", async () => {
    const w = await world();
    const run = await atWork(w);
    const first = await steer(w, { run: run.id, text: "only the €20 one", client_message_id: ID });
    expect(first).toMatchObject({ status: 200, body: { run: run.id } });
    const again = await steer(w, { run: run.id, text: "only the €20 one", client_message_id: ID });
    expect(again).toMatchObject({ status: 200, body: { run: run.id, repeat: true } });
    expect(w.agents.runs.find((r) => r.id === run.id)!.steering).toHaveLength(1);
    expect(w.agents.calls.filter((c) => c.startsWith("steerRun"))).toHaveLength(1);
  });

  it("a write kiff/agents took whose answer was lost is taken once when written again", async () => {
    const w = await world();
    const run = await atWork(w);
    w.agents.steerLost.err = new Error("connection reset after the steer was taken");
    expect((await steer(w, { run: run.id, text: "only the €20 one", client_message_id: ID })).status).toBeGreaterThanOrEqual(500);
    const again = await steer(w, { run: run.id, text: "only the €20 one", client_message_id: ID });
    expect(again).toMatchObject({ status: 200, body: { run: run.id, repeat: true } });
    expect(w.agents.runs.find((r) => r.id === run.id)!.steering).toHaveLength(1);
  });

  it("another text under the same id is 409 dedup_key_conflict, and an id that started a run is too", async () => {
    const w = await world();
    const run = await atWork(w);
    await steer(w, { run: run.id, text: "only the €20 one", client_message_id: ID });
    const other = await steer(w, { run: run.id, text: "all of them", client_message_id: ID });
    expect(other.status).toBe(409);
    expect(other.body.code).toBe("dedup_key_conflict");
    // kiff/agents' own check, with tafs cloud's record gone.
    await w.coach.remove(`t_acme#msg-key#msg:u_ana:${ID}`);
    const unrecorded = await steer(w, { run: run.id, text: "all of them", client_message_id: ID });
    expect(unrecorded.status).toBe(409);
    expect(unrecorded.body.code).toBe("dedup_key_conflict");
    expect(w.agents.runs.find((r) => r.id === run.id)!.steering).toHaveLength(1);
    // An id a message started a run with isn't a write's.
    const ID2 = "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f";
    await send(w, RAE, { text: "Refund order 42", client_message_id: ID2 });
    const crossed = await steer(w, { run: run.id, text: "Refund order 42", client_message_id: ID2 });
    expect(crossed.status).toBe(409);
    expect(crossed.body.code).toBe("dedup_key_conflict");
  });

  it("refuses an id that isn't a UUID with 400, and without one writes as before", async () => {
    const w = await world();
    const run = await atWork(w);
    expect((await steer(w, { run: run.id, text: "only the €20 one", client_message_id: "nope" })).status).toBe(400);
    await steer(w, { run: run.id, text: "only the €20 one" });
    await steer(w, { run: run.id, text: "only the €20 one" });
    expect(w.agents.runs.find((r) => r.id === run.id)!.steering).toHaveLength(2);
  });
});
