import { describe, expect, it } from "vitest";
import { alertChoices, defaultKinds, parseKinds } from "../src/cloud/alert-preferences.js";
import { LANGUAGES } from "../src/cloud/words/index.js";
import { registration, sendNotification, type Device, type Notification } from "../src/cloud/push.js";

const device: Device = { token: "a".repeat(64), subject: "person-example", answers: true, environment: "sandbox", at: "2026-11-01T12:00:00Z" };
describe("each phone's alert choices", () => {
  it("preserves older registrations and supplies labels from the server", () => {
    expect(parseKinds(undefined)).toBeUndefined();
    expect(alertChoices(undefined)).toHaveLength(4);
    expect(alertChoices(undefined).every((x) => x.enabled)).toBe(true);
    expect(registration({ device_token: device.token, environment: "sandbox", kinds: { answered: false } }).kinds?.answered).toBe(false);
  });
  it("says each kind in the phone's language, every language having all four", () => {
    expect(alertChoices(undefined).map((x) => x.title)).toEqual(["A taf needs you", "Every taf halted", "A taf answered someone", "A reply wasn't sent"]);
    const fr = alertChoices({ answered: false }, "fr");
    expect(fr.map((x) => x.title)).toEqual(["Un taf a besoin de vous", "Tous les tafs bloqués", "Un taf a répondu à quelqu’un", "Une réponse n'a pas été envoyée"]);
    expect(fr.find((x) => x.id === "answered")?.enabled).toBe(false);
    for (const lang of LANGUAGES) {
      for (const x of alertChoices(undefined, lang)) expect(x.title && x.words, `${lang} ${x.id}`).toBeTruthy();
      if (lang !== "en") expect(alertChoices(undefined, lang).map((x) => x.title)).not.toEqual(alertChoices(undefined).map((x) => x.title));
    }
  });
  it("rejects unknown keys and nonboolean choices", () => {
    for (const value of [{ answered: "no" }, { invented: true }, [], JSON.parse('{"__proto__":true}')]) expect(() => parseKinds(value)).toThrow();
  });
  it("suppresses the chosen kind on that phone, before either provider is called", async () => {
    const sent: Device[] = []; const sender = { async send(d: Device) { sent.push(d); return "sent" as const; } };
    const n: Notification = { kind: "alert", preference: "answered", payload: { aps: {} } };
    await sendNotification(sender, { ...device, kinds: { ...defaultKinds(), answered: false } }, n);
    await sendNotification(sender, { ...device, token: "b".repeat(64) }, n);
    expect(sent.map((d) => d.token)).toEqual(["b".repeat(64)]);
  });
  it("still sends background synchronization to clear previously shown holds", async () => {
    let sent = 0; const sender = { async send() { sent++; return "sent" as const; } };
    await sendNotification(sender, { ...device, kinds: { ...defaultKinds(), needs_you: false } }, { kind: "background", preference: "needs_you", payload: { answered: true } });
    expect(sent).toBe(1);
  });
});
