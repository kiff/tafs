/**
 * A taf's dial (the knob): what wakes it, when, and where its results go, with the face's word
 * for each and what tapping it does, all in tafs cloud's words. The apps draw them as they are
 * and compose none (docs/api.md, "The knob").
 */
import type { Schedule } from "./agents.js";
import type { ResultPlace } from "./handler.js";
import { type Trigger, triggerOf, triggerWords } from "./triggers.js";
import { type VendorPlace, vendorLine, vendorWords } from "./places.js";
import { timeIn } from "./work.js";

/** A version's dial as PUT takes it: its triggers, what a busy taf does, where results go. */
export interface KnobSetting { wakes: Trigger[]; busy: "skip" | "latest"; results: Array<ResultPlace | VendorPlace> }

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
  /** A trigger's form exactly as PUT takes it; absent on run now and on a schedule the dial can't set. */
  trigger?: Trigger;
  /** A follow-up's id, for DELETE /v1/tafs/{id}/follow-ups/{fid}, and when it fires. */
  follow_up?: string;
  expires?: string;
}

export interface Knob {
  version?: number;
  does: { label: string; line: string };
  wakes: RingItem[];
  when: RingItem[];
  results: (Omit<RingItem, "kind"> & { place: ResultPlace | VendorPlace["place"] })[];
  rim: { done: number; working: number; retrying: number; needs_you: number; line: string };
  /** A dial change waiting for the owner: its words, all of them and by ring, for Coach's card and the dial. */
  draft: null | { version: number; words: string[]; wakes: string[]; when: string[]; results: string[] };
  /** The shown version's dial as PUT takes it: a tap copies it, changes one thing and sends it back. */
  setting: KnobSetting;
}

/** A door as the doors list gives it. */
export interface KnobDoor { id: string; label: string; open: boolean; words: { state: string; tap: string } }

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
  /** The follow-ups the taf set itself, waiting to fire. */
  followUps?: Schedule[];
  /** A newer version's dial, made and not yet live: what waits for the owner's confirmation. */
  draft?: { version: number; setting: KnobSetting };
  /** The live version's dial, when a tap or Coach's draft set it; else it's read from the schedule. */
  setting?: KnobSetting;
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
    wakes.push({ id: `door_${d.id}`, kind: "door", door: d.id, label: d.label, words: d.words.state, on: d.open,
      state: d.open ? "Open" : "Closed", tap: d.words.tap });
  }
  // The dial's triggers: as the live version set them, or the taf's own schedule read back.
  const fromSchedule = !k.unscheduled && k.schedule ? triggerOf(k.schedule, k.zone) : undefined;
  const timed = k.setting ? k.setting.wakes.filter((t) => t.kind !== "door") : [];
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
  for (const f of k.followUps ?? []) {
    const check = f.input.trim().replace(/\s+/g, " ");
    wakes.push({ id: `trg_${f.id}`, kind: "follow_up", follow_up: f.id, expires: f.at!, label: "Follow-up", on: true, state: timeIn(f.at!, k.zone, k.now),
      words: `Checks again ${timeIn(f.at!, k.zone, k.now)}${check ? `: ${check.length > 120 ? `${check.slice(0, 119)}…` : check}` : ""}`, tap: "Tap to remove this follow-up" });
  }
  wakes.push({ id: "run_now", kind: "run_now", label: "Run now", words: "Runs it once, now", on: true, state: "Now",
    ...(k.unscheduled ? {} : { tap: "Tap to run once" }) });

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
  // A vendor place the taf writes itself: turning it takes it off; adding one is Coach's draft, since it needs a page, an address or a channel.
  for (const [i, p] of all.filter((x): x is VendorPlace => typeof x !== "string").entries()) {
    const w = vendorWords(p);
    results.push({ id: `res_${p.place}_${i + 1}`, place: p.place, ...w, on: true, state: "On", tap: `Turn to stop: ${w.words.charAt(0).toLowerCase()}${w.words.slice(1)}` });
  }

  const t = k.today;
  const parts = [`${t.done} done today`, ...(t.working ? [`${t.working} working`] : []), ...(t.retrying ? [`${t.retrying} retrying`] : []),
    ...(t.needs_you ? [`${t.needs_you} need${t.needs_you === 1 ? "s" : ""} you`] : [])];
  return {
    ...(k.version !== undefined ? { version: k.version } : {}),
    does: k.does, wakes, when, results,
    rim: { ...t, line: parts.join(" · ") },
    draft: k.draft ? { version: k.draft.version, words: settingWords(k.draft.setting), ...ringWords(k.draft.setting) } : null,
    setting: k.setting ?? {
      wakes: [...(k.doors.length ? [{ kind: "door" as const }] : []), ...(fromSchedule ? [fromSchedule] : [])],
      busy, results: k.places,
    },
  };
}

/** What a dial change does, by ring, for the owner to confirm. */
export function ringWords(k: KnobSetting): { wakes: string[]; when: string[]; results: string[] } {
  return {
    wakes: k.wakes.map(triggerWords),
    when: [k.busy === "latest" ? "If busy, runs the latest once it's free" : "If busy, skips the run"],
    results: [`Results go to ${k.results.map((p) => (typeof p === "string" ? p : vendorLine(p))).join(", ")}`],
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
