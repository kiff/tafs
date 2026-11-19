import { describe, expect, it } from "vitest";
import { handle, type HTTPEvent, type Taf } from "../src/cloud/handler.js";
import { contract } from "./contract.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryCoach, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

// The files the account's tafs deliver (kiff/agents' deliver_file): listed, read, downloaded and deleted by
// everyone on the account, each with its taf's name as tafs has it (docs/files.md).

const NOW = new Date("2026-11-19T08:00:00Z");
const ROWS = "taf-0bde5000000000000000";
const PEOPLE = { ana: { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] },
  bo: { tenant_id: "t_acme", subject: "u_bo", api_key: false, capabilities: ["read"] },
  eve: { tenant_id: "t_other", subject: "u_eve", api_key: false, capabilities: ["read", "cards.manage"] } } as const;

async function world() {
  const agents = fakeAgents(() => NOW);
  const tafs = memoryTafs();
  tafs.held.set(`t_acme#${ROWS}`, { id: ROWS, name: "Order rows", job: "orders", kiffAgent: "rows", nameClaim: "c", created: "2026-11-18T21:00:00Z" } satisfies Taf);
  const week = agents.deliver("t_acme", { taf: ROWS, run: "run-1", chat: "chat-1", name: "week.csv", media_type: "text/csv" }, "name,amount\nAna,12.50\n");
  const notes = agents.deliver("t_acme", { taf: ROWS, run: "run-2", name: "notes.md", media_type: "text/markdown" }, "# Notes\n");
  const theirs = agents.deliver("t_other", { taf: "taf-0e1e0000000000000000", run: "run-9", chat: "chat-9", name: "theirs.txt", media_type: "text/plain" }, "secret");
  return { agents, tafs, week, notes, theirs };
}
type World = Awaited<ReturnType<typeof world>>;

function deps(w: World) {
  return {
    agents: w.agents.api, keys: memoryKeys(), accounts: memoryAccounts(), tafs: w.tafs,
    names: memoryNames(), slots: memorySlots(), claims: memoryClaims(), removals: memoryRemovals(), archived: memoryArchived(), coach: memoryCoach(() => NOW),
    push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async () => "sent" as const },
    forward: async () => undefined,
    fetch: (async (_u: string, init?: RequestInit) => {
      const who = String((init?.headers as Record<string, string>).authorization).replace("Bearer ", "") as keyof typeof PEOPLE;
      return PEOPLE[who] ? new Response(JSON.stringify(PEOPLE[who])) : new Response("{}", { status: 401 });
    }) as typeof fetch,
    now: () => NOW,
  };
}

async function call(w: World, who: string, method: string, path: string) {
  const [rawPath, rawQueryString] = path.split("?");
  const event: HTTPEvent = { rawPath: rawPath!, rawQueryString, requestContext: { http: { method } }, headers: { Authorization: `Bearer ${who}` }, body: "" };
  const r = await handle(event, deps(w));
  return { status: r.statusCode, body: JSON.parse(r.body || "{}") as Record<string, unknown> };
}

describe("Files", () => {
  it("lists the account's files newest first, each with its taf's name, for anyone on the account", async () => {
    const w = await world();
    const r = await call(w, "bo", "GET", "/v1/files");
    expect(r.status).toBe(200);
    const files = r.body.files as Array<Record<string, unknown>>;
    expect(files.map((f) => f.id)).toEqual([w.notes.id, w.week.id]);
    expect(files.every((f) => f.taf_name === "Order rows")).toBe(true);
    contract("files-list", "GET /v1/files: the account's delivered files, newest first, each with its taf's name as tafs has it.", r.body);
  });

  it("narrows to a taf, a run or a chat, and passes them on as ids only", async () => {
    const w = await world();
    expect(((await call(w, "ana", "GET", "/v1/files?chat=chat-1")).body.files as unknown[]).length).toBe(1);
    expect(((await call(w, "ana", "GET", "/v1/files?run=run-2")).body.files as unknown[]).length).toBe(1);
    expect(((await call(w, "ana", "GET", `/v1/files?taf=${ROWS}`)).body.files as unknown[]).length).toBe(2);
    expect(w.agents.calls).toContain(`listFiles {"chat":"chat-1"}`);
    for (const q of ["taf=a%20b", "run=../x", "chat=" + "x".repeat(129), "after=att_1"]) {
      expect((await call(w, "ana", "GET", `/v1/files?${q}`)).status).toBe(400);
    }
  });

  it("reads, downloads and deletes one file, naming the member to kiff/agents", async () => {
    const w = await world();
    const one = await call(w, "bo", "GET", `/v1/files/${w.week.id}`);
    expect(one.status).toBe(200);
    expect(one.body).toMatchObject({ id: w.week.id, taf_name: "Order rows", preview: "name,amount\nAna,12.50\n", preview_truncated: false });
    contract("files-file", "GET /v1/files/{id}: one delivered file with its start as plain text, which the apps draw as text.", one.body);
    const dl = await call(w, "bo", "GET", `/v1/files/${w.week.id}/download`);
    expect(dl.status).toBe(200);
    expect(dl.body.url).toMatch(/^https:\/\//);
    contract("files-download", "GET /v1/files/{id}/download: a URL to the file's bytes for a minute; the app reads them to save or share the file itself.", dl.body);
    const del = await call(w, "ana", "DELETE", `/v1/files/${w.week.id}`);
    expect(del).toEqual({ status: 200, body: { deleted: w.week.id } });
    expect(w.agents.calls).toEqual(expect.arrayContaining([`readDeliveredFile ${w.week.id} member:u_bo`, `downloadDeliveredFile ${w.week.id} member:u_bo`, `deleteDeliveredFile ${w.week.id} member:u_ana`]));
    expect((await call(w, "bo", "GET", `/v1/files/${w.week.id}`)).status).toBe(404);
  });

  it("never reaches another account's file, by id or by filter", async () => {
    const w = await world();
    for (const [method, path] of [["GET", `/v1/files/${w.theirs.id}`], ["GET", `/v1/files/${w.theirs.id}/download`], ["DELETE", `/v1/files/${w.theirs.id}`]] as const) {
      expect((await call(w, "ana", method, path)).status).toBe(404);
    }
    expect(((await call(w, "ana", "GET", "/v1/files?chat=chat-9")).body.files as unknown[]).length).toBe(0);
    expect(w.agents.delivered.some((d) => d.file.id === w.theirs.id)).toBe(true);
    expect(((await call(w, "eve", "GET", "/v1/files")).body.files as Array<{ id: string; taf_name: unknown }>)).toEqual([expect.objectContaining({ id: w.theirs.id, taf_name: null })]);
  });

  it("refuses an id that isn't a file's before asking kiff/agents", async () => {
    const w = await world();
    for (const path of ["/v1/files/att_00000000000000000000000000000001", "/v1/files/..", `/v1/files/${w.week.id}/bytes`]) {
      expect((await call(w, "ana", "GET", path)).status).toBe(404);
    }
    expect(w.agents.calls.filter((c) => c.startsWith("readDeliveredFile"))).toEqual([]);
    expect((await call(w, "nobody", "GET", "/v1/files")).status).toBe(401);
  });
});
