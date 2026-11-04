/** KIFF carries connection provenance only after validating a structured, unsent error result. */
export function connectionRefusalWords(outcome: { status: string; refused_by?: string; code?: string } | null | undefined): string | undefined {
  if (outcome?.status !== "refused_by_tool" || outcome.refused_by !== "connection" || !/^[a-z_]{1,40}$/.test(outcome.code ?? "")) return;
  const reasons: Record<string, string> = {
    halt_unknown: "it couldn't check whether the taf was halted",
    halted: "the taf or its account is halted",
    halted_before: "the call was approved before the latest halt",
    not_granted: "the connection doesn't include this action",
    permit_invalid: "the call's permission couldn't be verified",
    permit_expired: "the call's permission expired before it left",
    bad_arguments: "the action's inputs couldn't be read",
    record_unavailable: "the call's record couldn't be kept",
    profile_not_applied: "the approved action definition isn't ready",
    definition_invalid: "the action names an input the app doesn't accept",
    definition_unread: "the action's inputs couldn't be checked",
  };
  const why = reasons[outcome.code!];
  return why ? `The connection refused it: ${why}. Nothing was sent.` : "The connection refused it before sending. Nothing was sent.";
}
