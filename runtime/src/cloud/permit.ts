import { createPublicKey, type KeyObject, verify as verifySignature } from "node:crypto";
import { sha256 } from "./jcs.js";
import type { Fetch } from "./kiff.js";

/**
 * KIFF's execution permits: a JWT signed with Ed25519 that authorizes at
 * most one execution of one exact call (kiff/tafs#206). tafs cloud's MCP
 * server checks one before every call Coach makes through KIFF, as planque
 * does: the signature with a key KIFF publishes, the issuer, the audience
 * (this server), the tool, the exact arguments' hash, and the lifetime.
 */

/** The header that carries the permit on the gateway's tools/call. */
export const PERMIT_HEADER = "kiff-permit";

const TYP = "kiff-permit+jwt";
const ALG = "Ed25519";
const DETAIL = "https://kiff.dev/permits/tool-call";
/** Clock skew allowed on iat and exp. */
export const TOLERANCE_MS = 30_000;
/** The longest exp - iat accepted. */
const LIFETIME_MS = 60_000;

export interface Permit {
  id: string;
  subject: string;
  tenant: string;
  tool: string;
  op: string;
  issuedAt: Date;
  expires: Date;
}

export interface Want {
  audience: string;
  tool: string;
  /** The arguments as received. */
  args: unknown;
}

export class PermitError extends Error {}

/** Returns a trusted public key by kid. */
export type Keys = (kid: string) => Promise<KeyObject>;

/**
 * Checks that token is a permit KIFF issued for exactly want, live at now.
 * The tenant isn't known before: it is the permit's, and the caller checks
 * the subject is that tenant's Coach.
 */
export async function verifyPermit(token: string | undefined, want: Want, keys: Keys, issuer: string, now: Date): Promise<Permit> {
  const parts = (token ?? "").trim().split(".");
  if (parts.length !== 3) throw new PermitError("no KIFF permit for this call");
  const [h64, c64, s64] = parts as [string, string, string];
  const header = decode(h64) as { alg?: string; typ?: string; kid?: string } | undefined;
  if (!header || header.typ !== TYP || header.alg !== ALG || !header.kid) throw new PermitError("the permit is not a well-formed KIFF permit");
  let key: KeyObject;
  try { key = await keys(header.kid); } catch (err) {
    throw new PermitError(`the permit's signature does not verify with a key KIFF publishes: ${(err as Error).message}`);
  }
  if (!verifySignature(null, Buffer.from(`${h64}.${c64}`), key, Buffer.from(s64, "base64url"))) {
    throw new PermitError("the permit's signature does not verify with a key KIFF publishes");
  }
  const c = decode(c64) as {
    iss?: string; aud?: unknown; sub?: string; iat?: number; exp?: number; jti?: string;
    authorization_details?: { type?: string; tenant?: string; tool?: string; args_sha256?: string; op?: string }[];
  } | undefined;
  const d = c?.authorization_details;
  if (!c || typeof c.iat !== "number" || typeof c.exp !== "number" || !c.jti || !c.sub || !Array.isArray(d) || d.length !== 1) {
    throw new PermitError("the permit is not a well-formed KIFF permit");
  }
  const detail = d[0]!;
  if (detail.type !== DETAIL || !detail.op || !detail.args_sha256 || !detail.tenant) throw new PermitError("the permit is not a well-formed KIFF permit");
  if (c.iss !== issuer) throw new PermitError("the permit is not from KIFF");
  const aud = Array.isArray(c.aud) && c.aud.length === 1 ? c.aud[0] : c.aud;
  if (aud !== want.audience) throw new PermitError("the permit is for another server");
  if (detail.tool !== want.tool) throw new PermitError("the permit is for another tool");
  let hash: string;
  try { hash = sha256(want.args); } catch { throw new PermitError("the permit is for other arguments"); }
  if (hash !== detail.args_sha256) throw new PermitError("the permit is for other arguments");
  const iat = c.iat * 1000, exp = c.exp * 1000;
  if (exp < iat || exp - iat > LIFETIME_MS || iat > now.getTime() + TOLERANCE_MS) throw new PermitError("the permit's lifetime is not one this server accepts");
  if (now.getTime() >= exp + TOLERANCE_MS) throw new PermitError("the permit has expired");
  return { id: c.jti, subject: c.sub, tenant: detail.tenant, tool: detail.tool, op: detail.op, issuedAt: new Date(iat), expires: new Date(exp) };
}

function decode(part: string): unknown {
  try { return JSON.parse(Buffer.from(part, "base64url").toString("utf8")); } catch { return undefined; }
}

/**
 * The keys KIFF publishes, refetched every 5 minutes, and on an unknown kid
 * at most once a minute. If refetching fails, the last good set is trusted
 * for at most an hour from its fetch.
 */
export function publishedKeys(url: string, doFetch: Fetch = fetch, now: () => number = Date.now): Keys {
  let keys: Map<string, KeyObject> | undefined;
  let fetchedAt = 0, lastUnknown = -Infinity;
  const refetch = async () => {
    try {
      const res = await doFetch(url, { signal: AbortSignal.timeout(5000) });
      if (!res.ok) return;
      const set = (await res.json()) as { keys?: { kty?: string; crv?: string; x?: string; kid?: string; alg?: string }[] };
      const next = new Map<string, KeyObject>();
      for (const k of set.keys ?? []) {
        if (k.kty !== "OKP" || k.crv !== "Ed25519" || !k.kid || !k.x || (k.alg && k.alg !== ALG)) continue;
        try { next.set(k.kid, createPublicKey({ key: { kty: "OKP", crv: "Ed25519", x: k.x }, format: "jwk" })); } catch { /* skipped */ }
      }
      keys = next;
      fetchedAt = now();
    } catch { /* the last good set stands */ }
  };
  return async (kid) => {
    const t = now();
    if (!keys || t - fetchedAt >= 5 * 60_000) await refetch();
    if (!keys?.has(kid) && t - lastUnknown >= 60_000) {
      lastUnknown = t;
      await refetch();
    }
    if (!keys || t - fetchedAt >= 60 * 60_000) throw new Error("KIFF's permit keys could not be fetched recently");
    const k = keys.get(kid);
    if (!k) throw new Error(`unknown key ${kid}`);
    return k;
  };
}
