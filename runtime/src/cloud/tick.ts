/**
 * The scheduled tick's sweeps, one after another, each timed. One log line per sweep,
 * `{"event":"sweep","name","accounts","ms"}`, so a tick that grows toward the function's
 * timeout shows which sweep grew, and one that stops partway shows where. A sweep that
 * throws logs `"failed":true` (never its error, which may name an account) and ends the
 * tick as before.
 *
 * The accounts are read once a tick and handed to every sweep, instead of once per sweep.
 */
import type { Deps } from "./handler.js";

export type Sweep = [name: string, run: (d: Deps) => Promise<unknown>];

export async function tick(d: Deps, sweeps: Sweep[], log: (line: string) => void = console.log): Promise<Record<string, unknown>> {
  const listed = await d.tafs.tenants();
  const once: Deps = { ...d, tafs: Object.assign(Object.create(d.tafs) as Deps["tafs"], { tenants: async () => listed }) };
  const out: Record<string, unknown> = {};
  for (const [name, run] of sweeps) {
    const start = Date.now();
    let failed = false;
    try {
      out[name] = await run(once);
    } catch (err) {
      failed = true;
      throw err;
    } finally {
      log(JSON.stringify({ event: "sweep", name, accounts: listed.length, ms: Date.now() - start, ...(failed ? { failed } : {}) }));
    }
  }
  return out;
}
