import { type Lang, type Words, wordsIn } from "./words/index.js";

/** The refusal codes a connection gives, by their words' catalog key. */
const REASONS: Record<string, keyof Words> = {
  halt_unknown: "rw_halt_unknown", halted: "rw_halted", halted_before: "rw_halted_before", not_granted: "rw_not_granted",
  permit_invalid: "rw_permit_invalid", permit_expired: "rw_permit_expired", bad_arguments: "rw_bad_arguments",
  record_unavailable: "rw_record_unavailable", profile_not_applied: "rw_profile_not_applied",
  definition_invalid: "rw_definition_invalid", definition_unread: "rw_definition_unread",
};

/** KIFF carries connection provenance only after validating a structured, unsent error result. In the person's language. */
export function connectionRefusalWords(outcome: { status: string; refused_by?: string; code?: string } | null | undefined, lang: Lang = "en"): string | undefined {
  if (outcome?.status !== "refused_by_tool" || outcome.refused_by !== "connection" || !/^[a-z_]{1,40}$/.test(outcome.code ?? "")) return;
  const w = wordsIn(lang);
  const key = Object.hasOwn(REASONS, outcome.code!) ? REASONS[outcome.code!] : undefined;
  return key ? w.refused_conn(w[key] as string) : w.refused_conn_none;
}
