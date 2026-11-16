/**
 * The rules the owner agreed, as data, and the draft checked against them (docs/chat.md, "The rules the owner agreed").
 *
 * Coach shows the rules as a `notice` whose lines are its words, and, beside them, `rules`: one entry per tool the draft
 * will cover, with what happens past its limit, `[{tool, over: "ask" | "refuse"}]`. `tool` is planque's name for it
 * before KIFF's suffix (`send_reply`, not `send_reply_k3f9`), since the rules are agreed before the tools are declared;
 * the check maps it through the grant's requested tools. The `confirm` that agrees to them names the notice
 * (`agrees: "<notice id>"`), and the owner agrees by tapping its option whose value is `agree`.
 *
 * At Approve, a draft's Cards are checked against the newest rules the owner agreed: each Card covering a tool has
 * `on_exceed: "owner"` where its rule says ask and `"refuse"` where it says refuse, and a write with no rule isn't
 * signed. A rules notice newer than that agreement (or one never agreed) blocks the draft until the owner agrees to it.
 * On a tool whose rule has figures, a Card carries those alone: any other figure, in any window, isn't signed
 * (`rulesUnnamedFigures`).
 *
 * A rule may also carry the figures the owner read, `limits: [{quantity, window, limit}]`: each call's (`window: "call"`)
 * and the day's total (`"calendar_day"`), in the Card's own units (minor units of the connection's currency, as
 * `per_action_max.max` and `aggregates[].limit` are). Each is on a Card covering the tool with exactly that figure, and
 * no Card covering it has another figure for the same quantity and window, stricter or looser.
 *
 * A figure may say what happens past it (`over`), when it isn't the tool's: "ask past €5 a refund, refuse past €50 a
 * day" is one tool, `over: refuse`, whose call figure says `over: ask`. A Card's `on_exceed` is then the `over` of the
 * agreed figures it carries (the tool's for one that carries none), so two figures with different answers are never on
 * one Card: one Card has one `on_exceed`.
 */
import type { ApprovalGrant } from "./approval.js";

export interface AgreedLimit { quantity: string; window: "call" | "calendar_day"; limit: number; over?: "ask" | "refuse" }
export interface AgreedRule { tool: string; over: "ask" | "refuse"; limits?: AgreedLimit[] }

/** What the conversation says of the rules: none shown, agreed (the newest agreement's), or shown and not agreed. */
export type RulesAgreement =
  | { state: "none" }
  | { state: "agreed"; notice: string; rules: AgreedRule[]; text: string }
  | { state: "unagreed"; notice: string };

/** At most this many rules on a notice, as kiff/agents' component schema takes them: past it, the notice carries none. */
export const RULES_MAX = 64;
/** At most this many figures on a rule, as kiff/agents' schema takes them: one for each quantity and window. */
export const LIMITS_MAX = 4;
/** A figure's quantity, as a Card writes it and kiff/agents' schema takes it: a count of calls, or the sum of one argument. */
const QUANTITY = /^(count|sum\([A-Za-z0-9_.]{1,64}\))$/;
/** The value of the confirm's option by which the owner agrees. */
export const AGREE = "agree";

/** A conversation's event, as kiff/agents records it: a component shown, or a card's answer. */
interface Event { kind?: string; run?: string; body?: unknown }

const fold = (v: unknown) => (typeof v === "string" ? v.trim().toLowerCase() : "");

/** A notice's rules, when it carries a list tafs cloud can read whole; undefined for any other component. */
function rulesOf(c: Record<string, unknown>): AgreedRule[] | undefined {
  if (c.type !== "notice" || !Array.isArray(c.rules) || c.rules.length === 0 || c.rules.length > RULES_MAX) return undefined;
  const out: AgreedRule[] = [];
  for (const r of c.rules as unknown[]) {
    const { tool, over } = (r ?? {}) as { tool?: unknown; over?: unknown };
    if (typeof tool !== "string" || !tool.trim() || tool.length > 128 || (over !== "ask" && over !== "refuse")) return undefined;
    const given = (r as { limits?: unknown }).limits;
    if (given === undefined) {
      out.push({ tool: tool.trim(), over });
      continue;
    }
    const limits = limitsOf(given);
    if (!limits) return undefined;
    out.push({ tool: tool.trim(), over, limits });
  }
  return out;
}

/**
 * A rule's figures, read whole or not at all: 1 to 4, each with a quantity as a Card writes it (`count`, `sum(<arg>)`,
 * matched exactly), a window of `call` or `calendar_day`, a whole limit from 0 (never) to 2^53−1, and, if it says
 * one, an `over` of ask or refuse, with no quantity and window twice. An empty list is no figures read, never figures agreed, so the notice carries no rules.
 */
function limitsOf(given: unknown): AgreedLimit[] | undefined {
  if (!Array.isArray(given) || given.length === 0 || given.length > LIMITS_MAX) return undefined;
  const out: AgreedLimit[] = [];
  for (const l of given as unknown[]) {
    const { quantity, window, limit, over } = (l ?? {}) as { quantity?: unknown; window?: unknown; limit?: unknown; over?: unknown };
    if (typeof quantity !== "string" || !QUANTITY.test(quantity)) return undefined;
    if (window !== "call" && window !== "calendar_day") return undefined;
    if (typeof limit !== "number" || !Number.isSafeInteger(limit) || limit < 0) return undefined;
    if (over !== undefined && over !== "ask" && over !== "refuse") return undefined;
    if (out.some((x) => x.quantity === quantity && x.window === window)) return undefined;
    out.push({ quantity, window, limit, ...(over === undefined ? {} : { over }) });
  }
  return out;
}

/**
 * The rules this conversation agreed, read from its events in order. Only the person's own tap counts: an `answered`
 * event for a confirm with `agrees`, answered `agree`, with no `answered_by` (tafs cloud's own answers carry it), not
 * words written while the card waited, and not a card that stopped waiting unanswered. `agrees` must name a rules notice
 * shown in this conversation before the answer; anything else agrees to nothing. The newest agreement counts, and a
 * rules notice shown after the one it agreed (another id, or the same id with other rules) leaves it unagreed.
 */
export function rulesAgreement(events: readonly Event[]): RulesAgreement {
  const notices = new Map<string, { rules: AgreedRule[]; at: number; text: string }>();
  const confirms: Array<{ run: string; id: string; agrees?: string }> = [];
  let agreed: { notice: string; rules: AgreedRule[]; shownAt: number; text: string } | undefined;
  let newest: { id: string; rules: AgreedRule[]; at: number } | undefined;
  events.forEach((e, at) => {
    const body = (e.body ?? {}) as Record<string, unknown>;
    if (e.kind === "component") {
      const id = typeof body.id === "string" ? body.id : "";
      if (!id) return;
      const rules = rulesOf(body);
      if (rules) {
        notices.set(id, { rules, at, text: typeof body.text === "string" ? body.text : "" });
        newest = { id, rules, at };
      } else if (body.type === "notice") notices.delete(id);
      if (body.type === "confirm") confirms.push({ run: e.run ?? "", id, ...(typeof body.agrees === "string" && body.agrees ? { agrees: body.agrees } : {}) });
      return;
    }
    if (e.kind !== "answered" || body.type !== "confirm") return;
    if (body.answer !== AGREE || body.unanswered === true || (typeof body.wrote === "string" && body.wrote.trim()) || body.answered_by) return;
    // The run's latest confirm by that id, as the thread places an answer.
    const card = confirms.findLast((c) => c.run === (e.run ?? "") && c.id === body.id);
    const notice = card?.agrees ? notices.get(card.agrees) : undefined;
    if (card?.agrees && notice) agreed = { notice: card.agrees, rules: notice.rules, shownAt: notice.at, text: notice.text };
  });
  if (!newest) return { state: "none" };
  if (!agreed) return { state: "unagreed", notice: newest.id };
  const shownAfter = newest.at > agreed.shownAt && !(newest.id === agreed.notice && sameRules(newest.rules, agreed.rules));
  return shownAfter ? { state: "unagreed", notice: newest.id } : { state: "agreed", notice: agreed.notice, rules: agreed.rules, text: agreed.text };
}

function sameRules(a: AgreedRule[], b: AgreedRule[]): boolean {
  return a.length === b.length && a.every((r, i) => fold(r.tool) === fold(b[i]!.tool) && r.over === b[i]!.over && sameLimits(r.limits, b[i]!.limits));
}

/** The same figures, in any order: a rule shown again with another figure is other rules. */
function sameLimits(a: AgreedLimit[] | undefined, b: AgreedLimit[] | undefined): boolean {
  const key = (l: AgreedLimit[] | undefined) => (l ?? []).map((x) => `${x.quantity}|${x.window}|${x.limit}|${x.over ?? ""}`).sort().join(",");
  return key(a) === key(b);
}

export type RulesMismatch =
  | { kind: "on_exceed"; tool: string; over: "ask" | "refuse"; onExceed: "owner" | "refuse" }
  | { kind: "mixed"; tool: string }
  | { kind: "missing"; tool: string }
  | { kind: "figure"; tool: string; quantity: string; window: "call" | "calendar_day"; agreed: number; drafted?: number };

/**
 * The first tool the draft's Cards cover against the agreed rules: a Card whose `on_exceed` isn't its rule's (ask is
 * `owner`, refuse is `refuse`; a Card that says nothing holds for the owner, as KIFF does), or a write with no rule.
 * Each tool of every grant given (the connection and the same taf's others) is matched by its KIFF name, its rule by
 * planque's name before the suffix (or the KIFF name). A tool no Card in the draft covers is the coverage checks'; a rule
 * for a tool no grant has is ignored, never a reason to approve. The tool is named as the connection titles it.
 */
export function rulesMismatch(grants: readonly ApprovalGrant[], cards: readonly unknown[], rules: readonly AgreedRule[]): RulesMismatch | undefined {
  for (const g of grants) {
    for (const t of g.request.tools ?? []) {
      const name = fold(t.name || t.tool);
      if (!name) continue;
      const before = fold(t.tool || t.name);
      const covering = cards.filter((raw) => {
        const card = raw as { grants?: unknown };
        return Array.isArray(card.grants) && card.grants.some((x) => fold((x as { action?: unknown }).action) === name);
      });
      if (!covering.length) continue;
      const shown = t.title || t.name || t.tool || name;
      const rule = rules.find((r) => fold(r.tool) === before) ?? rules.find((r) => fold(r.tool) === name);
      if (!rule) {
        if (!t.read_only && !t.setup_reads) return { kind: "missing", tool: shown };
        continue;
      }
      for (const raw of covering) {
        // What happens past this Card's limits: the `over` of the agreed figures it carries, else the tool's.
        const carried = new Set(figuresOn([raw], name)
          .map((f) => rule.limits?.find((l) => l.quantity === f.quantity && l.window === f.window))
          .filter((l): l is AgreedLimit => l !== undefined)
          .map((l) => l.over ?? rule.over));
        if (carried.size > 1) return { kind: "mixed", tool: shown };
        const over = carried.size === 1 ? [...carried][0]! : rule.over;
        const onExceed = (raw as { on_exceed?: unknown }).on_exceed === "refuse" ? "refuse" : "owner";
        if (onExceed !== (over === "ask" ? "owner" : "refuse")) return { kind: "on_exceed", tool: shown, over, onExceed };
      }
      for (const l of rule.limits ?? []) {
        const drafted = figuresOn(covering, name).filter((f) => f.quantity === l.quantity && f.window === l.window).map((f) => f.limit);
        // Missing on every Card, or another figure on any of them: what the owner read isn't what's signed.
        const other = drafted.find((d) => d !== l.limit);
        if (!drafted.length) return { kind: "figure", tool: shown, quantity: l.quantity, window: l.window, agreed: l.limit };
        if (other !== undefined) return { kind: "figure", tool: shown, quantity: l.quantity, window: l.window, agreed: l.limit, drafted: other };
      }
    }
  }
  return undefined;
}

interface Figure { quantity: string; window: "call" | "calendar_day"; limit: number }

/**
 * The figures the Cards set on one tool (its KIFF name, folded): each call's, from its grants' `per_action_max`, and
 * the day's totals, from the Cards' `aggregates` with `window: calendar_day` and no `per` (a total for each recipient
 * or customer is another total, never the day's). Quantities are as written, trimmed, never folded further, so
 * `sum( amount )` is another quantity than `sum(amount)`. A figure that isn't a number is read as -1, which no
 * agreed figure equals.
 */
function figuresOn(cards: readonly unknown[], name: string): Figure[] {
  const out: Figure[] = [];
  const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? v : -1);
  const trim = (v: unknown) => (typeof v === "string" ? v.trim() : "");
  for (const raw of cards) {
    const card = raw as { grants?: unknown; aggregates?: unknown };
    const grants = (Array.isArray(card.grants) ? card.grants : []) as Array<{ action?: unknown; per_action_max?: unknown }>;
    const mine = grants.filter((g) => fold(g.action) === name);
    if (!mine.length) continue;
    for (const g of mine) {
      for (const m of (Array.isArray(g.per_action_max) ? g.per_action_max : []) as Array<{ quantity?: unknown; max?: unknown }>) {
        out.push({ quantity: trim(m.quantity), window: "call", limit: num(m.max) });
      }
    }
    for (const a of (Array.isArray(card.aggregates) ? card.aggregates : []) as Array<{ quantity?: unknown; window?: unknown; per?: unknown; limit?: unknown }>) {
      if (fold(a.window) !== "calendar_day" || fold(a.per)) continue;
      out.push({ quantity: trim(a.quantity), window: "calendar_day", limit: num(a.limit) });
    }
  }
  return out;
}

/** A figure a Card adds on a tool the rules don't name: its quantity, its window as the Card writes it, and its limit. */
export interface UnnamedFigure { tool: string; quantity: string; window: string; limit: number }

/**
 * The figures the Cards add that the agreed rules don't name: on a tool whose rule has figures, each call's or a total
 * over any window (a day's, an hour's, a week's, a lifetime's) on a quantity and window the rule doesn't give. Refused at
 * Approve (Eval 43927756622: after a refused draft, Coach signed a rolling hour's €50 on refunds beside the agreed €5 a
 * refund and €50 a day, and held past it where the rules said refuse). Not named: a total for each item (`per`), whose
 * shapes the checks before this one judge; and a count of 0 (each call asks), which only asks. The tool is named as the
 * connection titles it.
 */
export function rulesUnnamedFigures(grants: readonly ApprovalGrant[], cards: readonly unknown[], rules: readonly AgreedRule[]): UnnamedFigure[] {
  const out: UnnamedFigure[] = [];
  for (const g of grants) {
    for (const t of g.request.tools ?? []) {
      const name = fold(t.name || t.tool);
      if (!name) continue;
      const before = fold(t.tool || t.name);
      const rule = rules.find((r) => fold(r.tool) === before) ?? rules.find((r) => fold(r.tool) === name);
      if (!rule?.limits) continue;
      for (const f of figuresInAnyWindow(cards, name)) {
        if (f.quantity === "count" && f.limit === 0) continue;
        if (!rule.limits.some((l) => l.quantity === f.quantity && l.window === f.window)) {
          out.push({ tool: t.title || t.name || t.tool || name, ...f });
        }
      }
    }
  }
  return out;
}

/**
 * The figures the Cards set on one tool (its KIFF name, folded), in every window: each call's, from its grants'
 * `per_action_max` (`call`), and each total with no `per`, from the Cards' `aggregates`, in the window it names, folded
 * (`calendar_day`, `rolling_1h`, `lifetime`…; none written reads as ""). Quantities as `figuresOn` reads them.
 */
function figuresInAnyWindow(cards: readonly unknown[], name: string): Array<{ quantity: string; window: string; limit: number }> {
  const out: Array<{ quantity: string; window: string; limit: number }> = figuresOn(cards, name).filter((f) => f.window === "call");
  const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? v : -1);
  const trim = (v: unknown) => (typeof v === "string" ? v.trim() : "");
  for (const raw of cards) {
    const card = raw as { grants?: unknown; aggregates?: unknown };
    const grants = (Array.isArray(card.grants) ? card.grants : []) as Array<{ action?: unknown }>;
    if (!grants.some((g) => fold(g.action) === name)) continue;
    for (const a of (Array.isArray(card.aggregates) ? card.aggregates : []) as Array<{ quantity?: unknown; window?: unknown; per?: unknown; limit?: unknown }>) {
      if (fold(a.per)) continue;
      out.push({ quantity: trim(a.quantity), window: fold(a.window), limit: num(a.limit) });
    }
  }
  return out;
}

/**
 * The agreed figures the notice's words don't say, for the log: each limit whose number, in the Card's units or in
 * major units (a hundredth, as cents are of euros), appears nowhere in the notice's text. Numbers in the text are read
 * with either `,` or `.` as the decimal mark and spaces, dots or commas between thousands, so "€5", "5,00 €" and
 * "1.000" are all found. Never a reason to refuse: formats vary by language, and a miss goes to the gap ledger.
 */
export function rulesUnsaid(rules: readonly AgreedRule[], text: string): string[] {
  const said = numbersIn(text);
  const out: string[] = [];
  for (const r of rules) {
    for (const l of r.limits ?? []) {
      if (!said.has(l.limit) && !said.has(l.limit / 100)) out.push(`${r.tool} ${l.quantity} ${l.window} ${l.limit}`);
    }
  }
  return out;
}

/** Every number the text writes, read each way its separators allow. */
function numbersIn(text: string): Set<number> {
  const out = new Set<number>();
  for (const m of text.matchAll(/\d(?:[\d\s.,\u00a0\u202f']*\d)?/g)) {
    const raw = m[0].replace(/[\s\u00a0\u202f']/g, "");
    const add = (s: string) => { const n = Number(s); if (Number.isFinite(n)) out.add(n); };
    // The last , or . as a decimal mark, the others between thousands; or every separator between thousands.
    const last = Math.max(raw.lastIndexOf(","), raw.lastIndexOf("."));
    if (last >= 0) add(raw.slice(0, last).replace(/[.,]/g, "") + "." + raw.slice(last + 1));
    add(raw.replace(/[.,]/g, ""));
  }
  return out;
}
