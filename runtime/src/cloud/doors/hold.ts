import { waitsForConsent } from "../consent.js";
import { randomBytes } from "node:crypto";
import { AgentsError } from "../agents.js";
import { deviceLang, type Notification, sendNotification } from "../push.js";
import { type Lang, plural, type Words, wordsIn } from "../words/index.js";
import { apsFor, levelOf } from "../levels.js";
import type { Taf } from "../handler.js";
import { APPS } from "./apps.js";
import { type DoorsContext, doorsOf, startDoorRun } from "./doors.js";
import { type Arrival, doorKeys } from "./model.js";

/**
 * A door's message or an app's event that came while KIFF refused the account's runs for credits
 * (docs/accounts.md, "A door's message, or an app's event"): kept for 48 hours, the door's replay
 * window, with nothing sent in the business's name meanwhile, and run oldest first, across the
 * account's tafs, once the tafs go on. One still waiting after 48 hours doesn't run: its door's
 * (or its event's) stop on the taf's dial says it went unanswered for 7 days, and the people who
 * answer the taf's holds are told once.
 *
 * Each item is kept on its own (a message can be 40,000 characters), under a list by taf of what
 * waits, in the order it came. The door store can't list items, so the sweep reads each taf's list
 * (`replayHeld`), as the other door sweeps read theirs.
 */

/** How a held item waits, and how long it's said once it went unanswered. */
export const HOLD = {
  /** The door's replay window: a message older than this is refused at the door too (DOOR.staleHours). */
  hours: 48,
  /** How long the dial says a held item went unanswered. */
  unansweredDays: 7,
  /** The most items one taf keeps waiting; past it, what comes is said unanswered at once. */
  perTaf: 200,
  /** The most items one replay starts, so a webhook that replays stays short; the sweep takes the rest. */
  perReplay: 20,
  /**
   * Starts an item may fail for anything but credits (kiff/agents refusing it, 4xx) before it's said
   * unanswered, so one bad item doesn't hold its taf's later ones for two days. A server error
   * (5xx, or none) isn't counted: kiff/agents down for a while loses nothing.
   */
  tries: 3,
} as const;

/** kiff/agents' codes for a run refused because the account's tafs wait for credits. */
export const CREDITS_CODES = ["credits_used_up", "limit_reached", "no_plan"] as const;

/** Whether err is kiff/agents refusing a run because the account's tafs wait for credits. */
export function waitsForCredits(err: unknown): err is AgentsError {
  return err instanceof AgentsError && err.status === 409 && (CREDITS_CODES as readonly string[]).includes(err.code ?? "");
}

/**
 * Whether what came in waits rather than runs: for the account's credits, or for its taf's consent to AI use with no person
 * in the moment (consent.ts). Either way it's kept, and starts once that's back, or is said unanswered after 48 hours.
 */
export function waitsToStart(err: unknown): boolean {
  return waitsForCredits(err) || waitsForConsent(err);
}

/** A door's message that waits: the arrival as its door took it, and the door it came through. */
export interface HeldDoor { kind: "door"; door: string; arrival: Arrival }

/** An app's event that waits: the run's input as the receiver wrote it, and the wake it came for. */
export interface HeldEvent { kind: "app_event"; event: string; grant: string; name: string; subscription: string; input: string; dedupKey: string }

export type Held = (HeldDoor | HeldEvent) & { id: string; taf: string; at: string };

/** What a taf's list keeps of each: enough to order it without reading it, and an event's key, so a delivery repeated is kept once. */
interface Waiting { id: string; at: string; key?: string; tries?: number }

/** A held item that went unanswered, for the dial: which door, or which app's event. */
export interface Unanswered { id: string; kind: "door" | "app_event"; door?: string; grant?: string; event?: string; at: string }

const hours = (n: number) => n * 3600_000;
const keptUntil = (at: string) => new Date(Date.parse(at) + hours(HOLD.hours) + hours(24));

/** Changes one item read-modify-write, written only if it's still what was read. */
async function change<T>(ctx: DoorsContext, key: string, f: (read: T | undefined) => T | undefined, until?: Date): Promise<T | undefined> {
  const { store } = ctx.doors;
  for (let i = 0; i < 6; i++) {
    const read = (await store.get(key)) as T | undefined;
    const next = f(read === undefined ? undefined : structuredClone(read));
    if (next === undefined) {
      if (read !== undefined) await store.remove(key);
      return undefined;
    }
    if (await store.swap(key, read, next, until)) return next;
  }
  throw new Error("held: changed at the same time six times");
}

async function waitingOf(ctx: DoorsContext, tenant: string, taf: string): Promise<Waiting[]> {
  const v = (await ctx.doors.store.get(doorKeys.held(tenant, taf))) as { items?: Waiting[] } | undefined;
  return Array.isArray(v?.items) ? v.items : [];
}

/** Whether anything of this taf waits for credits: what comes next waits behind it. */
export async function anyHeld(ctx: DoorsContext, tenant: string, taf: string): Promise<boolean> {
  return (await waitingOf(ctx, tenant, taf)).length > 0;
}

/**
 * Keeps an item to run once the tafs go on. False when the taf already keeps its most: that one is
 * said unanswered at once, as one that waited too long is.
 */
export async function hold(ctx: DoorsContext, tenant: string, taf: Taf, item: HeldDoor | HeldEvent): Promise<boolean> {
  const held: Held = { ...item, id: `h-${randomBytes(9).toString("base64url")}`, taf: taf.id, at: ctx.now.toISOString() };
  await ctx.doors.store.put(doorKeys.heldItem(tenant, held.id), held, keptUntil(held.at));
  const key = item.kind === "app_event" ? item.dedupKey : undefined;
  let kept = false, again = false;
  await change<{ items: Waiting[] }>(ctx, doorKeys.held(tenant, taf.id), (read) => {
    const items = read?.items ?? [];
    again = !!key && items.some((i) => i.key === key);
    kept = again || items.length < HOLD.perTaf;
    return { items: kept && !again ? [...items, { id: held.id, at: held.at, ...(key ? { key } : {}) }] : items };
  }, keptUntil(held.at));
  if (again) {
    await ctx.doors.store.remove(doorKeys.heldItem(tenant, held.id));
  } else if (!kept) {
    await ctx.doors.store.remove(doorKeys.heldItem(tenant, held.id));
    await markUnanswered(ctx, tenant, taf, held);
    // A busy door past the most it keeps: its answerers are told once an hour, not once a message.
    if (await ctx.doors.store.create(doorKeys.heldFull(tenant, taf.id), 1, new Date(ctx.now.getTime() + hours(1)))) {
      await tellUnanswered(ctx, tenant, taf, [held]);
    }
  }
  return kept;
}

/** Takes one item off its taf's list; true for the call that took it, so what follows happens once. */
async function take(ctx: DoorsContext, tenant: string, taf: string, id: string): Promise<boolean> {
  let took = false;
  await change<{ items: Waiting[] }>(ctx, doorKeys.held(tenant, taf), (read) => {
    const items = read?.items ?? [];
    took = items.some((i) => i.id === id);
    // An emptied list is kept (empty), never deleted: a delete isn't a compare-and-swap, so two
    // sweeps taking the last item at once would both take it, and say it twice.
    return { items: items.filter((i) => i.id !== id) };
  }, new Date(ctx.now.getTime() + hours(HOLD.hours + 24)));
  return took;
}

/**
 * The account's held items, oldest first across its tafs: each one past 48 hours said unanswered,
 * each other started, until kiff/agents refuses for credits again (the tafs still wait: nothing
 * more is tried now), or HOLD.perReplay have started. An item whose start fails otherwise stays,
 * and holds its taf's later ones behind it until the next try. What its door or wake no longer
 * allows (the door closed, the sender unpaired, the event no longer approved) is dropped unsaid:
 * the owner chose that meanwhile. Returns what was started and what went unanswered.
 */
export async function replayHeld(ctx: DoorsContext, tenant: string, only?: string): Promise<{ started: number; unanswered: number; waiting: boolean }> {
  const tafs = (await ctx.tafs.list(tenant)).filter((t) => !only || t.id === only);
  const queue: Array<Waiting & { taf: Taf; i: number }> = [];
  for (const taf of tafs) for (const [i, w] of (await waitingOf(ctx, tenant, taf.id)).entries()) queue.push({ ...w, taf, i });
  // A taf's list is in the order they came; two of the same instant on different tafs, by taf.
  queue.sort((a, b) => a.at.localeCompare(b.at) || (a.taf.id === b.taf.id ? a.i - b.i : a.taf.id.localeCompare(b.taf.id)));
  const out = { started: 0, unanswered: 0, waiting: false };
  const stuck = new Set<string>();
  // What this pass said unanswered, by taf: each taf's answerers are told once for all of it.
  const told = new Map<string, { taf: Taf; items: Held[] }>();
  const said = async (taf: Taf, held: Held) => {
    out.unanswered++;
    if (taf.removing) return;
    await markUnanswered(ctx, tenant, taf, held);
    const t = told.get(taf.id) ?? { taf, items: [] };
    t.items.push(held);
    told.set(taf.id, t);
  };
  for (const w of queue) {
    const held = (await ctx.doors.store.get(doorKeys.heldItem(tenant, w.id))) as Held | undefined;
    if (!held) {
      await take(ctx, tenant, w.taf.id, w.id);
      continue;
    }
    if (ctx.now.getTime() - Date.parse(held.at) >= hours(HOLD.hours)) {
      if (await take(ctx, tenant, w.taf.id, w.id)) {
        await ctx.doors.store.remove(doorKeys.heldItem(tenant, w.id));
        await said(w.taf, held);
      }
      continue;
    }
    if (out.waiting || stuck.has(w.taf.id) || out.started >= HOLD.perReplay) continue;
    try {
      const went = await start(ctx, tenant, w.taf, held);
      if (await take(ctx, tenant, w.taf.id, w.id)) await ctx.doors.store.remove(doorKeys.heldItem(tenant, w.id));
      if (went) out.started++;
    } catch (err) {
      if (waitsForCredits(err)) {
        out.waiting = true;
        continue;
      }
      // Its taf is paused for consent: its items wait, never counted as tries; other tafs' go on.
      if (waitsForConsent(err)) {
        stuck.add(w.taf.id);
        continue;
      }
      console.error("held item not started:", err instanceof Error ? err.message : "error");
      const refused = err instanceof AgentsError && err.status >= 400 && err.status < 500 && err.status !== 429;
      if (refused && (await tried(ctx, tenant, w.taf.id, w.id)) >= HOLD.tries && await take(ctx, tenant, w.taf.id, w.id)) {
        await ctx.doors.store.remove(doorKeys.heldItem(tenant, w.id));
        await said(w.taf, held);
        continue;
      }
      stuck.add(w.taf.id);
    }
  }
  for (const t of told.values()) await tellUnanswered(ctx, tenant, t.taf, t.items);
  return out;
}

/** Counts one refused start of an item; its tries so far. */
async function tried(ctx: DoorsContext, tenant: string, taf: string, id: string): Promise<number> {
  let n = 0;
  await change<{ items: Waiting[] }>(ctx, doorKeys.held(tenant, taf), (read) => {
    if (!read) return undefined;
    return { items: read.items.map((i) => (i.id === id ? { ...i, tries: (n = (i.tries ?? 0) + 1) } : i)) };
  }, new Date(ctx.now.getTime() + hours(HOLD.hours + 24)));
  return n;
}

/** Starts a held item's run, as its door or wake would have; false when they no longer allow it. */
async function start(ctx: DoorsContext, tenant: string, taf: Taf, held: Held): Promise<boolean> {
  if (taf.removing) return false;
  if (held.kind === "app_event") {
    const wake = taf.appEvents?.find((t) => t.grant === held.grant && t.event === held.event && !!t.subscription && t.subscription === held.subscription);
    if (!wake) return false;
    await ctx.agents.startRun(tenant, taf.id, held.input, { dedupKey: held.dedupKey, source: "app_event" });
    return true;
  }
  const cfg = await ctx.doors.config();
  const a = held.arrival;
  const door = (await doorsOf(ctx.doors.store, tenant, taf.id)).find((d) => d.id === held.door);
  const app = door && APPS.find((x) => x.id === door.app);
  if (!door || !door.open || !app) return false;
  const key = app.proof === "proven" && a.proven && door.keys.some((k) => k.id === a.from);
  if (!key) return false;
  const own = door.keys.find((k) => k.id === a.from && k.auto && k.subject)?.subject;
  await startDoorRun(ctx, cfg, tenant, taf, door, app, a, () => undefined, own);
  return true;
}

/** What the dial says of items that went unanswered (docs/accounts.md), for 7 days, beside a door's stop or an app event's. */
export function unansweredWords(n: number, what: "message" | "event", lang: Lang = "en"): string {
  return wordsIn(lang).unanswered_dial(n, plural(lang, n), what === "event");
}

/** Keeps that a held item went unanswered, for its stop on the dial, 7 days. */
async function markUnanswered(ctx: DoorsContext, tenant: string, taf: Taf, held: Held): Promise<void> {
  const until = new Date(ctx.now.getTime() + hours(24 * HOLD.unansweredDays));
  const line: Unanswered = held.kind === "door"
    ? { id: held.id, kind: "door", door: held.door, at: ctx.now.toISOString() }
    : { id: held.id, kind: "app_event", grant: held.grant, event: held.event, at: ctx.now.toISOString() };
  await change<{ items: Unanswered[] }>(ctx, doorKeys.unanswered(tenant, taf.id), (read) => {
    const items = (read?.items ?? []).filter((i) => ctx.now.getTime() - Date.parse(i.at) < hours(24 * HOLD.unansweredDays));
    return { items: [line, ...items].slice(0, 500) };
  }, until).catch((err) => console.error("unanswered not kept:", err instanceof Error ? err.message : "error"));
}

/**
 * Tells the phones that answer the taf's holds what just went unanswered: one push for the taf,
 * naming the one, or counting several, so a busy door is one push, not hundreds. Collapsed by taf.
 */
async function tellUnanswered(ctx: DoorsContext, tenant: string, taf: Taf, items: Held[]): Promise<void> {
  if (!items.length) return;
  let body: (w: Words, lang: Lang) => string;
  const one = items.length === 1 ? items[0]! : undefined;
  if (one?.kind === "door") {
    const door = (await doorsOf(ctx.doors.store, tenant, taf.id).catch(() => [])).find((d) => d.id === one.door);
    const app = APPS.find((x) => x.id === door?.app);
    body = (w) => w.unanswered_door(one.arrival.name ?? one.arrival.from, app ? w.door_name(app.id, app.label) : w.door_a);
  } else if (one?.kind === "app_event") {
    body = (w) => w.unanswered_event(one.name);
  } else {
    const messages = items.filter((i) => i.kind === "door").length;
    const events = items.filter((i) => i.kind === "app_event").length;
    body = (w, lang) => w.unanswered_many(messages, plural(lang, messages), events, plural(lang, events));
  }
  await tellAnswerers(ctx, tenant, (lang) => ({
    kind: "alert",
    payload: { aps: { alert: { title: taf.name, body: body(wordsIn(lang), lang) }, ...apsFor(levelOf("failing", false)) }, open: "taf", taf_id: taf.id },
    collapseID: `unanswered-${taf.id}`.slice(0, 64),
  }));
}

/** The phones of the people who answer the account's holds; a failure is logged, never in the way. */
// Each phone in its own language, the alert built once per language.
async function tellAnswerers(ctx: DoorsContext, tenant: string, build: (lang: Lang) => Notification): Promise<void> {
  try {
    const { devices } = await ctx.push.get(tenant);
    const built = new Map<Lang, Notification>();
    const of = (lang: Lang) => built.get(lang) ?? built.set(lang, build(lang)).get(lang)!;
    await Promise.all(devices.filter((d) => d.answers).map((d) => sendNotification(ctx.apns, d, of(deviceLang(d))).catch(() => "failed")));
  } catch (err) {
    console.error("unanswered alert not sent:", err instanceof Error ? err.message : "error");
  }
}

/** A taf's items that went unanswered in the last 7 days, for its dial. */
export async function unansweredOf(ctx: Pick<DoorsContext, "doors" | "now">, tenant: string, taf: string): Promise<Unanswered[]> {
  const v = (await ctx.doors.store.get(doorKeys.unanswered(tenant, taf))) as { items?: Unanswered[] } | undefined;
  return (Array.isArray(v?.items) ? v.items : []).filter((i) => ctx.now.getTime() - Date.parse(i.at) < hours(24 * HOLD.unansweredDays));
}
