import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

/** Every TAFS_ variable the code reads, from its sources. */
function flagsRead(dir: string): Set<string> {
  const out = new Set<string>();
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) flagsRead(path).forEach((f) => out.add(f));
    else if (name.endsWith(".ts")) for (const m of readFileSync(path, "utf8").matchAll(/process\.env\.(TAFS_[A-Z_]+)/g)) out.add(m[1]!);
  }
  return out;
}

describe("infra/aws.sh", () => {
  // A rerun writes the function's whole environment: a flag it doesn't name would be turned off without a word.
  it("keeps every feature flag the code reads across a rerun", () => {
    const script = readFileSync(new URL("../../infra/aws.sh", import.meta.url), "utf8");
    const kept = /for v in ((?:TAFS_[A-Z_]+ ?)+); do/.exec(script)?.[1]?.trim().split(/\s+/) ?? [];
    const read = [...flagsRead(new URL("../src", import.meta.url).pathname)].filter((f) => f !== "TAFS_PUBLIC_URL");
    expect(read.length).toBeGreaterThan(0);
    for (const flag of read) expect(kept, `${flag} isn't kept by infra/aws.sh`).toContain(flag);
    expect(script).toContain("$FLAGS_ENV}");
  });
});
