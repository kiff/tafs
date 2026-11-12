import { randomBytes } from "node:crypto";
import type { CoachStore } from "./coach.js";
import { type Lang, wordsIn } from "./words/index.js";

/**
 * Coach drives the app (kiff/tafs#544, docs/drive.md): the person asks Coach to do
 * something in the app, or accepts Coach's suggestion, and approves that one request
 * with the approval key. While the drive is on, Coach's `app_look` and `app_act` reach
 * the person's phone through the drive stream, and the phone answers each with the
 * new screen. tafs cloud only carries them: what Coach may touch, and what it can
 * never confirm, the app decides; what Coach's calls may do, KIFF decides on Coach's
 * Cards.
 *
 * One drive per person. The person's own touch pauses it, and Continue resumes it, with a
 * look first (docs/drive.md, "When the person touches the app"). It ends, and never resumes,
 * when the person takes over, the app leaves the foreground, they stop it, the screen locks,
 * they sign out, the account is halted, Coach says the task is done, or nothing happens for
 * DRIVE.idleMs, a pause included. A tool call after the end is answered with why, at once.
 */

export const DRIVE = {
  /** The most a Coach tool call waits for the phone's answer. */
  holdMs: 20_000,
  /** How often a held call and the stream look at the store. */
  pollMs: 250,
  /**
   * How long a refusal with no screen waits for the person's pause to land: the phone refuses a request
   * it holds the moment the person touches the app, and that answer can reach tafs cloud before the pause does.
   */
  pauseGraceMs: 3_000,
  /** A drive with nothing done for this long ends. */
  idleMs: 15 * 60_000,
  /** The stream's own lifetime: the app opens it again after this (a function's limit is 15 minutes). */
  streamMs: 14 * 60_000,
  /** A comment line on the stream this often, so nothing between closes it as idle. */
  pingMs: 15_000,
  /** How long a drive's records are kept after their last use. */
  keptMs: 24 * 3600_000,
  /** A screenshot's most bytes, as the phone sends it (a JPEG about 1,100 pixels on its long side fits). */
  imageMax: 200_000,
  /** The view tree's most characters, as JSON. */
  treeMax: 60_000,
  /** A task's, a line's and a typed text's most characters. */
  textMax: 2_000,
  /** The steps kept for the "What I did" card. */
  stepsKept: 200,
  /** How long a drive's end stays in Coach's chat (its recap): as long as the chat's own messages are kept. */
  recapKeptMs: 30 * 24 * 3600_000,
  /** The recaps a chat keeps, newest last. */
  recapsKept: 50,
} as const;

/** Why a drive ended, as the app or tafs cloud says it. */
export const END_WHY = ["owner_took_over", "left_app", "stopped", "locked", "signed_out", "halted", "done", "idle", "replaced"] as const;
export type EndWhy = typeof END_WHY[number];

/** What Coach is told, and the record shows, for each end. The person is shown END_SAID. */
export const END_WORDS: Record<EndWhy, string> = {
  owner_took_over: "the owner took over, so Coach is letting them carry on",
  left_app: "the app left the screen, so the drive is over",
  stopped: "the owner stopped it",
  locked: "the phone locked",
  signed_out: "the owner signed out",
  halted: "the account is halted",
  done: "the task is done",
  idle: "nothing was done for 15 minutes",
  replaced: "another drive started",
};

/**
 * What the person is shown for each end, in their own words: the caption, the "What I did"
 * card and the stop's answer. The same words the apps keep for an end whose words didn't
 * come (docs/drive.md), so the caption reads the same either way.
 */
export const END_SAID: Record<EndWhy, string> = {
  owner_took_over: "You took over, so Coach let you carry on",
  left_app: "The app left the screen, so Coach stopped",
  stopped: "You stopped Coach",
  locked: "The phone locked, so Coach stopped",
  signed_out: "You signed out, so Coach stopped",
  halted: "The account is halted, so Coach stopped",
  done: "Coach is done",
  idle: "Nothing was done for 15 minutes, so Coach stopped",
  replaced: "Another drive started",
};

/** Why a drive ended, as the person reads it, in their language (END_SAID is the English). */
export function endSaid(why: EndWhy, lang: Lang = "en"): string {
  return wordsIn(lang)[`drive_end_${why}`];
}

export interface Drive {
  id: string;
  /** The person whose chat with Coach asked for it, and approved it: KIFF's subject. */
  person: string;
  /** Coach's chat (its kiff/agents session) the drive is for. */
  session: string;
  /** The request, in the person's words, as the approval named it. */
  task: string;
  started: string;
  /** When it started; the last thing done is kept apart (driveKeys.last), so only a start or an end ever writes this record. */
  last: string;
  ended?: { why: EndWhy; at: string };
}

/** Coach's two tools: a look (read-only) and an action. */
export const DRIVE_TOOLS = ["app_look", "app_act"] as const;
export type DriveTool = typeof DRIVE_TOOLS[number];

export const ACTIONS = ["tap", "long_press", "swipe", "scroll", "type", "back", "done"] as const;
export type Action = typeof ACTIONS[number];

export const WAIT_EVENTS = ["screen_shown", "sheet_opened", "sheet_closed", "refreshed", "prompt_shown", "prompt_done", "notification_shown"] as const;

/** One request to the phone, as the stream sends it. */
export interface DriveRequest {
  id: string;
  tool: DriveTool;
  args: Record<string, unknown>;
  at: string;
  /**
   * As sent on the stream: how long the app still has to start it, by its own clock. Past
   * that, Coach has given up on it and may have sent its next step: the app answers it
   * refused ("too late") and never does it (agentgabo on #554).
   */
  start_within_ms?: number;
}

/** A request this close to Coach giving up isn't sent: the app couldn't start it in time. */
export const SEND_MARGIN_MS = 2_000;

/** The phone's answer to one request, as checked. */
export interface DriveResult {
  /** done: it was done (or looked at); refused: the app refused it, in its own words (a confirming control, nothing under the point). */
  outcome: "done" | "refused";
  /** The app's line: what it did or why not ("Tapped Run now", "That's yours to confirm"). */
  line: string;
  /** The screen after it, as the app captured it (its own windows only, secret views blanked). */
  image?: { media_type: "image/jpeg" | "image/png"; data: string };
  /** The view tree, when asked, as the app built it. */
  tree?: unknown;
  /** The app's diagnostics, when asked. */
  diagnostics?: unknown;
  /** What it touched, in the words on screen, and the taf whose screen it was: the record's words. */
  touched?: { words: string; taf?: string };
  /** The event waited for, if any, and whether it came. */
  waited?: { event: string; came: boolean };
}

/** A refusal Coach reads: the drive isn't on, or the arguments don't fit. */
export class DriveRefusal extends Error {}

/** A request or answer the phone sent that doesn't fit, or a drive that isn't this phone's. */
export class DriveError extends Error {
  constructor(readonly status: number, message: string) { super(message); }
}

export const driveKeys = {
  /** The drive record: written by startDrive and endDrive only, so an end can never be overwritten (qa-kiff on #554). */
  drive: (tenant: string, person: string) => `${tenant}#drive#${person}`,
  /** When the drive was last used: for the idle end. */
  last: (tenant: string, drive: string) => `${tenant}#drive-last#${drive}`,
  queue: (tenant: string, drive: string) => `${tenant}#drive-q#${drive}`,
  /** Each request as sent (its tool and caption), so a late answer still makes its step. */
  request: (tenant: string, drive: string, request: string) => `${tenant}#drive-rq#${drive}#${request}`,
  result: (tenant: string, drive: string, request: string) => `${tenant}#drive-r#${drive}#${request}`,
  steps: (tenant: string, drive: string) => `${tenant}#drive-steps#${drive}`,
  /** Since when the person has had the app (their touch paused the drive): kept apart, so only a start or an end writes the drive. */
  paused: (tenant: string, drive: string) => `${tenant}#drive-paused#${drive}`,
  /** Coach's drive calls within this pause: the second ends Coach's turn from tafs cloud's side. */
  pausedCalls: (tenant: string, drive: string) => `${tenant}#drive-paused-calls#${drive}`,
  /** Set at Continue: Coach looks before it acts again, since the screen may have changed. */
  look: (tenant: string, drive: string) => `${tenant}#drive-look#${drive}`,
  /** A drive's end as Coach's chat shows it (DriveRecap), written once, when it ends. */
  recap: (tenant: string, drive: string) => `${tenant}#drive-recap#${drive}`,
  /** The drives a Coach chat had, by id, in the order they ended: where its thread finds their recaps. */
  recaps: (tenant: string, session: string) => `${tenant}#drive-recaps#${session}`,
};

/**
 * A drive's end as a message in Coach's chat (kiff/tafs#568), written by tafs cloud when the drive
 * ends, at that time: the request, why it ended, in the person's words and Coach's, and the steps
 * from the drive's record. It's kept with the chat, so both apps draw it in place, by its time.
 */
export interface DriveRecap {
  id: string;
  session: string;
  task: string;
  why: EndWhy;
  /** Why, in the person's words (END_SAID). */
  words: string;
  steps: Step[];
  at: string;
}

const kept = (now: Date) => new Date(now.getTime() + DRIVE.keptMs);
const str = (v: unknown) => (typeof v === "string" ? v : undefined);
const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? v : undefined);

function words(v: unknown, what: string, max: number = DRIVE.textMax): string {
  const s = str(v)?.trim();
  if (!s) throw new DriveError(400, `${what} is required`);
  if (s.length > max) throw new DriveError(400, `${what} is at most ${max} characters`);
  return s;
}

/** The person's drive as it stands: ended once it has sat idle too long. Undefined when they never had one. */
export async function currentDrive(store: CoachStore, tenant: string, person: string, now: Date): Promise<Drive | undefined> {
  const d = (await store.get(driveKeys.drive(tenant, person))) as Drive | undefined;
  if (!d || d.ended) return d;
  const last = (await store.get(driveKeys.last(tenant, d.id))) as string | undefined;
  if (now.getTime() - Date.parse(last ?? d.last) > DRIVE.idleMs) return endDrive(store, tenant, person, "idle", now, d.id);
  return d;
}

/** The drive was used now: a step, a look, an answer. Never the drive record itself. */
async function touch(store: CoachStore, tenant: string, drive: string, now: Date): Promise<void> {
  await store.put(driveKeys.last(tenant, drive), now.toISOString(), kept(now));
}

/**
 * Starts the person's drive for the request they approved: the caller has checked the
 * approval key's signature. A drive already on for them ends as replaced.
 */
export async function startDrive(store: CoachStore, tenant: string, person: string, session: unknown, task: unknown, now: Date): Promise<Drive> {
  const s = words(session, "session", 200);
  const t = words(task, "task");
  const before = await currentDrive(store, tenant, person, now);
  if (before && !before.ended) await endDrive(store, tenant, person, "replaced", now, before.id);
  const d: Drive = { id: randomBytes(12).toString("base64url"), person, session: s, task: t, started: now.toISOString(), last: now.toISOString() };
  await store.put(driveKeys.drive(tenant, person), d, kept(now));
  return d;
}

/**
 * Ends the person's drive, once, with why; with `id`, only that drive (a phone that
 * stops a drive another started stops nothing). Returns the drive as it now stands.
 */
export async function endDrive(store: CoachStore, tenant: string, person: string, why: EndWhy, now: Date, id?: string): Promise<Drive | undefined> {
  const d = (await store.get(driveKeys.drive(tenant, person))) as Drive | undefined;
  if (!d || d.ended || (id && d.id !== id)) return d;
  const ended: Drive = { ...d, ended: { why, at: now.toISOString() } };
  await store.put(driveKeys.drive(tenant, person), ended, kept(now));
  // Its end, in Coach's chat, once: only the end that wrote the record writes it.
  const recap: DriveRecap = { id: d.id, session: d.session, task: d.task, why, words: END_SAID[why], steps: await driveSteps(store, tenant, d.id), at: now.toISOString() };
  const until = new Date(now.getTime() + DRIVE.recapKeptMs);
  await store.put(driveKeys.recap(tenant, d.id), recap, until);
  await store.append(driveKeys.recaps(tenant, d.session), d.id, until);
  return ended;
}

/** The ends of the drives a Coach chat had, oldest first: what its thread shows, at their times. */
export async function driveRecaps(store: CoachStore, tenant: string, session: string): Promise<DriveRecap[]> {
  const ids = (await store.get(driveKeys.recaps(tenant, session))) as unknown[] | undefined;
  if (!Array.isArray(ids)) return [];
  const picked = [...new Set(ids.filter((x): x is string => typeof x === "string"))].slice(-DRIVE.recapsKept);
  const read = await Promise.all(picked.map((id) => store.get(driveKeys.recap(tenant, id)) as Promise<DriveRecap | undefined>));
  return read.filter((r): r is DriveRecap => !!r && r.session === session);
}

/** Since when the person's touch has paused this drive; undefined while it isn't paused. */
export async function drivePaused(store: CoachStore, tenant: string, drive: string): Promise<string | undefined> {
  const at = await store.get(driveKeys.paused(tenant, drive));
  return typeof at === "string" ? at : undefined;
}

/** The person's own drive, by its id, still on: anyone else's, or one that isn't, is 404 (docs/drive.md). */
async function ownDrive(store: CoachStore, tenant: string, person: string, drive: string, now: Date): Promise<Drive> {
  const d = await currentDrive(store, tenant, person, now);
  if (!d || d.id !== drive) throw new DriveError(404, "that isn't your drive");
  return d;
}

/**
 * The person touched the app: their drive pauses. No request is sent until Continue, and the
 * requests already waiting are dropped, since the app refuses them anyway (its own pause comes
 * first). Pausing one already paused, or one that ended, changes nothing.
 */
export async function pauseDrive(store: CoachStore, tenant: string, person: string, drive: string, now: Date): Promise<Drive> {
  const d = await ownDrive(store, tenant, person, drive, now);
  if (d.ended || (await drivePaused(store, tenant, d.id))) return d;
  await store.put(driveKeys.paused(tenant, d.id), now.toISOString(), kept(now));
  await store.remove(driveKeys.pausedCalls(tenant, d.id)).catch(() => undefined);
  await store.take(driveKeys.queue(tenant, d.id));
  // The pause is the last thing done: paused for DRIVE.idleMs, the drive ends as idle.
  await touch(store, tenant, d.id, now);
  return d;
}

/**
 * The person tapped Continue: the drive goes on, and Coach's next step must be a look.
 * Resuming one that isn't paused, or one that ended, changes nothing.
 */
export async function resumeDrive(store: CoachStore, tenant: string, person: string, drive: string, now: Date): Promise<Drive> {
  const d = await ownDrive(store, tenant, person, drive, now);
  if (d.ended || !(await drivePaused(store, tenant, d.id))) return d;
  await store.put(driveKeys.look(tenant, d.id), now.toISOString(), kept(now));
  await store.remove(driveKeys.paused(tenant, d.id));
  await store.remove(driveKeys.pausedCalls(tenant, d.id)).catch(() => undefined);
  await touch(store, tenant, d.id, now);
  return d;
}

/** Why, from the phone's words: one of END_WHY. */
export function endWhyOf(v: unknown): EndWhy {
  const s = str(v);
  if (!s || !(END_WHY as readonly string[]).includes(s) || s === "replaced") throw new DriveError(400, `why is one of ${END_WHY.filter((w) => w !== "replaced").join(", ")}`);
  return s as EndWhy;
}

function point(v: unknown, what: string): { x: number; y: number } {
  const p = v as { x?: unknown; y?: unknown } | undefined;
  const x = num(p?.x);
  const y = num(p?.y);
  if (x === undefined || y === undefined || x < 0 || y < 0 || x > 10_000 || y > 10_000) throw new DriveRefusal(`${what} is {x, y}, a point on the last screenshot`);
  return { x: Math.round(x), y: Math.round(y) };
}

/**
 * Coach's arguments, checked and kept to what the app reads: a point on the last
 * screenshot or an element of the last tree, never both; a text only for type.
 */
export function driveArgs(tool: DriveTool, args: Record<string, unknown>): Record<string, unknown> {
  if (tool === "app_look") {
    const out: Record<string, unknown> = {};
    if (args.tree === true) out.tree = true;
    if (args.diagnostics === true) out.diagnostics = true;
    if (args.wait !== undefined) {
      const w = str(args.wait);
      if (!w || !(WAIT_EVENTS as readonly string[]).includes(w)) throw new DriveRefusal(`wait is one of ${WAIT_EVENTS.join(", ")}`);
      const t = num(args.timeout_s) ?? 10;
      if (t < 1 || t > DRIVE.holdMs / 1000) throw new DriveRefusal(`timeout_s is 1 to ${DRIVE.holdMs / 1000}`);
      out.wait = w;
      out.timeout_s = Math.round(t);
    }
    return out;
  }
  const action = str(args.action);
  if (!action || !(ACTIONS as readonly string[]).includes(action)) throw new DriveRefusal(`action is one of ${ACTIONS.join(", ")}`);
  const out: Record<string, unknown> = { action };
  const line = str(args.line)?.trim();
  if (!line) throw new DriveRefusal("line is the caption the owner sees for this step, in their words: \"Tapping Run now\"");
  out.line = line.slice(0, 60);
  const element = str(args.element);
  const at = () => {
    if (element && args.at !== undefined) throw new DriveRefusal("give at (a point) or element, not both");
    if (element) {
      if (element.length > 200) throw new DriveRefusal("element is an id from the last tree");
      out.element = element;
    } else out.at = point(args.at, "at");
  };
  switch (action as Action) {
    case "tap":
    case "long_press":
      at();
      break;
    case "swipe": {
      at();
      out.to = point(args.to, "to");
      const ms = num(args.ms) ?? 300;
      if (ms < 50 || ms > 3000) throw new DriveRefusal("ms is 50 to 3000");
      out.ms = Math.round(ms);
      break;
    }
    case "scroll": {
      const dir = str(args.direction);
      if (!dir || !["up", "down", "left", "right"].includes(dir)) throw new DriveRefusal("direction is up, down, left or right");
      out.direction = dir;
      if (element) out.element = element;
      break;
    }
    case "type": {
      const text = str(args.text);
      if (!text || text.length > DRIVE.textMax) throw new DriveRefusal(`text is 1 to ${DRIVE.textMax} characters, typed into the focused field`);
      out.text = text;
      break;
    }
    case "back":
    case "done":
      break;
  }
  return out;
}

/** The phone's answer, checked: nothing it may not send, and nothing too big for the store or the model. */
export function resultOf(body: Record<string, unknown>): DriveResult {
  const outcome = str(body.outcome);
  if (outcome !== "done" && outcome !== "refused") throw new DriveError(400, "outcome is done or refused");
  const out: DriveResult = { outcome, line: words(body.line, "line", 200) };
  if (body.image !== undefined) {
    const img = body.image as { media_type?: unknown; data?: unknown };
    const type = str(img?.media_type);
    const data = str(img?.data);
    if ((type !== "image/jpeg" && type !== "image/png") || !data || !/^[A-Za-z0-9+/]+={0,2}$/.test(data)) {
      throw new DriveError(400, "image is {media_type: image/jpeg or image/png, data: base64}");
    }
    if (Math.floor((data.length * 3) / 4) > DRIVE.imageMax) throw new DriveError(413, `image is at most ${DRIVE.imageMax} bytes: send it smaller`);
    out.image = { media_type: type, data };
  }
  for (const k of ["tree", "diagnostics"] as const) {
    if (body[k] === undefined) continue;
    if (JSON.stringify(body[k]).length > DRIVE.treeMax) throw new DriveError(413, `${k} is at most ${DRIVE.treeMax} characters as JSON`);
    out[k] = body[k];
  }
  if (body.touched !== undefined) {
    const t = body.touched as { words?: unknown; taf?: unknown };
    out.touched = { words: words(t?.words, "touched.words", 200), ...(str(t?.taf) ? { taf: str(t.taf)!.slice(0, 200) } : {}) };
  }
  if (body.waited !== undefined) {
    const w = body.waited as { event?: unknown; came?: unknown };
    out.waited = { event: words(w?.event, "waited.event", 64), came: w?.came === true };
  }
  return out;
}

export type Sleep = (ms: number) => Promise<void>;
export const sleep: Sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Coach's tool call, answered: the phone's result, or why there is none. */
export type Answer = { result: DriveResult; drive: Drive } | { ended: Drive["ended"] & object } | { off: string } | { timedOut: true }
  | { paused: string; calls: number; drive: string } | { lookFirst: true };

/**
 * Sends one request to the person's phone and holds for its answer, at most
 * DRIVE.holdMs: the drive must be on, for this person, in this Coach chat when the
 * call names one. Every call counts as activity, so a drive in use never goes idle.
 */
export async function askApp(store: CoachStore, tenant: string, person: string, tool: DriveTool, rawArgs: Record<string, unknown>,
  now: () => Date, wait: Sleep = sleep): Promise<Answer> {
  const args = driveArgs(tool, rawArgs);
  const d = await currentDrive(store, tenant, person, now());
  if (!d) return { off: "no drive is on: ask the person, or suggest driving with a drive card, and wait for their approval" };
  if (d.ended) return { ended: d.ended };
  // The person has the app: nothing is sent, and nothing counts as done (docs/drive.md).
  // Each call is counted, so the caller can end Coach's turn on the second (docs/drive.md).
  const pausedAnswer = async (since: string): Promise<Answer> => {
    const calls = (num(await store.get(driveKeys.pausedCalls(tenant, d.id))) ?? 0) + 1;
    await store.put(driveKeys.pausedCalls(tenant, d.id), calls, kept(now()));
    return { paused: since, calls, drive: d.id };
  };
  const paused = await drivePaused(store, tenant, d.id);
  if (paused) return pausedAnswer(paused);
  // After Continue, a look comes first: the screen may have changed while the person had it.
  const mustLook = !!(await store.get(driveKeys.look(tenant, d.id)));
  if (mustLook && tool === "app_act") return { lookFirst: true };
  const at = now();
  await touch(store, tenant, d.id, at);
  const req: DriveRequest = { id: randomBytes(9).toString("base64url"), tool, args, at: at.toISOString() };
  await store.put(driveKeys.request(tenant, d.id, req.id), { tool, ...(typeof args.action === "string" ? { action: args.action } : {}), ...(typeof args.line === "string" ? { line: args.line } : {}) }, kept(at));
  await store.append(driveKeys.queue(tenant, d.id), req, kept(at));
  const until = at.getTime() + DRIVE.holdMs;
  while (now().getTime() < until) {
    await wait(DRIVE.pollMs);
    const r = (await store.get(driveKeys.result(tenant, d.id, req.id))) as DriveResult | undefined;
    if (r) {
      await store.remove(driveKeys.result(tenant, d.id, req.id)).catch(() => undefined);
      // Refused with no screen: the person touched the app while the phone held this request (only that
      // refusal comes without one, docs/drive.md). Its pause may land a moment after; once it has, this call is
      // the pause's, so Coach's run waits rather than reading a refusal. Words are never matched: they're the app's.
      if (r.outcome === "refused" && !r.image) {
        // Counted in polls, not read off the clock, so it ends however the wait is kept.
        for (let left = Math.ceil(DRIVE.pauseGraceMs / DRIVE.pollMs); ; left--) {
          const since = await drivePaused(store, tenant, d.id);
          if (since) return pausedAnswer(since);
          if (left <= 0) break;
          await wait(DRIVE.pollMs);
        }
      }
      // The look after Continue came back: Coach may act again.
      if (mustLook && tool === "app_look" && r.outcome === "done") await store.remove(driveKeys.look(tenant, d.id)).catch(() => undefined);
      return { result: r, drive: d };
    }
    const now2 = (await store.get(driveKeys.drive(tenant, person))) as Drive | undefined;
    if (!now2 || now2.id !== d.id) return { ended: { why: "replaced", at: now().toISOString() } };
    if (now2.ended) return { ended: now2.ended };
    // The person touched the app while this call waited: its request was dropped, so say so now,
    // rather than as a phone that didn't answer (agentgabo on #749).
    const since = await drivePaused(store, tenant, d.id);
    if (since) return pausedAnswer(since);
  }
  return { timedOut: true };
}

/** What came of the phone's answer: the result, the step it made, and the drive's end when it was Coach's done. */
export interface Taken { result: DriveResult; drive: Drive; step?: Step; ended?: Drive }

/**
 * The phone's answer to one of its drive's requests: kept for the held call to take, and,
 * for a step the app did, put in the drive's record whether or not the call still waits
 * (a late answer is still a step done). Coach's `done` ends the drive.
 */
export async function putResult(store: CoachStore, tenant: string, person: string, drive: string, request: string, body: Record<string, unknown>, now: Date): Promise<Taken> {
  const d = await currentDrive(store, tenant, person, now);
  if (!d || d.id !== drive) throw new DriveError(404, "that isn't your drive");
  if (d.ended) throw new DriveError(409, `the drive is over: ${END_WORDS[d.ended.why]}`);
  if (!/^[A-Za-z0-9_-]{1,40}$/.test(request)) throw new DriveError(400, "not a request of this drive");
  const sent = (await store.get(driveKeys.request(tenant, d.id, request))) as { tool?: string; action?: string; line?: string } | undefined;
  if (!sent) throw new DriveError(404, "not a request of this drive");
  const r = resultOf(body);
  await store.put(driveKeys.result(tenant, d.id, request), r, new Date(now.getTime() + 2 * DRIVE.holdMs));
  await store.remove(driveKeys.request(tenant, d.id, request)).catch(() => undefined);
  await touch(store, tenant, d.id, now);
  if (sent.tool !== "app_act" || r.outcome !== "done") return { result: r, drive: d };
  if (sent.action === "done") return { result: r, drive: d, ended: await endDrive(store, tenant, person, "done", now, d.id) };
  const step: Step = { at: now.toISOString(), line: sent.line ?? "", ...(r.touched ? { touched: r.touched } : {}) };
  // A step again under the same caption ("Looking for Versions", then again on the next screen) is
  // the same step gone on: one line in the record (kiff/tafs#568), which keeps every touch of it,
  // in order ("Setup, Card"): the record says what was done, never less (qa-kiff on #569).
  const steps = await driveSteps(store, tenant, d.id);
  const last = steps.at(-1);
  if (last && last.line === step.line) {
    await store.put(driveKeys.steps(tenant, d.id), [...steps.slice(0, -1), sameStep(last, step)], kept(now));
  } else {
    await store.append(driveKeys.steps(tenant, d.id), step, kept(now));
  }
  return { result: r, drive: d, step };
}

/** A step gone on under the same caption: the later time, and every touch of both, in order, once each. */
function sameStep(before: Step, after: Step): Step {
  const words = [...(before.touched?.words.split(", ") ?? []), ...(after.touched ? [after.touched.words] : [])];
  const once = [...new Set(words.filter((w) => w))];
  const tafs = new Set([before.touched?.taf, after.touched?.taf].filter((t) => t !== undefined));
  const out: Step = { at: after.at, line: after.line };
  if (once.length) out.touched = { words: once.join(", "), ...(tafs.size === 1 ? { taf: [...tafs][0]! } : {}) };
  return out;
}

export interface Step { at: string; line: string; touched?: { words: string; taf?: string } }

/** The drive's steps, for the "What I did" card: the record of what was done, not Coach's memory of it. */
export async function driveSteps(store: CoachStore, tenant: string, drive: string): Promise<Step[]> {
  const list = (await store.get(driveKeys.steps(tenant, drive))) as Step[] | undefined;
  return Array.isArray(list) ? list.slice(-DRIVE.stepsKept) : [];
}

function sse(name: string, data: unknown): string {
  return `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`;
}

/**
 * The drive stream the phone holds while its drive is on: each request as it comes
 * (`request`), then `ended` with why once the drive ends, or `reopen` when the stream
 * has lived DRIVE.streamMs. A comment line every DRIVE.pingMs keeps it open.
 */
export async function* driveStream(store: CoachStore, tenant: string, person: string, drive: string, now: () => Date, wait: Sleep = sleep, lang: Lang = "en",
  halted: () => Promise<boolean> = async () => false): AsyncGenerator<string> {
  const opened = now().getTime();
  let pinged = opened;
  yield sse("drive", { drive });
  for (;;) {
    // A halt ends the drive at once, paused or not, without waiting for Coach's next call:
    // a paused drive makes none (docs/drive.md). Read with each ping, not each poll.
    if (now().getTime() - pinged >= DRIVE.pingMs && (await halted().catch(() => false))) {
      await endDrive(store, tenant, person, "halted", now(), drive);
    }
    const d = await currentDrive(store, tenant, person, now());
    if (!d || d.id !== drive) { yield sse("ended", { why: "replaced", words: END_SAID.replaced }); return; }
    if (d.ended) { yield sse("ended", { why: d.ended.why, words: endSaid(d.ended.why, lang) }); return; }
    for (const r of (await store.take(driveKeys.queue(tenant, drive))) as DriveRequest[]) {
      // A request Coach has given up on, or nearly, is never sent: a reconnecting stream doesn't do stale steps.
      const left = Date.parse(r.at) + DRIVE.holdMs - now().getTime();
      if (left > SEND_MARGIN_MS) yield sse("request", { ...r, start_within_ms: left - SEND_MARGIN_MS });
    }
    const t = now().getTime();
    if (t - opened >= DRIVE.streamMs) { yield sse("reopen", {}); return; }
    if (t - pinged >= DRIVE.pingMs) { pinged = t; yield ": ping\n\n"; }
    await wait(DRIVE.pollMs);
  }
}

/** What Coach is told at its first call while the person has the app: end the turn, and wait to be told. */
export const PAUSED_SAID = "The person is using the app: their touch paused the drive. Your run waits here until they tap Continue or the drive ends; then look before your next step.";

/** The grant KIFF's gateway reserves for a paused drive's wait only Coach's drive tools may use it. */
export const DRIVE_WAIT_GRANT = "coach-drive";
/** The event a paused drive's wait is answered with. */
export const DRIVE_WAIT_EVENT = "drive.continue";
/** The longest a parked run waits: a drive's idle limit, which the gateway caps it to anyway. */
export const DRIVE_WAIT_S = DRIVE.idleMs / 1000;

/** A paused drive's wait key, as the gateway requires it: the caller's tenant, then the drive. */
export function driveWaitKey(tenant: string, drive: string): string {
  return `${tenant}:${drive}`;
}

/**
 * What parks Coach's run while the person has the app: a wait for the drive's Continue or end,
 * which KIFF's gateway keeps only for Coach's own drive tools (docs/drive.md). Where the gateway
 * doesn't carry it, Coach reads PAUSED_SAID, and the second call's guard ends its turn.
 */
export function driveWait(tenant: string, drive: string): Record<string, unknown> {
  return {
    pending: true,
    timeout_s: DRIVE_WAIT_S,
    wait_for: { grant: DRIVE_WAIT_GRANT, event: DRIVE_WAIT_EVENT, key: driveWaitKey(tenant, drive), words: "The person is using the app" },
  };
}

/** The note that starts Coach's next turn when the person taps Continue. */
export const CONTINUE_SAID = "[drive continue] The person tapped Continue: the drive goes on. The screen may have changed while they had it, so look (app_look) before your next step.";

/** What Coach reads for an answer: the app's line, and the screen as an image when it came. */
export function answerContent(a: Answer): { content: Array<Record<string, unknown>>; isError: boolean } {
  if ("off" in a) return { content: [{ type: "text", text: a.off }], isError: true };
  if ("ended" in a) return { content: [{ type: "text", text: `The drive is over: ${END_WORDS[a.ended.why]}. Nothing more was sent. Tell the owner where you got to; they ask again to go on.` }], isError: true };
  if ("timedOut" in a) return { content: [{ type: "text", text: "The app didn't answer in 20 seconds. Look again before your next step." }], isError: true };
  if ("paused" in a) {
    return { content: [{ type: "text", text: a.calls > 1
      ? "The person is still using the app, so tafs ended this turn. Nothing was sent. You'll be told when they tap Continue."
      : `${PAUSED_SAID} Nothing was sent.` }], isError: true };
  }
  if ("lookFirst" in a) return { content: [{ type: "text", text: "The person tapped Continue, and the screen may have changed while they had it. Look (app_look) before you act. Nothing was sent." }], isError: true };
  const r = a.result;
  const parts: string[] = [r.outcome === "done" ? r.line : `Refused by the app: ${r.line}. Nothing was done.`];
  if (r.waited) parts.push(r.waited.came ? `${r.waited.event} came.` : `${r.waited.event} didn't come in time.`);
  if (r.tree !== undefined) parts.push(`The view tree: ${JSON.stringify(r.tree)}`);
  if (r.diagnostics !== undefined) parts.push(`Diagnostics: ${JSON.stringify(r.diagnostics)}`);
  const content: Array<Record<string, unknown>> = [{ type: "text", text: parts.join("\n") }];
  if (r.image) content.push({ type: "image", data: r.image.data, mimeType: r.image.media_type });
  return { content, isError: r.outcome === "refused" };
}
