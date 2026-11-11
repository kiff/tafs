import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * Credits come only from KIFF, as KIFF counts them: tafs cloud never works them out from a run's AI
 * cost. This checks the shape of the code, never a number: the estimate's files don't read an AI cost
 * at all, and no line of tafs cloud's code puts an AI cost and credits together.
 */
const SRC = join(__dirname, "..", "src");
const AI_COST = /cost_?micro|micro_?usd/i;

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? files(path) : /\.(ts|mjs|js)$/.test(name) ? [path] : [];
  });
}

describe("a taf's estimate reads credits only from KIFF", () => {
  it("the estimate's own files never read a run's AI cost", () => {
    for (const name of ["cloud/estimate.ts", "cloud/credits.ts"]) {
      const lines = readFileSync(join(SRC, name), "utf8").split("\n");
      const hits = lines.map((l, i) => [i + 1, l] as const).filter(([, l]) => AI_COST.test(l));
      expect(hits, name).toEqual([]);
    }
  });

  it("no line of tafs cloud's code puts a run's AI cost and credits together", () => {
    const hits: string[] = [];
    for (const path of files(SRC)) {
      readFileSync(path, "utf8").split("\n").forEach((l, i) => {
        if (AI_COST.test(l) && /credit/i.test(l)) hits.push(`${path.slice(SRC.length + 1)}:${i + 1}`);
      });
    }
    expect(hits).toEqual([]);
  });
});
