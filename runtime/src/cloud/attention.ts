import type { AgentAsk } from "./agents.js";
import type { Rule } from "./home.js";
import { timeIn } from "./work.js";

export interface CardUnitAsk {
  id: string; kind: "card_unit"; taf: string; name: string; title: string; words: string;
  history: string; action_title: string; coach_text: string;
}

/** Compose one stable row per standing ask, using only the taf's own typed state and Card titles. */
export function cardUnitAsks(taf: { id: string; name: string }, asks: AgentAsk[], rules: Rule[], zone: string, now: Date): CardUnitAsk[] {
  const seen = new Set<string>();
  return asks.flatMap((ask) => {
    if (ask.kind !== "card_unit") return [];
    if (!ask.id || !ask.tool || !/^[A-Z]{3}$/.test(ask.unit) || !Number.isSafeInteger(ask.count) || ask.count < 1
      || !Number.isFinite(Date.parse(ask.first_at)) || !Number.isFinite(Date.parse(ask.last_at))
      || (ask.card_unit !== undefined && !/^[A-Z]{3}$/.test(ask.card_unit))) throw new Error("The taf's requests could not be read");
    const key = `${ask.tool}\n${ask.unit}`;
    if (seen.has(key)) return [];
    seen.add(key);
    const title = rules.find((r) => r.action === ask.tool)?.label || "This action";
    const card = ask.card_unit ? `Its Card only allows ${ask.card_unit}.` : `Its Card has no ${ask.unit} limit.`;
    const since = new Intl.DateTimeFormat("en-GB", { timeZone: zone, day: "numeric", month: "short" }).format(new Date(ask.first_at));
    return [{ id: `${taf.id}:${ask.id}`, kind: "card_unit" as const, taf: taf.id, name: taf.name,
      title: `${taf.name} needs a ${ask.unit} limit`, words: `${title} gets work in ${ask.unit}. ${card} Add a ${ask.unit} limit?`,
      history: `Tried ${ask.count} time${ask.count === 1 ? "" : "s"} since ${since} · last ${timeIn(ask.last_at, zone, now)}`,
      action_title: `Add a ${ask.unit} limit in Coach`,
      coach_text: `Add a ${ask.unit} limit to ${title} for ${taf.name}. ${card} Read its current Card first and ask me to approve the change.` }];
  });
}
