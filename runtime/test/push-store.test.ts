import { describe, expect, it } from "vitest";
import { type Device } from "../src/cloud/push.js";
import { deviceItem, deviceOf } from "../src/cloud/push-store.js";

/** The push store's row for a phone (lambda.ts' DynamoDB store): every field it keeps comes back as it went. */
describe("the push store's phone", () => {
  // Invariant: a Device with every field set comes back whole: a field the row leaves out is lost at the next read.
  it("gives back every field of a phone it kept, its language and background-only flag included", () => {
    const full: Required<Device> = {
      token: "fcm-token", platform: "android", environment: "production", subject: "u_ana", answers: true, addsTasks: true,
      manages: true, silent: true, kinds: { needs_you: true, halted: false, answered: true, not_sent: true }, alerts: false, lang: "pl", at: "2026-11-25T17:00:00Z",
    };
    expect(deviceOf(full.token, deviceItem(full))).toEqual(full);
    // Each optional field, alone, comes back too.
    for (const extra of [{ lang: "fr" }, { alerts: false as const }, { kinds: { needs_you: false, halted: true, answered: true, not_sent: true } }]) {
      const d: Device = { token: "a".repeat(64), environment: "sandbox", subject: "u_bo", answers: false, at: "", ...extra };
      expect(deviceOf(d.token, deviceItem(d))).toMatchObject(extra);
    }
  });

  it("keeps a phone that asked for nothing special as one: English, alerts on, every kind", () => {
    const d = deviceOf("t", deviceItem({ token: "t", environment: "production", subject: "u", answers: false, at: "" }))!;
    expect(d.lang).toBeUndefined();
    expect(d.alerts).toBeUndefined();
    expect(d.kinds).toBeUndefined();
    expect(d.platform).toBe("ios");
  });

  it("reads no phone from a row with no environment it knows", () => {
    expect(deviceOf("t", { environment: { S: "dev" } })).toBeUndefined();
    expect(deviceOf("t", {})).toBeUndefined();
  });
});
