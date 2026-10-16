import { describe, expect, it } from "vitest";
import { ACCOUNT_TRIES, accountHash, type AccountStore, keepAccount } from "../src/cloud/accounts.js";

/** The secret and its hash, as DynamoDB and Secrets Manager hold them, with a hook that runs between steps. */
function store() {
  const s = {
    secret: undefined as string | undefined,
    hashed: undefined as string | undefined,
    writes: 0,
    /** Runs once, right after the next secret write: another writer's turn. */
    between: undefined as (() => Promise<void>) | undefined,
    api: undefined as unknown as AccountStore,
  };
  s.api = {
    async hash() { return s.hashed; },
    async swapHash(_account, prev, next) {
      if (s.hashed !== prev) return false;
      s.hashed = next;
      return true;
    },
    async writeSecret(_account, tenant, secret) {
      s.secret = `${tenant}\n${secret}`;
      s.writes++;
      const then = s.between;
      s.between = undefined;
      if (then) await then();
    },
  };
  return s;
}

/** Whether the hash names the secret that's written. */
const inStep = (s: ReturnType<typeof store>) => s.secret !== undefined && s.hashed === accountHash(...(s.secret.split("\n") as [string, string]));

describe("an account's alert secret for kiff/agents", () => {
  it("writes once, and the same secret again writes nothing", async () => {
    const s = store();
    await keepAccount(s.api, "t_acme", "t_acme", "whsec_a");
    await keepAccount(s.api, "t_acme", "t_acme", "whsec_a");
    expect(s.writes).toBe(1);
    expect(inStep(s)).toBe(true);
  });

  it("ends in step when a second target's alert writes between this one's secret and its hash", async () => {
    const s = store();
    s.between = () => keepAccount(s.api, "t_acme", "t_acme", "whsec_b");
    await keepAccount(s.api, "t_acme", "t_acme", "whsec_a");
    // b finished first, so a lost its swap, read again and wrote both: a is last, and in step.
    expect(inStep(s)).toBe(true);
    expect(s.secret).toBe("t_acme\nwhsec_a");
  });

  it("repairs the state where the secret is one target's and the hash the other's", async () => {
    const s = store();
    // b's view of the store: its swap waits until a has finished.
    let release!: () => void;
    const gate = new Promise<void>((r) => { release = r; });
    let bWrote!: () => void;
    const bAtGate = new Promise<void>((r) => { bWrote = r; });
    let swaps = 0;
    const b: AccountStore = {
      hash: s.api.hash,
      writeSecret: async (...args) => { await s.api.writeSecret(...args); bWrote(); },
      swapHash: async (...args) => { if (swaps++ === 0) await gate; return s.api.swapHash(...args); },
    };
    let bDone!: Promise<void>;
    // Right after a writes its secret, b reads (no hash yet), writes its own secret, and waits to swap.
    s.between = async () => {
      bDone = keepAccount(b, "t_acme", "t_acme", "whsec_b");
      await bAtGate;
    };
    await keepAccount(s.api, "t_acme", "t_acme", "whsec_a");
    // a swapped first: the hash names a, the secret is b's. The state to repair.
    expect(s.secret).toBe("t_acme\nwhsec_b");
    expect(s.hashed).toBe(accountHash("t_acme", "whsec_a"));
    release();
    await bDone;
    // b's swap lost, so it read again and wrote both.
    expect(inStep(s)).toBe(true);
    expect(s.secret).toBe("t_acme\nwhsec_b");
  });

  it("gives up after losing the race every time, so KIFF sends the alert again", async () => {
    const s = store();
    let n = 0;
    const rival = async () => {
      s.hashed = accountHash("t_acme", `whsec_r${n++}`);
      s.between = rival;
    };
    s.between = rival;
    await expect(keepAccount(s.api, "t_acme", "t_acme", "whsec_a")).rejects.toThrow(/try again/);
    expect(s.writes).toBe(ACCOUNT_TRIES);
  });
});
