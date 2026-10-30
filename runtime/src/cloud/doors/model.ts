/**
 * A taf's doors: the places where work reaches it from outside the app, with
 * the answer going back through the same door: an email address, and a
 * WhatsApp number (apps.ts).
 *
 * Who wrote decides what happens:
 * - a key (a sender the owner paired, on a door that can prove who wrote, and
 *   proven on this message) gives work: the message becomes a chat message
 *   for the taf, through the same path as the app's chat, so its run, Cards,
 *   holds and the Record are unchanged;
 * - anyone else knocks: the owner is told who wrote and what they want, and
 *   nothing is done until the owner says so. A message from a stranger is
 *   information, never instructions.
 */

export type DoorKind = "email" | "whatsapp";

/** A sender who may give the taf work through one door. */
export interface Key {
  /** The sender as the door names them: an email address, a phone number, an app's user id. Lowercase. */
  id: string;
  name?: string;
  /** Paired by tafs, not by the owner: the owner's own sign-in email. */
  auto?: boolean;
  added: string;
}

export interface Door {
  /** Unique among the taf's doors: "email" or "whatsapp". */
  id: string;
  kind: DoorKind;
  /** The app it opens on (apps.ts): "email" or "whatsapp". */
  app: string;
  open: boolean;
  /** What the owner sees and shares: an email address or a phone number. */
  address: string;
  /** How an arriving message finds this door (`door#<route>` names it). */
  route: string;
  keys: Key[];
  created: string;
  /** A WhatsApp door's number, as the WhatsApp Business Platform names it. */
  phoneNumberID?: string;
}

/** Where a door's answer goes back, in the same thread: what the door's provider needs. */
export type ThreadRef = Record<string, string>;

/** A message that came through a door, as its provider handed it over. */
export interface Arrival {
  route: string;
  /** The provider's id for the message: a message delivered twice starts nothing twice. */
  id: string;
  /** The sender, as keys name them: an email address or a phone number. */
  from: string;
  name?: string;
  /**
   * Whether the provider proved the message comes from `from`: an email that
   * passed the sender check, or a WhatsApp number. A
   * message that isn't proven is never a key's, whatever it says it is.
   */
  proven: boolean;
  /** The sender check failed: the message only says it's from `from`. */
  spoofed?: boolean;
  subject?: string;
  text: string;
  /** Attachments it carried: v1 reads text only, and says so. */
  attachments: number;
  thread: ThreadRef;
  /** Messages with the same thread key go on in the same chat. */
  threadKey: string;
  at: string;
}

/** Someone without a key wrote: the owner decides. */
export interface Knock {
  id: string;
  door: string;
  app: string;
  from: string;
  name?: string;
  /** "fail": the message claimed a sender and the check said it isn't. "none": the door can't prove who wrote. */
  proof: "pass" | "fail" | "none";
  subject?: string;
  text: string;
  attachments: number;
  at: string;
  state: "open" | "done" | "paired" | "ignored";
  /** Its sender was paired from it; the message still waits for Do it or Ignore (kiff/tafs#236, item 18). */
  paired?: boolean;
  /**
   * Not a message: the line saying the door had its day's messages, from keys
   * or from everyone else, and how many more were dropped since.
   */
  rest?: "keys" | "others";
  dropped?: number;
}

/** A reply a door owes: the run that answers a key's message, and where its answer goes. */
export interface Reply {
  taf: string;
  door: string;
  run: string;
  to: string;
  name?: string;
  subject?: string;
  thread: ThreadRef;
  attachments: number;
  cut: boolean;
  /** When the message arrived: WhatsApp takes a free-form reply for 24 hours. */
  at: string;
  /** Tries the provider didn't take. */
  tries?: number;
  /** When the next try is due, after one the provider didn't take. */
  next_at?: string;
  /** The places its result has reached, or given up on, so a later try sends only the rest. */
  done?: string[];
}

/** How a door's reply went, for the chat's stamp. */
export interface Sent {
  run: string;
  door: string;
  app: string;
  status: "sent" | "not_sent";
  why?: string;
  at: string;
}

/**
 * What doors keep, in tafs cloud's table. Every item but a door's route is
 * under the account's prefix, so deleting the account erases it (account.ts);
 * a route goes with its door.
 */
export interface DoorStore {
  get(key: string): Promise<unknown>;
  put(key: string, value: unknown, expiresAt?: Date): Promise<void>;
  /** Writes only when nothing unexpired is there; false when something is. */
  create(key: string, value: unknown, expiresAt?: Date): Promise<boolean>;
  remove(key: string): Promise<void>;
  /** Adds one to the count at key while it is below max; false at max. */
  bump(key: string, max: number, expiresAt: Date): Promise<boolean>;
  /**
   * Writes `next` only while the item is still `prev` as `get` read it
   * (undefined: still absent); false, and nothing written, when it changed.
   */
  swap(key: string, prev: unknown, next: unknown, expiresAt?: Date): Promise<boolean>;
}

/** Secrets a door needs, by name: a WhatsApp number's business token. */
export interface DoorSecrets {
  get(name: string): Promise<string | undefined>;
  put(name: string, value: string): Promise<void>;
  remove(name: string): Promise<void>;
}

/** Limits for one door. */
export const DOOR = {
  /** Messages a door takes in a day (UTC) from the owner and paired senders; past it, more are dropped until tomorrow. */
  keysPerDay: 20,
  /** And from anyone else, apart, so strangers can't use up the owner's day. */
  othersPerDay: 10,
  /** A message longer than this is cut, and the reply says so. */
  maxChars: 20_000,
  /** And never more than this many bytes, inside what a run takes. */
  maxBytes: 28_000,
  /** A message id is remembered this long. */
  seenDays: 30,
  /** A thread goes on in its chat this long after its last message. */
  threadDays: 30,
  /** A reply waits this long for its run to end (a run can wait on the owner for days). */
  replyDays: 30,
  /** A reply is sent once and, when the provider doesn't take it, tried again after each of these waits. */
  replyWaitsMinutes: [5, 20, 60],
  /** A message older than this is refused: a delivery captured and sent again later starts nothing. */
  staleHours: 48,
  /** A removed email address isn't given to another taf for this long, so its old senders never reach someone else. */
  retiredDays: 365,
  /** Knocks kept per taf, newest first, and how long. */
  knocksKept: 50,
  knockDays: 30,
  /** WhatsApp takes a free-form reply this long after the person's message. */
  whatsappWindowHours: 24,
} as const;

export const doorKeys = {
  route: (route: string) => `door#${route}`,
  doors: (tenant: string, taf: string) => `${tenant}#doors#${taf}`,
  seen: (tenant: string, hash: string) => `${tenant}#door-seen#${hash}`,
  day: (tenant: string, taf: string, door: string, day: string, who: "keys" | "others") => `${tenant}#door-day#${taf}#${door}#${day}#${who}`,
  thread: (tenant: string, taf: string, door: string, hash: string) => `${tenant}#door-thread#${taf}#${door}#${hash}`,
  reply: (tenant: string, ref: string) => `${tenant}#door-reply#${ref}`,
  sending: (tenant: string, ref: string) => `${tenant}#door-sending#${ref}`,
  pending: (tenant: string, taf: string) => `${tenant}#door-pending#${taf}`,
  /** The replies waiting for their next try, on every account: what the sweep reads. */
  retrying: () => "door-retrying",
  /** A door reply given up on after its tries, by its run: kept so the owner can send it again. */
  unsent: (tenant: string, taf: string, run: string) => `${tenant}#door-unsent#${taf}#${run}`,
  sent: (tenant: string, taf: string) => `${tenant}#door-sent#${taf}`,
  knocks: (tenant: string, taf: string) => `${tenant}#door-knocks#${taf}`,
  bound: (tenant: string) => `${tenant}#door-ai`,
};

/** A WhatsApp number's business token, in the secret store. */
export function whatsappSecretName(prefix: string, tenant: string, phoneNumberID: string): string {
  return `${prefix}${tenant}/whatsapp/${phoneNumberID}`;
}
