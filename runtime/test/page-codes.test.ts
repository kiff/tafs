import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { CODE_MASK, maskedCodes } from "../src/cloud/page-codes.js";

/** The cases the extension's `maskCodes` answers too (chrome/test/core.test.js): one rule on both sides. */
const CASES = JSON.parse(readFileSync(new URL("./fixtures/one-time-codes.json", import.meta.url), "utf8")) as { masked: [string, string][]; kept: string[] };

describe("one-time codes in a page Coach reads, at tafs cloud", () => {
  // Invariant: a code named as one, in English, French, Spanish or German, loses its digits and keeps its words
  it("masks each code's digits, its words kept", () => {
    for (const [text, masked] of CASES.masked) expect(maskedCodes(text)).toBe(masked);
  });

  // Invariant: a postal, promo, error or order number, a code that's only mentioned, and a phone number stay as written
  it("keeps numbers that aren't one-time codes", () => {
    for (const text of CASES.kept) expect(maskedCodes(text)).toBe(text);
  });

  // Invariant: the mask is the extension's own word, so a page masked twice reads the same
  it("masks as the extension does, and twice is once", () => {
    expect(CODE_MASK).toBe("[masked]");
    for (const [, masked] of CASES.masked) expect(maskedCodes(masked)).toBe(masked);
  });

  // Invariant: a page full of near-codes can't slow the check down
  it("stays fast on a page full of near-codes", () => {
    const start = Date.now();
    for (const s of ["verification code ".repeat(20000), "code: ".repeat(20000), "1234 is your ".repeat(20000)]) maskedCodes(s);
    expect(Date.now() - start).toBeLessThan(2000);
  });
});
