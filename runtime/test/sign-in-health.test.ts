import { describe, expect, it } from "vitest";
import { healthOf } from "../src/cloud/sign-in-health.js";

const now = new Date("2026-11-28T10:00:00Z");
const PARIS = "Europe/Paris";

// Browser batch 2: tafs cloud decides a sign-in's state from planque's verdict; the apps work out nothing.
describe("a saved sign-in's health", () => {
  it("is signed in, with the check's time today, when the site's own end is more than 3 days off or not set", () => {
    expect(healthOf({ site: "books.example", verdict: "signed_in", checked_at: "2026-11-28T06:00:00Z", expires_at: "2027-01-10T00:00:00Z" }, now, PARIS))
      .toEqual({ state: "signed_in", checked_at: "2026-11-28T06:00:00.000Z", words: { line: "Signed in · checked today 07:00" } });
    expect(healthOf({ site: "books.example", verdict: "signed_in", checked_at: "2026-11-27T06:00:00Z" }, now, PARIS).words.line).toBe("Signed in · checked 27 Nov");
    // "Today" is the account's own date: 00:30 on 1 Jan in Paris is still 31 Dec in UTC, and a year back is never today.
    expect(healthOf({ site: "books.example", verdict: "signed_in", checked_at: "2025-12-31T23:30:00Z" }, new Date("2026-01-01T10:00:00Z"), PARIS).words.line).toBe("Signed in · checked today 00:30");
    expect(healthOf({ site: "books.example", verdict: "signed_in", checked_at: "2025-11-28T06:00:00Z" }, now, PARIS).words.line).toBe("Signed in · checked 28 Nov");
  });

  it("needs the owner soon when the site set it to end within 3 days, by that day, with why it's known", () => {
    const h = healthOf({ site: "supplier-portal.example", verdict: "signed_in", checked_at: "2026-11-28T06:00:00Z", expires_at: "2026-11-30T09:00:00Z" }, now, PARIS);
    expect(h).toMatchObject({ state: "needs_you_soon", needs_you_by: "2026-11-30T09:00:00.000Z", words: { line: "Needs you by Mon 30 Nov" } });
    expect(h.words.why).toBe("At 07:00, tafs opened one page of the site with your saved sign-in, without clicking anything. The site set your saved sign-in to end on 30 Nov.");
  });

  it("has stopped working when planque read it signed out, needed now", () => {
    const h = healthOf({ site: "tax-office.example", verdict: "signed_out", checked_at: "2026-11-28T06:55:00Z" }, now, PARIS);
    expect(h).toMatchObject({ state: "stopped", needs_you_by: "2026-11-28T06:55:00.000Z", words: { line: "Stopped working at 07:55" } });
    expect(h.words.why).toContain("The site asked to sign in again.");
  });

  it("is not checked when planque couldn't tell, or hasn't checked: no warning, no date", () => {
    for (const v of [{ site: "a.example", verdict: "unknown" as const, checked_at: "2026-11-28T06:00:00Z" }, { site: "a.example", verdict: "unknown" as const, why: "no reviewed page" }, { site: "a.example", verdict: "signed_in" as const }]) {
      const h = healthOf(v, now, PARIS);
      expect(h.state).toBe("unknown");
      expect(h.words).toEqual({ line: "Not checked" });
      expect(h).not.toHaveProperty("needs_you_by");
    }
  });
});
