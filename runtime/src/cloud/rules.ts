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
 * no Card covering it has another figure for the same quantity and window, stricter or looser. A figure with `per` is a
 * total for each item ("one invoice per order, ever": `count`, `lifetime`, `per: order_id`), in any of an aggregate's
 * windows, matched on its quantity, window and per alike. A tool's per totals are held to its rule's when the rule names
 * one, and to the letter, on a rule that names none too, once `TAFS_RULES_PER=on` (when Coach's rules carry them).
 *
 * A figure may say what happens past it (`over`), when it isn't the tool's: "ask past €5 a refund, refuse past €50 a
 * day" is one tool, `over: refuse`, whose call figure says `over: ask`. A Card's `on_exceed` is then the `over` of the
 * agreed figures it carries (the tool's for one that carries none), so two figures with different answers are never on
 * one Card: one Card has one `on_exceed`.
 */
import type { ApprovalGrant } from "./approval.js";

export interface AgreedLimit { quantity: string; window: string; limit: number; over?: "ask" | "refuse"; per?: string }
export interface AgreedRule { tool: string; over: "ask" | "refuse"; limits?: AgreedLimit[] }

/** What the conversation says of the rules: none shown, agreed (the newest agreement's), or shown and not agreed. */
export type RulesAgreement =
  | { state: "none" }
  | { state: "agreed"; notice: string; rules: AgreedRule[]; text: string; connection: ConnectionLine[] }
  | { state: "unagreed"; notice: string };

/**
 * A line of an agreed rules notice that says the connection keeps it (kiff/agents' show, `kept: connection`): its words, and
 * the tools it says the connection holds it for, by planque's name. Approve holds it to the grant's pins (unpinnedConnection).
 */
export interface ConnectionLine { text: string; tools: string[] }

/** The notice's connection lines: those with kept "connection" and their tools, each by planque's name. */
function connectionLinesOf(c: Record<string, unknown>): ConnectionLine[] {
  if (!Array.isArray(c.lines)) return [];
  const out: ConnectionLine[] = [];
  for (const l of c.lines as unknown[]) {
    const line = (l ?? {}) as Record<string, unknown>;
    if (line.kept !== "connection") continue;
    const tools = Array.isArray(line.tools) ? line.tools.filter((t): t is string => typeof t === "string" && t.trim() !== "") : [];
    out.push({ text: typeof line.text === "string" ? line.text : "", tools });
  }
  return out;
}

/**
 * A notice's lines as one key: each line's words, where it says it's kept, its tools (as a set) and what, in order. A notice
 * shown again with other lines says other things are kept elsewhere, so it is another notice to agree to (rulesAgreement).
 */
function linesKeyOf(c: Record<string, unknown>): string {
  if (!Array.isArray(c.lines)) return "";
  return JSON.stringify((c.lines as unknown[]).map((l) => {
    const line = (l ?? {}) as Record<string, unknown>;
    const tools = Array.isArray(line.tools) ? line.tools.filter((t): t is string => typeof t === "string").map(fold).sort() : [];
    return [line.text ?? "", line.kept ?? "", tools, line.what ?? ""];
  }));
}

/** At most this many rules on a notice, as kiff/agents' component schema takes them: past it, the notice carries none. */
export const RULES_MAX = 64;
/** At most this many figures on a rule, as kiff/agents' schema takes them: one for each quantity and window. */
export const LIMITS_MAX = 4;
/** A figure's quantity, as a Card writes it and kiff/agents' schema takes it: a count of calls, or the sum of one argument. */
const QUANTITY = /^(count|sum\([A-Za-z0-9_.]{1,64}\))$/;
/** A total for each item's `per`, as a Card's aggregate writes it: the argument each item is counted by. */
const PER = /^[A-Za-z0-9_.]{1,64}$/;
/** The windows of a Card's aggregate, as kiff/agents' drafts take them: a total for each item may be over any of them. */
const PER_WINDOWS: ReadonlySet<string> = new Set(["calendar_day", "rolling_1h", "rolling_24h", "rolling_7d", "lifetime"]);
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
    // KIFF's own count is never the owner's: a notice may show it ("KIFF adds it"), but it isn't a figure they agreed,
    // so it is neither held to the notice's words nor required on a Card.
    const owners = limits.filter((l) => !kiffsCountAgreed(l));
    out.push(owners.length ? { tool: tool.trim(), over, limits: owners } : { tool: tool.trim(), over });
  }
  return out;
}

/**
 * Whether an agreed figure is KIFF's own count (KIFF_COUNT_A_DAY a calendar day, for no item), which Coach's rules may
 * list beside the owner's figures as the notice shows it. Read as the owner's, it made Setup agree to it, then require
 * it on the refund's Card, and the checks pulled against each other until Coach changed the owner's rule (Eval
 * 45062249364, Setup run 1); on reads it cost an extra agree and a refused approval. A figure that itself says
 * `over: "refuse"` is the owner's, whatever its number: KIFF's count holds for the owner and never refuses, so an
 * owner's "refuse past 10,000 calls a day" is kept, held to the notice's words and required on a Card.
 */
function kiffsCountAgreed(l: AgreedLimit): boolean {
  return l.quantity === "count" && l.window === "calendar_day" && l.per === undefined && l.limit === KIFF_COUNT_A_DAY && l.over !== "refuse";
}

/**
 * A rule's figures, read whole or not at all: 1 to 4, each with a quantity as a Card writes it (`count`, `sum(<arg>)`,
 * matched exactly), a window of `call` or `calendar_day` (with a `per`, any of an aggregate's windows), a whole limit
 * from 0 (never) to 2^53−1, and, if it says one, an `over` of ask or refuse, with no quantity, window and per twice. An empty list is no figures read, never figures agreed, so the notice carries no rules.
 * A count for each call is read as what it means: KIFF counts calls over a window, never for one call ("a count per action
 * is always one", so it refuses one as a per-action limit). 0 for each call is every call past its limit, the same as 0 a
 * calendar day, and is read as that, so the Cards hold it (a phone test, 25 Nov: "every refund waits for you" agreed as a count of
 * 0 for each call, which no Card KIFF signs can carry). Any other count for each call limits nothing, and the notice carries no rules.
 */
function limitsOf(given: unknown): AgreedLimit[] | undefined {
  if (!Array.isArray(given) || given.length === 0 || given.length > LIMITS_MAX) return undefined;
  const out: AgreedLimit[] = [];
  for (const l of given as unknown[]) {
    const { quantity, window: given, limit, over, per } = (l ?? {}) as { quantity?: unknown; window?: unknown; limit?: unknown; over?: unknown; per?: unknown };
    if (typeof quantity !== "string" || !QUANTITY.test(quantity)) return undefined;
    if (per !== undefined && (typeof per !== "string" || !PER.test(per))) return undefined;
    if (typeof given !== "string" || (per === undefined ? given !== "call" && given !== "calendar_day" : !PER_WINDOWS.has(given))) return undefined;
    let window: string = given;
    if (typeof limit !== "number" || !Number.isSafeInteger(limit) || limit < 0) return undefined;
    if (over !== undefined && over !== "ask" && over !== "refuse") return undefined;
    if (quantity === "count" && window === "call") {
      if (limit !== 0) return undefined;
      window = "calendar_day";
    }
    if (out.some((x) => x.quantity === quantity && x.window === window && x.per === per)) return undefined;
    out.push({ quantity, window, limit, ...(over === undefined ? {} : { over }), ...(per === undefined ? {} : { per }) });
  }
  return out;
}

/**
 * The rules this conversation agreed, read from its events in order. Only the person's own tap counts: an `answered`
 * event for a confirm with `agrees`, answered `agree`, with no `answered_by` (tafs cloud's own answers carry it), not
 * words written while the card waited, and not a card that stopped waiting unanswered. `agrees` must name a rules notice
 * shown in this conversation before the answer; anything else agrees to nothing. The newest agreement counts, and a
 * rules notice shown after the one it agreed (another id, or the same id with other rules or other lines) leaves it unagreed.
 */
export function rulesAgreement(events: readonly Event[]): RulesAgreement {
  const notices = new Map<string, { rules: AgreedRule[]; at: number; text: string; connection: ConnectionLine[]; lines: string }>();
  const confirms: Array<{ run: string; id: string; agrees?: string }> = [];
  let agreed: { notice: string; rules: AgreedRule[]; shownAt: number; text: string; connection: ConnectionLine[]; lines: string } | undefined;
  let newest: { id: string; rules: AgreedRule[]; at: number; lines: string } | undefined;
  events.forEach((e, at) => {
    const body = (e.body ?? {}) as Record<string, unknown>;
    if (e.kind === "component") {
      const id = typeof body.id === "string" ? body.id : "";
      if (!id) return;
      const rules = rulesOf(body);
      if (rules) {
        const lines = linesKeyOf(body);
        notices.set(id, { rules, at, text: typeof body.text === "string" ? body.text : "", connection: connectionLinesOf(body), lines });
        newest = { id, rules, at, lines };
      } else if (body.type === "notice") notices.delete(id);
      if (body.type === "confirm") confirms.push({ run: e.run ?? "", id, ...(typeof body.agrees === "string" && body.agrees ? { agrees: body.agrees } : {}) });
      return;
    }
    if (e.kind !== "answered" || body.type !== "confirm") return;
    if (body.answer !== AGREE || body.unanswered === true || (typeof body.wrote === "string" && body.wrote.trim()) || body.answered_by) return;
    // The run's latest confirm by that id, as the thread places an answer.
    const card = confirms.findLast((c) => c.run === (e.run ?? "") && c.id === body.id);
    const notice = card?.agrees ? notices.get(card.agrees) : undefined;
    if (card?.agrees && notice) agreed = { notice: card.agrees, rules: notice.rules, shownAt: notice.at, text: notice.text, connection: notice.connection, lines: notice.lines };
  });
  if (!newest) return { state: "none" };
  if (!agreed) return { state: "unagreed", notice: newest.id };
  const shownAfter = newest.at > agreed.shownAt && !(newest.id === agreed.notice && sameRules(newest.rules, agreed.rules) && newest.lines === agreed.lines);
  return shownAfter ? { state: "unagreed", notice: newest.id } : { state: "agreed", notice: agreed.notice, rules: agreed.rules, text: agreed.text, connection: agreed.connection };
}

function sameRules(a: AgreedRule[], b: AgreedRule[]): boolean {
  return a.length === b.length && a.every((r, i) => fold(r.tool) === fold(b[i]!.tool) && r.over === b[i]!.over && sameLimits(r.limits, b[i]!.limits));
}

/** The same figures, in any order: a rule shown again with another figure is other rules. */
function sameLimits(a: AgreedLimit[] | undefined, b: AgreedLimit[] | undefined): boolean {
  const key = (l: AgreedLimit[] | undefined) => (l ?? []).map((x) => `${x.quantity}|${x.window}|${x.limit}|${x.over ?? ""}|${x.per ?? ""}`).sort().join(",");
  return key(a) === key(b);
}

export type RulesMismatch =
  | { kind: "on_exceed"; tool: string; over: "ask" | "refuse"; onExceed: "owner" | "refuse" }
  | { kind: "mixed"; tool: string }
  | { kind: "missing"; tool: string }
  | { kind: "figure"; tool: string; quantity: string; window: string; per?: string; agreed: number; drafted?: number };

/**
 * The first tool the draft's Cards cover against the agreed rules: a Card whose `on_exceed` isn't its rule's (ask is
 * `owner`, refuse is `refuse`; a Card that says nothing holds for the owner, as KIFF does), or a write with no rule.
 * Each tool of every grant given (the connection and the same taf's others) is matched by its KIFF name, its rule by
 * planque's name before the suffix (or the KIFF name). A tool no Card in the draft covers is the coverage checks'; a rule
 * for a tool no grant has is ignored, never a reason to approve. The tool is named as the connection titles it.
 */
export function rulesMismatch(grants: readonly ApprovalGrant[], cards: readonly unknown[], rules: readonly AgreedRule[]): RulesMismatch | undefined {
  for (const g of grants) {
    const suffix = grantSuffix(g.id);
    for (const t of g.request.tools ?? []) {
      const name = fold(t.name || t.tool);
      if (!name) continue;
      const covering = cards.filter((raw) => covers(raw, name));
      if (!covering.length) continue;
      const shown = t.title || t.name || t.tool || name;
      const rule = ruleOf(t, suffix, rules);
      if (!rule) {
        if (!t.read_only && !t.setup_reads) return { kind: "missing", tool: shown };
        continue;
      }
      for (const raw of covering) {
        // What happens past this Card's limits: the `over` of the agreed figures it carries, else the tool's. On a held
        // Card, a refused call's cap that a refusing Card covering the tool also carries, at the same figure, is that
        // Card's: KIFF needs every Card on a money tool to bound its amount, and a call past it is refused, since a
        // refusing Card wins over a held one (cardsFromRules).
        const held = (raw as { on_exceed?: unknown }).on_exceed !== "refuse";
        const refusedToo = (l: AgreedLimit, f: Figure) => held && l.window === "call" && (l.over ?? rule.over) === "refuse" &&
          covering.some((o) => o !== raw && (o as { on_exceed?: unknown }).on_exceed === "refuse" &&
            figuresOn([o], name).some((g) => sameFigure(l, g) && g.limit === f.limit && f.limit === l.limit));
        const carried = new Set(figuresOn([raw], name)
          .map((f) => ({ f, l: rule.limits?.find((l) => sameFigure(l, f)) }))
          .filter((x): x is { f: Figure; l: AgreedLimit } => x.l !== undefined && !refusedToo(x.l, x.f))
          .map(({ l }) => l.over ?? rule.over));
        if (carried.size > 1) return { kind: "mixed", tool: shown };
        const over = carried.size === 1 ? [...carried][0]! : rule.over;
        const onExceed = (raw as { on_exceed?: unknown }).on_exceed === "refuse" ? "refuse" : "owner";
        if (onExceed !== (over === "ask" ? "owner" : "refuse")) return { kind: "on_exceed", tool: shown, over, onExceed };
      }
      for (const l of rule.limits ?? []) {
        const drafted = figuresOn(covering, name).filter((f) => sameFigure(l, f)).map((f) => f.limit);
        // Missing on every Card, or another figure on any of them: what the owner read isn't what's signed.
        const other = drafted.find((d) => d !== l.limit);
        const per = l.per ? { per: l.per } : {};
        if (!drafted.length) return { kind: "figure", tool: shown, quantity: l.quantity, window: l.window, ...per, agreed: l.limit };
        if (other !== undefined) return { kind: "figure", tool: shown, quantity: l.quantity, window: l.window, ...per, agreed: l.limit, drafted: other };
      }
    }
  }
  return undefined;
}

/** A tool of a grant's request, as `request.tools` lists it. */
type GrantTool = NonNullable<ApprovalGrant["request"]["tools"]>[number];

/**
 * A tool's agreed rule: by planque's name before the suffix, else KIFF's name, else the name a rule gives, KIFF's name
 * with this grant's own suffix off ("send_reminder" for "send_reminder_3qa1" on grt_3Qa1…), as Coach reads it. A managed
 * app's `tool` is its connector's id, which no rule names (the owner's phone, 18 Nov: a Gmail write refused three times
 * though every notice named it).
 */
function ruleOf(t: GrantTool, suffix: string, rules: readonly AgreedRule[]): AgreedRule | undefined {
  const name = fold(t.name || t.tool);
  const before = fold(t.tool || t.name);
  const job = jobOf(name, suffix);
  return rules.find((r) => fold(r.tool) === before) ?? rules.find((r) => fold(r.tool) === name) ??
    (job ? rules.find((r) => job.covers(fold(r.tool))) : undefined);
}

/** Whether a drafted Card covers a tool (its KIFF name, folded). */
function covers(raw: unknown, name: string): boolean {
  const card = raw as { grants?: unknown };
  return Array.isArray(card.grants) && card.grants.some((x) => fold((x as { action?: unknown }).action) === name);
}

/** A Card tafs cloud builds for a money tool: one action, the agreed figures, and what happens past them. */
export interface MoneyCard {
  id: string;
  subject: string;
  domain: "gateway";
  grants: [{ action: string; per_action_max?: Array<{ quantity: string; max: number }> }];
  aggregates: Array<{ quantity: string; limit: number; window: string; per?: string }>;
  on_exceed: "owner" | "refuse";
}

/**
 * Why a money tool's agreed rules give no Cards KIFF signs: a refused limit with no refused total (KIFF needs a total on
 * every Card), a held Card that would bound no amount, a held Card with no total of its own and no refused day's total
 * beside it for KIFF's count, or a count of a money tool's calls (its limits are its amounts).
 */
export type MoneyUnbuilt = "refused_total" | "held_bound" | "held_total" | "count" | "asks_unnamed" | "ask_inherited" | "every_call_unheld";

/** A money tool's Cards, as the agreed rules give them, or why they can't be. `tool` is named as the connection titles it. */
export type MoneyCards = { tool: string; action: string } & ({ cards: MoneyCard[] } | { unbuilt: MoneyUnbuilt });

const summed = (quantity: string) => quantity.startsWith("sum(");

/**
 * The Cards a money tool's agreed rules give, built here rather than written by Coach (a phone test, 25 Nov: every
 * hand-written draft of "every refund held, over €50 refused, €200 a day" was refused by KIFF or by these checks, and
 * Coach went on to ask the owner to change their rules). A money tool is a write whose rule has a figure on an amount
 * (`sum(…)`). Its figures go on single-action Cards by what happens past them (the figure's `over`, else the rule's):
 * - the refused ones on one Card, `on_exceed: "refuse"`: each call's as `per_action_max`, the totals as aggregates;
 * - the asked ones on another, `on_exceed: "owner"`. KIFF needs every Card on a money tool to bound its own amount, so a
 *   held Card with no amount figure of its own also carries the refused Card's cap on each call, the same figure: a call
 *   past it breaches both Cards, and the refusing one wins (KIFF's authorizer: refuse takes precedence), so it is
 *   refused, never held. The person reads that cap once (home.ts). A held Card with no total of its own carries KIFF's
 *   count (kiffsOwnCount), beside the refused day's total.
 * Every other tool is the checks' as before. `agent` is the Cards' subject.
 */
export function cardsFromRules(grants: readonly ApprovalGrant[], rules: readonly AgreedRule[], agent: string, text = ""): MoneyCards[] {
  const everyCall = saysEveryCallAsks(text);
  const out: MoneyCards[] = [];
  for (const g of grants) {
    const suffix = grantSuffix(g.id);
    for (const t of g.request.tools ?? []) {
      const action = (t.name || t.tool || "").trim();
      if (!action || t.read_only || t.setup_reads || out.some((m) => fold(m.action) === fold(action))) continue;
      const rule = ruleOf(t, suffix, rules);
      if (!rule?.limits?.some((l) => summed(l.quantity))) continue;
      const tool = t.title || action;
      const built = moneyCardsOf(rule, action, agent, everyCall);
      out.push(typeof built === "string" ? { tool, action, unbuilt: built } : { tool, action, cards: built });
    }
  }
  return out;
}

function moneyCardsOf(rule: AgreedRule, action: string, agent: string, everyCall = false): MoneyCard[] | MoneyUnbuilt {
  const limits = rule.limits ?? [];
  if (limits.some((l) => l.quantity === "count" && l.limit > 0 && !l.per)) return "count";
  const over = (l: AgreedLimit) => l.over ?? rule.over;
  // A rule that says its calls ask, with no figure that asks, names nothing a Card can hold: built as its refused figures
  // alone, every call under them would run unasked, though the owner read that it asks (a phone test, 25 Nov: "Every refund
  // asks you first", agreed as `over: "ask"` beside a refused €50 a refund and €200 a day). It's never guessed either way:
  // the rules are shown again with the figure that asks ("every call asks" is a count of 0 a day), or with nothing asking.
  if (rule.over === "ask" && !limits.some((l) => over(l) === "ask")) return "asks_unnamed";
  // In any language: a rule that asks says what happens past each of its figures. A figure that inherits the rule's ask is
  // a cap that asks only past it, whatever the words say, so every call under it runs unasked; it's shown again with
  // each figure's over said, never read as the owner's.
  if (rule.over === "ask" && limits.some((l) => l.over === undefined)) return "ask_inherited";
  // The notice says every call asks, and this rule asks past a figure, but none of them is a count of 0 a day that asks:
  // a positive cap, or one a figure inherits from the rule's `over`, lets every call under it run unasked.
  if (everyCall && limits.some((l) => over(l) === "ask") &&
    !limits.some((l) => over(l) === "ask" && l.quantity === "count" && l.limit === 0 && l.window === "calendar_day" && !l.per)) return "every_call_unheld";
  const cap = (l: AgreedLimit) => ({ quantity: l.quantity, max: l.limit });
  const total = (l: AgreedLimit) => ({ quantity: l.quantity, limit: l.limit, window: l.window, ...(l.per ? { per: l.per } : {}) });
  const card = (kind: "refused" | "held", caps: Array<{ quantity: string; max: number }>, totals: MoneyCard["aggregates"]): MoneyCard => ({
    id: `${agent}-${action}-${kind}`, subject: agent, domain: "gateway",
    grants: [{ action, ...(caps.length ? { per_action_max: caps } : {}) }], aggregates: totals,
    on_exceed: kind === "refused" ? "refuse" : "owner",
  });
  const refused = limits.filter((l) => over(l) === "refuse");
  const asked = limits.filter((l) => over(l) === "ask");
  const out: MoneyCard[] = [];
  if (refused.length) {
    const totals = refused.filter((l) => l.window !== "call").map(total);
    if (!totals.length) return "refused_total";
    out.push(card("refused", refused.filter((l) => l.window === "call").map(cap), totals));
  }
  if (asked.length) {
    const caps = asked.filter((l) => l.window === "call").map(cap);
    const totals = asked.filter((l) => l.window !== "call").map(total);
    if (![...caps.map((c) => c.quantity), ...totals.map((a) => a.quantity)].some(summed)) {
      const copied = refused.filter((l) => l.window === "call" && summed(l.quantity)).map(cap);
      if (!copied.length) return "held_bound";
      caps.push(...copied);
    }
    if (!totals.length) {
      if (!refused.some((l) => l.window !== "call" && summed(l.quantity) && !l.per)) return "held_total";
      totals.push({ quantity: "count", limit: KIFF_COUNT_A_DAY, window: "calendar_day" });
    }
    out.push(card("held", caps, totals));
  }
  return out;
}

/**
 * The first money tool whose drafted Cards aren't the ones its agreed rules give (cardsFromRules), or whose rules give
 * none: every Card covering it, in any order, ids and titles aside. Compared on an allowlist, failing closed: a Card
 * with any other field, or another subject or domain, isn't one the rules give, whatever it limits (a field the check
 * didn't read could widen it: a total's extra room raises its ceiling, its measure changes what it counts, and KIFF
 * checks a money tool's amounts only on a Card in a domain). A Card over this tool and another isn't either. Undefined
 * when every one matches. The drafted Cards are read as KIFF shows a draft (GET /v1/me/agent-drafts/{id}), so the words
 * KIFF writes on each for the owner to read are set aside first (kiffShownAside), and only those.
 */
export function moneyCardsOff(grants: readonly ApprovalGrant[], cards: readonly unknown[], rules: readonly AgreedRule[], agent: string, text = ""): MoneyCards | undefined {
  for (const m of cardsFromRules(grants, rules, agent, text)) {
    if ("unbuilt" in m) return m;
    const name = fold(m.action);
    const drafted = cards.filter((raw) => covers(raw, name)).map((c) => limitsKey(kiffShownAside(c), agent));
    const built = m.cards.map((c) => limitsKey(c, agent));
    if (drafted.some((k) => k === undefined)) return m;
    const [a, b] = [(drafted as string[]).sort(), (built as string[]).sort()];
    if (a.length !== b.length || a.some((k, i) => k !== b[i])) return m;
  }
  return undefined;
}

/**
 * What KIFF writes on a draft's Cards as it shows them, for the owner to read, and never reads back as a term (kiff/cloud,
 * handlers: draftViewFor, withLines, withTitles, withQuantityOf, withUnits, withUnitGaps; a Card's terms are mandateBody's,
 * which name none of these): on a Card, its lines, and on a revision what it revises and how that reads now; on a grant,
 * its tool's title; on a total or a cap, what its quantity counts and its amount's unit, scale and pending use. Without
 * them set aside, every money draft read from KIFF has a field the allowlist doesn't name, and is refused whatever it
 * holds, the Cards the refusal prescribes included.
 */
const KIFF_SHOWN_ON_CARD = ["lines", "revises", "current", "unchanged"];
const KIFF_SHOWN_ON_GRANT = ["title"];
const KIFF_SHOWN_ON_LINE = ["quantity_of", "unit", "scale", "pending_text", "unit_missing", "unit_words"];

/** A drafted Card without what KIFF writes on it as it shows it (KIFF_SHOWN_*), each where KIFF writes it; anything else as it is. */
function kiffShownAside(raw: unknown): unknown {
  const isObject = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);
  const without = (o: Record<string, unknown>, fields: readonly string[]) => Object.fromEntries(Object.entries(o).filter(([k]) => !fields.includes(k)));
  const lines = (v: unknown) => (Array.isArray(v) ? v.map((l) => (isObject(l) ? without(l, KIFF_SHOWN_ON_LINE) : l)) : v);
  if (!isObject(raw)) return raw;
  const card = without(raw, KIFF_SHOWN_ON_CARD);
  if (Array.isArray(card.grants)) {
    card.grants = card.grants.map((g) => {
      if (!isObject(g)) return g;
      const grant = without(g, KIFF_SHOWN_ON_GRANT);
      if ("per_action_max" in grant) grant.per_action_max = lines(grant.per_action_max);
      return grant;
    });
  }
  if ("aggregates" in card) card.aggregates = lines(card.aggregates);
  return card;
}

/** The fields a money tool's drafted Card may have: the built Cards' own, and a title, which limits nothing. */
const CARD_FIELDS = new Set(["id", "title", "subject", "domain", "grants", "aggregates", "on_exceed"]);
const GRANT_FIELDS = new Set(["action", "per_action_max"]);
const CAP_FIELDS = new Set(["quantity", "max"]);
const TOTAL_FIELDS = new Set(["quantity", "limit", "window", "per", "measure"]);

/**
 * What limits a Card, as one string, the same for the same terms whatever their order; undefined for a Card with a
 * field the allowlist doesn't name, a subject other than `agent`, a domain other than "gateway", an `on_exceed` other
 * than "owner" or "refuse" (unsaid is "owner", as KIFF reads a new Card's), or a total's `measure` other than KIFF's
 * default for its quantity (calls count attempts, amounts count effects).
 */
function limitsKey(raw: unknown, agent: string): string | undefined {
  const isObject = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);
  const within = (v: unknown, fields: ReadonlySet<string>): v is Record<string, unknown> => isObject(v) && Object.keys(v).every((k) => fields.has(k));
  if (!within(raw, CARD_FIELDS) || raw.subject !== agent || raw.domain !== "gateway") return undefined;
  if (raw.on_exceed !== undefined && raw.on_exceed !== "owner" && raw.on_exceed !== "refuse") return undefined;
  if (!Array.isArray(raw.grants) || !Array.isArray(raw.aggregates)) return undefined;
  const grants: string[] = [];
  for (const g of raw.grants) {
    if (!within(g, GRANT_FIELDS) || typeof g.action !== "string") return undefined;
    const caps = g.per_action_max === undefined ? [] : g.per_action_max;
    if (!Array.isArray(caps) || !caps.every((c) => within(c, CAP_FIELDS))) return undefined;
    grants.push(JSON.stringify([fold(g.action), (caps as Array<Record<string, unknown>>).map((c) => JSON.stringify([c.quantity, c.max])).sort()]));
  }
  const totals: string[] = [];
  for (const a of raw.aggregates) {
    if (!within(a, TOTAL_FIELDS) || typeof a.quantity !== "string") return undefined;
    if (a.measure !== undefined && a.measure !== (a.quantity.trim() === "count" ? "attempts" : "effects")) return undefined;
    totals.push(JSON.stringify([a.quantity, a.limit, a.window, a.per ?? null]));
  }
  return JSON.stringify([grants.sort(), totals.sort(), raw.on_exceed ?? "owner"]);
}

/**
 * KIFF's own count, never the owner's: KIFF refuses a Card with no total ("a mandate with no aggregate bounds nothing"),
 * so a Card the owner gave no total carries this one, 10,000 calls a day, shown as "KIFF adds it". The reads' Card has
 * it, and so does a held Card whose only figure is each call's: "ask past €5 a refund, refuse past €50 a day" is that
 * Card beside a refused one with the day's €50, since KIFF's Cards are conjunctive (Eval 43975057022, Setup run 3:
 * refused as a count beside the amount, the held Card had no total left, and Coach changed the agreed rule to get out).
 */
export const KIFF_COUNT_A_DAY = 10_000;

/**
 * Whether `a` is KIFF's own count on `card`, never a figure of the owner's: a count of KIFF_COUNT_A_DAY a calendar day,
 * for no item, as the only total of a Card that holds what's past it (not one that refuses), and, for each tool the Card
 * covers, another of `cards` covering it with a total on an amount over all items, never one item's (alone, or
 * beside €50 for each customer, 10,000 refunds of €4.99 a day would pass unasked, a sum no one agreed). Without that other Card, the count is a count beside the amount, as before.
 */
export function kiffsOwnCount(cards: readonly unknown[], card: unknown, a: unknown): boolean {
  const c = (card ?? {}) as { grants?: unknown; aggregates?: unknown; on_exceed?: unknown };
  const x = (a ?? {}) as { quantity?: unknown; window?: unknown; per?: unknown; limit?: unknown };
  if (c.on_exceed === "refuse" || !Array.isArray(c.aggregates) || c.aggregates.length !== 1 || c.aggregates[0] !== a) return false;
  if (fold(x.quantity) !== "count" || fold(x.window) !== "calendar_day" || fold(x.per) || x.limit !== KIFF_COUNT_A_DAY) return false;
  const actions = (Array.isArray(c.grants) ? c.grants : []).map((g) => fold((g as { action?: unknown })?.action)).filter(Boolean);
  const bounded = (action: string) => cards.some((o) => {
    const other = (o ?? {}) as { grants?: unknown; aggregates?: unknown };
    return o !== card && Array.isArray(other.grants) && other.grants.some((g) => fold((g as { action?: unknown })?.action) === action) &&
      Array.isArray(other.aggregates) && other.aggregates.some((t) => fold((t as { quantity?: unknown })?.quantity).startsWith("sum(") && !fold((t as { per?: unknown })?.per));
  });
  return actions.length > 0 && actions.every(bounded);
}

/**
 * The suffix planque gives a grant's tools in KIFF when the request names none (`toolKey`): `_` and the first four
 * letters or digits of the grant's id after `grt_`, lowercased. Empty when the id has none.
 */
export function grantSuffix(id: string): string {
  const four = (id.startsWith("grt_") ? id.slice(4) : id).toLowerCase().replace(/[^a-z0-9]/g, "").slice(0, 4);
  return four ? `_${four}` : "";
}

/** A tool's job and the four letters or digits after it, when its name ends as `toolKey` or `numberedKey` make it. */
function tailOf(name: string): { job: string; four: string } | undefined {
  const m = /^(.+)_([a-z0-9]{4})(\d{0,2})$/.exec(name);
  return m && !(m[3] && Number(m[3]) < 2) ? { job: m[1]!, four: m[2]! } : undefined;
}

/**
 * The connection's own tools among the taf's when `tool` isn't that connection's (kiff/tafs: the phone's retest, 18
 * Nov): a taf switched to a new connection of the same app keeps the old one's tools too. A tool ending as planque
 * names a connection's tools (`toolKey`, `numberedKey`) is the connection whose suffix it ends in, so one that doesn't
 * end in `grant`'s is another's, whatever tools the taf keeps (none of the connection's own, maybe). A tool named
 * without a suffix is another's only when a tool of its job ends in `grant`'s. Undefined when it may be the connection's.
 */
export function anotherConnectionsTool(tool: string, grant: string, tools: readonly string[]): string[] | undefined {
  const suffix = grantSuffix(grant);
  if (!suffix || jobOf(tool, suffix)) return undefined;
  const own = tools.filter((t) => jobOf(t, suffix));
  if (tailOf(tool)) return own;
  return own.some((t) => tailOf(t)?.job === tool) ? own : undefined;
}

/** The suffix a tool's name ends in when planque named it for a connection (`_` and four letters or digits), else undefined. */
export function connectionTail(tool: string): string | undefined {
  const t = tailOf(tool);
  return t ? `_${t.four}` : undefined;
}

/**
 * The connections a tool's job has among the taf's tools, by the suffix their tools end in, with each one's tools:
 * two or more when the taf has that app twice, so a read named without its connection could be either.
 */
export function connectionsOfJob(tool: string, tools: readonly string[]): Map<string, string[]> {
  const job = tailOf(tool)?.job;
  const out = new Map<string, string[]>();
  if (!job) return out;
  const fours = new Set(tools.map(tailOf).filter((t) => t?.job === job).map((t) => t!.four));
  for (const four of fours) out.set(four, tools.filter((t) => tailOf(t)?.four === four));
  return out;
}

/**
 * The job name in KIFF's name for a tool, with this grant's suffix off: `<job><suffix>` (`toolKey`), or
 * `<job><suffix><n>` for n from 2 when that was taken (`numberedKey`). planque cuts the job to 59 characters, less
 * n's digits, so a job cut exactly there covers a rule that starts with it; any other covers only itself.
 */
function jobOf(name: string, suffix: string): { covers: (rule: string) => boolean } | undefined {
  if (!suffix) return undefined;
  const m = new RegExp(`^(.+)${suffix}(\\d{0,2})$`).exec(name);
  if (!m || (m[2] && Number(m[2]) < 2)) return undefined;
  const job = m[1]!;
  const cut = job.length === 59 - m[2]!.length;
  return { covers: (rule) => rule === job || (cut && rule.length > job.length && rule.startsWith(job)) };
}

/** A figure on the Cards: `per` is "" on each call's and on a total over all items, else folded. */
interface Figure { quantity: string; window: string; per: string; limit: number }

/** An agreed figure and a drafted one are the same limit: the same quantity, window and per (folded). */
const sameFigure = (l: AgreedLimit, f: { quantity: string; window: string; per: string }) =>
  l.quantity === f.quantity && l.window === f.window && fold(l.per) === f.per;

/**
 * The figures the Cards set on one tool (its KIFF name, folded): each call's, from its grants' `per_action_max`, the
 * day's totals, from the Cards' `aggregates` with `window: calendar_day` and no `per`, and the totals for each item,
 * from those with a `per`, in the window they name (a total for each recipient or customer is another total, never
 * the day's). Quantities are as written, trimmed, never folded further, so
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
        out.push({ quantity: trim(m.quantity), window: "call", per: "", limit: num(m.max) });
      }
    }
    for (const a of (Array.isArray(card.aggregates) ? card.aggregates : []) as Array<{ quantity?: unknown; window?: unknown; per?: unknown; limit?: unknown }>) {
      if (kiffsOwnCount(cards, card, a)) continue;
      if (fold(a.per)) out.push({ quantity: trim(a.quantity), window: fold(a.window), per: fold(a.per), limit: num(a.limit) });
      else if (fold(a.window) === "calendar_day") out.push({ quantity: trim(a.quantity), window: "calendar_day", per: "", limit: num(a.limit) });
    }
  }
  return out;
}

/** A figure a Card adds on a tool the rules don't name: its quantity, its window as the Card writes it, its per ("" for none), and its limit. */
export interface UnnamedFigure { tool: string; quantity: string; window: string; per: string; limit: number }

/**
 * The figures the Cards add that the agreed rules don't name: on a tool whose rule has figures, each call's or a total
 * over any window (a day's, an hour's, a week's, a lifetime's) on a quantity and window the rule doesn't give. Refused at
 * Approve (Eval 43927756622: after a refused draft, Coach signed a rolling hour's €50 on refunds beside the agreed €5 a
 * refund and €50 a day, and held past it where the rules said refuse). A total for each item (`per`) is named by a figure
 * with the same quantity, window and per: on a rule that names a per total, every one is held to it; on a rule that names
 * none, only with `perToTheLetter` (TAFS_RULES_PER=on, once Coach's rules carry them; until then the shape checks before
 * this one judge them). Not named: a count of 0 (each call asks), which only asks, and KIFF's own count (kiffsOwnCount). The tool is named as the connection
 * titles it.
 */
export function rulesUnnamedFigures(grants: readonly ApprovalGrant[], cards: readonly unknown[], rules: readonly AgreedRule[], perToTheLetter = false): UnnamedFigure[] {
  const out: UnnamedFigure[] = [];
  for (const g of grants) {
    for (const t of g.request.tools ?? []) {
      const name = fold(t.name || t.tool);
      if (!name) continue;
      const before = fold(t.tool || t.name);
      const rule = rules.find((r) => fold(r.tool) === before) ?? rules.find((r) => fold(r.tool) === name);
      if (!rule?.limits) continue;
      const namesPer = rule.limits.some((l) => l.per);
      for (const f of figuresInAnyWindow(cards, name)) {
        if (f.quantity === "count" && f.limit === 0) continue;
        if (f.per && !namesPer && !perToTheLetter) continue;
        if (!rule.limits.some((l) => sameFigure(l, f))) {
          out.push({ tool: t.title || t.name || t.tool || name, ...f });
        }
      }
    }
  }
  return out;
}

/**
 * The figures the Cards set on one tool (its KIFF name, folded), in every window: each call's, from its grants'
 * `per_action_max` (`call`), and each total from the Cards' `aggregates`, in the window it names, folded (`calendar_day`,
 * `rolling_1h`, `lifetime`…; none written reads as ""), with its `per`, folded. Quantities as `figuresOn` reads them.
 */
function figuresInAnyWindow(cards: readonly unknown[], name: string): Figure[] {
  const out: Figure[] = figuresOn(cards, name).filter((f) => f.window === "call");
  const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? v : -1);
  const trim = (v: unknown) => (typeof v === "string" ? v.trim() : "");
  for (const raw of cards) {
    const card = raw as { grants?: unknown; aggregates?: unknown };
    const grants = (Array.isArray(card.grants) ? card.grants : []) as Array<{ action?: unknown }>;
    if (!grants.some((g) => fold(g.action) === name)) continue;
    for (const a of (Array.isArray(card.aggregates) ? card.aggregates : []) as Array<{ quantity?: unknown; window?: unknown; per?: unknown; limit?: unknown }>) {
      if (kiffsOwnCount(cards, card, a)) continue;
      out.push({ quantity: trim(a.quantity), window: fold(a.window), per: fold(a.per), limit: num(a.limit) });
    }
  }
  return out;
}

/**
 * The agreed figures the notice's words don't say: each limit whose number, in the Card's units or in major units (a
 * hundredth, as cents are of euros), the notice's text doesn't write. The owner agrees to the words they read, so a figure
 * the words leave out was never agreed (Eval 44765665210: invoices-3's notice said "a draft invoice for each one", and the
 * Card held 10 a day and 1 per order). Numbers are read in digits, with either `,` or `.` as the decimal mark and spaces,
 * dots or commas between thousands, so "€5", "5,00 €" and "1.000" are all found; and 1 as a word only where ONE_PER
 * reads it. Any other figure written as a word isn't found, and Coach shows it again in digits: unsure is unsaid.
 */
export function rulesUnsaid(rules: readonly AgreedRule[], text: string): string[] {
  const said = numbersIn(text);
  const folded = text.normalize("NFC").toLowerCase();
  if (ONE_PER.some((re) => re.test(folded))) said.add(1);
  const out: string[] = [];
  for (const r of rules) {
    for (const l of r.limits ?? []) {
      if (!said.has(l.limit) && !said.has(l.limit / 100)) out.push(`${r.tool} ${l.quantity} ${l.window} ${l.limit}${l.per ? ` per ${l.per}` : ""}`);
    }
  }
  return out;
}

/**
 * "One" where it can't be an article, in the languages that have such a word, and their words for "each" or "per" after
 * it, as a notice says a limit of 1 for each item ("one invoice per order", "één factuur per bestelling"). The word for
 * one counts only with its own language's per word at most three words after it. Articles never count ("a", "un",
 * "una", "ein", "een", "um"…: "una factura enviada por correo" says no limit), nor a per word that is mostly a
 * preposition ("na", "za", "je"): those languages write 1 in digits, as draft-the-card says. A pair from two languages
 * isn't read as one either.
 */
const ONE_PER_WORDS: Record<string, { one: string[]; per: string[] }> = {
  en: { one: ["one"], per: ["per", "each", "every", "for each", "for every"] },
  de: { one: ["eins"], per: ["pro", "für jede", "für jeden", "für jedes", "jede", "jeden", "jedes"] },
  nl: { one: ["één"], per: ["per", "voor elke", "voor ieder", "voor iedere", "elke", "iedere"] },
  pl: { one: ["jeden", "jedna", "jedno", "jedną"], per: ["dla każdego", "dla każdej", "każdy", "każda", "każde", "każdego", "każdej"] },
};
const alt = (words: readonly string[]) => words.map((w) => w.replace(/ /g, "\\s+")).sort((a, b) => b.length - a.length).join("|");
const ONE_PER = Object.values(ONE_PER_WORDS).map(({ one, per }) =>
  new RegExp(`(?<![\\p{L}\\p{N}])(?:${alt(one)})(?:\\s+[\\p{L}\\p{N}'’-]+){0,3}?\\s+(?:${alt(per)})(?![\\p{L}\\p{N}])`, "u"));

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

/**
 * The first tool an agreed connection line names that the grants don't pin: the line says the connection keeps it, so the
 * tool, matched by planque's name before KIFF's suffix as rulesMismatch does, must carry a pinned value
 * (`fixed_arguments`) in every grant that has it, and one must. A line naming no tool, or a tool no grant has, isn't held by any
 * connection either. undefined when every line is pinned, or there are none (a notice from before lines).
 */
export function unpinnedConnection(grants: readonly ApprovalGrant[], lines: readonly ConnectionLine[]): { line: string; tool: string; title: string } | undefined {
  for (const line of lines) {
    if (line.tools.length === 0) return { line: line.text, tool: "", title: line.text };
    for (const tool of line.tools) {
      const want = fold(tool);
      // Every grant's entry for the tool is pinned, and there is one: a pin on another app's tool of the same name holds nothing here.
      const entries = grants.flatMap((g) => (g.request?.tools ?? []).filter((t) => {
        const name = fold(t.tool ?? t.name);
        return name === want || name.startsWith(want + "_");
      }));
      const pinned = entries.length > 0 && entries.every((t) => t.fixed_arguments !== null && typeof t.fixed_arguments === "object" && Object.keys(t.fixed_arguments).length > 0);
      if (!pinned) return { line: line.text, tool, title: entries.find((t) => t.title)?.title ?? tool };
    }
  }
  return undefined;
}

/**
 * Whether a rules notice's words say every call asks the owner: "every refund asks you first", "chaque remboursement attend
 * votre accord". Read in the languages tafs cloud speaks to Coach in most (en, fr, de, es, it, nl, pl, pt), as each says it:
 * a word for every or each, then the verb that asks, with no digit between them, since "every refund over 50 EUR asks" is a
 * figure that asks, not every call. Used only to hold a money rule that asks to a count of 0 a day (moneyCardsOf); words in
 * another language aren't read, and the rule's own figures stand, as before.
 */
export function saysEveryCallAsks(text: string): boolean {
  const folded = text.normalize("NFC").toLowerCase();
  return EVERY_CALL_ASKS.some((re) => re.test(folded));
}

const between = "[^\\d.;:!?\\n]{1,60}?";
const EVERY_CALL_ASKS: RegExp[] = [
  ["every|each", [
    "(?:will |must |should |always |first )?asks? (?:you|first)",
    "(?:will |must |should |always )?waits? for (?:your|you)",
    "(?:will |must |should )?(?:needs?|requires?) (?:your (?:approval|ok|okay|yes|confirmation)|you to (?:approve|confirm|ok))",
    "(?:is|are|will be|must be|gets?|has to be) (?:approved|confirmed|okayed|ok'?d|checked) by you",
    "(?:goes|go|will go|is sent|comes|will come) to you first",
  ].join("|")],
  ["chaque|tout|toute", [
    "vous (?:demande|demandera|sera soumise?|est soumise?|est envoyée?)",
    "(?:attend|attendra|nécessite|nécessitera|requiert|demande|demandera) votre (?:accord|approbation|validation|feu vert)",
    "(?:est|sera|doit être) (?:validée?|approuvée?|confirmée?) par vous",
    "passe (?:d'abord |d’abord )?par vous",
  ].join("|")],
  ["jede|jeder|jedes|jeden", [
    "fragt (?:dich|sie)", "wird (?:dich|sie) (?:zuerst )?fragen",
    "(?:wartet|wird) auf (?:deine|ihre) (?:zustimmung|freigabe|genehmigung)",
    "(?:braucht|benötigt|erfordert|muss|wird) (?:deine|ihre) (?:zustimmung|freigabe|genehmigung)",
    "(?:wird|muss) (?:zuerst )?von (?:dir|ihnen) (?:freigegeben|genehmigt|bestätigt)",
    "geht (?:zuerst )?an (?:dich|sie)",
  ].join("|")],
  ["cada|todo|toda", [
    "(?:te|le) (?:pregunta|preguntará)",
    "(?:espera|esperará|necesita|necesitará|requiere|requerirá) (?:tu|su) (?:aprobación|visto bueno|autorización|confirmación)",
    "(?:es|será|debe ser) (?:aprobad[oa]|confirmad[oa]|autorizad[oa]) por (?:ti|usted)",
    "(?:pasa|pasará|va) (?:primero )?por (?:ti|usted)",
  ].join("|")],
  ["ogni", [
    "(?:ti|le) (?:chiede|chiederà)",
    "(?:aspetta|aspetterà|attende|attenderà|richiede|richiederà) la (?:tua|sua) (?:approvazione|conferma|autorizzazione)",
    "ha bisogno della (?:tua|sua) (?:approvazione|conferma)",
    "(?:è|sarà|deve essere) (?:approvat[oa]|confermat[oa]|autorizzat[oa]) da te",
    "passa (?:prima )?da te",
  ].join("|")],
  ["elke|iedere|ieder", [
    "vraagt (?:je|jou|u)", "zal (?:je|jou|u) (?:eerst )?vragen",
    "(?:wacht|zal wachten) op (?:je|jouw|uw) (?:goedkeuring|akkoord|ok)",
    "(?:heeft|vereist|vraagt) (?:je|jouw|uw) (?:goedkeuring|akkoord)",
    "(?:wordt|moet worden|zal worden) (?:eerst )?(?:goedgekeurd|bevestigd) door (?:jou|u)",
    "gaat (?:eerst )?naar (?:jou|u)",
  ].join("|")],
  ["każdy|każda|każde|każdego|każdej", [
    "pyta (?:cię|ciebie|pana|panią)", "zapyta (?:cię|ciebie|pana|panią)",
    "(?:czeka|będzie czekać) na (?:twoją|pana|pani) (?:zgodę|akceptację|zatwierdzenie)",
    "(?:wymaga|potrzebuje|będzie wymagać) (?:twojej|pana|pani) (?:zgody|akceptacji|zatwierdzenia)",
    "(?:jest|będzie|musi być) (?:najpierw )?(?:zatwierdzan[yae]|zatwierdzon[yae]|akceptowan[yae]) przez (?:ciebie|pana|panią)",
    "trafia (?:najpierw )?do (?:ciebie|pana|pani)",
  ].join("|")],
  ["cada|todo|toda", [
    "(?:lhe|te) (?:pergunta|perguntará)", "(?:pergunta|perguntará)-(?:lhe|te)",
    "(?:espera|esperará|aguarda|aguardará|precisa|precisará|requer|exige) (?:da |a |pela )?(?:sua|tua) (?:aprovação|autorização|confirmação)",
    "(?:é|será|tem de ser|deve ser) (?:aprovad[oa]|confirmad[oa]|autorizad[oa]) por (?:você|ti)",
    "(?:passa|vai) (?:primeiro )?por (?:você|ti)",
  ].join("|")],
].map(([every, asks]) => new RegExp(`(?<![\\p{L}\\p{N}])(?:${every})\\s${between}(?:${asks})(?![\\p{L}\\p{N}])`, "u"));
