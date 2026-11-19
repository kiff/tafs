import { describe, expect, it } from "vitest";
import { apiDoorView, apiRequestsPath, listDoors, type DoorsContext } from "../src/cloud/doors/doors.js";
import { API_DOOR, type Door, doorKeys, type DoorStore } from "../src/cloud/doors/model.js";
import type { Taf } from "../src/cloud/handler.js";
import { contract } from "./contract.js";

const NOW = new Date("2026-11-19T21:00:00Z");
const TAF: Taf = { id: "taf-0bde5000000000000000", name: "Returns", job: "custom", kiffAgent: "returns", nameClaim: "c", created: "2026-11-01T09:00:00Z", unscheduled: true };

/** A door "From another application" as tafs cloud keeps it: two keys, kept as hashes. */
const API: Door = {
  id: "door_ord7", kind: "api", app: "api", open: true, address: "", route: "api:door_ord7", keys: [], created: "2026-11-19T08:00:00.000Z",
  apiKeys: [
    { id: "key_0000000000000001", name: "ERP production", last4: "iXrW", hash: "a".repeat(64), made: "2026-11-19T08:00:00.000Z", lastUsed: "2026-11-19T09:42:15.000Z" },
    { id: "key_0000000000000002", name: "ERP staging", last4: "Qp0z", hash: "b".repeat(64), made: "2026-11-19T10:00:00.000Z" },
  ],
  waiting: 2,
  lastRequest: { status: "done", at: "2026-11-19T09:42:15.000Z" },
};

function store(held: Map<string, unknown>): DoorStore {
  return {
    async get(k) { return structuredClone(held.get(k)); },
    async put(k, v) { held.set(k, structuredClone(v)); },
    async create(k, v) { if (held.has(k)) return false; held.set(k, structuredClone(v)); return true; },
    async remove(k) { held.delete(k); },
    async swap(k, prev, next) { if (JSON.stringify(held.get(k)) !== JSON.stringify(prev)) return false; held.set(k, structuredClone(next)); return true; },
    async bump(k, max) { const n = (held.get(k) as number | undefined) ?? 0; if (n >= max) return false; held.set(k, n + 1); return true; },
  };
}

function ctx(held: Map<string, unknown>, lang: "en" | "de" = "en"): DoorsContext {
  return {
    doors: { store: store(held), secrets: { async get() { return undefined; }, async put() {}, async remove() {} }, config: async () => ({}) },
    tafs: {} as never, agents: {} as never, push: {} as never, apns: {} as never, now: NOW, lang,
  };
}

describe("a door From another application (api), in the owner's list", () => {
  it("carries every field today's builds require, plus its own, and never a key or its hash (contracts/doors-api.json)", async () => {
    const held = new Map<string, unknown>([[doorKeys.doors("t_acme", TAF.id), { doors: [API] }], [doorKeys.day("t_acme", TAF.id, API.id, "2026-11-19", "keys"), 37]]);
    const out = await listDoors(ctx(held), { tenant: "t_acme", say: async () => undefined }, TAF);
    const door = out.doors[0] as Record<string, unknown>;
    // What a build without row 4 requires (iOS TafsCloud.Door, Android Doors.kt): an older app shows it as a plain door.
    for (const k of ["id", "app", "kind", "label", "open", "address", "proof", "how", "keys", "today", "limit", "resting", "words"]) expect(door).toHaveProperty(k);
    expect(door).toMatchObject({ kind: "api", proof: "proven", address: apiRequestsPath("door_ord7"), today: 37, limit: API_DOOR.perDay, resting: false });
    expect((door.keys as Array<Record<string, unknown>>).every((k) => typeof k.id === "string")).toBe(true);
    // Its own: counts, the last request in tafs cloud's word, each key's name, last four and dates.
    expect(door.counts).toEqual({ today: 37, waiting: 2 });
    expect(door.last_request).toEqual({ status: "done", at: "2026-11-19T09:42:15.000Z", words: "Done" });
    expect(door.keys).toEqual([
      { id: "key_0000000000000001", name: "ERP production", last4: "iXrW", made_at: "2026-11-19T08:00:00.000Z", last_used_at: "2026-11-19T09:42:15.000Z" },
      { id: "key_0000000000000002", name: "ERP staging", last4: "Qp0z", made_at: "2026-11-19T10:00:00.000Z" },
    ]);
    // Never a key or its hash, anywhere in the answer.
    const text = JSON.stringify(out);
    expect(text).not.toContain("a".repeat(64));
    expect(text).not.toContain("hash");
    expect(text).not.toContain("tafs_api_");
    contract("doors-api", "GET /v1/tafs/{id}/doors with a door From another application (api): the fields older builds require, its counts, its last request in tafs cloud's word, and its keys by name and last four, never a key.", out);
  });

  it("says paused, resting at its day's cap, and nothing of a last request it hasn't had", () => {
    const paused = apiDoorView({ ...API, open: false, lastRequest: undefined });
    expect(paused.words.state).toBe("Paused: requests are refused until you open it.");
    expect(paused).not.toHaveProperty("last_request");
    const full = apiDoorView(API, { keys: API_DOOR.perDay, others: 0 });
    expect(full.resting).toBe(true);
    expect(full.words.state).toBe(`Today's ${API_DOOR.perDay} requests are used: more are refused until midnight UTC.`);
    expect(apiDoorView({ ...API, perDay: 500 }).limit).toBe(500);
  });

  it("has a word for each of a request's states", () => {
    for (const s of ["accepted", "working", "needs_you", "done", "failed", "cancelled", "expired"] as const) {
      const w = apiDoorView({ ...API, lastRequest: { status: s, at: NOW.toISOString() } }).last_request?.words;
      expect(w && w !== s, s).toBeTruthy();
    }
  });
});
