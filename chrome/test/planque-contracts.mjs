// planque's golden files for this extension: what planque's own tests write of the messages it sends a Chrome, read here by
// the extension's tests and by the end-to-end fake planque (e2e/planque.mjs), so a change of planque's shapes fails here, in
// seconds, never first in someone's Chrome (contracts/README.md).
//
// The seam: the three files below, in contracts/. Until planque's change writes them they aren't there, and everything falls
// back to the shapes the fake has always sent. Dropping the files in is the only change left: no code here changes then.

import { existsSync, readFileSync } from "node:fs";
import { sign, LABEL } from "../src/crypto.js";

/** The files, by the message each holds: `{about, answer}` as every contract is, `answer` the message as planque sends it. */
export const PLANQUE_CONTRACTS = Object.freeze({
  welcome: "chrome-welcome.json", // {type: "welcome", sig, connections, now, …}
  connections: "chrome-connections.json", // {type: "connections", connections: [{grant, agent, name, sites, sites_signed?, …}]}
  step: "chrome-step.json", // {type: "step", step: {id, nonce, exp, chrome_id, run, taf, grant, sites, sites_signed, op, …}, sig}
});

const DIR = new URL("../../contracts/", import.meta.url);

/** The message planque's file [name] holds, or null while the file isn't in contracts/ yet. */
export function planqueContract(name, dir = DIR) {
  const file = new URL(PLANQUE_CONTRACTS[name], dir);
  if (!existsSync(file)) return null;
  const c = JSON.parse(readFileSync(file, "utf8"));
  return c && typeof c === "object" && c.answer && typeof c.answer === "object" ? c.answer : c;
}

/**
 * [live] laid over planque's [contract]: the contract's fields and fixed values, with what only this run can make (a signature,
 * nonces, ids, times, its keys' sites) from [live]. A field [live] sets that the contract doesn't carry throws: the fake would
 * send what planque doesn't. With no contract, [live] as it is.
 */
export function layOver(contract, live, what = "planque's message") {
  if (!contract) return live;
  const extra = Object.keys(live).filter((k) => !(k in contract));
  if (extra.length) throw new Error(`${what} has no ${extra.join(", ")}: the fake would send what planque doesn't`);
  return { ...contract, ...live };
}

/** One connection as planque lists it: planque's own connection (the connections file's first, else the welcome's) under [live]. */
export function connectionAsPlanque(live, dir = DIR) {
  const list = (planqueContract("connections", dir) || {}).connections || (planqueContract("welcome", dir) || {}).connections;
  return layOver(Array.isArray(list) && list[0] && typeof list[0] === "object" ? list[0] : null, live, "planque's connection");
}

/** planque's welcome, its connections each as planque lists one. */
export function welcomeAsPlanque(live, dir = DIR) {
  const out = layOver(planqueContract("welcome", dir), live, PLANQUE_CONTRACTS.welcome);
  return { ...out, connections: (live.connections || []).map((c) => connectionAsPlanque(c, dir)) };
}

/** planque's `{type: "connections"}` on a change. */
export function connectionsAsPlanque(live, dir = DIR) {
  const out = layOver(planqueContract("connections", dir), live, PLANQUE_CONTRACTS.connections);
  return { ...out, connections: (live.connections || []).map((c) => connectionAsPlanque(c, dir)) };
}

/**
 * A step's own fields, which differ by op or by case (a sensitive site's allowance): never taken from planque's one step into
 * another, and never required of it.
 */
export const STEP_OP_FIELDS = Object.freeze(["op", "url", "read_id", "act", "hand", "offset", "sensitive_allowed"]);

/**
 * A step's body as planque signs it: planque's step's other fields (its envelope) under [live], whose op fields are its own
 * (sign it after: planque signs the whole body). Only the envelope must be in planque's step.
 */
export function stepAsPlanque(live, dir = DIR) {
  const c = planqueContract("step", dir);
  const step = c && c.step && typeof c.step === "object" ? c.step : null;
  if (!step) return live;
  const envelope = Object.fromEntries(Object.entries(step).filter(([k]) => !STEP_OP_FIELDS.includes(k)));
  const own = Object.fromEntries(Object.entries(live).filter(([k]) => STEP_OP_FIELDS.includes(k)));
  const rest = Object.fromEntries(Object.entries(live).filter(([k]) => !STEP_OP_FIELDS.includes(k)));
  return { ...layOver(envelope, rest, "planque's step"), ...own };
}

/**
 * planque's connection [c] with its `sites_signed.sig` signed again by [phoneKey] for [chromeId]: the file's own grant and
 * sites, under `tafs-chrome/sites/v1`. The files hold a placeholder there, not a signature anyone can check; a test signs it as
 * the paired phone does. A connection with no `sites_signed` is left as it is.
 */
export async function withPhoneSignature(c, { phoneKey, chromeId }) {
  if (!c || !c.sites_signed) return c;
  const sites = c.sites_signed.sites;
  return { ...c, sites_signed: { ...c.sites_signed, sig: await sign(phoneKey, LABEL.sites, { chrome_id: chromeId, grant: c.grant, sites }) } };
}
