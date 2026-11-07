/**
 * A taf's dial (the knob): what wakes it, when, and where its results go, with the face's word
 * for each and what tapping it does, all in tafs cloud's words. The apps draw them as they are
 * and compose none (docs/api.md, "The knob").
 */
import type { Schedule } from "./agents.js";
import type { ResultPlace } from "./handler.js";
import { type Trigger, triggerOf, triggerWords, sameAppEvent } from "./triggers.js";
import { placeWords, type VendorPlace } from "./places.js";
import { timeIn } from "./work.js";

/** A version's dial as PUT takes it: its triggers, what a busy taf does, where results go. */
/** One step of the taf's recipe, as Coach wrote it: what it does, in order. `tool` is the KIFF tool it uses, when one. */
export interface Step { label: string; words: string; tool?: string }

export interface KnobSetting {
  wakes: Trigger[];
  busy: "skip" | "latest";
  results: Array<ResultPlace | VendorPlace>;
  /** Coach's recipe and the taf's purpose in a few words, for the What ring and the face. */
  what?: Step[];
  why?: string;
}

/** One stop on a ring. */
export interface RingItem {
  id: string;
  kind: string;
  /** The word on the ring. */
  label: string;
  /** Its line, under the dial. */
  words: string;
  on: boolean;
  /** The face's big word when the stop is chosen. */
  state: string;
  /** What tapping the face does; absent when it does nothing for this stop. */
  tap?: string;
  /** The door a door's stop opens or closes (PATCH /v1/tafs/{id}/doors/{door}). */
  door?: string;
  /**
   * What the dial's OK (pressing the face) does on this stop, the same in both apps: open or close its door,
   * run the taf once, remove a follow-up, or open Coach. Absent: nothing.
   */
  ok?: "door" | "run" | "remove_follow_up" | "coach" | "approve";
  /**
   * An app event's subscription at planque: `needed` until the owner's phone approves it (the stop's `ok` is
   * `approve`), then `approved` with planque's subscription id.
   */
  approval?: { state: "needed" | "approved"; grant: string; event: string; config: Record<string, unknown>; subscription?: string };
  /** A door stop's look: its arc lit, dashed, grey while resting, or amber while a knock waits. */
  look?: "open" | "closed" | "resting" | "knock";
  /**
   * The stop's one choice, a few fixed values (the dial's − and +): `index` is the value now, and each
   * choice's `setting` is the whole PUT body that makes it, every other stop as it is.
   */
  size?: { label: string; index: number; choices: Array<{ label: string; setting: KnobSetting }> };
  /** A trigger's form exactly as PUT takes it; absent on run now and on a schedule the dial can't set. */
  trigger?: Trigger;
  /** The connection (planque's grant) a connected app's stop belongs to: the dial groups stops by it. */
  grant?: string;
  /** A follow-up's id, for DELETE /v1/tafs/{id}/follow-ups/{fid}, and when it fires. */
  follow_up?: string;
  expires?: string;
}

export interface Knob {
  version?: number;
  /** The dial change shown instead of the live dial, when asked for with ?version=N. */
  shown?: number;
  does: { label: string; line: string };
  /** The face's word when nothing is chosen: the taf's purpose, as Coach wrote it. */
  why: string;
  /**
   * The three rings, the same on every taf, inner to outer: When (`wakes`), What (`what`, the recipe in
   * order), Where (`results`). `when` is kept for older apps and isn't drawn on the dial.
   */
  rings: Array<{ key: "wakes" | "what" | "results"; name: string }>;
  what: Array<RingItem & { step: number; tool?: string }>;
  wakes: RingItem[];
  when: RingItem[];
  /** Each place, on or off; an action place also names its connection and action. */
  results: (Omit<RingItem, "kind"> & { place: ResultPlace | VendorPlace["place"]; grant?: string; action?: string; taf?: string })[];
  rim: { done: number; working: number; retrying: number; needs_you: number; line: string };
  /** Whether ⏻ applies: false for a taf that works from its chat, which has no schedule to pause (409). */
  can_pause: boolean;
  /**
   * The change waiting to go live, the same in both apps: the version Make it live switches to, and how many
   * stops it changes from the live dial (for "Make N changes live"). Null when nothing waits.
   */
  pending: null | { version: number; changes: number };
  /** A dial change waiting for the owner: its words, all of them and by ring, for Coach's card and the dial. */
  draft: null | {
    version: number; words: string[]; wakes: string[]; when: string[]; results: string[]; what: string[];
    /** The same lines by connection, for Coach's card: each connected app's, then This taf's (no `grant`). */
    groups: Array<{ grant?: string; name: string; wakes: string[]; what: string[]; results: string[] }>;
  };
  /** The shown version's dial as PUT takes it: a tap copies it, changes one thing and sends it back. */
  setting: KnobSetting;
  /**
   * The connections the dial's stops come from, in the order they first appear, for the chips under it
   * (All · each connection · This taf): a stop with no `grant` is the taf's own.
   */
  connections: Array<{ grant: string; name: string; wakes: number; results: number }>;
}

/** A door as the doors list gives it. */
export interface KnobDoor {
  id: string; label: string; open: boolean; words: { state: string; tap: string }; resting?: boolean; knocks?: number;
  /** Its messages that went unanswered while the tafs waited for credits, in the last 7 days, in words (doors/hold.ts). */
  unanswered?: string;
}

export interface KnobInput {
  name: string;
  /** What it does, in one word ("Refund"), and in a line. */
  does: { label: string; line: string };
  unscheduled: boolean;
  schedule?: Schedule;
  doors: KnobDoor[];
  places: ResultPlace[];
  /** Today's requests by state, in the account's zone. */
  today: { done: number; working: number; retrying: number; needs_you: number };
  /** What it does when a trigger fires while it works. */
  busy?: "skip" | "latest";
  /** Whether the caller manages the taf, so When ends with "Add a door". */
  addDoor?: boolean;
  /** The follow-ups the taf set itself, waiting to fire. */
  followUps?: Schedule[];
  /** A newer version's dial, made and not yet live: what waits for the owner's confirmation. */
  draft?: { version: number; setting: KnobSetting };
  /** The taf's tools through KIFF, by tool, with KIFF's title: the recipe when Coach wrote none. */
  tools?: ReadonlyMap<string, string>;
  shown?: number;
  /** The live version's dial, when a tap or Coach's draft set it; else it's read from the schedule. */
  setting?: KnobSetting;
  /** The app events the taf wakes on, as applied, with planque's subscription once approved. */
  appEvents?: Trigger[];
  /** By connection and event: those that went unanswered while the tafs waited for credits, in the last 7 days, in words. */
  unansweredEvents?: Array<{ grant: string; event: string; line: string }>;
  version?: number;
  zone: string;
  now: Date;
}

const DAYS = ["Sundays", "Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays"];

/** The schedule in words, for the forms the dial sets; any other cron reads as "On a schedule". */
export function cronWords(cron: string): { label: string; words: string } {
  const f = cron.trim().split(/\s+/);
  if (f.length !== 5) return { label: "Schedule", words: "On a schedule" };
  const [min, hour, dom, mon, dow] = f as [string, string, string, string, string];
  const at = (h: string, m: string) => `${h.padStart(2, "0")}:${m.padStart(2, "0")}`;
  const plain = (s: string) => /^\d{1,2}$/.test(s);
  const every = /^\*\/(\d+)$/.exec(min);
  if (every && hour === "*" && dom === "*" && mon === "*" && dow === "*") return { label: `Every ${every[1]} min`, words: `Every ${every[1]} minutes` };
  const between = /^(\d{1,2})-(\d{1,2})$/.exec(hour);
  if (every && between && dom === "*" && mon === "*") {
    const days = dow === "1-5" ? " on weekdays" : dow === "*" ? "" : null;
    if (days !== null) return { label: `Every ${every[1]} min`, words: `Every ${every[1]} minutes from ${at(between[1]!, "0")} to ${at(String(Number(between[2]) + 1), "0")}${days}` };
  }
  if (!plain(min) || !plain(hour) || mon !== "*") return { label: "Schedule", words: "On a schedule" };
  const t = at(hour, min);
  if (dom === "*" && dow === "*") return { label: t, words: `Every day at ${t}` };
  if (dom === "*" && dow === "1-5") return { label: "Weekdays", words: `Weekdays at ${t}` };
  if (dom === "*" && /^[0-6](,[0-6])*$/.test(dow)) {
    const names = dow.split(",").map((d) => DAYS[Number(d)]!);
    const list = names.length > 1 ? `${names.slice(0, -1).join(", ")} and ${names.at(-1)}` : names[0]!;
    return { label: names.length === 1 ? names[0]!.slice(0, 3) : "Weekly", words: `${list} at ${t}` };
  }
  if (plain(dom) && dow === "*") return { label: "Monthly", words: `On day ${Number(dom)} of each month at ${t}` };
  return { label: "Schedule", words: "On a schedule" };
}

const PLACE: Record<ResultPlace, { label: string; words: string }> = {
  door_reply: { label: "Sender", words: "Replies to whoever wrote" },
  app: { label: "You", words: "Shows each result in the app" },
  owner_email: { label: "Email", words: "Emails you each result" },
};

export function knobOf(k: KnobInput): Knob {
  const wakes: RingItem[] = [];
  for (const d of k.doors) {
    wakes.push({ id: `door_${d.id}`, kind: "door", door: d.id, label: d.label, words: d.unanswered ? `${d.words.state} · ${d.unanswered}` : d.words.state, on: d.open,
      state: d.open ? "Open" : "Closed", tap: d.words.tap, ok: "door",
      look: d.knocks ? "knock" : d.resting && d.open ? "resting" : d.open ? "open" : "closed" });
  }
  // The dial's triggers: as the live version set them, or the taf's own schedule read back.
  const fromSchedule = !k.unscheduled && k.schedule ? triggerOf(k.schedule, k.zone) : undefined;
  const timed = k.setting ? k.setting.wakes.filter((t) => t.kind === "schedule" || t.kind === "app_check") : [];
  if (!k.unscheduled && k.schedule && (!k.setting || timed.length)) {
    const s = k.schedule;
    const next = s.enabled && s.next ? `, next ${timeIn(s.next, k.zone, k.now)}` : "";
    const stops = k.setting ? timed.map((t) => ({ trigger: t, ...wordsOf(t) })) : [{ trigger: fromSchedule, ...cronWords(s.cron) }];
    for (const [i, t] of stops.entries()) {
      // The scheduler says when the taf's own schedule fires next; that's the first trigger's.
      const words = s.enabled ? `${t.words}${i === 0 ? next : ""}` : `${t.words}, paused`;
      wakes.push({ id: i === 0 ? "trg_schedule" : `trg_${i + 1}`, kind: t.trigger?.kind === "app_check" ? "app_check" : "schedule", label: t.label, words,
        on: s.enabled, state: s.enabled ? t.label : "Paused", ...(t.trigger ? { trigger: t.trigger } : {}) });
    }
  }
  // Any connected app's event the live version wakes on: its own stop, named as the owner picked it.
  for (const [i, t] of (k.setting?.wakes ?? []).filter((x) => x.kind === "app_event").entries()) {
    const name = (t.name ?? t.event ?? "").slice(0, 100);
    const subscription = k.appEvents?.find((a) => sameAppEvent(a, t))?.subscription;
    const approval = { state: subscription ? "approved" as const : "needed" as const, grant: t.grant ?? "", event: t.event ?? "", config: t.config ?? {},
      ...(subscription ? { subscription } : {}) };
    const unanswered = k.unansweredEvents?.find((u) => u.grant === t.grant && u.event === t.event)?.line;
    wakes.push({ id: `trg_event_${i + 1}`, kind: "app_event", ...(t.grant ? { grant: t.grant } : {}), label: name.split(" · ")[0] || name,
      words: unanswered ? `${triggerWords(t)} · ${unanswered}` : triggerWords(t),
      on: !!subscription, state: subscription ? "On" : "Needs your approval", approval, ...(subscription ? {} : { ok: "approve" as const, tap: "Approve to start listening" }), trigger: t });
  }
  for (const f of k.followUps ?? []) {
    const check = f.input.trim().replace(/\s+/g, " ");
    wakes.push({ id: `trg_${f.id}`, kind: "follow_up", follow_up: f.id, expires: f.at!, label: "Follow-up", on: true, state: timeIn(f.at!, k.zone, k.now),
      words: `Checks again ${timeIn(f.at!, k.zone, k.now)}${check ? `: ${check.length > 120 ? `${check.slice(0, 119)}…` : check}` : ""}`, tap: "Tap to remove this follow-up", ok: "remove_follow_up" });
  }
  wakes.push({ id: "run_now", kind: "run_now", label: "Run now", words: "Runs it once, now", on: true, state: "Now",
    ...(k.unscheduled ? {} : { tap: "Tap to run once", ok: "run" as const }) });
  // The last When stop, for the people who manage it: a new door is Coach's to set up.
  if (k.addDoor) wakes.push({ id: "add_door", kind: "add_door", label: "Add a door", words: "Ask Coach to add a door", on: true, state: "Add", tap: "Tap to ask Coach", ok: "coach" });

  const when: RingItem[] = [];
  if (k.doors.some((d) => d.open)) when.push({ id: "when_arrivals", kind: "arrivals", label: "Arrivals", words: "Works each one as it arrives", on: true, state: "On" });
  if (!k.unscheduled && k.schedule) {
    when.push({ id: "when_missed", kind: "missed", label: "If missed", words: "A run missed by under an hour runs once; later, it's skipped", on: true, state: "Once" });
  }
  const busy = k.setting?.busy ?? k.busy ?? "skip";
  const latest = busy === "latest";
  when.push({ id: "when_busy", kind: "busy", label: "If busy", words: latest ? "Runs the latest once it's free" : "Skips a run while it's working", on: true,
    state: latest ? "Latest" : "Skip", tap: latest ? "Turn to skip a run while it's working" : "Turn to run the latest once it's free" });

  // Every place, on or off: turning one changes where results go, as the taf's next version. The last one on stays.
  const all = k.setting?.results ?? k.places;
  const places = all.filter((p): p is ResultPlace => typeof p === "string");
  const results: Knob["results"] = (Object.keys(PLACE) as ResultPlace[]).map((p) => {
    const on = places.includes(p);
    return { id: `res_${p}`, place: p, label: PLACE[p].label, words: PLACE[p].words, on, state: on ? "On" : "Off",
      ...(on && places.length === 1 ? {} : { tap: on ? `Turn to stop: ${PLACE[p].words.toLowerCase()}` : `Turn on: ${PLACE[p].words.toLowerCase()}` }) };
  });
  // A connected app's action the taf calls itself, or another taf the result is handed to: turning it takes it
  // off; adding one is the picker's or Coach's draft.
  for (const [i, p] of all.filter((x): x is VendorPlace => typeof x !== "string").entries()) {
    const w = placeWords(p);
    const who = p.place === "taf" ? { taf: p.taf } : { grant: p.grant, action: p.action };
    results.push({ id: `res_${p.place}_${i + 1}`, place: p.place, ...who, ...w, on: true, state: "On", tap: `Turn to stop: ${w.words.charAt(0).toLowerCase()}${w.words.slice(1)}` });
  }

  const setting: KnobSetting = k.setting ?? {
    wakes: [...(k.doors.length ? [{ kind: "door" as const }] : []), ...(fromSchedule ? [fromSchedule] : [])],
    busy, results: k.places,
  };
  sizeStops(wakes, results, setting);

  const t = k.today;
  const parts = [`${t.done} done today`, ...(t.working ? [`${t.working} working`] : []), ...(t.retrying ? [`${t.retrying} retrying`] : []),
    ...(t.needs_you ? [`${t.needs_you} need${t.needs_you === 1 ? "s" : ""} you`] : [])];
  return {
    ...(k.version !== undefined ? { version: k.version } : {}), ...(k.shown !== undefined ? { shown: k.shown } : {}),
    does: k.does, wakes, when, results,
    why: (k.setting?.why || k.does.label).slice(0, 60),
    rings: [{ key: "wakes", name: "When" }, { key: "what", name: "What" }, { key: "results", name: "Where" }],
    what: whatOf(k),
    rim: { ...t, line: parts.join(" · ") },
    can_pause: !k.unscheduled,
    connections: connectionsOf(wakes, results),
    draft: k.draft ? { version: k.draft.version, words: settingWords(k.draft.setting), ...ringWords(k.draft.setting),
      what: (k.draft.setting.what ?? []).map((s, i) => `${i + 1} · ${s.label}: ${s.words}`), groups: groupsOf(k.draft.setting) } : null,
    setting,
    pending: k.draft ? { version: k.draft.version, changes: Math.max(1, changesOf(k.setting, k.draft.setting)) } : null,
  };
}

/** How many stops a dial change changes: triggers, places and recipe steps added or removed, and If busy. */
function changesOf(live: KnobSetting | undefined, next: KnobSetting): number {
  const key = (x: unknown) => JSON.stringify(x);
  const diff = (a: unknown[], b: unknown[]) => {
    const as = a.map(key), bs = b.map(key);
    return Math.max(as.filter((x) => !bs.includes(x)).length, bs.filter((x) => !as.includes(x)).length);
  };
  if (!live) return diff([], next.wakes) + diff([], next.results) + diff([], next.what ?? []);
  return diff(live.wakes, next.wakes) + diff(live.results, next.results) + diff(live.what ?? [], next.what ?? []) + (live.busy !== next.busy ? 1 : 0);
}

/** A dial change's lines by connection: a connected app's events and actions under it, everything else under This taf. */
function groupsOf(s: KnobSetting): NonNullable<Knob["draft"]>["groups"] {
  const by = new Map<string, NonNullable<Knob["draft"]>["groups"][number]>();
  const own = { name: "This taf", wakes: [] as string[], what: [] as string[], results: [] as string[] };
  const of = (grant: string | undefined, name: string | undefined) => {
    if (!grant) return own;
    let g = by.get(grant);
    if (!g) by.set(grant, (g = { grant, name: (name ?? "").split(" · ")[0] || "App", wakes: [], what: [], results: [] }));
    return g;
  };
  for (const t of s.wakes) of(t.kind === "app_event" ? t.grant : undefined, t.name).wakes.push(triggerWords(t));
  for (const [i, w] of (s.what ?? []).entries()) own.what.push(`${i + 1} · ${w.label}: ${w.words}`);
  for (const p of s.results) {
    if (typeof p === "string") own.results.push(`Results go to ${p}`);
    else if (p.place === "taf") own.results.push(`Results are handed to ${p.name}`);
    else of(p.grant, p.name).results.push(`Results go to ${p.name}`);
  }
  own.wakes.push(s.busy === "latest" ? "If busy, runs the latest once it's free" : "If busy, skips the run");
  return [...by.values(), own];
}

const TIMES = ["07:00", "08:00", "09:00", "12:00", "17:00", "18:00"];
const EVERY = [15, 30, 60, 120, 240];

/**
 * Each stop's one size, from the dial's own forms, so both apps show the same few values: a schedule's time
 * or how often, and a result place on or off (the last place on stays on).
 */
function sizeStops(wakes: RingItem[], results: Knob["results"], s: KnobSetting): void {
  const json = (x: unknown) => JSON.stringify(x);
  const withWake = (i: number, t: Trigger): KnobSetting => ({ ...s, wakes: s.wakes.map((x, j) => (j === i ? t : x)) });
  for (const w of wakes) {
    const i = w.trigger ? s.wakes.findIndex((x) => json(x) === json(w.trigger)) : -1;
    const t = i >= 0 ? s.wakes[i]! : undefined;
    if (!t || t.kind !== "schedule") continue;
    if (t.at && ["daily", "weekdays", "weekly", "monthly", "monthly_last_day"].includes(t.form ?? "")) {
      const values = [...new Set([...TIMES, t.at])].sort();
      w.size = { label: "Time", index: values.indexOf(t.at), choices: values.map((v) => ({ label: v, setting: withWake(i, { ...t, at: v }) })) };
    } else if (t.form === "every" && t.every_minutes) {
      const values = [...new Set([...EVERY, t.every_minutes])].sort((a, b) => a - b);
      const say = (n: number) => n < 60 ? `Every ${n} minutes` : `Every ${n / 60} hour${n === 60 ? "" : "s"}`;
      w.size = { label: "How often", index: values.indexOf(t.every_minutes), choices: values.map((v) => ({ label: say(v), setting: withWake(i, { ...t, every_minutes: v }) })) };
    }
  }
  // A vendor place is the same one by its connection and action, or by the taf it hands to.
  const same = (x: VendorPlace, p: Knob["results"][number]) => x.place === "taf" ? p.place === "taf" && x.taf === p.taf : p.place === "action" && x.grant === p.grant && x.action === p.action;
  const on = (p: Knob["results"][number]) => s.results.some((x) => (typeof x === "string" ? x === p.place : same(x, p)));
  for (const p of results) {
    const isOn = on(p);
    const without = s.results.filter((x) => (typeof x === "string" ? x !== p.place : !same(x, p)));
    if (isOn && !without.some((x) => typeof x === "string")) continue; // the last place tafs delivers stays on
    const added = isOn ? s.results : typeof p.place === "string" && p.place !== "action" && p.place !== "taf" ? [...s.results, p.place as ResultPlace] : s.results;
    w(p, isOn, without, added);
  }
  function w(p: Knob["results"][number], isOn: boolean, off: KnobSetting["results"], onSet: KnobSetting["results"]) {
    p.size = { label: "On or off", index: isOn ? 1 : 0, choices: [{ label: "Off", setting: { ...s, results: off } }, { label: "On", setting: { ...s, results: onSet } }] };
  }
}

/** Only a saved recipe says what runs first. The order of connected tools says nothing about execution. */
function whatOf(k: KnobInput): Knob["what"] {
  const steps: Step[] = k.setting?.what ?? [];
  if (!steps.length) return [{ id: "what_ask", kind: "step", step: 1, label: "Ask Coach", words: "Ask Coach to set what it does", on: true, state: "Ask", tap: "Tap to ask Coach", ok: "coach" }];
  return steps.map((s, i) => ({ id: `what_${i + 1}`, kind: "step", step: i + 1, label: s.label, words: s.words, on: true, state: `Step ${i + 1}`,
    ...(s.tool ? { tool: s.tool } : {}) }));
}

/** A recipe from a request: steps in order, each a short label and its line; an Error says what's wrong. */
export function recipeOf(v: unknown): Step[] {
  if (!Array.isArray(v)) throw new Error("what is the recipe: a list of steps, in order");
  const one = (x: unknown, max: number) => (typeof x === "string" ? x.replace(/[\u0000-\u001f\u007f]+/g, " ").trim().slice(0, max) : "");
  return v.map((s) => {
    const o = (s && typeof s === "object" ? s : {}) as Record<string, unknown>;
    const label = one(o.label, 24), words = one(o.words, 200), tool = one(o.tool, 128);
    if (!label) throw new Error("each step has a short label");
    return { label, words: words || label, ...(tool ? { tool } : {}) };
  });
}

/** The connections behind the dial's stops, each named by its stops' app ("HubSpot" of "HubSpot · new deal"). */
function connectionsOf(wakes: RingItem[], results: Knob["results"]): Knob["connections"] {
  const by = new Map<string, Knob["connections"][number]>();
  const add = (grant: string | undefined, label: string, ring: "wakes" | "results") => {
    if (!grant) return;
    const c = by.get(grant) ?? { grant, name: label, wakes: 0, results: 0 };
    c[ring]++;
    by.set(grant, c);
  };
  for (const w of wakes) add(w.grant, w.label, "wakes");
  for (const r of results) if (r.on) add(r.grant, r.label, "results");
  return [...by.values()];
}

/** What a dial change does, by ring, for the owner to confirm. */
export function ringWords(k: KnobSetting): { wakes: string[]; when: string[]; results: string[] } {
  return {
    wakes: k.wakes.map(triggerWords),
    when: [k.busy === "latest" ? "If busy, runs the latest once it's free" : "If busy, skips the run"],
    results: [`Results go to ${k.results.map((p) => (typeof p === "string" ? p : p.name)).join(", ")}`],
  };
}

/** The same words in one list, as a dial change answers them. */
export function settingWords(k: KnobSetting): string[] {
  const r = ringWords(k);
  return [...r.wakes, ...r.when, ...r.results];
}

/** A trigger's word on the ring and its line. */
function wordsOf(t: Trigger): { label: string; words: string } {
  if (t.kind === "app_check") return { label: "Check", words: triggerWords(t) };
  const label = t.form === "every" ? `Every ${t.every_minutes} min` : t.form === "weekdays" ? "Weekdays" : t.form === "monthly" || t.form === "monthly_last_day" ? "Monthly"
    : t.form === "once" ? "Once" : t.form === "weekly" ? "Weekly" : t.form === "times_a_day" ? `${t.times?.length ?? 0} a day` : t.at ?? "Schedule";
  return { label, words: triggerWords(t) };
}

/** The calendar day of an instant in zone, as YYYY-MM-DD: what "today" means for the rim. */
export function dayIn(at: string, zone: string): string {
  try {
    return new Intl.DateTimeFormat("en-CA", { timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date(at));
  } catch {
    return at.slice(0, 10);
  }
}

/** What each job does, in one word, for the dial's face. */
export const DOES: Record<string, string> = { refunds: "Refund", billpay: "Pay bills", access: "Access", deploys: "Deploy", chargebacks: "Chargebacks" };
