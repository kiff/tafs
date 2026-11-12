import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { type CoachCall, Rich } from "../src/cloud/coach.js";
import { askApp, CONTINUE_SAID, currentDrive, DRIVE, driveArgs, DriveError, drivePaused, driveRecaps, DriveRefusal, driveSteps, driveStream, endDrive, END_SAID, END_WHY, END_WORDS, PAUSED_SAID, pauseDrive, putResult, resultOf, resumeDrive, startDrive } from "../src/cloud/drive.js";
import { coachTool, type Deps, handle, type HTTPEvent, type Taf } from "../src/cloud/handler.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryCoach, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

/**
 * Coach drives the app (kiff/tafs#544, docs/drive.md): the person approves one request with
 * the approval key; while the drive is on, Coach's looks and steps reach their phone through
 * the drive stream and come back with the screen. It ends, and never resumes, when the owner
 * touches the app, leaves it, stops it, or it sits idle; a halt ends it too.
 */

const T = "t_acme";
const ANA = "u_ana";
const NOW = new Date("2026-11-06T00:30:00Z");
const PNG = Buffer.from("not really a png, but base64").toString("base64");

/** A clock the held call moves forward, and a phone that answers each request the stream hands it. */
function rig() {
  let clock = NOW;
  const store = memoryCoach(() => clock);
  const now = () => clock;
  let phone: ((r: { id: string; tool: string; args: Record<string, unknown> }, drive: string) => Record<string, unknown> | undefined) | undefined;
  const wait = async (ms: number) => {
    clock = new Date(clock.getTime() + ms);
    if (!phone) return;
    const d = await currentDrive(store, T, ANA, clock);
    if (!d || d.ended) return;
    for (const r of (await store.take(`${T}#drive-q#${d.id}`)) as Array<{ id: string; tool: string; args: Record<string, unknown> }>) {
      const answer = phone(r, d.id);
      if (answer) await putResult(store, T, ANA, d.id, r.id, answer, clock);
    }
  };
  return { store, now, wait, at: (d: Date) => { clock = d; }, answers: (f: typeof phone) => { phone = f; } };
}

describe("a drive, on its own", () => {
  it("starts for one request, and a second start ends the first as replaced", async () => {
    const r = rig();
    const a = await startDrive(r.store, T, ANA, "s_1", "Set up Refunds", r.now());
    expect(a).toMatchObject({ person: ANA, session: "s_1", task: "Set up Refunds" });
    const b = await startDrive(r.store, T, ANA, "s_1", "Turn off suggestions", r.now());
    expect(b.id).not.toBe(a.id);
    expect((await currentDrive(r.store, T, ANA, r.now()))?.id).toBe(b.id);
    await expect(startDrive(r.store, T, ANA, "s_1", "", r.now())).rejects.toBeInstanceOf(DriveError);
  });

  it("sends Coach's step to the phone and holds for the screen after it", async () => {
    const r = rig();
    await startDrive(r.store, T, ANA, "s_1", "Run Chargebacks", r.now());
    const seen: unknown[] = [];
    r.answers((req) => {
      seen.push(req);
      return { outcome: "done", line: "Tapped Run now", image: { media_type: "image/png", data: PNG }, touched: { words: "Run now", taf: "Chargebacks" } };
    });
    const a = await askApp(r.store, T, ANA, "app_act", { action: "tap", line: "Tapping Run now", at: { x: 120.4, y: 980 } }, r.now, r.wait);
    expect(seen).toMatchObject([{ tool: "app_act", args: { action: "tap", line: "Tapping Run now", at: { x: 120, y: 980 } } }]);
    expect(a).toMatchObject({ result: { outcome: "done", line: "Tapped Run now", image: { media_type: "image/png" } } });
  });

  it("answers at once when no drive is on, or it ended, and says why", async () => {
    const r = rig();
    expect(await askApp(r.store, T, ANA, "app_look", {}, r.now, r.wait)).toMatchObject({ off: expect.stringContaining("no drive is on") });
    const d = await startDrive(r.store, T, ANA, "s_1", "Run Chargebacks", r.now());
    await endDrive(r.store, T, ANA, "owner_took_over", r.now(), d.id);
    expect(await askApp(r.store, T, ANA, "app_look", {}, r.now, r.wait)).toMatchObject({ ended: { why: "owner_took_over" } });
  });

  it("ends a held call as soon as the owner takes over, and never resumes", async () => {
    const r = rig();
    const d = await startDrive(r.store, T, ANA, "s_1", "Run Chargebacks", r.now());
    let polls = 0;
    const wait = async (ms: number) => {
      await r.wait(ms);
      if (++polls === 3) await endDrive(r.store, T, ANA, "owner_took_over", r.now(), d.id);
    };
    expect(await askApp(r.store, T, ANA, "app_look", {}, r.now, wait)).toMatchObject({ ended: { why: "owner_took_over" } });
    expect((await currentDrive(r.store, T, ANA, r.now()))?.ended?.why).toBe("owner_took_over");
    await expect(putResult(r.store, T, ANA, d.id, "rq_1", { outcome: "done", line: "late" }, r.now())).rejects.toMatchObject({ status: 409 });
  });

  it("gives up on a phone that doesn't answer in 20 seconds", async () => {
    const r = rig();
    await startDrive(r.store, T, ANA, "s_1", "Run Chargebacks", r.now());
    const started = r.now().getTime();
    expect(await askApp(r.store, T, ANA, "app_look", {}, r.now, r.wait)).toEqual({ timedOut: true });
    expect(r.now().getTime() - started).toBeGreaterThanOrEqual(DRIVE.holdMs);
  });

  it("ends when nothing is done for 15 minutes", async () => {
    const r = rig();
    await startDrive(r.store, T, ANA, "s_1", "Run Chargebacks", r.now());
    r.at(new Date(NOW.getTime() + DRIVE.idleMs + 1000));
    expect((await currentDrive(r.store, T, ANA, r.now()))?.ended?.why).toBe("idle");
  });

  it("takes only another phone's answer for its own drive", async () => {
    const r = rig();
    await startDrive(r.store, T, ANA, "s_1", "Run Chargebacks", r.now());
    await expect(putResult(r.store, T, ANA, "someone-elses", "rq_1", { outcome: "done", line: "x" }, r.now())).rejects.toMatchObject({ status: 404 });
    await expect(putResult(r.store, T, "u_bo", "x", "rq_1", { outcome: "done", line: "x" }, r.now())).rejects.toMatchObject({ status: 404 });
  });

  it("streams each request to the phone, then ended with why", async () => {
    const r = rig();
    const d = await startDrive(r.store, T, ANA, "s_1", "Run Chargebacks", r.now());
    await r.store.append(`${T}#drive-q#${d.id}`, { id: "rq_1", tool: "app_look", args: {}, at: NOW.toISOString() }, new Date(NOW.getTime() + 60_000));
    const out: string[] = [];
    let polls = 0;
    for await (const chunk of driveStream(r.store, T, ANA, d.id, r.now, async (ms) => {
      await r.wait(ms);
      if (++polls === 2) await endDrive(r.store, T, ANA, "left_app", r.now(), d.id);
    })) out.push(chunk);
    expect(out[0]).toBe(`event: drive\ndata: ${JSON.stringify({ drive: d.id })}\n\n`);
    expect(out[1]).toContain("event: request");
    expect(out[1]).toContain("\"rq_1\"");
    expect(out.at(-1)).toBe(`event: ended\ndata: ${JSON.stringify({ why: "left_app", words: "The app left the screen, so Coach stopped" })}\n\n`);
  });

  it("tells the person each end in their own words, as docs/drive.md lists them, and Coach in its own", () => {
    const doc = readFileSync(new URL("../../docs/drive.md", import.meta.url), "utf8");
    for (const why of END_WHY) {
      expect(doc).toContain(`| \`${why}\` | ${END_SAID[why]} |`);
      expect(END_SAID[why]).not.toBe(END_WORDS[why]);
      expect(END_SAID[why]).not.toMatch(/\bthe owner\b/i);
    }
  });

  it("checks Coach's arguments: a point or an element, a caption, a text only for type", () => {
    expect(() => driveArgs("app_act", { action: "tap", at: { x: 1, y: 2 } })).toThrow(DriveRefusal);
    expect(() => driveArgs("app_act", { action: "tap", line: "Tapping", at: { x: 1, y: 2 }, element: "e1" })).toThrow(/not both/);
    expect(() => driveArgs("app_act", { action: "approve", line: "x" })).toThrow(/action is one of/);
    expect(() => driveArgs("app_look", { wait: "anything" })).toThrow(/wait is one of/);
    expect(driveArgs("app_act", { action: "type", line: "Typing the limit", text: "50", at: { x: 1, y: 1 } })).toEqual({ action: "type", line: "Typing the limit", text: "50" });
    expect(driveArgs("app_look", { tree: true, extra: "dropped" })).toEqual({ tree: true });
  });

  it("checks the phone's answer: an image within its size, of an image type", () => {
    expect(() => resultOf({ outcome: "maybe", line: "x" })).toThrow(DriveError);
    expect(() => resultOf({ outcome: "done", line: "x", image: { media_type: "image/gif", data: PNG } })).toThrow(/image is/);
    const big = "A".repeat(Math.ceil((DRIVE.imageMax + 10) * 4 / 3));
    expect(() => resultOf({ outcome: "done", line: "x", image: { media_type: "image/jpeg", data: big } })).toThrow(/at most/);
    expect(resultOf({ outcome: "refused", line: "That's yours to confirm", secret: "dropped" })).toEqual({ outcome: "refused", line: "That's yours to confirm" });
  });
});

// The handler: the phone's routes and Coach's tool.

const ME = { tenant_id: T, subject: ANA, api_key: false, capabilities: ["read", "cards.manage"] };
const COACH = "taf-cccc0000000000000000";

function net(signed: { key?: string; window?: unknown }) {
  return (async (url: string, init?: RequestInit) => {
    const headers = (init?.headers ?? {}) as Record<string, string>;
    if (String(headers.authorization ?? "") !== "Bearer ana") return new Response("{}", { status: 401 });
    if (url.endsWith("/v1/me")) return new Response(JSON.stringify(ME), { status: 200 });
    if (url.endsWith("/v1/me/device-signature")) return new Response(JSON.stringify({ signed: true, ...signed }), { status: 200 });
    return new Response("{}", { status: 404 });
  }) as typeof fetch;
}

async function world(signed: { key?: string; window?: unknown } = { key: "critical" }) {
  let clock = NOW;
  const agents = fakeAgents(() => clock);
  const tafs = memoryTafs();
  const taf: Taf = { id: COACH, name: "Coach", job: "setup", kiffAgent: "setup-acme", nameClaim: "c", created: "2026-11-01T09:00:00Z" };
  tafs.held.set(`${T}#${COACH}`, taf);
  await agents.api.putAgent(T, COACH, {} as never);
  agents.access.set(ANA, { cards_manage: true });
  const run = await agents.api.startRun(T, COACH, "Show me how to run Chargebacks", { requester: { kind: "member", id: ANA } });
  const stored = agents.runs.find((r) => r.id === run.id)!;
  agents.ask(stored, { type: "drive", id: "d1", text: "Run Chargebacks" });
  /** Coach's run at work, as it is while it calls a tool. */
  const working = () => { stored.status = "RUNNING"; };
  const coach = memoryCoach(() => clock);
  const events: Array<Record<string, unknown>> = [];
  const deps: Deps = {
    agents: agents.api, keys: memoryKeys(), accounts: memoryAccounts(), tafs, names: memoryNames(), slots: memorySlots(), claims: memoryClaims(),
    removals: memoryRemovals(), archived: memoryArchived(),
    push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async () => "sent" as const }, forward: async () => undefined,
    fetch: net(signed), now: () => clock, coach, kiffBase: "https://api.test",
    events: { add: async (_t, e) => { events.push(e as Record<string, unknown>); }, list: async () => [] },
    sleep: async (ms) => { clock = new Date(clock.getTime() + ms); },
  };
  const call = async (method: string, path: string, body?: unknown, headers: Record<string, string> = {}) => {
    const event: HTTPEvent = {
      rawPath: path, requestContext: { http: { method } },
      headers: { authorization: "Bearer ana", ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
    };
    const r = await handle(event, deps);
    return { status: r.statusCode, body: JSON.parse(r.body) as Record<string, unknown> };
  };
  const told = () => (stored.requirements ?? []).find((q) => (q.input as { id?: string }).id === "d1")?.result as { answer?: unknown } | undefined;
  const coachCall: CoachCall = { tenant: T, coach: "setup-acme", notify: () => ({ url: "https://tafs.test/v1/runs/ended", ref: "r" }), person: { subject: ANA, permit: "p" } };
  return { agents, deps, coach, call, told, events, coachCall, session: stored.session, working, stored };
}

const SIGNED = { "kiff-device-signature": "t=2026-11-06T00:30:00Z,v1=sig" };

describe("a drive, from the phone", () => {
  it("starts only with the approval key, and Coach's drive card hears it's on", async () => {
    const w = await world();
    const r = await w.call("POST", "/v1/coach/drive", { session: w.session, task: "Run Chargebacks", component: "d1" }, SIGNED);
    expect(r.status).toBe(200);
    expect(r.body).toMatchObject({ drive: { session: w.session, task: "Run Chargebacks" } });
    expect(w.told()?.answer).toBe("approved: the drive is on");
    expect(w.events).toEqual([{ kind: "drive_started", title: "Coach started driving", detail: "Run Chargebacks" }]);
  });

  it("refuses the device key, a window's tap, and no signature: nothing starts", async () => {
    for (const signed of [{ key: "device" }, { key: "critical", window: { id: "w", open: true } }, {}]) {
      const w = await world(signed);
      const r = await w.call("POST", "/v1/coach/drive", { session: w.session, task: "Run Chargebacks", component: "d1" }, SIGNED);
      expect(r.status, JSON.stringify(signed)).toBe(403);
      expect(r.body.code).toBe("critical_key_required");
      expect(await currentDrive(w.coach, T, ANA, NOW)).toBeUndefined();
      expect(w.told()).toBeUndefined();
    }
    const w = await world();
    expect((await w.call("POST", "/v1/coach/drive", { session: w.session, task: "x" })).status).toBe(403);
  });

  it("never takes a drive card's approval as a plain answer; declining is one", async () => {
    const w = await world();
    const approve = await w.call("POST", `/v1/tafs/${COACH}/components/d1/answer`, { answer: "approved: the drive is on" });
    expect(approve.status).toBe(400);
    expect(w.told()).toBeUndefined();
    const decline = await w.call("POST", `/v1/tafs/${COACH}/components/d1/answer`, { answer: "declined" });
    expect(decline.status).toBe(200);
    expect(w.told()?.answer).toBe("declined");
  });

  it("stops when the owner takes over, once, and says so to Coach's next call", async () => {
    const w = await world();
    const started = await w.call("POST", "/v1/coach/drive", { session: w.session, task: "Run Chargebacks" }, SIGNED);
    const id = (started.body.drive as { id: string }).id;
    const stop = await w.call("POST", "/v1/coach/drive/stop", { drive: id, why: "owner_took_over" });
    expect(stop.body).toMatchObject({ ended: { why: "owner_took_over", words: "You took over, so Coach let you carry on" } });
    expect(w.events.at(-1)).toMatchObject({ kind: "drive_ended", detail: "the owner took over, so Coach is letting them carry on" });
    expect((await w.call("POST", "/v1/coach/drive/stop", { drive: id, why: "stopped" })).body).toMatchObject({ ended: { why: "owner_took_over" } });
    w.working();
    const a = await coachTool("app_look", {}, w.coachCall, w.deps) as Rich;
    expect(a.isError).toBe(true);
    expect(a.content[0]).toMatchObject({ type: "text", text: expect.stringContaining("the owner took over") });
    expect((await w.call("POST", "/v1/coach/drive/stop", { drive: id, why: "replaced" })).status).toBe(400);
    const read = await w.call("GET", "/v1/coach/drive");
    expect(read.body.drive).toMatchObject({ id, ended: { why: "owner_took_over", words: "You took over, so Coach let you carry on" } });
    expect(read.body.drive).not.toHaveProperty("person");
  });
});

describe("Coach's drive tools", () => {
  it("answer with the app's line and the screen as an image; a retry gets the words only", async () => {
    const w = await world();
    w.working();
    const d = await startDrive(w.coach, T, ANA, w.session, "Run Chargebacks", NOW);
    w.deps.sleep = async () => {
      for (const req of (await w.coach.take(`${T}#drive-q#${d.id}`)) as Array<{ id: string }>) {
        await w.call("POST", `/v1/coach/drive/${d.id}/results/${req.id}`, { outcome: "done", line: "Tapped Run now", image: { media_type: "image/png", data: PNG }, touched: { words: "Run now", taf: "Chargebacks" } });
      }
    };
    const a = await coachTool("app_act", { action: "tap", line: "Tapping Run now", at: { x: 10, y: 20 } }, w.coachCall, w.deps) as Rich;
    expect(a).toBeInstanceOf(Rich);
    expect(a.content).toEqual([{ type: "text", text: "Tapped Run now" }, { type: "image", data: PNG, mimeType: "image/png" }]);
    expect(a.kept).not.toContain(PNG);
    expect(w.events).toEqual([{ kind: "drive_step", title: "Coach: Tapping Run now", detail: "Run now · Chargebacks", name: "Chargebacks" }]);
  });

  it("pass on the app's refusal of a confirming control, as a refusal", async () => {
    const w = await world();
    w.working();
    const d = await startDrive(w.coach, T, ANA, w.session, "Approve it", NOW);
    w.deps.sleep = async () => {
      for (const req of (await w.coach.take(`${T}#drive-q#${d.id}`)) as Array<{ id: string }>) {
        await w.call("POST", `/v1/coach/drive/${d.id}/results/${req.id}`, { outcome: "refused", line: "That's yours to confirm" });
      }
    };
    const a = await coachTool("app_act", { action: "tap", line: "Tapping Approve", at: { x: 10, y: 20 } }, w.coachCall, w.deps) as Rich;
    expect(a.isError).toBe(true);
    expect(a.content[0]).toMatchObject({ text: "Refused by the app: That's yours to confirm. Nothing was done." });
    expect(w.events).toEqual([]);
  });

  it("are a person's: refused in Coach's own name, and ended by a halt", async () => {
    const w = await world();
    const { person: _p, ...asCoach } = w.coachCall;
    expect((await coachTool("app_look", {}, asCoach, w.deps) as Rich).content[0]).toMatchObject({ text: expect.stringContaining("A drive is a person's") });
    w.working();
    await startDrive(w.coach, T, ANA, w.session, "Run Chargebacks", NOW);
    await w.agents.api.halt(T);
    const a = await coachTool("app_look", {}, w.coachCall, w.deps) as Rich;
    expect(a.content[0]).toMatchObject({ text: expect.stringContaining("the account is halted") });
    expect((await currentDrive(w.coach, T, ANA, NOW))?.ended?.why).toBe("halted");
  });

  it("end the drive with done, and the record says how many steps it took", async () => {
    const w = await world();
    w.working();
    const d = await startDrive(w.coach, T, ANA, w.session, "Run Chargebacks", NOW);
    w.deps.sleep = async () => {
      for (const req of (await w.coach.take(`${T}#drive-q#${d.id}`)) as Array<{ id: string }>) {
        await w.call("POST", `/v1/coach/drive/${d.id}/results/${req.id}`, { outcome: "done", line: "Done" });
      }
    };
    await coachTool("app_act", { action: "done", line: "Done" }, w.coachCall, w.deps);
    expect((await currentDrive(w.coach, T, ANA, NOW))?.ended?.why).toBe("done");
    expect(w.events.at(-1)).toMatchObject({ kind: "drive_ended", title: "Coach finished driving" });
  });
});

// qa-kiff's negative cases on #554.
describe("a drive's end holds, and its record is the app's", () => {
  it("N1: an end that lands while Coach's call is being sent stays an end", async () => {
    const r = rig();
    const d = await startDrive(r.store, T, ANA, "s_1", "Run Chargebacks", r.now());
    // The owner's stop lands just after the call read the drive: the call's own writes never touch the drive record.
    const store = { ...r.store, async put(key: string, value: unknown, at: Date) {
      if (key.startsWith(`${T}#drive-last#`)) await endDrive(r.store, T, ANA, "owner_took_over", r.now(), d.id);
      return r.store.put(key, value, at);
    } };
    expect(await askApp(store, T, ANA, "app_look", {}, r.now, r.wait)).toMatchObject({ ended: { why: "owner_took_over" } });
    expect((await currentDrive(r.store, T, ANA, r.now()))?.ended?.why).toBe("owner_took_over");
  });

  it("N2: an end that lands while the phone's answer is being kept stays an end", async () => {
    const r = rig();
    const d = await startDrive(r.store, T, ANA, "s_1", "Run Chargebacks", r.now());
    await askApp(r.store, T, ANA, "app_look", {}, r.now, async (ms) => { await r.wait(ms); });
    await r.store.put(`${T}#drive-rq#${d.id}#rq_2`, { tool: "app_look" }, new Date(NOW.getTime() + 60_000));
    const store = { ...r.store, async put(key: string, value: unknown, at: Date) {
      if (key.startsWith(`${T}#drive-r#`)) await endDrive(r.store, T, ANA, "owner_took_over", r.now(), d.id);
      return r.store.put(key, value, at);
    } };
    await putResult(store, T, ANA, d.id, "rq_2", { outcome: "done", line: "Looked" }, r.now());
    expect((await currentDrive(r.store, T, ANA, r.now()))?.ended?.why).toBe("owner_took_over");
  });

  it("N3: a step the app did after the 20-second hold is still in the record", async () => {
    const w = await world();
    w.working();
    const d = await startDrive(w.coach, T, ANA, w.session, "Run Chargebacks", NOW);
    const a = await coachTool("app_act", { action: "tap", line: "Tapping Run now", at: { x: 10, y: 20 } }, w.coachCall, w.deps) as Rich;
    expect(a.content[0]).toMatchObject({ text: expect.stringContaining("didn't answer in 20 seconds") });
    const [req] = (await w.coach.take(`${T}#drive-q#${d.id}`)) as Array<{ id: string }>;
    const late = await w.call("POST", `/v1/coach/drive/${d.id}/results/${req!.id}`, { outcome: "done", line: "Tapped Run now", touched: { words: "Run now", taf: "Chargebacks" } });
    expect(late.status).toBe(200);
    expect((await w.call("GET", "/v1/coach/drive")).body.steps).toMatchObject([{ line: "Tapping Run now", touched: { words: "Run now", taf: "Chargebacks" } }]);
    expect(w.events.at(-1)).toMatchObject({ kind: "drive_step", title: "Coach: Tapping Run now" });
  });

  it("N4: a start for a drive card that isn't there starts nothing", async () => {
    const w = await world();
    const r = await w.call("POST", "/v1/coach/drive", { session: w.session, task: "Run Chargebacks", component: "nope" }, SIGNED);
    expect(r.status).toBe(404);
    expect(await currentDrive(w.coach, T, ANA, NOW)).toBeUndefined();
    expect(w.events).toEqual([]);
  });

  it("an answer for a request this drive never sent is refused", async () => {
    const r = rig();
    const d = await startDrive(r.store, T, ANA, "s_1", "Run Chargebacks", r.now());
    await expect(putResult(r.store, T, ANA, d.id, "made_up", { outcome: "done", line: "x" }, r.now())).rejects.toMatchObject({ status: 404 });
  });

  it("only the chat that approved the drive drives: another chat of the same person is refused", async () => {
    const w = await world();
    await startDrive(w.coach, T, ANA, "another-chat", "Run Chargebacks", NOW);
    w.working();
    const a = await coachTool("app_look", {}, w.coachCall, w.deps) as Rich;
    expect(a.content[0]).toMatchObject({ text: expect.stringContaining("approved in another chat") });
    expect(await w.coach.get(`${T}#drive-q#${(await currentDrive(w.coach, T, ANA, NOW))!.id}`)).toBeUndefined();
  });
});

// agentgabo's points on #554.
describe("a request Coach gave up on never runs, and done always ends", () => {
  it("the stream never sends a request Coach has given up on, and gives the app the time left to start the others", async () => {
    const r = rig();
    const d = await startDrive(r.store, T, ANA, "s_1", "Run Chargebacks", r.now());
    const until = new Date(NOW.getTime() + 60_000);
    await r.store.append(`${T}#drive-q#${d.id}`, { id: "stale", tool: "app_act", args: {}, at: new Date(NOW.getTime() - DRIVE.holdMs).toISOString() }, until);
    await r.store.append(`${T}#drive-q#${d.id}`, { id: "nearly", tool: "app_act", args: {}, at: new Date(NOW.getTime() - DRIVE.holdMs + 1_000).toISOString() }, until);
    await r.store.append(`${T}#drive-q#${d.id}`, { id: "fresh", tool: "app_act", args: {}, at: new Date(NOW.getTime() - 5_000).toISOString() }, until);
    const out: string[] = [];
    let polls = 0;
    for await (const chunk of driveStream(r.store, T, ANA, d.id, r.now, async (ms) => {
      await r.wait(ms);
      if (++polls === 1) await endDrive(r.store, T, ANA, "stopped", r.now(), d.id);
    })) out.push(chunk);
    const sent = out.filter((c) => c.startsWith("event: request")).map((c) => JSON.parse(c.split("data: ")[1]!) as { id: string; start_within_ms: number });
    expect(sent.map((x) => x.id)).toEqual(["fresh"]);
    expect(sent[0]!.start_within_ms).toBe(DRIVE.holdMs - 5_000 - 2_000);
  });

  it("Coach's done ends the drive even when the app refuses it or doesn't answer", async () => {
    const w = await world();
    w.working();
    const d = await startDrive(w.coach, T, ANA, w.session, "Run Chargebacks", NOW);
    await coachTool("app_act", { action: "done", line: "Done" }, w.coachCall, w.deps);
    expect((await currentDrive(w.coach, T, ANA, NOW))).toMatchObject({ id: d.id, ended: { why: "done" } });
    expect(w.events.filter((e) => e.kind === "drive_ended")).toHaveLength(1);
  });

  it("a start without a card names one of the person's own chats with Coach", async () => {
    const w = await world();
    const r = await w.call("POST", "/v1/coach/drive", { session: "not-theirs", task: "Run Chargebacks" }, SIGNED);
    expect(r.status).toBe(404);
    expect(await currentDrive(w.coach, T, ANA, NOW)).toBeUndefined();
  });
});

// kiff/agents names the chat on Coach's calls (SESSION_ARG): exact, with no need of the runs at work.
describe("a drive's chat, named on the call", () => {
  it("lets the drive's own chat drive, and refuses another, without reading the runs", async () => {
    const w = await world();
    const d = await startDrive(w.coach, T, ANA, w.session, "Run Chargebacks", NOW);
    w.deps.sleep = async () => {
      for (const req of (await w.coach.take(`${T}#drive-q#${d.id}`)) as Array<{ id: string }>) {
        await w.call("POST", `/v1/coach/drive/${d.id}/results/${req.id}`, { outcome: "done", line: "Looked" });
      }
    };
    // Coach's run isn't at work in the fake (it's paused on its card): the named chat is enough.
    const own = await coachTool("app_look", {}, { ...w.coachCall, session: w.session }, w.deps) as Rich;
    expect(own.content[0]).toMatchObject({ text: "Looked" });
    const other = await coachTool("app_look", {}, { ...w.coachCall, session: "another-chat" }, w.deps) as Rich;
    expect(other).toMatchObject({ isError: true });
    expect(other.content[0]).toMatchObject({ text: expect.stringContaining("approved in another chat") });
  });
});

// kiff/tafs#568: a drive's end is a message in Coach's chat, from tafs cloud's record, and a step is recorded once.
describe("a drive's end in Coach's chat", () => {
  it("records a step again under the same caption once, where it ended", async () => {
    const r = rig();
    const d = await startDrive(r.store, T, ANA, "s_1", "Open Versions", r.now());
    const act = async (id: string, line: string, words: string) => {
      await r.store.put(`${T}#drive-rq#${d.id}#${id}`, { tool: "app_act", action: "tap", line }, new Date(NOW.getTime() + 60_000));
      await putResult(r.store, T, ANA, d.id, id, { outcome: "done", line: `Tapped ${words}`, touched: { words } }, r.now());
    };
    // "Looking for Versions" in Setup, then in Card (the owner's phone, 6 Nov): one line, which keeps both touches.
    await act("rq1", "Looking for Versions", "Setup");
    await act("rq2", "Looking for Versions", "Card");
    await act("rq2b", "Looking for Versions", "Card");
    await act("rq3", "Opening Versions", "Versions");
    const steps = await driveSteps(r.store, T, d.id);
    expect(steps.map((x) => x.line)).toEqual(["Looking for Versions", "Opening Versions"]);
    expect(steps[0]!.touched?.words).toBe("Setup, Card");
    // The same caption after another step is a step of its own.
    await act("rq4", "Looking for Versions", "Settings");
    expect((await driveSteps(r.store, T, d.id)).map((x) => x.line)).toEqual(["Looking for Versions", "Opening Versions", "Looking for Versions"]);
  });

  it("keeps the end once, with the request, why and the steps, and Coach's thread shows it at its time", async () => {
    const w = await world();
    const started = await w.call("POST", "/v1/coach/drive", { session: w.session, task: "Run Chargebacks" }, SIGNED);
    const id = (started.body.drive as { id: string }).id;
    await w.coach.put(`${T}#drive-rq#${id}#rq1`, { tool: "app_act", action: "tap", line: "Opening Chargebacks" }, new Date(NOW.getTime() + 60_000));
    await w.call("POST", `/v1/coach/drive/${id}/results/rq1`, { outcome: "done", line: "Tapped Chargebacks", touched: { words: "Chargebacks" } });
    await w.call("POST", "/v1/coach/drive/stop", { drive: id, why: "owner_took_over" });
    // A second end changes nothing, and writes no second recap.
    await w.call("POST", "/v1/coach/drive/stop", { drive: id, why: "stopped" });
    const recaps = await driveRecaps(w.coach, T, w.session);
    expect(recaps).toHaveLength(1);
    expect(recaps[0]).toMatchObject({ id, session: w.session, task: "Run Chargebacks", why: "owner_took_over", words: "You took over, so Coach let you carry on",
      steps: [{ line: "Opening Chargebacks", touched: { words: "Chargebacks" } }], at: NOW.toISOString() });
    // Another chat of the same person has none.
    expect(await driveRecaps(w.coach, T, "s_other")).toEqual([]);
    const thread = await w.call("GET", `/v1/tafs/${COACH}/conversation`);
    const items = thread.body.items as Array<{ type: string; card?: { component: { type?: string } }; drive?: Record<string, unknown> }>;
    const card = items.findIndex((i) => i.type === "card" && i.card?.component.type === "drive");
    const end = items.findIndex((i) => i.type === "drive");
    expect(card).toBeGreaterThanOrEqual(0);
    expect(end).toBeGreaterThan(card);
    expect(items[end]!.drive).toMatchObject({ id, why: "owner_took_over", words: "You took over, so Coach let you carry on", task: "Run Chargebacks" });
  });
});

describe("a touch pauses the drive (docs/drive.md, When the person touches the app)", () => {
  it("sends nothing while paused, drops what waited, and counts Coach's calls in the pause", async () => {
    const r = rig();
    const d = await startDrive(r.store, T, ANA, "s_1", "Run Chargebacks", r.now());
    await r.store.append(`${T}#drive-q#${d.id}`, { id: "rq_old", tool: "app_look", args: {}, at: NOW.toISOString() }, new Date(NOW.getTime() + 60_000));
    await pauseDrive(r.store, T, ANA, d.id, r.now());
    expect(await drivePaused(r.store, T, d.id)).toBe(NOW.toISOString());
    expect(await r.store.take(`${T}#drive-q#${d.id}`)).toEqual([]);
    const seen: unknown[] = [];
    r.answers((req) => { seen.push(req); return { outcome: "done", line: "Looked" }; });
    expect(await askApp(r.store, T, ANA, "app_look", {}, r.now, r.wait)).toEqual({ paused: NOW.toISOString(), calls: 1 });
    expect(await askApp(r.store, T, ANA, "app_act", { action: "tap", line: "Tapping Run now", at: { x: 1, y: 2 } }, r.now, r.wait)).toEqual({ paused: NOW.toISOString(), calls: 2 });
    expect(seen).toEqual([]);
  });

  it("after Continue, refuses an act until Coach has looked, then lets it act", async () => {
    const r = rig();
    const d = await startDrive(r.store, T, ANA, "s_1", "Run Chargebacks", r.now());
    await pauseDrive(r.store, T, ANA, d.id, r.now());
    await resumeDrive(r.store, T, ANA, d.id, r.now());
    expect(await drivePaused(r.store, T, d.id)).toBeUndefined();
    const seen: string[] = [];
    r.answers((req) => { seen.push(req.tool); return { outcome: "done", line: req.tool === "app_look" ? "Looked" : "Tapped Run now" }; });
    const act = { action: "tap", line: "Tapping Run now", at: { x: 1, y: 2 } };
    expect(await askApp(r.store, T, ANA, "app_act", act, r.now, r.wait)).toEqual({ lookFirst: true });
    expect(seen).toEqual([]);
    expect(await askApp(r.store, T, ANA, "app_look", {}, r.now, r.wait)).toMatchObject({ result: { line: "Looked" } });
    expect(await askApp(r.store, T, ANA, "app_act", act, r.now, r.wait)).toMatchObject({ result: { line: "Tapped Run now" } });
    expect(seen).toEqual(["app_look", "app_act"]);
  });

  it("a second pause or a Continue with no pause changes nothing; only the drive's person, and only that drive", async () => {
    const r = rig();
    const d = await startDrive(r.store, T, ANA, "s_1", "Run Chargebacks", r.now());
    await pauseDrive(r.store, T, ANA, d.id, r.now());
    r.at(new Date(NOW.getTime() + 5_000));
    await pauseDrive(r.store, T, ANA, d.id, r.now());
    expect(await drivePaused(r.store, T, d.id)).toBe(NOW.toISOString());
    await resumeDrive(r.store, T, ANA, d.id, r.now());
    await resumeDrive(r.store, T, ANA, d.id, r.now());
    expect(await r.store.get(`${T}#drive-look#${d.id}`)).toBeTruthy();
    await expect(pauseDrive(r.store, T, "u_bo", d.id, r.now())).rejects.toMatchObject({ status: 404 });
    await expect(resumeDrive(r.store, T, ANA, "another", r.now())).rejects.toMatchObject({ status: 404 });
  });

  it("a call already waiting when the pause lands is told the person has the app, not that the phone didn't answer", async () => {
    const r = rig();
    const d = await startDrive(r.store, T, ANA, "s_1", "Run Chargebacks", r.now());
    let polls = 0;
    const wait = async (ms: number) => {
      await r.wait(ms);
      if (++polls === 2) await pauseDrive(r.store, T, ANA, d.id, r.now());
    };
    const started = r.now().getTime();
    const a = await askApp(r.store, T, ANA, "app_look", {}, r.now, wait);
    expect(a).toMatchObject({ calls: 1 });
    expect("paused" in a).toBe(true);
    expect(r.now().getTime() - started).toBeLessThan(DRIVE.holdMs);
  });

  it("a pause counts as nothing done: 15 minutes paused ends the drive as idle", async () => {
    const r = rig();
    const d = await startDrive(r.store, T, ANA, "s_1", "Run Chargebacks", r.now());
    await pauseDrive(r.store, T, ANA, d.id, r.now());
    r.at(new Date(NOW.getTime() + 60_000));
    await askApp(r.store, T, ANA, "app_look", {}, r.now, r.wait);
    r.at(new Date(NOW.getTime() + DRIVE.idleMs + 1000));
    expect((await currentDrive(r.store, T, ANA, r.now()))?.ended?.why).toBe("idle");
  });

  it("the stream ends a drive at once when the account is halted, paused or not", async () => {
    const r = rig();
    const d = await startDrive(r.store, T, ANA, "s_1", "Run Chargebacks", r.now());
    await pauseDrive(r.store, T, ANA, d.id, r.now());
    const out: string[] = [];
    for await (const c of driveStream(r.store, T, ANA, d.id, r.now, r.wait, "en", async () => true)) out.push(c);
    expect(out.at(-1)).toContain("event: ended");
    expect(out.at(-1)).toContain('"why":"halted"');
    expect((await currentDrive(r.store, T, ANA, r.now()))?.ended?.why).toBe("halted");
  });

  it("from the phone: pause and resume are the drive's person's, the read says paused, and Continue starts Coach's turn", async () => {
    const w = await world();
    const started = await w.call("POST", "/v1/coach/drive", { session: w.session, task: "Run Chargebacks" }, SIGNED);
    const id = (started.body.drive as { id: string }).id;
    expect((await w.call("POST", "/v1/coach/drive/pause", { drive: "not-it" })).status).toBe(404);
    const pause = await w.call("POST", "/v1/coach/drive/pause", { drive: id });
    expect(pause.status).toBe(200);
    expect(pause.body).toEqual({ paused: NOW.toISOString() });
    expect((await w.call("GET", "/v1/coach/drive")).body.drive).toMatchObject({ id, paused: NOW.toISOString() });

    // Coach's first call is told to end its turn; nothing goes to the phone.
    w.working();
    const first = await coachTool("app_look", {}, w.coachCall, w.deps) as Rich;
    expect(first.isError).toBe(true);
    expect(first.content[0]).toMatchObject({ text: `${PAUSED_SAID} Nothing was sent.` });
    expect(w.stored.status).toBe("RUNNING");
    // A second call in the same pause: tafs cloud ends Coach's turn itself.
    const second = await coachTool("app_look", {}, w.coachCall, w.deps) as Rich;
    expect(second.content[0]).toMatchObject({ text: expect.stringContaining("tafs ended this turn") });
    expect(w.stored.status).toBe("CANCELLED");

    // Continue: Coach's next turn starts in the drive's chat, told to look first.
    const resume = await w.call("POST", "/v1/coach/drive/resume", { drive: id });
    expect(resume.status).toBe(200);
    expect(resume.body).toMatchObject({ resumed: true, coach: { session: w.session } });
    const next = w.agents.runs.at(-1)!;
    expect(next.session).toBe(w.session);
    const said = (await w.agents.api.session(T, w.session)).events.filter((e) => e.kind === "owner_message").at(-1)?.body as { text?: string } | undefined;
    expect(said?.text).toContain(CONTINUE_SAID);
    expect((await w.call("GET", "/v1/coach/drive")).body.drive).not.toHaveProperty("paused");
    expect((await w.call("POST", "/v1/coach/drive/resume", { drive: id })).body).toEqual({ resumed: false });
  });

  it("Continue while Coach's turn is still at work goes to that turn", async () => {
    const w = await world();
    const started = await w.call("POST", "/v1/coach/drive", { session: w.session, task: "Run Chargebacks" }, SIGNED);
    const id = (started.body.drive as { id: string }).id;
    await w.call("POST", "/v1/coach/drive/pause", { drive: id });
    w.working();
    const runs = w.agents.runs.length;
    const resume = await w.call("POST", "/v1/coach/drive/resume", { drive: id });
    expect(resume.body).toMatchObject({ resumed: true, coach: { run: w.stored.id } });
    expect(w.stored.steering?.at(-1)?.text).toBe(CONTINUE_SAID);
    expect(w.agents.runs.length).toBe(runs);
  });

  it("Continue on a halted account ends the drive as halted instead", async () => {
    const w = await world();
    const started = await w.call("POST", "/v1/coach/drive", { session: w.session, task: "Run Chargebacks" }, SIGNED);
    const id = (started.body.drive as { id: string }).id;
    await w.call("POST", "/v1/coach/drive/pause", { drive: id });
    await w.agents.api.halt(T);
    const resume = await w.call("POST", "/v1/coach/drive/resume", { drive: id });
    expect(resume.body).toMatchObject({ resumed: false, ended: { why: "halted" } });
    expect(w.events.at(-1)).toMatchObject({ kind: "drive_ended", detail: END_WORDS.halted });
  });
});
