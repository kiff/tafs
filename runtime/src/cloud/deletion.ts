/**
 * Deleting an account, driven by tafs cloud (the server-driven Delete account). The phone signs one
 * intent with its approval key, behind the prompt:
 *
 *   tafs.delete-account.v2
 *   tenant=<tenant id>
 *   device=<KIFF device id>
 *   issued=<RFC 3339, UTC>
 *   expires=<at most 10 minutes after issued>
 *   nonce=<32 hex>
 *
 * one field a line, LF between, no newline at the end; the signature is base64 DER ECDSA over its
 * SHA-256. tafs cloud never vouches for it: planque and KIFF each verify it with the device's key.
 *
 * Two phases. **Accept**, within the intent's 10 minutes: planque, then KIFF, each verifies the
 * intent and records the deletion as accepted, deleting nothing. From KIFF's accept on, the account
 * can't be used. **Finish**, with no deadline: tafs cloud erases its own, then planque (while KIFF's
 * connector key still works), then KIFF, last. Each finish is idempotent, and the intent is kept, so
 * a deletion cut short (the phone gone, a service down) is finished by the sweep, with no session
 * and no new signature. There is no undo.
 */

export const INTENT_HEADER = "tafs.delete-account.v2";
/** How long after it was made an intent may first be accepted. */
export const INTENT_WINDOW_MS = 10 * 60_000;
/** How far ahead of tafs cloud's clock a phone's may be. */
const SKEW_MS = 60_000;

export interface Intent {
  tenant: string;
  device: string;
  issued: Date;
  expires: Date;
  nonce: string;
}

export class IntentError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

const FIELDS = ["tenant", "device", "issued", "expires", "nonce"] as const;
const RFC3339 = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/;

/** The intent's fields, exactly as the format says, or why not. */
export function parseIntent(text: string): Intent {
  const lines = text.split("\n");
  if (lines[0] !== INTENT_HEADER || lines.length !== FIELDS.length + 1) throw new IntentError(400, `the intent is ${INTENT_HEADER} and its ${FIELDS.length} fields, one a line`);
  const got: Record<string, string> = {};
  lines.slice(1).forEach((line, i) => {
    const want = FIELDS[i]!;
    if (!line.startsWith(`${want}=`)) throw new IntentError(400, `the intent's line ${i + 2} is ${want}=…`);
    got[want] = line.slice(want.length + 1);
  });
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(got.tenant!) || !/^[A-Za-z0-9_.:-]{1,128}$/.test(got.device!)) throw new IntentError(400, "the intent's tenant or device isn't an id");
  if (!RFC3339.test(got.issued!) || !RFC3339.test(got.expires!)) throw new IntentError(400, "issued and expires are RFC 3339 times in UTC");
  if (!/^[0-9a-f]{32}$/.test(got.nonce!)) throw new IntentError(400, "the nonce is 16 random bytes, in lower-case hex");
  return { tenant: got.tenant!, device: got.device!, issued: new Date(got.issued!), expires: new Date(got.expires!), nonce: got.nonce! };
}

/** Whether an intent may be accepted now, for this account: refused before anything is asked of planque or KIFF. */
export function checkIntent(intent: Intent, tenant: string, now: Date): void {
  if (intent.tenant !== tenant) throw new IntentError(403, "the intent names another account");
  const issued = intent.issued.getTime(), expires = intent.expires.getTime(), t = now.getTime();
  if (expires <= issued || expires - issued > INTENT_WINDOW_MS) throw new IntentError(400, "an intent expires within 10 minutes of being made");
  if (issued > t + SKEW_MS) throw new IntentError(400, "the intent was made in the future: check the phone's clock");
  if (expires <= t) throw new IntentError(409, "the intent expired: confirm again");
}

/** A deletion's three steps, in the order they finish. */
export const STEPS = ["tafs", "planque", "kiff"] as const;
export type Step = (typeof STEPS)[number];

/** A deletion tafs cloud keeps until it's finished: the signed intent, and which steps are done. */
export interface Deletion {
  tenant: string;
  intent: string;
  signature: string;
  accepted: string;
  done: Step[];
  /** The last failure of each step not done, in words, and when it was tried. */
  failed?: Partial<Record<Step, string>>;
  tried?: string;
  /** How many times the sweep tried it, for its backoff. */
  tries?: number;
  /** Kept before KIFF's accept answered: the sweep asks KIFF whether it took it before finishing. */
  accepting?: boolean;
}

export interface Deletions {
  get(tenant: string): Promise<Deletion | undefined>;
  put(d: Deletion): Promise<void>;
  /** The deletions not finished yet. */
  pending(): Promise<Deletion[]>;
  /** A finished deletion: its intent and signature go; only that it was done stays. */
  finished(tenant: string, at: string): Promise<void>;
}

/** planque or KIFF, for a deletion: accept checks the signed intent and records it; finish deletes, again and again the same. */
export interface DeletionPeer {
  /** With `bearer`, the person's session goes with it: KIFF's accept takes the phone's session. */
  accept(intent: string, signature: string, bearer?: string): Promise<void>;
  finish(intent: string, signature: string): Promise<void>;
  /** Whether it accepted this intent: false for one it never took (403 `not_accepted`). */
  accepted(intent: string, signature: string): Promise<boolean>;
}

/** A peer's refusal: `status` as it answered, `why` in its words. */
export class PeerError extends Error {
  constructor(readonly status: number, message: string, readonly code?: string) {
    super(message);
  }
}

/**
 * A peer over HTTP: `POST {base}{path}` and `{base}{path}/finish` with `{intent, signature}`. The
 * intent's signature is the authority, so no session goes with it.
 */
export function httpPeer(base: string, path: string, net: typeof fetch = fetch): DeletionPeer {
  const post = async (url: string, intent: string, signature: string, bearer?: string) => {
    let res: Response;
    try {
      res = await net(url, {
        method: "POST",
        headers: { "content-type": "application/json", ...(bearer ? { authorization: `Bearer ${bearer}` } : {}) },
        body: JSON.stringify({ intent, signature }),
        signal: AbortSignal.timeout(20_000),
      });
    } catch (err) {
      throw new PeerError(502, (err as Error).message);
    }
    if (res.ok) return;
    const body = await res.json().catch(() => ({})) as { error?: unknown; detail?: unknown; message?: unknown };
    const why = typeof body.detail === "string" ? body.detail : typeof body.error === "string" ? body.error : typeof body.message === "string" ? body.message : `answered ${res.status}`;
    throw new PeerError(res.status, why, typeof body.error === "string" ? body.error : undefined);
  };
  const url = `${base.replace(/\/+$/, "")}${path}`;
  return {
    accept: (intent, signature, bearer) => post(url, intent, signature, bearer),
    finish: (intent, signature) => post(`${url}/finish`, intent, signature),
    async accepted(intent, signature) {
      try {
        await post(`${url}/status`, intent, signature);
        return true;
      } catch (err) {
        if (err instanceof PeerError && err.code === "not_accepted") return false;
        throw err;
      }
    },
  };
}

/** The sweep's wait before trying a deletion again: 5 minutes, doubling, at most 6 hours. */
export function backoffMs(tries: number): number {
  return Math.min(5 * 60_000 * 2 ** Math.max(0, tries - 1), 6 * 3600_000);
}
