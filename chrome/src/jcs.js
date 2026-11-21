// Canonical JSON (RFC 8785, JCS), as every signed body between the extension, planque and the phones is written:
// object keys sorted by their UTF-16 code units, no whitespace, numbers as ECMAScript writes them. The phones already
// sign JCS (ios/Tafs/KIFF/JCS.swift); planque's Go side reads the same vectors (vectors/chrome-protocol.json).

export function canonical(value) {
  if (value === null || typeof value === "boolean") return JSON.stringify(value);
  if (typeof value === "number") {
    if (!Number.isFinite(value)) throw new TypeError("jcs: a number must be finite");
    return JSON.stringify(value);
  }
  if (typeof value === "string") return JSON.stringify(value);
  if (Array.isArray(value)) return "[" + value.map((v) => canonical(v === undefined ? null : v)).join(",") + "]";
  if (typeof value === "object") {
    const keys = Object.keys(value).filter((k) => value[k] !== undefined).sort();
    return "{" + keys.map((k) => JSON.stringify(k) + ":" + canonical(value[k])).join(",") + "}";
  }
  throw new TypeError("jcs: " + typeof value + " can't be written");
}
