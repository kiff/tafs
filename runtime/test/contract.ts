import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { expect } from "vitest";

/**
 * Keeps one answer tafs cloud gives the apps as contracts/<name>.json (contracts/README.md): the test that produced it
 * compares it with the file, and the iOS and Android tests read the same file. A changed answer fails here until the
 * file is written again (UPDATE_CONTRACTS=1), then fails in whichever app can't read it. Never written by hand.
 */
export function contract(name: string, about: string, answer: unknown): void {
  const file = new URL(`../../contracts/${name}.json`, import.meta.url);
  const text = JSON.stringify({ about: `${about} Written by tafs cloud's tests (runtime/test/contract.ts); write it again with UPDATE_CONTRACTS=1, never by hand.`, answer }, null, 2) + "\n";
  if (process.env.UPDATE_CONTRACTS === "1") writeFileSync(file, text);
  expect(existsSync(file), `contracts/${name}.json is missing: write it with UPDATE_CONTRACTS=1`).toBe(true);
  expect(readFileSync(file, "utf8"), `tafs cloud's answer changed: write contracts/${name}.json again (UPDATE_CONTRACTS=1), and make both apps read it`).toBe(text);
}
