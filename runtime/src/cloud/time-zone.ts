import type { Agents, Schedule } from "./agents.js";

/** Reapply KIFF's confirmed account zone to recurring schedules; never shift absolute follow-ups. */
export async function syncScheduleZone(agents: Pick<Agents, "listSchedules" | "updateSchedule">, tenant: string,
  tafs: Array<{ id: string }>, zone: string): Promise<{ updated: number; failed: number }> {
  new Intl.DateTimeFormat("en-GB", { timeZone: zone });
  let updated = 0, failed = 0;
  for (const taf of tafs) {
    let schedules: Schedule[];
    try { schedules = await agents.listSchedules(tenant, taf.id); } catch { failed++; continue; }
    for (const schedule of schedules) {
      if (schedule.agent !== taf.id || schedule.follow_up || schedule.kind === "once" || (schedule.time_zone || "UTC") === zone) continue;
      try {
        await agents.updateSchedule(tenant, { ...schedule, time_zone: zone });
        updated++;
      } catch { failed++; }
    }
  }
  return { updated, failed };
}
