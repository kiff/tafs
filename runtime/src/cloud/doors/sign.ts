import { createHmac, timingSafeEqual } from "node:crypto";

/**
 * Checks that a webhook came from the provider tafs gave the secret to.
 * Nothing a webhook says is read before its signature holds.
 */

/** Signatures older or newer than this are refused, so a captured delivery can't be replayed later. */
export const TOLERANCE_SECONDS = 300;

function same(a: Buffer, b: Buffer): boolean {
  return a.length === b.length && timingSafeEqual(a, b);
}

/**
 * The signed-webhook scheme the email provider uses: HMAC-SHA256 over "<id>.<timestamp>.<body>", base64, in a header
 * that may list several "v1,<signature>" (one per secret while one rotates).
 * The key is the secret's bytes: base64 after "whsec_", else as written.
 */
export function signedWebhook(opts: {
  secret: string;
  id: string;
  timestamp: string;
  signature: string;
  body: string;
  now: Date;
}): boolean {
  const { secret, id, timestamp, signature, body } = opts;
  if (!secret || !id || !timestamp || !signature) return false;
  const ts = Number(timestamp);
  if (!Number.isInteger(ts) || Math.abs(opts.now.getTime() / 1000 - ts) > TOLERANCE_SECONDS) return false;
  const key = secret.startsWith("whsec_") ? Buffer.from(secret.slice("whsec_".length), "base64") : Buffer.from(secret, "utf8");
  const want = createHmac("sha256", key).update(`${id}.${timestamp}.${body}`).digest();
  return signature.split(/\s+/).some((part) => {
    const sig = part.includes(",") ? part.slice(part.indexOf(",") + 1) : part;
    return same(Buffer.from(sig, "base64"), want);
  });
}

/** WhatsApp's: "sha256=<hex>" of the raw body, with the app's secret. */
export function whatsappSigned(appSecret: string, header: string, body: string): boolean {
  if (!appSecret || !header.startsWith("sha256=")) return false;
  const want = createHmac("sha256", appSecret).update(body, "utf8").digest();
  return same(Buffer.from(header.slice("sha256=".length), "hex"), want);
}
