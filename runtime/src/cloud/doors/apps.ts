import type { DoorKind } from "./model.js";

/**
 * The apps a door can open on, as data. Email and WhatsApp are built in; any
 * other app is a connector door, described by where its "new message" event
 * keeps the sender, the text and the thread, and the tool the taf answers
 * with. A new app is a new entry here (or in the deployment's config), not new
 * code, and the app lists whatever tafs cloud sends.
 */
export interface DoorApp {
  id: string;
  label: string;
  kind: DoorKind;
  /**
   * "proven": the app tells who wrote (a signed-in member, a verified number,
   * an email that passed the sender check), so the door can hold keys.
   * "none": anyone can type any name; everything there knocks.
   */
  proof: "proven" | "none";
  /** One line on how it knows who's writing, for the owner. */
  how: string;
  /** A connector door's event and fields. */
  event?: ConnectorEvent;
}

export interface ConnectorEvent {
  /** The catalog's trigger that reports a new message. */
  trigger: string;
  /** Dotted paths into the event's data. */
  sender: string;
  name?: string;
  text: string;
  /** What groups messages into one conversation; the sender when absent. */
  thread?: string;
  /** The message's own id; the delivery's id when absent. */
  id?: string;
  /** The tool the taf answers with, through KIFF, and which fields it answers to. */
  reply: { tool: string; to: string; thread?: string };
}

export const BUILT_IN: DoorApp[] = [
  { id: "email", label: "Email", kind: "email", proof: "proven", how: "The email must pass the sender check" },
  { id: "whatsapp", label: "WhatsApp", kind: "whatsapp", proof: "proven", how: "WhatsApp knows the phone number" },
];

/**
 * Connector apps come from the deployment's config (`DOORS_APPS`, JSON
 * DoorApp[]): each names its catalog's trigger, the fields of its event and
 * its reply tool, and is offered only when the catalog has a sign-in set up
 * for it. Adding an app is a config change, not an app release. Built-in
 * apps can't be replaced.
 */
export function apps(extra: DoorApp[] = []): DoorApp[] {
  const byID = new Map<string, DoorApp>();
  for (const a of extra) if (a.kind === "connector" && a.event && validApp(a)) byID.set(a.id, a);
  for (const a of BUILT_IN) byID.set(a.id, a);
  return [...byID.values()];
}

/** A config entry is used only when it is whole: an id the routes can carry, and every field the door reads. */
function validApp(a: DoorApp): boolean {
  const e = a.event;
  return /^[a-z][a-z0-9-]{1,30}$/.test(a.id) && typeof a.label === "string" && Boolean(a.label) &&
    (a.proof === "proven" || a.proof === "none") && typeof a.how === "string" &&
    Boolean(e?.trigger && e.sender && e.text && e.reply?.tool && e.reply.to);
}

/** The deployment's connector apps, from DOORS_APPS; a malformed config is no apps, never a crash. */
export function configuredApps(raw: string | undefined): DoorApp[] {
  if (!raw) return [];
  try {
    const list = JSON.parse(raw) as unknown;
    return Array.isArray(list) ? (list as DoorApp[]) : [];
  } catch {
    console.error("DOORS_APPS is not JSON; no connector apps offered");
    return [];
  }
}

/** A value at a dotted path, as text; undefined when missing or not a scalar. */
export function at(data: unknown, path: string | undefined): string | undefined {
  if (!path) return undefined;
  let v: unknown = data;
  for (const part of path.split(".")) {
    if (typeof v !== "object" || v === null) return undefined;
    v = (v as Record<string, unknown>)[part];
  }
  if (typeof v === "string") return v;
  if (typeof v === "number" || typeof v === "boolean") return String(v);
  return undefined;
}
