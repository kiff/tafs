import type { Held, Rule } from "./home.js";
import { timeIn } from "./work.js";
import { KIFFError } from "./kiff.js";
import type { ActivityCall } from "./activity.js";

export const CARD_UNIT_NOTICE = "Currency requests couldn't be read. Your activity is still shown; try refreshing.";

export interface CardUnitException {
  id: string; kind: "card_unit"; taf: string; name: string; title: string; words: string;
  history: string; action_title: string; coach_text: string; dismiss_title: string; can_approve_once: false;
}

/** The same KIFF exception appears once in Needs you and Setup; every decision stays in the Record. */
export function cardUnitExceptions(taf: { id: string; name: string; kiffAgent: string }, exceptions: Held[], rules: Rule[], zone: string, now: Date): CardUnitException[] {
  const seen = new Set<string>();
  return exceptions.flatMap((exception) => {
    if (exception.kind !== "card_unit" || exception.subject !== taf.kiffAgent || exception.status !== "held") return [];
    const breach = exception.breaches?.find((b) => b.outcome === "amount_unit_mismatch");
    const unit = breach?.unit, cardUnit = breach?.card_unit;
    if (!exception.id || !exception.action || !/^[A-Z]{3}$/.test(unit ?? "") || !Number.isSafeInteger(exception.count) || exception.count! < 1
      || !Number.isFinite(Date.parse(exception.created_at ?? "")) || !Number.isFinite(Date.parse(exception.last_at ?? ""))
      || (cardUnit !== undefined && !/^[A-Z]{3}$/.test(cardUnit))) throw new KIFFError(502, "The taf's currency exceptions could not be read");
    if (seen.has(exception.id)) return [];
    seen.add(exception.id);
    // The action's name without a currency it ends with ("…, in EUR", "… (EUR)"): the ask is about
    // another currency, so the suffix would contradict it (kiff/tafs#469 item 3).
    const title = (exception.title || rules.find((r) => r.action === exception.action)?.label || "this action")
      .replace(/,?\s+in\s+[A-Z]{3}$|\s*\([A-Z]{3}\)$/, "");
    const card = cardUnit ? `Its Card only allows ${cardUnit}.` : `Its Card has no ${unit} limit.`;
    const since = new Intl.DateTimeFormat("en-GB", { timeZone: zone, day: "numeric", month: "short" }).format(new Date(exception.created_at!));
    return [{ id: exception.id, kind: "card_unit" as const, taf: taf.id, name: taf.name,
      title: `${taf.name} needs a ${unit} limit`, words: `${title} gets work in ${unit}. ${card} Add a ${unit} limit?`,
      history: `Tried ${exception.count} time${exception.count === 1 ? "" : "s"} since ${since} · last ${timeIn(exception.last_at!, zone, now)}`,
      action_title: `Add a ${unit} limit to ${title}`, dismiss_title: "Dismiss", can_approve_once: false as const,
      coach_text: `Add a ${unit} limit to ${title} for ${taf.name}. ${card} Read its current Card first and ask me to approve the change.` }];
  });
}

/** Collapse only typed refusals belonging to this still-open exception's episode. Record is unchanged. */
export function collapsedCurrencyDecision(call: ActivityCall, exceptions: Held[]): boolean {
  if (call.outcome !== "blocked" || !call.reasons?.includes("amount_unit_mismatch") || !call.amount?.unit) return false;
  return exceptions.some((exception) => exception.kind === "card_unit" && exception.status === "held"
    && exception.subject === call.actor_id && exception.action === call.action_name
    && exception.breaches?.some((b) => b.outcome === "amount_unit_mismatch" && b.unit === call.amount!.unit)
    && (exception.proposal_id === call.proposal_id || (Date.parse(call.created_at) >= Date.parse(exception.created_at ?? "")
      && Date.parse(call.created_at) <= Date.parse(exception.last_at ?? ""))));
}
