/**
 * Result places at a connected app: any action of any connection the taf has (planque's grants, Composio's
 * catalog among them), picked on the dial or drafted by Coach. tafs cloud knows no vendor: a place is the
 * connection, the action as KIFF registered it, the arguments the owner fixed (a page, a channel, a sheet)
 * and the name they picked it by. The taf makes the call itself, through KIFF inside its Card, so a
 * version's instructions carry its places and they go live with it.
 */

export interface ActionPlace {
  place: "action";
  /** The connection (planque's grant) the action belongs to. */
  grant: string;
  /** The action's tool name, as KIFF registered it for the taf. */
  action: string;
  /** The arguments the owner fixed; the taf fills the rest from its result. */
  args: Record<string, string | number | boolean>;
  /** What the owner reads: "Notion · Support log". One line, at most 100 characters. */
  name: string;
  /**
   * What goes where: for each argument the taf fills from its result, one line in words ("the amount
   * refunded, in EUR"), Coach's words about the result, so two runs fill a row the same way. At most
   * 20 fields of 200 characters.
   */
  fill?: Record<string, string>;
}

/**
 * Another taf as a place (chaining): the result is handed to that taf as its work, a task it runs under
 * its own Card. tafs cloud hands it over when the run ends; the taf writes the hand-off at the end of
 * its result (HANDOFF_HEADING). One hop: a run from a hand-off hands nothing on.
 */
export interface TafPlace {
  place: "taf";
  /** The receiving taf's id. */
  taf: string;
  /** What the owner reads: the taf's name. One line, at most 100 characters. */
  name: string;
  /** What the hand-off carries, in words, as `ActionPlace.fill` says it for a field. */
  fill?: Record<string, string>;
}

/** A place the taf's own words reach: an action at a connected app, or another taf. */
export type VendorPlace = ActionPlace | TafPlace;

const GRANT = /^[A-Za-z0-9_-]{1,128}$/;
const ACTION = /^[A-Za-z0-9_.-]{1,128}$/;
const ARG = /^[A-Za-z0-9_.-]{1,64}$/;
const TAF = /^[A-Za-z0-9_-]{1,64}$/;
/** How much fixed data one place holds: places themselves aren't counted, as many as the owner wants. */
const MAX_ARGS_BYTES = 2048;
const MAX_FILL = 20;
const MAX_FILL_WORDS = 200;

/** The heading the taf writes its hand-off under, the last section of its result. */
export const HANDOFF_HEADING = "Hand-off";

/** One line for people to read: control characters out, at most `max`. */
function line(v: unknown, max: number): string {
  return typeof v === "string" ? v.replace(/[\u0000-\u001f\u007f]+/g, " ").trim().slice(0, max) : "";
}

/** An action place from a request, checked; an Error says what's wrong, in words. */
export function actionPlace(v: unknown): ActionPlace {
  const o = (v && typeof v === "object" ? v : {}) as Record<string, unknown>;
  if (o.place !== "action") throw new Error("a place is door_reply, app, owner_email, or {place: \"action\", grant, action, args, name}");
  const grant = typeof o.grant === "string" ? o.grant.trim() : "";
  const action = typeof o.action === "string" ? o.action.trim() : "";
  if (!GRANT.test(grant)) throw new Error("an action place names its connection (grant)");
  if (!ACTION.test(action)) throw new Error("an action place names its action, as the connection lists it");
  const raw = o.args === undefined ? {} : o.args;
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) throw new Error("an action place's args are an object");
  const args: ActionPlace["args"] = {};
  for (const [k, x] of Object.entries(raw as Record<string, unknown>)) {
    if (!ARG.test(k)) throw new Error(`${k} isn't an argument name`);
    if (typeof x !== "string" && typeof x !== "number" && typeof x !== "boolean") throw new Error(`${k} is a text, a number or true/false`);
    args[k] = typeof x === "string" ? x.slice(0, 500) : x;
  }
  if (JSON.stringify(args).length > MAX_ARGS_BYTES) throw new Error("an action place's args are too long");
  const name = line(o.name, 100);
  if (!name) throw new Error("an action place has the name the owner picked it by");
  const fill = fillOf(o.fill);
  return { place: "action", grant, action, args, name, ...(fill ? { fill } : {}) };
}

/** What goes where, checked: argument names, and one line of words each. Undefined when none. */
function fillOf(v: unknown): Record<string, string> | undefined {
  if (v === undefined || v === null) return undefined;
  if (typeof v !== "object" || Array.isArray(v)) throw new Error("fill maps each field to what it takes, in words");
  const out: Record<string, string> = {};
  for (const [k, w] of Object.entries(v as Record<string, unknown>)) {
    if (!ARG.test(k)) throw new Error(`${k} isn't a field name`);
    const words = line(w, MAX_FILL_WORDS);
    if (!words) throw new Error(`fill.${k} says what the field takes, in words`);
    out[k] = words;
  }
  if (Object.keys(out).length > MAX_FILL) throw new Error(`fill names at most ${MAX_FILL} fields`);
  return Object.keys(out).length ? out : undefined;
}

/** A taf place from a request, checked; `own` is the taf whose place it would be, which can't be its own. */
export function tafPlace(v: unknown, own?: string): TafPlace {
  const o = (v ?? {}) as Record<string, unknown>;
  if (o.place !== "taf") throw new Error("a taf place is {place: \"taf\", taf, name}");
  const taf = typeof o.taf === "string" ? o.taf.trim() : "";
  if (!TAF.test(taf)) throw new Error("a taf place names the taf that takes the result");
  if (own && taf === own) throw new Error("a taf can't be its own destination");
  const name = line(o.name, 100);
  if (!name) throw new Error("a taf place has the taf's name, as the owner reads it");
  const fill = fillOf(o.fill);
  return { place: "taf", taf, name, ...(fill ? { fill } : {}) };
}

/** Any vendor place from a request, by its `place`, checked. */
export function vendorPlace(v: unknown, own?: string): VendorPlace {
  const place = (v as { place?: unknown } | null)?.place;
  if (place === "taf") return tafPlace(v, own);
  if (place === "action") return actionPlace(v);
  throw new Error("a place is door_reply, app, owner_email, {place: \"action\", grant, action, args, name} or {place: \"taf\", taf, name}");
}

/** The place on the dial's Results ring, and its line. */
export function placeWords(p: VendorPlace): { label: string; words: string } {
  const fields = p.fill ? `: ${Object.values(p.fill).join(", ")}` : "";
  if (p.place === "taf") return { label: p.name, words: `Each result is handed to ${p.name}${fields}` };
  return { label: p.name.split(" · ")[0] || p.name, words: `Each result goes to ${p.name}${fields}` };
}

/** What heads them in a version's instructions. */
export const ACTION_PLACES_INTRO = "Where your results also go:\n";

/**
 * A version's instructions for its action places: the taf calls each one itself, inside its Card. The ids and
 * fixed arguments only; the owner's names never reach the instructions, so a name can't steer the taf.
 */
export function placesInstructions(ps: VendorPlace[]): string | undefined {
  const actions = ps.filter((p): p is ActionPlace => p.place === "action");
  const tafs = ps.filter((p): p is TafPlace => p.place === "taf");
  if (!actions.length && !tafs.length) return undefined;
  const fillWords = (fill: Record<string, string>) => Object.entries(fill).map(([k, w]) => `${k} with: ${w}`).join("; ");
  const one = (p: ActionPlace) => `- Call ${p.action} with these arguments as given: ${JSON.stringify(p.args)}` +
    (p.fill ? `; fill ${fillWords(p.fill)}; its other fields from your result.` : ", filling its other fields from your result.");
  const parts: string[] = [];
  if (actions.length) {
    parts.push(`${ACTION_PLACES_INTRO}${actions.map(one).join("\n")}\n` +
      "Do each one at the end of every request, as a call through KIFF like any other, inside your Card. " +
      "If you have no such tool, or KIFF refuses or holds the call, say so in your result; never skip one silently.");
  }
  if (tafs.length) {
    // The hand-off is tafs cloud's to make: the taf only writes it, once, for every taf place.
    const fills = tafs.filter((p) => p.fill).map((p) => Object.values(p.fill!).join(", "));
    parts.push(`Your result is also handed to another taf as its work. End every result with a section headed "${HANDOFF_HEADING}", the last one, ` +
      "written for a taf that takes it on without having seen your work: what you did, what it acts on (by its reference), and " +
      (fills.length ? `${[...new Set(fills)].join("; ")}. ` : "what it needs next. ") +
      "tafs cloud hands it over; you call nothing for it, and you never hand on a task that came to you as a hand-off.");
  }
  return parts.join("\n\n");
}
