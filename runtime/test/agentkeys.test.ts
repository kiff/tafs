import { describe, expect, it } from "vitest";
import { agentKeys, type KeyStore } from "../src/cloud/agentkeys.js";
import type { Keys } from "../src/cloud/handler.js";

/** Records where each write went: kiff/agents, or the old secrets. */
function stores(keeps: boolean | Error) {
  const calls: string[] = [];
  const store: KeyStore = {
    async putKey(tenant, taf, key) {
      calls.push(`agents put ${tenant} ${taf} ${key}`);
      if (keeps instanceof Error) throw keeps;
      return keeps;
    },
    async removeKey(tenant, taf) {
      calls.push(`agents remove ${tenant} ${taf}`);
      if (keeps instanceof Error) throw keeps;
      return keeps;
    },
  };
  const old: Keys = {
    async put(tenant, taf, key) { calls.push(`secret put ${tenant} ${taf} ${key}`); },
    async remove(tenant, taf) { calls.push(`secret remove ${tenant} ${taf}`); },
  };
  return { keys: agentKeys(store, old), calls };
}

describe("the tafs' keys through kiff/agents", () => {
  it("writes and removes them only through kiff/agents once it keeps them", async () => {
    const { keys, calls } = stores(true);
    await keys.put("t1", "taf_1", "k");
    await keys.remove("t1", "taf_1");
    expect(calls).toEqual(["agents put t1 taf_1 k", "agents remove t1 taf_1"]);
  });

  it("keeps them as secrets while kiff/agents keeps none (its routes answer 404)", async () => {
    const { keys, calls } = stores(false);
    await keys.put("t1", "taf_1", "k");
    await keys.remove("t1", "taf_1");
    expect(calls).toEqual(["agents put t1 taf_1 k", "secret put t1 taf_1 k", "agents remove t1 taf_1", "secret remove t1 taf_1"]);
  });

  it("never falls back to a secret when kiff/agents fails: the write fails", async () => {
    const { keys, calls } = stores(new Error("kiff/agents: 503"));
    await expect(keys.put("t1", "taf_1", "k")).rejects.toThrow("503");
    await expect(keys.remove("t1", "taf_1")).rejects.toThrow("503");
    expect(calls.filter((c) => c.startsWith("secret"))).toEqual([]);
  });
});
