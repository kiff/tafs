import type { Keys } from "./handler.js";

/** Where kiff/agents keeps the tafs' keys: false, it keeps none yet. */
export interface KeyStore {
  putKey(tenant: string, tafID: string, key: string): Promise<boolean>;
  removeKey(tenant: string, tafID: string): Promise<boolean>;
}

/**
 * The tafs' KIFF keys, written through kiff/agents, which keeps them sealed
 * and deletes their old secrets. Until it keeps keys (its routes answer 404),
 * they are written as before: `old`, a secret each. So this ships before
 * kiff/agents turns its store on, and nothing is written past it after.
 */
export function agentKeys(store: KeyStore, old: Keys): Keys {
  return {
    async put(tenant, tafID, key) {
      if (!(await store.putKey(tenant, tafID, key))) await old.put(tenant, tafID, key);
    },
    async remove(tenant, tafID) {
      if (!(await store.removeKey(tenant, tafID))) await old.remove(tenant, tafID);
    },
  };
}
