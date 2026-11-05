import { randomBytes } from "node:crypto";
import type { CoachStore } from "./coach.js";

/**
 * Coach drives the app (kiff/tafs#544, docs/drive.md): the person asks Coach to do
 * something in the app, or accepts Coach's suggestion, and approves that one request
 * with the approval key. While the drive is on, Coach's `app_look` and `app_act` reach
 * the person's phone through the drive stream, and the phone answers each with the
 * new screen. tafs cloud only carries them: what Coach may touch, and what it can
 * never confirm, the app decides; what Coach's calls may do, KIFF decides on Coach's
 * Cards.
 *
 * One drive per person. It ends, and never resumes, when the person touches the app
 * themselves, the app leaves the foreground, they stop it, the screen locks, they sign
 * out, the account is halted, Coach says the task is done, or nothing happens for
 * DRIVE.idleMs. A tool call after the end is answered with why, at once.
 */

export const DRIVE = {
  /** The most a Coach tool call waits for the phone's answer. */
  holdMs: 20_000,
  /** How often a held call and the stream look at the store. */
  pollMs: 250,
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
} as const;

/** Why a drive ended, as the app or tafs cloud says it. */
export const END_WHY = ["owner_took_over", "left_app", "stopped", "locked", "signed_out", "halted", "done", "idle", "replaced"] as const;
export type EndWhy = typeof END_WHY[number];

/** What Coach is told, and the record shows, for each end. */
export const END_WORDS: Record<EndWhy, string> = {
  owner_took_over: "the owner touched the app, so they have it now",
  left_app: "the app left the screen, so the drive is over",
  stopped: "the owner stopped it",
  locked: "the phone locked",
  signed_out: "the owner signed out",
  halted: "the account is halted",
  done: "the task is done",
  idle: "nothing was done for 15 minutes",
  replaced: "another drive started",
};

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
}

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
};

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
  return ended;
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
export type Answer = { result: DriveResult; drive: Drive } | { ended: Drive["ended"] & object } | { off: string } | { timedOut: true };

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
      return { result: r, drive: d };
    }
    const now2 = (await store.get(driveKeys.drive(tenant, person))) as Drive | undefined;
    if (!now2 || now2.id !== d.id) return { ended: { why: "replaced", at: now().toISOString() } };
    if (now2.ended) return { ended: now2.ended };
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
  await store.append(driveKeys.steps(tenant, d.id), step, kept(now));
  return { result: r, drive: d, step };
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
export async function* driveStream(store: CoachStore, tenant: string, person: string, drive: string, now: () => Date, wait: Sleep = sleep): AsyncGenerator<string> {
  const opened = now().getTime();
  let pinged = opened;
  yield sse("drive", { drive });
  for (;;) {
    const d = await currentDrive(store, tenant, person, now());
    if (!d || d.id !== drive) { yield sse("ended", { why: "replaced", words: END_WORDS.replaced }); return; }
    if (d.ended) { yield sse("ended", { why: d.ended.why, words: END_WORDS[d.ended.why] }); return; }
    for (const r of (await store.take(driveKeys.queue(tenant, drive))) as DriveRequest[]) yield sse("request", r);
    const t = now().getTime();
    if (t - opened >= DRIVE.streamMs) { yield sse("reopen", {}); return; }
    if (t - pinged >= DRIVE.pingMs) { pinged = t; yield ": ping\n\n"; }
    await wait(DRIVE.pollMs);
  }
}

/** What Coach reads for an answer: the app's line, and the screen as an image when it came. */
export function answerContent(a: Answer): { content: Array<Record<string, unknown>>; isError: boolean } {
  if ("off" in a) return { content: [{ type: "text", text: a.off }], isError: true };
  if ("ended" in a) return { content: [{ type: "text", text: `The drive is over: ${END_WORDS[a.ended.why]}. Nothing more was sent. Tell the owner where you got to; they ask again to go on.` }], isError: true };
  if ("timedOut" in a) return { content: [{ type: "text", text: "The app didn't answer in 20 seconds. Look again before your next step." }], isError: true };
  const r = a.result;
  const parts: string[] = [r.outcome === "done" ? r.line : `Refused by the app: ${r.line}. Nothing was done.`];
  if (r.waited) parts.push(r.waited.came ? `${r.waited.event} came.` : `${r.waited.event} didn't come in time.`);
  if (r.tree !== undefined) parts.push(`The view tree: ${JSON.stringify(r.tree)}`);
  if (r.diagnostics !== undefined) parts.push(`Diagnostics: ${JSON.stringify(r.diagnostics)}`);
  const content: Array<Record<string, unknown>> = [{ type: "text", text: parts.join("\n") }];
  if (r.image) content.push({ type: "image", data: r.image.data, mimeType: r.image.media_type });
  return { content, isError: r.outcome === "refused" };
}
