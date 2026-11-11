/**
 * A taf's dial (the knob): what wakes it, when, and where its results go, with the face's word
 * for each and what tapping it does, all in tafs cloud's words. The apps draw them as they are
 * and compose none (docs/api.md, "The knob").
 */
import type { Schedule } from "./agents.js";
import type { ResultPlace } from "./handler.js";
import { type Check, plannedOf, type Trigger, triggerOf, triggerWords, sameAppEvent } from "./triggers.js";
import { placeWords, type VendorPlace } from "./places.js";
import { timeIn } from "./work.js";
import { everyWords, type Lang, plural, wordsIn } from "./words/index.js";

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
  /** The read each of its schedules makes first (triggers.ts, `Check`): kept from version to version, applied when the version goes live. */
  check?: Check;
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
  /** The person's language (docs/languages.md): the dial's words are written in it. */
  lang?: Lang;
  /** The read its schedules make first, as the taf keeps it: when the live version's dial names none. */
  check?: Check;
}

/** A ring's short word for how often: "Every 15 min", or whole hours as hours ("Every 4 hours"). */
function everyLabel(lang: Lang, minutes: number): string {
  return minutes >= 60 && minutes % 60 === 0 ? everyWords(lang, minutes) : wordsIn(lang).dial_every_min(minutes);
}

/** The schedule in words, for the forms the dial sets; any other cron reads as "On a schedule". */
export function cronWords(cron: string, lang: Lang = "en"): { label: string; words: string } {
  const w = wordsIn(lang);
  const f = cron.trim().split(/\s+/);
  if (f.length !== 5) return { label: w.dial_schedule, words: w.dial_on_schedule };
  const [min, hour, dom, mon, dow] = f as [string, string, string, string, string];
  const at = (h: string, m: string) => `${h.padStart(2, "0")}:${m.padStart(2, "0")}`;
  const plain = (s: string) => /^\d{1,2}$/.test(s);
  const every = /^\*\/(\d+)$/.exec(min);
  if (every && hour === "*" && dom === "*" && mon === "*" && dow === "*") return { label: everyLabel(lang, Number(every[1])), words: everyWords(lang, Number(every[1])) };
  const between = /^(\d{1,2})-(\d{1,2})$/.exec(hour);
  if (every && between && dom === "*" && mon === "*") {
    if (dow === "1-5" || dow === "*") return { label: everyLabel(lang, Number(every[1])), words: w.dial_every_between(Number(every[1]), at(between[1]!, "0"), at(String(Number(between[2]) + 1), "0"), dow === "1-5") };
  }
  if (!plain(min) || !plain(hour) || mon !== "*") return { label: w.dial_schedule, words: w.dial_on_schedule };
  const t = at(hour, min);
  if (dom === "*" && dow === "*") return { label: t, words: w.dial_daily(t) };
  if (dom === "*" && dow === "1-5") return { label: w.dial_weekdays, words: w.dial_weekdays_at(t) };
  if (dom === "*" && /^[0-6](,[0-6])*$/.test(dow)) {
    const days = dow.split(",").map(Number);
    return { label: days.length === 1 ? w.dial_day_short(days[0]!) : w.dial_weekly, words: w.dial_days_at(days, t) };
  }
  if (plain(dom) && dow === "*") return { label: w.dial_monthly, words: w.dial_monthly_at(Number(dom), t) };
  return { label: w.dial_schedule, words: w.dial_on_schedule };
}

/** tafs' own places, in the person's language. */
function placeOf(lang: Lang): Record<ResultPlace, { label: string; words: string }> {
  const w = wordsIn(lang);
  return {
    door_reply: { label: w.dial_place_sender, words: w.dial_place_sender_words },
    app: { label: w.dial_place_you, words: w.dial_place_you_words },
    owner_email: { label: w.dial_place_email, words: w.dial_place_email_words },
  };
}

export function knobOf(k: KnobInput): Knob {
  const lang = k.lang ?? "en";
  const w = wordsIn(lang);
  const PLACE = placeOf(lang);
  const time = (at: string) => timeIn(at, k.zone, k.now, lang);
  const wakes: RingItem[] = [];
  for (const d of k.doors) {
    wakes.push({ id: `door_${d.id}`, kind: "door", door: d.id, label: d.label, words: d.unanswered ? `${d.words.state} · ${d.unanswered}` : d.words.state, on: d.open,
      state: d.open ? w.dial_open : w.dial_closed, tap: d.words.tap, ok: "door",
      look: d.knocks ? "knock" : d.resting && d.open ? "resting" : d.open ? "open" : "closed" });
  }
  // The dial's triggers: as the live version set them, or the taf's own schedule read back.
  const fromSchedule = !k.unscheduled && k.schedule ? triggerOf(k.schedule, k.zone) : undefined;
  const timed = k.setting ? k.setting.wakes.filter((t) => t.kind === "schedule" || t.kind === "app_check") : [];
  // The taf's check, unless the live version has none of its own and more than one timetable: that version was
  // written unchecked (applyKnob), so its schedules don't check first and the words don't say they do.
  const check = k.setting?.check ?? (k.check && (!k.setting || takesCheck(k.setting, k.check)) ? k.check : undefined);
  if (!k.unscheduled && k.schedule && (!k.setting || timed.length)) {
    const s = k.schedule;
    const next = s.enabled && s.next ? w.dial_next(time(s.next)) : "";
    const stops = k.setting ? timed.map((t) => ({ trigger: t, ...wordsOf(t, lang) })) : [{ trigger: fromSchedule, ...cronWords(s.cron, lang) }];
    for (const [i, t] of stops.entries()) {
      // The scheduler says when the taf's own schedule fires next; that's the first trigger's.
      const first = check && t.trigger?.kind !== "app_check" ? ` · ${w.dial_checks_first}` : "";
      const words = s.enabled ? `${t.words}${i === 0 ? next : ""}${first}` : `${t.words}${w.dial_paused_after}${first}`;
      wakes.push({ id: i === 0 ? "trg_schedule" : `trg_${i + 1}`, kind: t.trigger?.kind === "app_check" ? "app_check" : "schedule", label: t.label, words,
        on: s.enabled, state: s.enabled ? t.label : w.dial_paused, ...(t.trigger ? { trigger: t.trigger } : {}) });
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
      words: unanswered ? `${triggerWords(t, lang)} · ${unanswered}` : triggerWords(t, lang),
      on: !!subscription, state: subscription ? w.dial_on : w.dial_needs_approval, approval, ...(subscription ? {} : { ok: "approve" as const, tap: w.dial_approve_tap }), trigger: t });
  }
  for (const f of k.followUps ?? []) {
    const check = f.input.trim().replace(/\s+/g, " ");
    wakes.push({ id: `trg_${f.id}`, kind: "follow_up", follow_up: f.id, expires: f.at!, label: w.dial_follow_up, on: true, state: time(f.at!),
      words: w.dial_checks_again(time(f.at!), check.length > 120 ? `${check.slice(0, 119)}…` : check), tap: w.dial_follow_up_tap, ok: "remove_follow_up" });
  }
  wakes.push({ id: "run_now", kind: "run_now", label: w.from_run_now, words: w.dial_run_now_words, on: true, state: w.dial_now,
    ...(k.unscheduled ? {} : { tap: w.dial_run_tap, ok: "run" as const }) });
  // The last When stop, for the people who manage it: a new door is Coach's to set up.
  if (k.addDoor) wakes.push({ id: "add_door", kind: "add_door", label: w.dial_add_door, words: w.dial_add_door_words, on: true, state: w.dial_add, tap: w.dial_coach_tap, ok: "coach" });

  const when: RingItem[] = [];
  if (k.doors.some((d) => d.open)) when.push({ id: "when_arrivals", kind: "arrivals", label: w.dial_arrivals, words: w.dial_arrivals_words, on: true, state: w.dial_on });
  if (!k.unscheduled && k.schedule) {
    when.push({ id: "when_missed", kind: "missed", label: w.dial_if_missed, words: w.dial_missed_words, on: true, state: w.dial_once });
  }
  const busy = k.setting?.busy ?? k.busy ?? "skip";
  const latest = busy === "latest";
  when.push({ id: "when_busy", kind: "busy", label: w.dial_if_busy, words: latest ? w.dial_busy_latest : w.dial_busy_skip, on: true,
    state: latest ? w.dial_latest : w.dial_skip, tap: latest ? w.dial_tap_skip : w.dial_tap_latest });

  // Every place, on or off: turning one changes where results go, as the taf's next version. The last one on stays.
  const all = k.setting?.results ?? k.places;
  const places = all.filter((p): p is ResultPlace => typeof p === "string");
  const results: Knob["results"] = (Object.keys(PLACE) as ResultPlace[]).map((p) => {
    const on = places.includes(p);
    return { id: `res_${p}`, place: p, label: PLACE[p].label, words: PLACE[p].words, on, state: on ? w.dial_on : w.dial_off,
      ...(on && places.length === 1 ? {} : { tap: on ? w.dial_turn_stop(PLACE[p].words) : w.dial_turn_on(PLACE[p].words) }) };
  });
  // A connected app's action the taf calls itself, or another taf the result is handed to: turning it takes it
  // off; adding one is the picker's or Coach's draft.
  for (const [i, p] of all.filter((x): x is VendorPlace => typeof x !== "string").entries()) {
    const pw = placeWords(p, lang);
    const who = p.place === "taf" ? { taf: p.taf } : { grant: p.grant, action: p.action };
    results.push({ id: `res_${p.place}_${i + 1}`, place: p.place, ...who, ...pw, on: true, state: w.dial_on, tap: w.dial_turn_stop(pw.words) });
  }

  const setting: KnobSetting = k.setting ?? {
    wakes: [...(k.doors.length ? [{ kind: "door" as const }] : []), ...(fromSchedule ? [fromSchedule] : [])],
    busy, results: k.places, ...(k.check ? { check: k.check } : {}),
  };
  sizeStops(wakes, results, setting, lang);

  const t = k.today;
  const parts = [w.dial_done_today(t.done), ...(t.working ? [w.dial_working(t.working)] : []), ...(t.retrying ? [w.dial_retrying(t.retrying)] : []),
    ...(t.needs_you ? [w.dial_need_you(t.needs_you, plural(lang, t.needs_you))] : [])];
  return {
    ...(k.version !== undefined ? { version: k.version } : {}), ...(k.shown !== undefined ? { shown: k.shown } : {}),
    does: k.does, wakes, when, results,
    why: (k.setting?.why || k.does.label).slice(0, 60),
    rings: [{ key: "wakes", name: w.dial_ring_when }, { key: "what", name: w.dial_ring_what }, { key: "results", name: w.dial_ring_where }],
    what: whatOf(k, lang),
    rim: { ...t, line: parts.join(" · ") },
    can_pause: !k.unscheduled,
    connections: connectionsOf(wakes, results),
    draft: k.draft ? { version: k.draft.version, words: settingWords(k.draft.setting, lang), ...ringWords(k.draft.setting, lang),
      what: (k.draft.setting.what ?? []).map((s, i) => `${i + 1} · ${s.label}: ${s.words}`), groups: groupsOf(k.draft.setting, lang) } : null,
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
  if (!live) return diff([], next.wakes) + diff([], next.results) + diff([], next.what ?? []) + (next.check ? 1 : 0);
  return diff(live.wakes, next.wakes) + diff(live.results, next.results) + diff(live.what ?? [], next.what ?? []) + (live.busy !== next.busy ? 1 : 0)
    + (key(live.check ?? null) !== key(next.check ?? null) ? 1 : 0);
}

/** A dial change's lines by connection: a connected app's events and actions under it, everything else under This taf. */
function groupsOf(s: KnobSetting, lang: Lang = "en"): NonNullable<Knob["draft"]>["groups"] {
  const w = wordsIn(lang);
  const by = new Map<string, NonNullable<Knob["draft"]>["groups"][number]>();
  const own = { name: w.dial_this_taf, wakes: [] as string[], what: [] as string[], results: [] as string[] };
  const of = (grant: string | undefined, name: string | undefined) => {
    if (!grant) return own;
    let g = by.get(grant);
    if (!g) by.set(grant, (g = { grant, name: (name ?? "").split(" · ")[0] || w.dial_app, wakes: [], what: [], results: [] }));
    return g;
  };
  for (const t of s.wakes) of(t.kind === "app_event" ? t.grant : undefined, t.name).wakes.push(checkedWords(t, s.check, lang));
  for (const [i, w] of (s.what ?? []).entries()) own.what.push(`${i + 1} · ${w.label}: ${w.words}`);
  for (const p of s.results) {
    if (typeof p === "string") own.results.push(w.dial_results_go(p));
    else if (p.place === "taf") own.results.push(w.dial_results_handed(p.name));
    else of(p.grant, p.name).results.push(w.dial_results_go(p.name));
  }
  own.wakes.push(s.busy === "latest" ? w.dial_busy_latest_line : w.dial_busy_skip_line);
  return [...by.values(), own];
}

const TIMES = ["07:00", "08:00", "09:00", "12:00", "17:00", "18:00"];
const EVERY = [15, 30, 60, 120, 240];

/**
 * Each stop's one size, from the dial's own forms, so both apps show the same few values: a schedule's time
 * or how often, and a result place on or off (the last place on stays on).
 */
function sizeStops(wakes: RingItem[], results: Knob["results"], s: KnobSetting, lang: Lang = "en"): void {
  const words = wordsIn(lang);
  const json = (x: unknown) => JSON.stringify(x);
  const withWake = (i: number, t: Trigger): KnobSetting => ({ ...s, wakes: s.wakes.map((x, j) => (j === i ? t : x)) });
  for (const w of wakes) {
    const i = w.trigger ? s.wakes.findIndex((x) => json(x) === json(w.trigger)) : -1;
    const t = i >= 0 ? s.wakes[i]! : undefined;
    if (!t || t.kind !== "schedule") continue;
    if (t.at && ["daily", "weekdays", "weekly", "monthly", "monthly_last_day"].includes(t.form ?? "")) {
      const values = [...new Set([...TIMES, t.at])].sort();
      w.size = { label: words.dial_time, index: values.indexOf(t.at), choices: values.map((v) => ({ label: v, setting: withWake(i, { ...t, at: v }) })) };
    } else if (t.form === "every" && t.every_minutes) {
      const values = [...new Set([...EVERY, t.every_minutes])].sort((a, b) => a - b);
      const say = (n: number) => everyWords(lang, n);
      w.size = { label: words.dial_how_often, index: values.indexOf(t.every_minutes), choices: values.map((v) => ({ label: say(v), setting: withWake(i, { ...t, every_minutes: v }) })) };
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
    p.size = { label: words.dial_on_or_off, index: isOn ? 1 : 0, choices: [{ label: words.dial_off, setting: { ...s, results: off } }, { label: words.dial_on, setting: { ...s, results: onSet } }] };
  }
}

/** Only a saved recipe says what runs first. The order of connected tools says nothing about execution. */
function whatOf(k: KnobInput, lang: Lang = "en"): Knob["what"] {
  const w = wordsIn(lang);
  const steps: Step[] = k.setting?.what ?? [];
  if (!steps.length) return [{ id: "what_ask", kind: "step", step: 1, label: w.dial_ask_coach, words: w.dial_ask_coach_words, on: true, state: w.dial_ask, tap: w.dial_coach_tap, ok: "coach" }];
  return steps.map((s, i) => ({ id: `what_${i + 1}`, kind: "step", step: i + 1, label: s.label, words: s.words, on: true, state: w.dial_step(i + 1),
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
export function ringWords(k: KnobSetting, lang: Lang = "en"): { wakes: string[]; when: string[]; results: string[] } {
  const w = wordsIn(lang);
  return {
    wakes: k.wakes.map((t) => checkedWords(t, k.check, lang)),
    when: [k.busy === "latest" ? w.dial_busy_latest_line : w.dial_busy_skip_line],
    results: [w.dial_results_go(k.results.map((p) => (typeof p === "string" ? p : p.name)).join(", "))],
  };
}

/** The same words in one list, as a dial change answers them (a version's note keeps them in English). */
export function settingWords(k: KnobSetting, lang: Lang = "en"): string[] {
  const r = ringWords(k, lang);
  return [...r.wakes, ...r.when, ...r.results];
}

/** Whether a dial's triggers make one timetable, so its schedules can carry the check (triggers.ts, plannedOf). */
function takesCheck(s: KnobSetting, check: Check): boolean {
  try { plannedOf(s.wakes, s.busy, check); return true; } catch { return false; }
}

/** A trigger's line, and that it checks first when it's a schedule of a taf with a check: "Weekdays at 09:00 · checks for new work first". */
function checkedWords(t: Trigger, check: Check | undefined, lang: Lang = "en"): string {
  const words = triggerWords(t, lang);
  return check && t.kind === "schedule" ? `${words} · ${wordsIn(lang).dial_checks_first}` : words;
}

/** A trigger's word on the ring and its line. */
function wordsOf(t: Trigger, lang: Lang = "en"): { label: string; words: string } {
  const w = wordsIn(lang);
  if (t.kind === "app_check") return { label: w.dial_check, words: triggerWords(t, lang) };
  const label = t.form === "every" ? everyLabel(lang, t.every_minutes ?? 0) : t.form === "weekdays" ? w.dial_weekdays : t.form === "monthly" || t.form === "monthly_last_day" ? w.dial_monthly
    : t.form === "once" ? w.dial_once : t.form === "weekly" ? w.dial_weekly : t.form === "times_a_day" ? w.dial_n_a_day(t.times?.length ?? 0) : t.at ?? w.dial_schedule;
  return { label, words: triggerWords(t, lang) };
}

/** The calendar day of an instant in zone, as YYYY-MM-DD: what "today" means for the rim. */
export function dayIn(at: string, zone: string): string {
  try {
    let format = dayFormats.get(zone);
    if (!format) {
      format = new Intl.DateTimeFormat("en-CA", { timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit" });
      dayFormats.set(zone, format);
    }
    return format.format(new Date(at));
  } catch {
    return at.slice(0, 10);
  }
}

/** One day formatter per zone: making one is slow, and an export reads a day for each of thousands of calls. */
const dayFormats = new Map<string, Intl.DateTimeFormat>();

/** What each job does, in one word, for the dial's face. */
export const DOES: Record<string, string> = { refunds: "Refund", billpay: "Pay bills", access: "Access", deploys: "Deploy", chargebacks: "Chargebacks" };
