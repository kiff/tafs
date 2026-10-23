import { createHash } from "node:crypto";

/**
 * The JSON Canonicalization Scheme (RFC 8785): the exact bytes KIFF hashes
 * into a permit's args_sha256. Keys sort by UTF-16 code units, and numbers and
 * strings serialize as ECMAScript does, which JSON.stringify is. An integer
 * past 2^53 - 1 is refused, as KIFF refuses it: two different values could
 * hash the same.
 */
export function canonical(value: unknown): string {
  if (value === null || typeof value === "boolean" || typeof value === "string") return JSON.stringify(value);
  if (typeof value === "number") {
    if (!Number.isFinite(value)) throw new Error("jcs: a number that isn't finite");
    if (Number.isInteger(value) && !Number.isSafeInteger(value)) throw new Error("jcs: an integer past 2^53 - 1");
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (typeof value === "object") {
    const o = value as Record<string, unknown>;
    return `{${Object.keys(o).sort().map((k) => `${JSON.stringify(k)}:${canonical(o[k])}`).join(",")}}`;
  }
  throw new Error(`jcs: a ${typeof value} isn't JSON`);
}

/** The lowercase hex SHA-256 of value's canonical form. */
export function sha256(value: unknown): string {
  return createHash("sha256").update(canonical(value), "utf8").digest("hex");
}
