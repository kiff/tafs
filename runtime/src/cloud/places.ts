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
}

const GRANT = /^[A-Za-z0-9_-]{1,128}$/;
const ACTION = /^[A-Za-z0-9_.-]{1,128}$/;
const ARG = /^[A-Za-z0-9_.-]{1,64}$/;
/** At most this many places at connected apps, and this much fixed data in one. */
export const MAX_ACTION_PLACES = 5;
const MAX_ARGS_BYTES = 2048;

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
  return { place: "action", grant, action, args, name };
}

/** The place on the dial's Results ring, and its line. */
export function placeWords(p: ActionPlace): { label: string; words: string } {
  return { label: p.name.split(" · ")[0] || p.name, words: `Each result goes to ${p.name}` };
}

/** What heads them in a version's instructions. */
export const ACTION_PLACES_INTRO = "Where your results also go:\n";

/**
 * A version's instructions for its action places: the taf calls each one itself, inside its Card. The ids and
 * fixed arguments only; the owner's names never reach the instructions, so a name can't steer the taf.
 */
export function placesInstructions(ps: ActionPlace[]): string | undefined {
  if (!ps.length) return undefined;
  const one = (p: ActionPlace) => `- Call ${p.action} with these arguments as given: ${JSON.stringify(p.args)}, filling its other fields from your result.`;
  return `${ACTION_PLACES_INTRO}${ps.map(one).join("\n")}\n` +
    "Do each one at the end of every request, as a call through KIFF like any other, inside your Card. " +
    "If you have no such tool, or KIFF refuses or holds the call, say so in your result; never skip one silently.";
}
