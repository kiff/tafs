import { describe, expect, it } from "vitest";
import { cronWords, dayIn, knobOf, type KnobInput, recipeOf } from "../src/cloud/knob.js";

const now = new Date("2026-11-02T21:00:00Z");
const base: KnobInput = {
  name: "Refunds", does: { label: "Refund", line: "Refunds a payment in Stripe, within its Card." }, unscheduled: false,
  schedule: { id: "s", agent: "a", cron: "0 9 * * 1-5", time_zone: "Europe/Paris", input: "", enabled: true, next: "2026-11-03T08:00:00Z" },
  doors: [{ id: "d1", label: "Email", open: true, words: { state: "Open: messages from you and paired senders to r@x start work.", tap: "Tap to close the email door." } }],
  places: ["door_reply", "app"], today: { done: 12, working: 1, retrying: 1, needs_you: 1 }, zone: "Europe/Paris", now,
};

describe("the dial", () => {
  it("says every ring in tafs cloud's words, with the face's word and tap", () => {
    const k = knobOf(base);
    expect(k.does).toEqual({ label: "Refund", line: "Refunds a payment in Stripe, within its Card." });
    expect(k.wakes.map((w) => [w.kind, w.label, w.state, w.tap])).toEqual([
      ["door", "Email", "Open", "Tap to close the email door."],
      ["schedule", "Weekdays", "Weekdays", undefined],
      ["run_now", "Run now", "Now", "Tap to run once"],
    ]);
    // A door's stop carries its door, for the doors API.
    expect(k.wakes[0]!.door).toBe("d1");
    expect(k.wakes[1]!.words).toBe("Weekdays at 09:00, next Tue 09:00");
    expect(k.when.map((w) => w.kind)).toEqual(["arrivals", "missed", "busy"]);
    // Every place is on the ring, on or off, and turning one says what it changes; If busy turns too.
    expect(k.results.map((r) => [r.place, r.label, r.state, r.tap])).toEqual([
      ["door_reply", "Sender", "On", "Turn to stop: replies to whoever wrote"],
      ["app", "You", "On", "Turn to stop: shows each result in the app"],
      ["owner_email", "Email", "Off", "Turn on: emails you each result"],
    ]);
    expect(k.when.find((w) => w.kind === "busy")!.tap).toBe("Turn to run the latest once it's free");
    expect(k.rim.line).toBe("12 done today · 1 working · 1 retrying · 1 needs you");
    expect(k.draft).toBeNull();
    // Each trigger carries its form as PUT takes it, and the knob the whole setting.
    expect(k.wakes[1]!.trigger).toEqual({ kind: "schedule", form: "weekdays", at: "09:00" });
    expect(k.setting).toEqual({ wakes: [{ kind: "door" }, { kind: "schedule", form: "weekdays", at: "09:00" }], busy: "skip", results: ["door_reply", "app"] });
  });

  it("says every ring in the person's language: its stops, states, taps, times and the rim", () => {
    const fr = knobOf({ ...base, lang: "fr" });
    expect(fr.wakes.map((w) => [w.label, w.state, w.tap ?? null])).toEqual([
      ["Email", "Ouverte", "Tap to close the email door."],
      ["En semaine", "En semaine", null],
      ["Lancer maintenant", "Maintenant", "Touchez pour lancer une fois"],
    ]);
    const tue = new Intl.DateTimeFormat("fr", { timeZone: "Europe/Paris", weekday: "short" }).format(new Date("2026-11-03T08:00:00Z"));
    expect(fr.wakes[1]!.words).toBe(`En semaine à 09:00, prochain ${tue} 09:00`);
    expect(fr.results.map((r) => [r.label, r.state, r.tap])).toEqual([
      ["Expéditeur", "Activé", "Tournez pour arrêter : répond à la personne qui a écrit"],
      ["Vous", "Activé", "Tournez pour arrêter : affiche chaque résultat dans l'app"],
      ["E-mail", "Désactivé", "Activer : vous envoie chaque résultat par e-mail"],
    ]);
    expect(fr.rings.map((r) => r.name)).toEqual(["Quand", "Quoi", "Où"]);
    expect(fr.rim.line).toBe("12 terminées aujourd'hui · 1 en cours · 1 en nouvel essai · 1 vous attend");
    expect(fr.what[0]).toMatchObject({ label: "Demander à Coach", state: "Demander" });
    // The setting each stop sends back is the same in every language: only words change.
    expect(fr.setting).toEqual(knobOf(base).setting);
    // A schedule read back from cron, in German and Polish, with the weekday in the language.
    expect(cronWords("0 9 * * 1,5", "de").words).toBe(`${new Intl.ListFormat("de", { type: "conjunction" }).format(["Montag", "Freitag"])} um 09:00`);
    expect(cronWords("*/15 8-17 * * 1-5", "pl")).toEqual({ label: "Co 15 min", words: "Co 15 minut od 08:00 do 18:00 w dni robocze" });
    expect(cronWords("0 9 1 * *", "es")).toEqual({ label: "Mensual", words: "El día 1 de cada mes a las 09:00" });
    // English as before.
    expect(cronWords("0 9 * * 1,5")).toEqual({ label: "Weekly", words: "Mondays and Fridays at 09:00" });
  });

  it("a version's dial shows each of its triggers, as it set them", () => {
    const setting = { wakes: [{ kind: "door" as const }, { kind: "schedule" as const, form: "daily" as const, at: "09:00" },
      { kind: "schedule" as const, form: "every" as const, every_minutes: 30, between: { from: "08:00", to: "18:00" } }], busy: "latest" as const, results: ["app" as const] };
    const k = knobOf({ ...base, schedule: { ...base.schedule!, cron: "0 9 * * *" }, setting, version: 3 });
    expect(k.wakes.map((w) => [w.id, w.label, w.words])).toEqual([
      ["door_d1", "Email", "Open: messages from you and paired senders to r@x start work."],
      ["trg_schedule", "09:00", "Every day at 09:00, next Tue 09:00"],
      ["trg_2", "Every 30 min", "Every 30 minutes from 08:00 to 18:00"],
      ["run_now", "Run now", "Runs it once, now"],
    ]);
    expect(k.wakes[2]!.trigger).toEqual(setting.wakes[2]);
    expect(k.when.find((w) => w.kind === "busy")!.state).toBe("Latest");
    expect(k.setting).toEqual(setting);
    expect(k.version).toBe(3);
    // The last place on can't be turned off.
    expect(k.results.find((r) => r.place === "app")!.tap).toBeUndefined();
  });

  it("a taf that works from its chat has no schedule, and its face doesn't run it", () => {
    const k = knobOf({ ...base, unscheduled: true, schedule: undefined, doors: [], today: { done: 0, working: 0, retrying: 0, needs_you: 0 } });
    expect(k.wakes.map((w) => w.kind)).toEqual(["run_now"]);
    expect(k.wakes[0]!.tap).toBeUndefined();
    expect(k.when.map((w) => w.kind)).toEqual(["busy"]);
    expect(k.rim.line).toBe("0 done today");
    expect(k.can_pause).toBe(false);
    expect(knobOf(base).can_pause).toBe(true);
  });

  it("a follow-up the taf set itself shows on Wakes it, with when it fires and how to remove it", () => {
    const f = { id: "fu-ab12", agent: "a", cron: "", input: "Check refund re_9 went through", enabled: true, kind: "once" as const, at: "2026-11-03T08:00:00Z", follow_up: true };
    const k = knobOf({ ...base, followUps: [f] });
    expect(k.wakes.find((w) => w.kind === "follow_up")).toEqual({ id: "trg_fu-ab12", kind: "follow_up", follow_up: "fu-ab12", expires: "2026-11-03T08:00:00Z",
      label: "Follow-up", on: true, state: "Tue 09:00", words: "Checks again Tue 09:00: Check refund re_9 went through", tap: "Tap to remove this follow-up", ok: "remove_follow_up" });
    // It's not one of the dial's triggers: PUT never carries it.
    expect(k.setting.wakes.some((t) => (t.kind as string) === "follow_up")).toBe(false);
  });

  it("an app's event the live version wakes on has its own stop, named as the owner picked it", () => {
    const ev = { kind: "app_event" as const, grant: "grt_hub", event: "HUBSPOT_DEAL_CREATED", name: "HubSpot · new deal" };
    const k = knobOf({ ...base, setting: { wakes: [{ kind: "door" }, ev], busy: "skip", results: ["app"] } });
    expect(k.wakes.find((w) => w.kind === "app_event")).toMatchObject({ grant: "grt_hub", label: "HubSpot", words: "Wakes on HubSpot · new deal", trigger: ev });
    // Until the owner's phone approves it at planque, the stop says so and pressing the face approves.
    expect(k.wakes.find((w) => w.kind === "app_event")).toMatchObject({ on: false, ok: "approve",
      approval: { state: "needed", grant: "grt_hub", event: "HUBSPOT_DEAL_CREATED", config: {} } });
    const ok = knobOf({ ...base, setting: { wakes: [ev], busy: "skip", results: ["app"] }, appEvents: [{ ...ev, subscription: "sub_1" }] });
    expect(ok.wakes.find((w) => w.kind === "app_event")).toMatchObject({ on: true, approval: { state: "approved", subscription: "sub_1" } });
    expect(ok.wakes.find((w) => w.kind === "app_event")!.ok).toBeUndefined();
    // One that went unanswered while the tafs waited for credits says so on its stop, in tafs cloud's words.
    const missed = knobOf({ ...base, setting: { wakes: [ev], busy: "skip", results: ["app"] }, appEvents: [{ ...ev, subscription: "sub_1" }],
      unansweredEvents: [{ grant: "grt_hub", event: "HUBSPOT_DEAL_CREATED", line: "2 events went unanswered while your tafs waited for credits" }] });
    expect(missed.wakes.find((w) => w.kind === "app_event")!.words).toBe("Wakes on HubSpot · new deal · 2 events went unanswered while your tafs waited for credits");
    // Other filters are another subscription: still to approve.
    const other = knobOf({ ...base, setting: { wakes: [{ ...ev, config: { pipeline: "2" } }], busy: "skip", results: ["app"] }, appEvents: [{ ...ev, subscription: "sub_1" }] });
    expect(other.wakes.find((w) => w.kind === "app_event")!.approval!.state).toBe("needed");
    // The same filters written in another key order are the same subscription.
    const nested = { ...ev, config: { a: 1, b: { x: 1, y: [1, 2] } } };
    const reordered = knobOf({ ...base, setting: { wakes: [{ ...ev, config: { b: { y: [1, 2], x: 1 }, a: 1 } }], busy: "skip", results: ["app"] }, appEvents: [{ ...nested, subscription: "sub_2" }] });
    expect(reordered.wakes.find((w) => w.kind === "app_event")!.approval!.state).toBe("approved");
    // The dial groups stops by connection, for its chips: All · HubSpot · This taf.
    const two = knobOf({ ...base, setting: { wakes: [ev, { ...ev, event: "HUBSPOT_DEAL_WON", name: "HubSpot · deal won" }],
      busy: "skip", results: ["app", { place: "action", grant: "grt_slack", action: "slack_post", args: { channel: "C1" }, name: "Slack · #sales" }] } });
    expect(two.connections).toEqual([{ grant: "grt_hub", name: "HubSpot", wakes: 2, results: 0 }, { grant: "grt_slack", name: "Slack", wakes: 0, results: 1 }]);
    // It isn't a schedule: the schedule's stop isn't shown for a dial with no times.
    expect(k.wakes.some((w) => w.kind === "schedule")).toBe(false);
  });

  it("the rings are When, What and Where, the face its Why, and What is Coach's recipe in order", () => {
    const k = knobOf({ ...base, setting: { wakes: [{ kind: "door" }], busy: "skip", results: ["app"], why: "Refund double charges",
      what: [{ label: "Check", words: "Looks up the charge in Stripe", tool: "stripe_get_charge" }, { label: "Refund", words: "Refunds it, within its Card" }] } });
    expect(k.rings.map((r) => r.name)).toEqual(["When", "What", "Where"]);
    expect(k.why).toBe("Refund double charges");
    expect(k.what.map((w) => [w.step, w.label, w.tool])).toEqual([[1, "Check", "stripe_get_charge"], [2, "Refund", undefined]]);
    // Connected-tool order is not execution order. A taf without a saved recipe asks Coach.
    const plain = knobOf({ ...base, tools: new Map([["stripe_refund", "Refund a payment"]]) });
    expect(plain.what.map((w) => w.label)).toEqual(["Ask Coach"]);
    expect(plain.why).toBe("Refund");
  });

  it("each stop's one size comes from tafs cloud, each choice the whole setting that makes it", () => {
    const setting = { wakes: [{ kind: "door" as const }, { kind: "schedule" as const, form: "weekdays" as const, at: "09:00" }], busy: "skip" as const, results: ["app" as const, "door_reply" as const] };
    const k = knobOf({ ...base, schedule: { ...base.schedule!, cron: "0 9 * * 1-5" }, setting });
    const sched = k.wakes.find((w) => w.kind === "schedule")!;
    expect(sched.size!.label).toBe("Time");
    expect(sched.size!.choices[sched.size!.index]!.label).toBe("09:00");
    const at17 = sched.size!.choices.find((c) => c.label === "17:00")!.setting;
    expect(at17.wakes).toEqual([{ kind: "door" }, { kind: "schedule", form: "weekdays", at: "17:00" }]);
    expect(at17.results).toEqual(["app", "door_reply"]);
    // A place on or off; the email to the owner is off, and turning it on adds it.
    const email = k.results.find((r) => r.place === "owner_email")!;
    expect(email.size!.index).toBe(0);
    expect(email.size!.choices[1]!.setting.results).toEqual(["app", "door_reply", "owner_email"]);
    const app = k.results.find((r) => r.place === "app")!;
    expect(app.size!.choices[0]!.setting.results).toEqual(["door_reply"]);
    // The last place tafs delivers has no size: it can't be turned off.
    const one = knobOf({ ...base, setting: { ...setting, results: ["app"] } });
    expect(one.results.find((r) => r.place === "app")!.size).toBeUndefined();
  });

  it("supplies the current How often value in full words", () => {
    const k = knobOf({ ...base, setting: { wakes: [{ kind: "schedule", form: "every", every_minutes: 240 }], busy: "skip", results: ["app"] } });
    const size = k.wakes.find((w) => w.kind === "schedule")!.size!;
    expect(size.label).toBe("How often");
    expect(size.choices[size.index]!.label).toBe("Every 4 hours");
  });

  it("tells both apps the same rules: OK on each stop, a door's look, Add a door, and what's pending", () => {
    const k = knobOf({ ...base, addDoor: true, doors: [{ ...base.doors[0]!, knocks: 1 }, { id: "slack", label: "Slack", open: false, words: { state: "Closed", tap: "Tap to open" } },
      { id: "wa", label: "WhatsApp", open: true, resting: true, words: { state: "Resting", tap: "Tap to close" } }] });
    expect(k.wakes.filter((w) => w.kind === "door").map((w) => [w.label, w.ok, w.look])).toEqual([["Email", "door", "knock"], ["Slack", "door", "closed"], ["WhatsApp", "door", "resting"]]);
    expect(k.wakes.find((w) => w.kind === "run_now")!.ok).toBe("run");
    expect(k.wakes.at(-1)).toMatchObject({ id: "add_door", label: "Add a door", ok: "coach" });
    // No recipe and no tools: a stand-in step that asks Coach.
    expect(k.what).toEqual([expect.objectContaining({ id: "what_ask", label: "Ask Coach", ok: "coach" })]);
    expect(k.pending).toBeNull();
    const live = { wakes: [{ kind: "door" as const }], busy: "skip" as const, results: ["app" as const] };
    const p = knobOf({ ...base, setting: live, draft: { version: 7, setting: { ...live, busy: "latest", results: ["app", "owner_email"] } } });
    expect(p.pending).toEqual({ version: 7, changes: 2 });
  });

  it("a draft carries its recipe's lines, for Coach's card", () => {
    const k = knobOf({ ...base, draft: { version: 4, setting: { wakes: [], busy: "skip", results: ["app"], what: [{ label: "Check", words: "Looks up the charge" }] } } });
    expect(k.draft!.what).toEqual(["1 · Check: Looks up the charge"]);
    // By connection, for Coach's card: each connected app's lines, then This taf's.
    const g = knobOf({ ...base, draft: { version: 5, setting: { wakes: [{ kind: "door" }, { kind: "app_event", grant: "grt_hub", event: "E", name: "HubSpot · deal won" }],
      busy: "skip", results: ["app", { place: "action", grant: "grt_sl", action: "slack_post", args: {}, name: "Slack · #sales" }] } } });
    expect(g.draft!.groups.map((x) => [x.grant, x.name, x.wakes.length, x.results.length])).toEqual([
      ["grt_hub", "HubSpot", 1, 0], ["grt_sl", "Slack", 0, 1], [undefined, "This taf", 2, 1]]);
  });

  it("a recipe is checked: steps in order, each with a short label", () => {
    expect(recipeOf([{ label: "Check the charge in Stripe now please", words: "x" }])[0]!.label).toBe("Check the charge in Stri");
    expect(() => recipeOf([{ words: "no label" }])).toThrow("short label");
    expect(() => recipeOf("check")).toThrow("list of steps");
  });

  // Every schedule of a taf with a check reads its work first: the When ring says so after the time, in each language.
  it("a taf that checks first says so on each schedule, in the person's language, and its setting carries the check", () => {
    const check = { tool: "list_requests_k3f9", arguments: {}, items: "data", cursor: "created", first: "all" as const };
    const k = knobOf({ ...base, check });
    expect(k.wakes[1]!.words).toBe("Weekdays at 09:00, next Tue 09:00 · checks for new work first");
    expect(k.setting.check).toEqual(check);
    expect(knobOf({ ...base, check, lang: "de" }).wakes[1]!.words).toContain(" · prüft zuerst auf neue Arbeit");
    expect(knobOf({ ...base, check, lang: "pl" }).wakes[1]!.words).toContain(" · najpierw sprawdza, czy jest nowa praca");
    // A version's dial words each schedule with it, but not a door, nor an app check, which reads its own.
    const appCheck = { kind: "app_check" as const, operation: "list_charges", items: "data", cursor: "created", every_minutes: 30 };
    const v = knobOf({ ...base, setting: { wakes: [{ kind: "door" }, { kind: "schedule", form: "daily", at: "08:00" }], busy: "skip", results: ["app"], check } });
    expect(v.wakes.filter((w) => w.kind === "schedule").map((w) => w.words)).toEqual(["Every day at 08:00, next Tue 09:00 · checks for new work first"]);
    const draft = knobOf({ ...base, draft: { version: 3, setting: { wakes: [{ kind: "schedule", form: "weekdays", at: "09:00" }, { kind: "door" }], busy: "skip", results: ["app"], check } } });
    expect(draft.draft!.wakes).toEqual(["Weekdays at 09:00 · checks for new work first", "Wakes on its door"]);
    expect(draft.pending).toEqual({ version: 3, changes: 4 });
    const ac = knobOf({ ...base, check, setting: { wakes: [appCheck], busy: "skip", results: ["app"] } });
    expect(ac.wakes.find((w) => w.kind === "app_check")!.words).not.toContain("first");
    // A taf with no check reads as before.
    expect(knobOf(base).wakes[1]!.words).toBe("Weekdays at 09:00, next Tue 09:00");
    // A version from before with two timetables and no check of its own was written unchecked: its stops don't say it checks.
    const two = knobOf({ ...base, check, setting: { wakes: [{ kind: "schedule", form: "daily", at: "08:00" }, { kind: "schedule", form: "daily", at: "15:30" }], busy: "skip", results: ["app"] } });
    expect(two.wakes.filter((w) => w.kind === "schedule").map((w) => w.words)).toEqual(["Every day at 08:00, next Tue 09:00", "Every day at 15:30"]);
    // One timetable from before takes the taf's check, as applyKnob writes it.
    const one = knobOf({ ...base, check, setting: { wakes: [{ kind: "schedule", form: "daily", at: "08:00" }], busy: "skip", results: ["app"] } });
    expect(one.wakes.find((w) => w.kind === "schedule")!.words).toContain(" · checks for new work first");
  });

  it("a paused schedule says so", () => {
    const k = knobOf({ ...base, schedule: { ...base.schedule!, enabled: false, next: undefined } });
    expect(k.wakes[1]).toMatchObject({ on: false, state: "Paused", words: "Weekdays at 09:00, paused" });
  });
});

describe("a schedule in words", () => {
  it.each([
    ["0 9 * * *", "09:00", "Every day at 09:00"],
    ["30 8 * * 1-5", "Weekdays", "Weekdays at 08:30"],
    ["0 9 * * 1,3", "Weekly", "Mondays and Wednesdays at 09:00"],
    ["0 9 * * 5", "Fri", "Fridays at 09:00"],
    ["*/15 * * * *", "Every 15 min", "Every 15 minutes"],
    ["*/20 8-17 * * 1-5", "Every 20 min", "Every 20 minutes from 08:00 to 18:00 on weekdays"],
    ["0 7 1 * *", "Monthly", "On day 1 of each month at 07:00"],
    ["5 4 * 2 *", "Schedule", "On a schedule"],
  ])("%s", (cron, label, words) => {
    expect(cronWords(cron)).toEqual({ label, words });
  });

  it("today is the account's day", () => {
    expect(dayIn("2026-11-02T23:30:00Z", "Europe/Paris")).toBe("2026-11-03");
    expect(dayIn("2026-11-02T23:30:00Z", "UTC")).toBe("2026-11-02");
  });
});
