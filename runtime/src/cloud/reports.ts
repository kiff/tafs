import { createHash } from "node:crypto";
import type { OutgoingEmail } from "./doors/email.js";
import type { DoorStore } from "./doors/model.js";

/**
 * A person reports an answer Coach or a taf wrote (Google Play's rule for apps that
 * generate content: a report from inside the app, without leaving it). tafs cloud keeps
 * the report with the answer as kiff/agents holds it, never words the app sent for it,
 * and emails KIFF's support so a person reads it. One report per person and answer:
 * reporting it again answers the first one.
 */

export const REPORT_REASONS = ["offensive", "harmful", "wrong", "other"] as const;
export type ReportReason = (typeof REPORT_REASONS)[number];

export const REPORTS = {
  /** Where reports go: a person at KIFF reads them. */
  to: "hello@kiff.dev",
  /** How long a report is kept; the account's deletion erases it before that. */
  days: 180,
  /** The longest note a person adds. */
  noteChars: 1000,
  /** The longest answer kept and sent. */
  textChars: 8000,
} as const;

export interface Report {
  id: string;
  at: string;
  tenant: string;
  /** Who reported: their KIFF subject. */
  by: string;
  taf: string;
  tafName: string;
  /** Coach (the account's Setup) or a taf. */
  coach: boolean;
  session: string;
  message: string;
  reason: ReportReason;
  note?: string;
  /** The answer, as kiff/agents holds it. */
  text: string;
}

export interface ReportsDeps {
  store: DoorStore;
  /** The email provider and the domain tafs sends from; missing, the report is kept and logged only. */
  email?: () => Promise<{ send?: (email: OutgoingEmail) => Promise<void>; domain?: string }>;
}

/** The report's key: under the account's prefix, so deleting the account erases it (account.ts). */
export const reportKey = (tenant: string, by: string, message: string) => `${tenant}#report#${createHash("sha256").update(`${by}\n${message}`).digest("hex").slice(0, 32)}`;

/** The reason and note a request gives; throws the words for a 400. */
export function reportInput(body: Record<string, unknown>): { reason: ReportReason; note?: string } {
  const reason = body.reason;
  if (typeof reason !== "string" || !(REPORT_REASONS as readonly string[]).includes(reason)) {
    throw new Error(`reason is one of ${REPORT_REASONS.join(", ")}`);
  }
  if (body.note !== undefined && typeof body.note !== "string") throw new Error("note is text");
  const note = typeof body.note === "string" ? body.note.trim() : "";
  if (note.length > REPORTS.noteChars) throw new Error(`a note is at most ${REPORTS.noteChars} characters`);
  return { reason: reason as ReportReason, ...(note ? { note } : {}) };
}

/**
 * Keeps the report and emails support. A report already kept for this person and answer
 * is answered as it was, and nothing is sent again. The email failing doesn't fail the
 * report: it is kept, and the function's log says it wasn't sent.
 */
export async function fileReport(deps: ReportsDeps, r: Omit<Report, "id" | "text"> & { text: string }, now: Date): Promise<{ report: Report; new: boolean }> {
  const key = reportKey(r.tenant, r.by, r.message);
  const report: Report = { ...r, id: key.split("#").pop()!, text: r.text.slice(0, REPORTS.textChars) };
  const until = new Date(now.getTime() + REPORTS.days * 86_400_000);
  if (!(await deps.store.create(key, report, until))) {
    const kept = (await deps.store.get(key)) as Report | undefined;
    return { report: kept ?? report, new: false };
  }
  console.log(`report ${report.id}: ${report.reason} on ${report.coach ? "Coach" : "a taf"} (${report.tenant}, ${report.taf}, ${report.message})`);
  try {
    const mail = await deps.email?.();
    if (!mail?.send || !mail.domain) {
      console.error(`report ${report.id}: not emailed: email isn't set up`);
    } else {
      await mail.send({ from: `tafs reports <reports@${mail.domain}>`, to: REPORTS.to, ...reportEmail(report), idempotencyKey: `report-${report.id}` });
    }
  } catch (err) {
    console.error(`report ${report.id}: not emailed: ${err instanceof Error ? err.message : "error"}`);
  }
  return { report, new: true };
}

/** The email support reads: what was reported, by whom, and the answer itself. */
export function reportEmail(r: Report): { subject: string; text: string } {
  const who = r.coach ? "Coach" : `the taf ${r.tafName}`;
  return {
    subject: `Report: ${r.reason} answer from ${who}`,
    text: [
      `Someone reported an answer from ${who} as ${r.reason}.`,
      "",
      `Report: ${r.id}`,
      `At: ${r.at}`,
      `Account: ${r.tenant}`,
      `Reported by: ${r.by}`,
      `Taf: ${r.taf}${r.coach ? " (Coach)" : ""}`,
      `Chat: ${r.session}`,
      `Message: ${r.message}`,
      ...(r.note ? ["", "Their note:", r.note] : []),
      "",
      "The answer:",
      r.text,
    ].join("\n"),
  };
}
