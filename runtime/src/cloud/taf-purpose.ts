import type { Job } from "../jobs.js";

/** A starter template is not a custom taf's job. Use its own saved description first. */
export function tafPurpose(taf: { name: string; summary?: string; ownerNotes?: string }, job?: Job): string {
  return taf.summary?.trim() || taf.ownerNotes?.trim()
    || (taf.name.trim().toLowerCase() === job?.name.toLowerCase() ? job.summary : "Ask Coach to describe this taf's job");
}
