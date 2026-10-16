import { createHash } from "node:crypto";

/**
 * Keeping each KIFF account's alert secret where kiff/agents checks alerts
 * with it. tafs cloud can't read that secret back, so what it last wrote is
 * remembered as a hash beside it, and the same secret again writes nothing.
 *
 * Two alerts from two targets can write at once. The hash is written only if
 * it is still the one read before writing the secret; one that lost the race
 * reads again and writes once more. So the writer that finishes last wrote
 * both the secret and the hash: once every write has finished, they name the
 * same secret. A write that loses the race ACCOUNT_TRIES times fails, and its
 * alert is answered 502, so KIFF sends it again and the retry sets both.
 */
export interface AccountStore {
  /** The hash last written for the account; undefined when none was. */
  hash(account: string): Promise<string | undefined>;
  /** Writes `next` only while the hash is still `prev` (undefined: none yet). False when it wasn't. */
  swapHash(account: string, prev: string | undefined, next: string): Promise<boolean>;
  /** Writes the secret kiff/agents reads. */
  writeSecret(account: string, tenant: string, secret: string): Promise<void>;
}

/** How often a write that lost the race tries again before failing, so KIFF sends the alert again. */
export const ACCOUNT_TRIES = 3;

export function accountHash(tenant: string, secret: string): string {
  return createHash("sha256").update(`${tenant}\n${secret}`).digest("hex");
}

export async function keepAccount(store: AccountStore, account: string, tenant: string, secret: string): Promise<void> {
  const want = accountHash(tenant, secret);
  for (let i = 0; i < ACCOUNT_TRIES; i++) {
    const had = await store.hash(account);
    if (had === want) return;
    await store.writeSecret(account, tenant, secret);
    if (await store.swapHash(account, had, want)) return;
  }
  throw new Error(`the alert secret of ${account} changed while it was written; try again`);
}
