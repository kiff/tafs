import { describe, expect, it, vi } from "vitest";
import { syncScheduleZone } from "../src/cloud/time-zone.js";
import { tafPurpose } from "../src/cloud/taf-purpose.js";
import { jobById } from "../src/jobs.js";
import { handle, type Deps, type HTTPEvent } from "../src/cloud/handler.js";
import type { Schedule } from "../src/cloud/agents.js";

const schedule = (id: string, extra: Partial<Schedule> = {}): Schedule => ({ id, agent: "taf-example", cron: "0 9 * * 1-5", time_zone: "Europe/Paris", enabled: true, input: "Do its job", ...extra });

describe("the account's confirmed time zone", () => {
  it("updates every recurring schedule, preserves pause and busy rules, and never moves absolute follow-ups", async () => {
    const schedules = [schedule("main", { enabled: false, busy: "latest", skip: ["2026-12-25"], check: { tool: "read_case", arguments: {}, items: "items", cursor: "updated" } }),
      schedule("month-end", { kind: "monthly_last_day" }), schedule("follow", { kind: "once", follow_up: true, at: "2026-11-05T09:00:00Z" }),
      schedule("one-off", { kind: "once", at: "2026-11-05T09:00:00Z" }), schedule("another-agent", { agent: "other-taf" })];
    const updateSchedule = vi.fn(async (_tenant, s) => s);
    const agents = { listSchedules: vi.fn(async () => schedules), updateSchedule };
    expect(await syncScheduleZone(agents, "account-example", [{ id: "taf-example" }], "Asia/Tokyo")).toEqual({ updated: 2, failed: 0 });
    expect(updateSchedule.mock.calls).toEqual(schedules.slice(0, 2).map((s) => ["account-example", { ...s, time_zone: "Asia/Tokyo" }]));
    expect(schedules[0]!.time_zone).toBe("Europe/Paris", "the read snapshot isn't mutated");
  });
  it("retries only unfinished schedules and reports partial failures without claiming completion", async () => {
    let schedules = [schedule("one"), schedule("two")]; let fail = true;
    const agents = { listSchedules: async () => schedules, updateSchedule: vi.fn(async (_tenant: string, s: Schedule) => {
      if (s.id === "two" && fail) throw new Error("unavailable");
      schedules = schedules.map((v) => v.id === s.id ? s : v); return s;
    }) };
    expect(await syncScheduleZone(agents, "account-example", [{ id: "taf-example" }], "Asia/Tokyo")).toEqual({ updated: 1, failed: 1 });
    fail = false;
    expect(await syncScheduleZone(agents, "account-example", [{ id: "taf-example" }], "Asia/Tokyo")).toEqual({ updated: 1, failed: 0 });
    expect(await syncScheduleZone(agents, "account-example", [{ id: "taf-example" }], "Asia/Tokyo")).toEqual({ updated: 0, failed: 0 });
    expect(agents.updateSchedule).toHaveBeenCalledTimes(3);
  });
  it("takes the zone and account from KIFF, checks capability, and never takes a caller's zone", async () => {
    let allowed = true; const updated: Schedule[] = [];
    const deps = { tafs: { list: async (tenant: string) => { expect(tenant).toBe("account-example"); return [{ id: "taf-example", job: "refunds" }, { id: "setup", job: "setup" }, { id: "removing", removing: true }]; } },
      agents: { listSchedules: async (tenant: string, id: string) => { expect(tenant).toBe("account-example"); expect(id).toBe("taf-example"); return [schedule("main")]; },
        updateSchedule: async (_tenant: string, s: Schedule) => { updated.push(s); return s; } },
      fetch: (async () => new Response(JSON.stringify({ tenant_id: "account-example", subject: "person-example", capabilities: allowed ? ["cards.manage"] : ["read"], time_zone: "Asia/Tokyo" }))) as typeof fetch,
    } as unknown as Deps;
    const event: HTTPEvent = { rawPath: "/v1/account/time-zone", headers: { authorization: "Bearer synthetic-session" }, requestContext: { http: { method: "POST" } }, body: JSON.stringify({ time_zone: "America/New_York", tenant: "other-account" }) };
    const result = await handle(event, deps);
    expect(result.statusCode).toBe(200); expect(JSON.parse(result.body)).toEqual({ time_zone: "Asia/Tokyo", updated: 1 });
    expect(updated[0]?.time_zone).toBe("Asia/Tokyo");
    allowed = false; expect((await handle(event, deps)).statusCode).toBe(403); expect(updated).toHaveLength(1);
  });
});

describe("a taf's own job", () => {
  it("uses its saved description or notes rather than a different starter job", () => {
    const job = jobById("refunds");
    expect(tafPurpose({ name: "Example", summary: "Saves subscriptions", ownerNotes: "Read subscriptions first" }, job)).toBe("Saves subscriptions");
    expect(tafPurpose({ name: "Example", ownerNotes: "Read subscriptions first" }, job)).toBe("Read subscriptions first");
    expect(tafPurpose({ name: "Example" }, job)).toBe("Ask Coach to describe this taf's job");
    expect(tafPurpose({ name: "Refunds" }, job)).toBe(job.summary);
  });
});
