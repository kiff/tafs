import { readdirSync, readFileSync, statSync } from "node:fs";
import { extname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

/**
 * How credits are priced, or worked out from what the AI costs, is KIFF's alone: credits only ever come from
 * KIFF, already counted. Nothing in this repo may say how, in code, tests, generators, docs or comments. This
 * walks the tree and fails on any fragment of such a rule. The patterns are fragments, built in pieces, so this
 * file matches none of them itself.
 */

const ROOT = fileURLToPath(new URL("../../", import.meta.url));
const DIRS = ["runtime/src", "runtime/scripts", "runtime/test", "runtime/skills", "docs", "recipes", "CHANGELOG.md"];
const SKIP_DIRS = new Set(["node_modules", ".git", "dist", "dist-lambda"]);
const BINARY = new Set([".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".pdf", ".zip", ".woff", ".woff2", ".ttf", ".otf", ".mp4", ".mov", ".heic"]);

/** A line about credits, costs or money: where a bare number or factor says something. */
const MONEY = /credit|cost|cent|usd|eur|€|\$|pric|meter/i;
const word = (...parts: string[]) => new RegExp(parts.join(""), "i");

/** Each fragment, with whether it counts only on a line about money. Named by number: what each meant isn't said here either. */
const FRAGMENTS: Array<{ name: string; re: RegExp; money?: boolean }> = [
  { name: "fragment 1", re: word("credits", "Of\\b") },
  { name: "fragment 2", re: word("usd", "Per", "EUR") },
  { name: "fragment 3", re: word("Credit", "Multi", "plier") },
  { name: "fragment 4", re: word("multi", "plier"), money: true },
  { name: "fragment 5", re: word("\\b1[.,]0", "8\\b") },
  { name: "fragment 6", re: word("\\b10", "8\\b"), money: true },
  { name: "fragment 7", re: word("[*×]\\s*", "4\\s*/"), money: true },
  { name: "fragment 8", re: word("[*×]\\s*100\\s*[*×]\\s*", "100\\b"), money: true },
  { name: "fragment 9", re: word("(converted|metered|converts|meters)\\s+as\\s+KIFF") },
  { name: "fragment 10", re: word("[*×/]\\s*\\(?\\s*10", "8\\b") },
];

function files(path: string): string[] {
  const full = join(ROOT, path);
  const st = statSync(full, { throwIfNoEntry: false });
  if (!st) return [];
  if (st.isFile()) return [full];
  return readdirSync(full).flatMap((name) => (SKIP_DIRS.has(name) ? [] : files(join(path, name))));
}

/** Text files only: a known binary extension, or a NUL byte, is skipped. */
function text(file: string): string | undefined {
  if (BINARY.has(extname(file).toLowerCase())) return undefined;
  const buf = readFileSync(file);
  return buf.includes(0) ? undefined : buf.toString("utf8");
}

/** Each fragment found in a text, as "line: name". */
export function findings(body: string): string[] {
  const out: string[] = [];
  body.split("\n").forEach((line, i) => {
    for (const f of FRAGMENTS) if (f.re.test(line) && (!f.money || MONEY.test(line))) out.push(`${i + 1}: ${f.name}`);
  });
  return out;
}

describe("no credit pricing in this repo", () => {
  it("finds no fragment of how credits are priced or worked out from cost, in any file", () => {
    const all = DIRS.flatMap(files);
    expect(all.length).toBeGreaterThan(100);
    const hits: string[] = [];
    for (const f of all) {
      const body = text(f);
      if (body !== undefined) for (const h of findings(body)) hits.push(`${relative(ROOT, f)}:${h}`);
    }
    expect(hits).toEqual([]);
  });

  // The guard must catch any fragment coming back, each on its own, and leave ordinary numbers alone.
  it("catches each fragment on its own, and passes numbers that say nothing about money", () => {
    const back = [
      ["x = credits", "Of(y)"], ["usd", "PerEUR"], ["credit", "Multi", "plier"], ["the credits' multi", "plier"], ["rate 1.0", "8"],
      ["credits: 10", "8"], ["credits = a * ", "4 / b"], ["cost * 100 * 1", "00"], ["converted as KI", "FF does"], ["d = n * 10", "8;"],
    ].map((parts) => parts.join(""));
    for (const line of back) expect(findings(line), line).not.toEqual([]);
    // Every fragment is caught by some line, and a hit names its line.
    expect(new Set(back.flatMap((line) => findings(line).map((f) => f.split(": ")[1]))).size).toBe(FRAGMENTS.length);
    expect(findings(["first line", ["credits", "Of(z)"].join("")].join("\n"))).toEqual(["2: fragment 1"]);
    for (const line of [
      "const big = \"A\".repeat(Math.ceil((DRIVE.imageMax + 10) * 4 / 3));",
      "// review on #108",
      "<path d=\"M336 520 H596 L690 446\" stroke-width=\"108\"/>",
      "backoff: a multiplier of 2 between tries",
      "Company's published €0.01 a credit",
      "version 1.0.8",
    ]) expect(findings(line), line).toEqual([]);
  });
});
