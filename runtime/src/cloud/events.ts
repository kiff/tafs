import type { DoorStore } from "./doors/model.js";

/**
 * What happens in tafs itself, for the app's Activity beside KIFF's calls
 * (kiff/tafs#236, items 15 and 19): a halt and its resume, a knock at a door
 * and what the owner did with it, a door's removal. KIFF's record doesn't see
 * these: nothing goes through its gateway.
 *
 * One list per account, newest first, the latest KEPT for DAYS, written with
 * the store's compare-and-swap so two writes at once both land. Words for
 * people only: a taf's name and a sender as the door named them, never a key.
 * A stranger's address isn't kept here, only the name their message gave: the
 * knock itself keeps it for 30 days (docs/data.md).
 */
export interface AccountEvent {
  at: string;
  kind: "halted" | "resumed" | "knocked" | "knock_done" | "knock_ignored" | "paired" | "door_removed";
  /** The taf's id in tafs cloud; absent for all of the account's tafs. */
  taf?: string;
  /** The taf's name when it happened. */
  name?: string;
  /** The door's app ("email", "whatsapp") for a door's event. */
  app?: string;
  /** Who wrote: the name their message gave; for a pairing, else their address (now a key). */
  from?: string;
}

export interface EventLog {
  add(tenant: string, e: Omit<AccountEvent, "at">, now?: Date): Promise<void>;
  list(tenant: string): Promise<AccountEvent[]>;
}

export const EVENTS = { kept: 200, days: 90 } as const;

export const eventsKey = (tenant: string) => `${tenant}#events`;

export function eventLog(store: DoorStore): EventLog {
  return {
    async add(tenant, e, now = new Date()) {
      const event: AccountEvent = { at: now.toISOString(), ...e };
      const until = new Date(now.getTime() + EVENTS.days * 86_400_000);
      for (let i = 0; i < 6; i++) {
        const read = (await store.get(eventsKey(tenant))) as { events?: AccountEvent[] } | undefined;
        const events = [event, ...(Array.isArray(read?.events) ? read.events : [])].slice(0, EVENTS.kept);
        if (await store.swap(eventsKey(tenant), read, { events }, until)) return;
      }
      throw new Error("events: changed at the same time six times");
    },
    async list(tenant) {
      const read = (await store.get(eventsKey(tenant))) as { events?: AccountEvent[] } | undefined;
      return Array.isArray(read?.events) ? read.events : [];
    },
  };
}

/**
 * Adds an event without failing what it records: the halt or the knock has
 * happened, and a log that can't be written says so in the function's own log.
 */
export async function logEvent(log: EventLog | undefined, tenant: string, e: Omit<AccountEvent, "at">, now?: Date): Promise<void> {
  if (!log) return;
  await log.add(tenant, e, now).catch((err) => console.error(`events: not kept: ${(err as Error).message}`));
}
