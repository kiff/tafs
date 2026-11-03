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
  });

  it("a follow-up the taf set itself shows on Wakes it, with when it fires and how to remove it", () => {
    const f = { id: "fu-ab12", agent: "a", cron: "", input: "Check refund re_9 went through", enabled: true, kind: "once" as const, at: "2026-11-03T08:00:00Z", follow_up: true };
    const k = knobOf({ ...base, followUps: [f] });
    expect(k.wakes.find((w) => w.kind === "follow_up")).toEqual({ id: "trg_fu-ab12", kind: "follow_up", follow_up: "fu-ab12", expires: "2026-11-03T08:00:00Z",
      label: "Follow-up", on: true, state: "Tue 09:00", words: "Checks again Tue 09:00: Check refund re_9 went through", tap: "Tap to remove this follow-up" });
    // It's not one of the dial's triggers: PUT never carries it.
    expect(k.setting.wakes.some((t) => (t.kind as string) === "follow_up")).toBe(false);
  });

  it("an app's event the live version wakes on has its own stop, named as the owner picked it", () => {
    const ev = { kind: "app_event" as const, grant: "grt_hub", event: "HUBSPOT_DEAL_CREATED", name: "HubSpot · new deal" };
    const k = knobOf({ ...base, setting: { wakes: [{ kind: "door" }, ev], busy: "skip", results: ["app"] } });
    expect(k.wakes.find((w) => w.kind === "app_event")).toMatchObject({ grant: "grt_hub", label: "HubSpot", words: "Wakes on HubSpot · new deal", trigger: ev });
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
    // A taf Coach set no recipe for: its tools, by KIFF's title; its Why is what it does.
    const plain = knobOf({ ...base, tools: new Map([["stripe_refund", "Refund a payment"]]) });
    expect(plain.what.map((w) => w.label)).toEqual(["Refund a payment"]);
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

  it("a draft carries its recipe's lines, for Coach's card", () => {
    const k = knobOf({ ...base, draft: { version: 4, setting: { wakes: [], busy: "skip", results: ["app"], what: [{ label: "Check", words: "Looks up the charge" }] } } });
    expect(k.draft!.what).toEqual(["1 · Check: Looks up the charge"]);
  });

  it("a recipe is checked: steps in order, each with a short label", () => {
    expect(recipeOf([{ label: "Check the charge in Stripe now please", words: "x" }])[0]!.label).toBe("Check the charge in Stri");
    expect(() => recipeOf([{ words: "no label" }])).toThrow("short label");
    expect(() => recipeOf("check")).toThrow("list of steps");
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
