import { grantSuffix, kiffsOwnCount, KIFF_COUNT_A_DAY, rulesMismatch, rulesUnnamedFigures, rulesUnsaid, type RulesAgreement, unpinnedConnection } from "./rules.js";
import { noteSendingOutside, sendingProfileOf } from "./sends-outside.js";
/**
 * An approval card's outcome is tafs cloud's to decide (kiff/tafs#413). The
 * phone says the person tapped Approve; tafs cloud reads planque's grant and
 * KIFF's draft, as that phone, and says what needs signing, if anything:
 *
 * - `answered`: nothing to sign, and Coach heard it from tafs cloud: `already
 *   approved: <why>` when the connection and its Card were approved earlier,
 *   with their own signature; `failed: <why>` otherwise. The card shows the note.
 * - `read_again`: the card is out of date (its terms or its draft changed since
 *   it was shown) or KIFF's words for the connection aren't on the phone yet.
 *   Nothing is told to Coach: the owner reads it again and approves.
 * - `sign`: one message for the device's key, under biometrics, and the call it
 *   goes into, exactly as the phone must make it: planque's approve body, or
 *   KIFF's draft and version. The phone signs only that, makes that call, and
 *   reports what came of it, signed as a setup call (KIFF-Device-Signature).
 *
 * The report answers Coach `approved` only once KIFF says this phone signed
 * it, and planque or KIFF says the connection or the Card went through: tafs
 * cloud never vouches for an answer, and the apps never build one. Every word
 * the apps show for an outcome comes from here.
 */
import { createHash } from "node:crypto";
import type { CoachStore } from "./coach.js";
import { sha256 } from "./jcs.js";
import { readsOnly, takesKey } from "./sheet.js";
import { actionsInForce, draftInfo, type Fetch, issued, KIFF_API, KIFFError } from "./kiff.js";
import { vendorName } from "./steps.js";
import { type Lang, wordsIn } from "./words/index.js";

/** A grant as planque sends it, in what the decision reads. */
export interface ApprovalGrant {
  id: string;
  status: string;
  digest: string;
  vendor?: string;
  /** The name of the taf it was made for, as planque keeps it: named before the taf exists, during its setup. */
  agent_name?: string;
  reason?: string;
  /** The app's sign-in was set up on the owner's pick, not by an operator (kiff/planque#87). */
  on_demand?: boolean;
  /** planque's name for the connection, the same in every app: its app and, when known, its account ("Gmail · Demo Shop"). */
  title?: string;
  request: {
    agent?: string; server?: string; profile?: unknown; tools?: Array<{ tool?: string; name?: string; title?: string; read_only?: boolean; setup_reads?: boolean; fixed_arguments?: Record<string, unknown> | null }>;
    /**
     * A managed connector's request (kiff/planque `ConnectorRequest`): `usual_access` when planque couldn't narrow the app's
     * sign-in to what the taf uses (its ceiling has no narrower scope, such as Google Sheets with no read-only one), so the
     * sign-in asks for more access than the job needs.
     */
    connector?: { usual_access?: boolean } & Record<string, unknown>;
  } & Record<string, unknown>;
  /** The tools it had before the addition that waits now: a connected grant whose new tools wait for their approval. */
  connected?: unknown[];
  /** planque holds a confirmed sign-in for it (a reused one, or one made earlier): approving its tools needs no sign-in. */
  signed_in?: boolean;
  /** Its tools are declared in KIFF while they wait for approval, so a Card can be drafted and confirmed over them. */
  declared?: boolean;
}

/**
 * The Card's draft, read and checked for signing (the cards step's checks, and `key_and_cards`'): KIFF has it,
 * it's still a draft at the version the card showed, an app set up on demand lets none of its writes run outright,
 * and every tool the connection gives the taf is on a Card. Otherwise what answers instead.
 */
/**
 * The refusals of a draft's Cards that a new draft mends: Coach hears them before the owner is shown the card, when tafs
 * cloud checks the draft as the phone reads the chat (handler.ts, precheck), so the owner approves one Card, not a
 * refused one and then its redraft (Eval 43690861487's Refunds). The rest (no draft, a removed taf, another app first,
 * a read that didn't come back) wait for the owner's tap, as before.
 */
export const REDRAFT_CODES: ReadonlySet<string> = new Set(["rules_unpinned", "no_card", "vendor_name", "stray", "uncovered", "uncovered_elsewhere", "on_demand", "lifetime_cap", "lifetime_sum", "day_total_twice", "count_on_amount",
  "rules_on_exceed", "rules_missing", "rules_figures", "rules_unagreed_figure", "rules_unagreed", "rules_required", "rules_unsaid"]);
/** The refusals Coach mends by having the owner agree to the rules first, then drafting again. */
/**
 * Said with every refusal for rules not agreed: nothing waits for the owner until Coach shows the rules, so Coach shows
 * them in the same turn. 16 Nov: after this refusal, Coach ended its turn on "waiting for you to approve" with nothing
 * on screen to tap, and showed the rules card only when nudged.
 */
export const SHOW_NOW = "Nothing is waiting for the owner now: this approval is over, and there is no card for them to tap until you show one. Show the rules' confirm in this turn, before you end it, and never end a turn saying you're waiting for their approval with no card on screen.";

const AGREE_FIRST_CODES: ReadonlySet<string> = new Set(["rules_unagreed", "rules_required", "rules_unsaid"]);

/**
 * What a refusal Coach redrafts on adds, so the redraft mends only what was named: the redraft under Eval 43690861487's
 * refusal merged two agreed read limits into one and put a count back on a refund.
 */
/**
 * The one count a Card of reads carries: KIFF refuses a Card with no total, so the reads' Card has this, the same in
 * draft-the-card and here, never Coach's choice and never shown as the owner's limit. A held Card with no total of the
 * owner's carries it too (rules.ts, kiffsOwnCount).
 */
export const READS_A_DAY = KIFF_COUNT_A_DAY;

export const KEEP_THE_REST = "Change only what this names; every other line stays exactly as agreed.";

type CardsChecked = Decision | { waitsFor: string } | { draft: NonNullable<Awaited<ReturnType<typeof draftInfo>>> & { version: string } };

/**
 * What every refusal a new draft mends says to Coach, so it never reads as an approval still waiting (Eval 43705726781's
 * Refunds: told "failed: …" on the read, Coach said the open approval was the one to finish, and waited four turns).
 */
export const REFUSAL_CLOSED = "Refused by tafs cloud's check, not by the owner: this approval is closed and nothing waits for them.";
export const DRAW_AGAIN = "Draft it again now and show a new approval.";
export const AGREE_FIRST = "Show the rules for the owner to agree first, then draft it again and show a new approval.";
/** A count_on_amount refusal's: the count was never the owner's, so Coach drops it without asking them again. */
export const COUNT_NEVER_A_RULE = "A count of calls on a tool with an amount limit is never one of the owner's rules: drop it without asking them.";

/**
 * cardsChecked, with what each refusal a new draft mends adds, wherever Coach hears it: the approval is closed, what it
 * would also have carried wasn't approved either (its tools or its key, on a grant not yet connected), and KEEP_THE_REST.
 * `failed: …` stays first, as the apps and the e2e read it.
 */
async function cardsReady(ask: ApprovalAsk, draftID: string, grant: ApprovalGrant, vendor: string, ctx: DecideContext, others: ApprovalGrant[] = [], combined = false): Promise<CardsChecked> {
  const checked = await cardsChecked(ask, draftID, grant, vendor, ctx, others, combined);
  if ("outcome" in checked && checked.outcome === "answered" && checked.code && REDRAFT_CODES.has(checked.code)) {
    const carried = grant.status === "connected" ? ""
      : takesKey(grant) ? " Its key wasn't approved either: the new approval carries it with the Card."
      : " Its tools weren't approved either: the new approval carries them with the Card.";
    const count = checked.code === "count_on_amount" ? ` ${COUNT_NEVER_A_RULE}` : "";
    const next = AGREE_FIRST_CODES.has(checked.code) ? `${AGREE_FIRST} ${SHOW_NOW}` : DRAW_AGAIN;
    return { ...checked, answer: `${checked.answer.replace(/\s+$/, "")} ${REFUSAL_CLOSED}${carried}${count} ${next} ${KEEP_THE_REST}` };
  }
  return checked;
}

async function cardsChecked(ask: ApprovalAsk, draftID: string, grant: ApprovalGrant, vendor: string, ctx: DecideContext, others: ApprovalGrant[] = [], combined = false): Promise<CardsChecked> {
  const W = approvalWords(ctx.lang);
  const draft = await draftInfo(ctx.token, draftID, ctx.fetch, ctx.kiffBase ?? KIFF_API, ctx.now);
  if (!draft) return { outcome: "answered", answer: `failed: KIFF has no Card draft ${draftID} on this account`, note: `KIFF has no Card draft for this. Ask Coach for a new one.`, code: "no_draft" };
  // Its Card confirmed already: a real approval, signed earlier (another phone, or a tap before this one).
  if (issued(draft.status)) return { outcome: "answered", answer: `already approved: ${vendor} is connected and its Card is confirmed`, note: W.alreadyApproved, code: "already_approved" };
  if (draft.status !== "draft") return { outcome: "answered", answer: `failed: the Card draft is ${draft.status}`, note: `The Card draft is ${draft.status}. Ask Coach for a new one.`, code: "draft_status" };
  if (!draft.version || draft.version !== ask.shown_draft) return { outcome: "read_again", note: W.outOfDate, code: "out_of_date" };
  // Every check below reads the Cards as KIFF shows them, so a draft is never signed without them (fail closed). A read
  // that didn't bring them is read again; a draft with no Card at all (KIFF's null, or an empty list) is answered, so
  // Coach drafts one rather than the card asking the same draft again (#1067).
  if (!draft.cards) return { outcome: "read_again", note: W.cardsUnread, code: "cards_unread" };
  if (!draft.cards.length) return { outcome: "answered", answer: `failed: the Card draft ${draftID} has no Card, so nothing in it covers the taf's tools and it isn't signed. Draft it again with a Card over each tool the taf's connections give it.`, note: W.noCard, code: "no_card" };
  // An app set up on demand asks for every change (kiff/planque#87): a draft that lets one of its
  // writes run outright isn't signed, and Coach hears why, so the default holds here, not only in its words.
  const letsRun = onDemandLetsRun(grant, draft.cards);
  if (letsRun) {
    const why = W.onDemandLetsRun(vendor, letsRun);
    return { outcome: "answered", answer: `failed: ${vendor} was set up on demand, so every write and money tool of it must ask the owner each time (0 a day, held); the draft lets "${letsRun}" run without asking. Draft it again with that tool held.`, note: why, code: "on_demand" };
  }
  // Every tool the taf was given goes on a Card, the reads too: a draft that leaves one uncovered,
  // with no Card in force covering it either, isn't signed, and Coach hears which. A draft read
  // without its Cards, or Cards in force that can't be read, isn't signed on trust.
  {
    let inForce: Set<string>;
    try {
      inForce = await actionsInForce(ctx.token, draft.agent, ctx.fetch, ctx.kiffBase ?? KIFF_API);
    } catch {
      return { outcome: "read_again", note: W.cardsUnread, code: "in_force_unread" };
    }
    // And the other way (a review's negative 1 on #919): every action the draft's Cards allow is one of this connection's
    // tools, one of the same taf's other connections', or allowed already. A Card signed under "Connect Stripe" never
    // also lets the taf act on something no connection of its gives it here.
    // A tool planque renamed for this taf (its vendor name was taken in KIFF) is covered by its registered name only
    // (Eval 43681227913: the Card covered "list_charges", another connection's tool, and the taf was granted "list_charges_noyy").
    // Coach hears the name to draft it with. Before the stray check: another grant's tool by that name would pass it.
    const renamed = vendorNameOnCard(grant, others, draft.cards, inForce);
    if (renamed) {
      return { outcome: "answered", answer: `failed: the Card covers "${renamed.action}", but this taf's connection gives that tool as "${renamed.name}": "${renamed.action}" is another connection's name in KIFF, so it isn't signed. Draft it again with "${renamed.name}" in its place.`, note: W.registeredName(renamed.action, renamed.name), code: "vendor_name" };
    }
    const stray = strayAction(grant, others, draft.cards, inForce);
    if (stray) {
      return { outcome: "answered", answer: `failed: the Card covers "${stray}", which no connection of this taf gives it, so it isn't signed. Draft it again over the taf's connected tools only.`, note: W.stray(stray), code: "stray" };
    }
    // A Card over two apps waits for the other app's tools (P0, 14 Nov): one signature carries one connection's approve,
    // so a Card signed while another app it covers is still waiting would be "approved" with that app's tools never
    // approved. That app's own approval goes first; the Card comes with the last one.
    // On a step that would carry this app's tools with the Card, it's this app's connect alone instead (the Card comes
    // with the last app): refusing both would leave each card sending the person to the other.
    const waiting = appsWaiting(grant, others, draft.cards);
    if (waiting.length && combined) return { waitsFor: waiting[0]!.name };
    if (waiting.length) {
      const first = waiting[0]!;
      // Named so it differs from this app (Eval 18, intros: two key apps on one host were both "the host", and Coach put the
      // Card on the waiting app's own approval): its tools when the names clash, and its grant id for Coach.
      // The Card comes with the last app's approval, as the person reads it (approval_other_app_first): the waiting app's,
      // with this same draft. Never this one again once it's connected (Eval 19, intros: told "then show this one again:
      // it carries the Card", Coach showed the connected sheet's approval again after the mailbox's had confirmed the Card).
      const own = `${ownName(grant, others)} (grant ${grant.id})`;
      const next = grant.status === "connected"
        ? `show ${first.name}'s approval with this same draft: it carries the Card. ${own} is connected already: never show its approval again`
        : `show ${own}'s approval for its connection alone, with no draft, then ${first.name}'s with this same draft: the last one carries the Card. Show each approval once`;
      // The waiting app's approval must carry this draft (Eval 20b, intros run 1: Coach showed the mailbox's key alone, then
      // the Card on a third approval). Kept so the chat's read refuses that approval shown without it (splitApproval).
      await ctx.store.put(waitingDraftKey(ctx.tenant, first.grant), { draft: draftID, name: first.name, at: ctx.now.toISOString() } satisfies WaitingDraft,
        new Date(ctx.now.getTime() + WAITING_DRAFT_KEPT_MS)).catch(() => undefined);
      return { outcome: "answered", answer: `failed: the Card also covers ${first.name}'s tools (grant ${first.grant}), which still wait for their own approval. The Card comes with the last app's approval: ${next}.`, note: W.otherAppFirst(first.name), code: "other_app_first" };
    }
    const left = uncoveredTool(grant, draft.cards, inForce);
    if (left) {
      return { outcome: "answered", answer: `failed: no Card covers "${left}", which the taf's connection gives it, so KIFF would refuse every call of it (no_mandate_issued). Draft it again with "${left}" on a Card (a read goes on the reads' Card, which carries KIFF's count of ${READS_A_DAY.toLocaleString("en")} a day, not the owner's).`, note: W.uncovered(left), code: "uncovered" };
    }
    // Every tool the taf will hold, not only this connection's (Eval 43657637947): each of the same taf's other
    // connected apps is covered too, by the draft or by a Card in force, or the taf would hold a tool KIFF refuses,
    // and a refused read stops its session before the write that was allowed.
    const elsewhere = uncoveredElsewhere(grant, others, draft.cards, inForce);
    if (elsewhere) {
      return { outcome: "answered", answer: `failed: no Card covers "${elsewhere.tool}", which the taf's ${elsewhere.vendor} connection gives it, so KIFF would refuse every call of it (no_mandate_issued). Draft it again with "${elsewhere.tool}" on a Card too (a read goes on the reads' Card, which carries KIFF's count of ${READS_A_DAY.toLocaleString("en")} a day, not the owner's).`, note: W.uncovered(elsewhere.tool), code: "uncovered_elsewhere" };
    }
    // A limit for each item drafted as one lifetime count on the whole tool stops the taf after its first item
    // (Eval 43402441612: "one invoice per order, ever" signed as 1 create_invoice in the Card's life). Never signed.
    const capped = lifetimeCapOnWrite(grant, draft.cards);
    if (capped) {
      const per = capped.resource ? `per: "${capped.resource}" (its resource_argument)` : "per its resource_argument";
      return { outcome: "answered", answer: `failed: the draft counts "${capped.tool}" ${capped.limit} in its whole life, across every item, so the taf would stop after its first. A limit for each item is a total with ${per}: count, lifetime, limit ${capped.limit}, per that argument, beside the day's total. With no resource_argument, it goes in the taf's instructions, and the rules say KIFF can't count it. Draft it again.`, note: W.lifetimeCap(capped.tool), code: "lifetime_cap" };
    }
    // An amount capped over the taf's whole life, across every item, is a total nobody agreed to (Eval 43674391351:
    // a lifetime sum(amount) of 1,000,000 on refund_charge beside the agreed €5 a call and €50 a day). The rules have
    // each call's limit and a day's total; a budget, if an owner ever asks for one, is a feature of its own. Never signed.
    const budget = lifetimeSumOnWrite(grant, draft.cards);
    if (budget) {
      return { outcome: "answered", answer: `failed: the draft caps "${budget.tool}"'s ${budget.quantity} at ${budget.limit} over its whole life, across every item: a lifetime total isn't something the owner agreed to. Its limits are each call's and the day's total, as agreed: no lifetime total on an amount. Draft it again without it.`, note: W.lifetimeSum(budget.tool), code: "lifetime_sum" };
    }
    // A tool's amount over a day is counted once across the draft's Cards (Eval 43666453786: a held per-refund Card also
    // got a day's total, so past it the refund waited for the owner where the rules said refuse). Two day totals of the
    // same amount on one tool make the stricter one decide past the limit, whatever was agreed. Never signed.
    const twice = dayTotalTwice(grant, draft.cards);
    if (twice) {
      return { outcome: "answered", answer: `failed: the draft counts "${twice.tool}"'s ${twice.quantity} over a day on two Cards: ${twice.first.limit} on one whose on_exceed is "${twice.first.onExceed}", and ${twice.second.limit} on one whose on_exceed is "${twice.second.onExceed}". A day's total appears once, at the agreed amount, on the Card whose on_exceed is the agreed answer; a held per-action limit's Card has no day's total. Draft it again.`, note: W.dayTotalTwice(twice.tool), code: "day_total_twice" };
    }
    // A write with an amount limit is limited by its amounts, as agreed: a count of its calls beside them is a limit
    // nobody set (Evals 10 to 12: a refund Card also held past 1,000 refunds a day, so 1,001 one-cent refunds under the
    // agreed cap would wait for the owner). Never signed. A count of 0 held (asking each time) is kept, and so is the owner's
    // limit for each item, in its one shape; any other count per item is one nobody set (a live test's "1 a day for each
    // payment intent" on a money tool).
    const counted = countOnAmountWrite(grant, draft.cards);
    if (counted) {
      const each = counted.per ? ` for each ${counted.per}` : "";
      return { outcome: "answered", answer: `failed: the draft counts "${counted.tool}"'s calls (${counted.limit} per ${counted.window}${each}) beside its amount limits. A write with an amount limit has only the agreed amounts, each call's and the day's total: no count of its calls. Draft it again without the count.`, note: W.countOnAmount(counted.tool), code: "count_on_amount" };
    }
  }
  // What happens past each limit is what the owner agreed (rules.ts): the newest rules they agreed in this conversation,
  // as data on the rules notice, never Coach's words alone (Evals 5 to 9: a refused day's total drafted as held).
  const ruled = await rulesChecked(grant, others, draft.cards, ctx);
  if (ruled) return ruled;
  return { draft: draft as NonNullable<typeof draft> & { version: string } };
}

/**
 * The first write tool of the connection that the draft's Cards limit by its amount (a per-call cap on a sum, or a
 * sum over any window) and also count by its calls (count, a limit above 0), on the same Card or another. A count of 0
 * (each call asks the owner, as on demand) is kept, as is a count on a read. A count per item is kept only in the
 * shape of the owner's limit for each item ("one invoice per order, ever"): lifetime or rolling_7d, per the tool's own
 * resource_argument. KIFF's own count, the only total of a held Card, is never the owner's and is kept (kiffsOwnCount).
 * The tool is named as the connection titles it, with the count's limit, window and item.
 */
export function countOnAmountWrite(grant: ApprovalGrant, cards: unknown[]): { tool: string; limit: number; window: string; per?: string } | undefined {
  const fold = (v: unknown) => (typeof v === "string" ? v.trim().toLowerCase() : "");
  const writes = (grant.request.tools ?? []).filter((t) => !t.read_only && !t.setup_reads);
  type Aggregate = { quantity?: unknown; window?: unknown; per?: unknown; limit?: unknown };
  type Grant = { action?: unknown; per_action_max?: unknown };
  for (const t of writes) {
    const name = fold(t.name || t.tool);
    if (!name) continue;
    let amount = false;
    let count: { limit: number; window: string; per?: string } | undefined;
    const resource = fold((t as { resource_argument?: unknown }).resource_argument);
    // The owner's limit for each item, as the rules draft it: never another item, or a day's count per item.
    const owners = (a: Aggregate) => !!resource && fold(a.per) === resource && ["lifetime", "rolling_7d"].includes(fold(a.window));
    for (const raw of cards) {
      const card = raw as { grants?: unknown; aggregates?: unknown };
      const grants = (Array.isArray(card.grants) ? card.grants : []) as Grant[];
      const mine = grants.filter((g) => fold(g.action) === name);
      if (!mine.length) continue;
      const aggregates = (Array.isArray(card.aggregates) ? card.aggregates : []) as Aggregate[];
      if (mine.some((g) => Array.isArray(g.per_action_max) && (g.per_action_max as Aggregate[]).some((m) => fold(m.quantity).startsWith("sum(")))) amount = true;
      for (const a of aggregates) {
        if (kiffsOwnCount(cards, card, a)) continue;
        if (fold(a.quantity).startsWith("sum(")) amount = true;
        const limit = typeof a.limit === "number" ? a.limit : 0;
        if (count || fold(a.quantity) !== "count" || limit <= 0) continue;
        if (!fold(a.per)) count = { limit, window: fold(a.window) || "window" };
        else if (!owners(a)) count = { limit, window: fold(a.window) || "window", per: (a.per as string).trim() };
      }
    }
    if (amount && count) return { tool: t.title || t.name || t.tool || name, ...count };
  }
  return undefined;
}

/**
 * The first tool whose amount the draft's Cards count over a calendar day more than once (the same sum(...) quantity,
 * with no per), across its Cards or on one, with both limits and what each Card does past them. A day's total on the
 * amount beside a count a day, or beside an hour's or a lifetime total, is a different total and is kept. The tool is
 * named as the connection titles it when it does.
 */
export function dayTotalTwice(grant: ApprovalGrant, cards: unknown[]): { tool: string; quantity: string; first: { limit: number; onExceed: string }; second: { limit: number; onExceed: string } } | undefined {
  const fold = (v: unknown) => (typeof v === "string" ? v.trim().toLowerCase() : "");
  const seen = new Map<string, { limit: number; onExceed: string }>();
  for (const raw of cards) {
    const card = raw as { grants?: unknown; aggregates?: unknown; on_exceed?: unknown };
    if (!Array.isArray(card.grants) || !Array.isArray(card.aggregates)) continue;
    const onExceed = card.on_exceed === "refuse" ? "refuse" : "owner";
    for (const a of card.aggregates as Array<{ quantity?: unknown; window?: unknown; per?: unknown; limit?: unknown }>) {
      const quantity = fold(a.quantity);
      if (!quantity.startsWith("sum(") || fold(a.window) !== "calendar_day" || fold(a.per)) continue;
      const limit = typeof a.limit === "number" ? a.limit : 0;
      for (const g of card.grants as Array<{ action?: unknown }>) {
        const action = fold(g.action);
        if (!action) continue;
        const key = `${action}|${quantity}`;
        const first = seen.get(key);
        if (first) {
          const t = (grant.request.tools ?? []).find((x) => fold(x.name) === action);
          return { tool: t?.title || (g.action as string).trim(), quantity: quantity.slice(4, -1) || quantity, first, second: { limit, onExceed } };
        }
        seen.set(key, { limit, onExceed });
      }
    }
  }
  return undefined;
}

/**
 * A write tool of the connection that a Card counts over its whole life with no `per`: one count total on the tool
 * for every item, which stops it after the first (count, lifetime, no per). A count per item is the limit for each
 * item, and is kept; a sum over a lifetime is lifetimeSumOnWrite's. The first such tool, with its resource argument if the
 * request names one, so Coach knows what to count per.
 */
export function lifetimeCapOnWrite(grant: ApprovalGrant, cards: unknown[]): { tool: string; limit: number; resource?: string } | undefined {
  const fold = (v: unknown) => (typeof v === "string" ? v.trim().toLowerCase() : "");
  const writes = (grant.request.tools ?? []).filter((t) => !t.read_only && !t.setup_reads);
  for (const t of writes) {
    const name = fold(t.name || t.tool);
    if (!name) continue;
    for (const raw of cards) {
      const card = raw as { grants?: unknown; aggregates?: unknown };
      const actions = Array.isArray(card.grants) ? card.grants.map((g) => fold((g as { action?: unknown }).action)) : [];
      if (!actions.includes(name) || !Array.isArray(card.aggregates)) continue;
      for (const a of card.aggregates as Array<{ quantity?: unknown; window?: unknown; per?: unknown; limit?: unknown }>) {
        if (fold(a.quantity) === "count" && fold(a.window) === "lifetime" && !fold(a.per)) {
          const resource = (t as { resource_argument?: unknown }).resource_argument;
          return { tool: t.title || t.name || t.tool || name, limit: typeof a.limit === "number" ? a.limit : 0,
            ...(typeof resource === "string" && resource.trim() ? { resource: resource.trim() } : {}) };
        }
      }
    }
  }
  return undefined;
}

/**
 * A write tool of the connection whose amount a Card sums over its whole life with no `per`: one total across every
 * item that the owner never agreed to, since the rules give each call's limit and a day's total (Eval 43674391351).
 * A sum per item (`per` its argument) is kept, as is a lifetime count on a read. The first such tool, with its total.
 */
export function lifetimeSumOnWrite(grant: ApprovalGrant, cards: unknown[]): { tool: string; quantity: string; limit: number } | undefined {
  const fold = (v: unknown) => (typeof v === "string" ? v.trim().toLowerCase() : "");
  const writes = (grant.request.tools ?? []).filter((t) => !t.read_only && !t.setup_reads);
  for (const t of writes) {
    const name = fold(t.name || t.tool);
    if (!name) continue;
    for (const raw of cards) {
      const card = raw as { grants?: unknown; aggregates?: unknown };
      const actions = Array.isArray(card.grants) ? card.grants.map((g) => fold((g as { action?: unknown }).action)) : [];
      if (!actions.includes(name) || !Array.isArray(card.aggregates)) continue;
      for (const a of card.aggregates as Array<{ quantity?: unknown; window?: unknown; per?: unknown; limit?: unknown }>) {
        if (fold(a.quantity).startsWith("sum(") && fold(a.window) === "lifetime" && !fold(a.per)) {
          return { tool: t.title || t.name || t.tool || name, quantity: fold(a.quantity), limit: typeof a.limit === "number" ? a.limit : 0 };
        }
      }
    }
  }
  return undefined;
}

/**
 * An app set up on demand asks the owner for every change: a Card drafted over its tools lets
 * no write or money tool run without asking. KIFF's draft shows each Card's actions, what
 * happens past its limits (`on_exceed`) and its totals; a write tool of the connection is held
 * for the owner only when every Card covering it holds past 0 calls. The first tool the draft
 * lets run outright is named, so Coach redrafts; a tool no Card covers can't run at all.
 */
export function onDemandLetsRun(grant: ApprovalGrant, cards: unknown[]): string | undefined {
  if (grant.on_demand !== true) return undefined;
  const writes = (grant.request.tools ?? []).filter((t) => !t.read_only && !t.setup_reads);
  // KIFF matches a Card's action to a tool ignoring case, and trims a quantity: so does this.
  const fold = (v: unknown) => (typeof v === "string" ? v.trim().toLowerCase() : "");
  for (const t of writes) {
    const name = fold(t.name || t.tool);
    if (!name) continue;
    for (const raw of cards) {
      const card = raw as { on_exceed?: unknown; grants?: unknown; aggregates?: unknown };
      const actions = Array.isArray(card.grants) ? card.grants.map((g) => fold((g as { action?: unknown }).action)) : [];
      if (!actions.includes(name)) continue;
      const asks = card.on_exceed === "owner" && Array.isArray(card.aggregates)
        && card.aggregates.some((a) => fold((a as { quantity?: unknown }).quantity) === "count" && (a as { limit?: unknown }).limit === 0);
      if (!asks) return t.title || t.name || t.tool || name;
    }
  }
  return undefined;
}

/**
 * The step a tap asks once another app the draft's Cards cover is still waiting: a combined step (the tools or the key with
 * the Card) becomes this app's own step without the Card, as `decide` falls through on `waitsFor` (the Card comes with the
 * last app's approval). Otherwise `step` as it is. The chat's read marks its card by this, so the card never says it signs
 * the Card while its tap asks for the connection alone: the phone refuses a step that isn't its mark and reads again, and
 * read after read the owner tapped the same card (17 Nov, five taps in 45 s).
 */
export function stepWithWaiting(step: SignStep, grant: ApprovalGrant, grants: ApprovalGrant[], cards: unknown[] | undefined, ask: { draft?: string }): SignStep | undefined {
  if (step !== "tools_and_cards" && step !== "key_and_cards") return step;
  if (!cards || !appsWaiting(grant, sameTafOf(grant, grants), cards).length) return step;
  return stepFor(grant, { draft: ask.draft, can: [] }, {});
}

/**
 * The first tool of the grant no Card covers, once the draft is confirmed: neither the draft's Cards
 * nor the agent's Cards in force (inForce, as actionsInForce reads them) name it. KIFF refuses every
 * call no Card covers, so the taf couldn't use it, and would ask its owner for a Card it shouldn't
 * need (Eval 42781271564). Setup's own reads (setup_reads) are on its reader Card, never the taf's, and a
 * tool listed with no KIFF name is left out.
 */
/**
 * The first action a draft's Cards allow that neither this grant nor another grant of the same taf gives it (its tools,
 * or what it had connected before an addition, by their names in KIFF), and that no Card in force allows already.
 */
/** An app of the same taf whose tools still wait for their own approval: its grant, and its name for people and Coach. */
export interface WaitingApp { grant: string; name: string }

/**
 * The Card draft a waiting app's approval must carry, kept against its grant when tafs cloud answers `other_app_first`:
 * that app's connection and the Card go under one approval, so its approval shown without the draft would split them
 * (Eval 20b, intros run 1: the mailbox's key approved alone, then the Card on a third approval).
 */
export interface WaitingDraft { draft: string; name: string; at: string }
export const waitingDraftKey = (tenant: string, grant: string) => `${tenant}#waiting-draft#${grant}`;
/** How long the note holds: past it, a draftless approval of that app is shown as any other. */
export const WAITING_DRAFT_KEPT_MS = 24 * 3600_000;

/**
 * The waiting-draft note for a grant while its draft is still a draft in KIFF: once the draft is confirmed (the waiting
 * app approved with it, the case working as meant), revoked or gone, the note is removed and nothing is refused, so a
 * later Sign in again or tools addition of that app is shown as any other. A draft that can't be read refuses nothing:
 * never refused on a guess. `read` is KIFF's draft read (undefined for a draft KIFF doesn't have).
 */
export async function liveWaitingDraft(store: CoachStore, tenant: string, grant: string, read: (draft: string) => Promise<{ status: string } | undefined>): Promise<WaitingDraft | undefined> {
  const key = waitingDraftKey(tenant, grant);
  const n = (await store.get(key).catch(() => undefined)) as Partial<WaitingDraft> | null | undefined;
  if (!n || typeof n.draft !== "string" || !n.draft || typeof n.name !== "string" || !n.name) return undefined;
  let d: { status: string } | undefined;
  try { d = await read(n.draft); } catch { return undefined; }
  if (!d || d.status !== "draft") {
    await store.remove(key).catch(() => undefined);
    return undefined;
  }
  return { draft: n.draft, name: n.name, at: typeof n.at === "string" ? n.at : "" };
}

/**
 * What Coach hears for an approval card shown for an app whose approval must carry a waiting draft, and was shown with
 * none: undefined for any other card (one with a draft, a redraft's included, or an app with no waiting draft). The
 * card is answered on the chat's read, before the person sees Approve, so the key or tools can't be approved apart
 * from the Card, however Coach read `other_app_first`.
 */
export function splitApproval(card: { grant?: unknown; draft?: unknown }, note: unknown): string | undefined {
  if (typeof card.grant !== "string" || !card.grant || (typeof card.draft === "string" && card.draft)) return undefined;
  const n = note as Partial<WaitingDraft> | null | undefined;
  if (!n || typeof n.draft !== "string" || !n.draft || typeof n.name !== "string" || !n.name) return undefined;
  return `failed: ${n.name}'s approval was shown without the Card draft ${n.draft}, which waits for it: ${n.name}'s connection and the Card go under one approval, so this one is never shown to the owner. ${REFUSAL_CLOSED} Show ${n.name}'s approval again with draft ${n.draft}: it carries the Card. Show it once.`;
}

/**
 * The apps of the same taf, other than `grant`'s, that a draft's Cards cover and that aren't connected yet: their tools
 * still wait for their own approval. In the order the grants come, each named so it differs from `grant`'s app and from
 * the others (waitingNames): two apps on one host are never both "the host".
 */
export function appsWaiting(grant: ApprovalGrant, others: ApprovalGrant[], cards: unknown[]): WaitingApp[] {
  const fold = (v: unknown) => (typeof v === "string" ? v.trim().toLowerCase() : "");
  const covered = new Set<string>();
  for (const raw of cards) {
    const card = raw as { grants?: unknown };
    if (Array.isArray(card.grants)) for (const g of card.grants) covered.add(fold((g as { action?: unknown }).action));
  }
  const waiting = others.filter((g) => g.id !== grant.id && g.status !== "connected" && (g.request.tools ?? []).some((t) => covered.has(fold(t.name))));
  return waitingNames(grant, waiting);
}

/** The app as people know it, for telling two apps apart: planque's title for the connection, else its app's name. */
function appLabel(g: ApprovalGrant): string {
  const title = typeof g.title === "string" ? g.title.replace(/\s+/g, " ").trim() : "";
  return title || appNameOf(g) || vendorOf(g);
}

/** A grant's tools as people read them, at most three: each one's title, else its vendor name, else its name in KIFF. */
function toolsLabel(g: ApprovalGrant): string {
  const names = (g.request.tools ?? []).filter((t) => t.setup_reads !== true)
    .map((t) => [t.title, t.tool, t.name].map((x) => (typeof x === "string" ? x.trim() : "")).find((x) => x) ?? "").filter((x) => x);
  const unique = [...new Set(names)];
  return unique.length > 3 ? `${unique.slice(0, 3).join(", ")}, …` : unique.join(", ");
}

/** This app's own name beside the apps that wait: its label, with its tools when another app of the taf has that label too. */
function ownName(grant: ApprovalGrant, others: ApprovalGrant[]): string {
  const label = appLabel(grant);
  const clash = others.some((g) => g.id !== grant.id && appLabel(g).toLowerCase() === label.toLowerCase());
  const tools = clash ? toolsLabel(grant) : "";
  return tools ? `${label} (${tools})` : label;
}

/**
 * Each waiting app's name: its label (appLabel), with its tools beside it when that label is `grant`'s own or another
 * waiting app's, and its catalog entry when even that doesn't tell them apart.
 */
function waitingNames(grant: ApprovalGrant, waiting: ApprovalGrant[]): WaitingApp[] {
  const key = (s: string) => s.toLowerCase();
  const labels = waiting.map(appLabel);
  const own = key(appLabel(grant));
  const named = waiting.map((g, i) => {
    const label = labels[i]!;
    const clash = key(label) === own || labels.some((l, j) => j !== i && key(l) === key(label));
    const tools = clash ? toolsLabel(g) : "";
    return tools ? `${label} (${tools})` : label;
  });
  return waiting.map((g, i) => {
    const entry = typeof (g as { entry?: unknown }).entry === "string" ? ((g as { entry?: string }).entry ?? "").trim() : "";
    const still = named.some((n, j) => j !== i && key(n) === key(named[i]!)) || key(named[i]!) === own;
    return { grant: g.id, name: still && entry ? `${named[i]} [${entry}]` : named[i]! };
  });
}

/**
 * The first Card action that is the vendor's own name of a tool planque renamed for this taf, while the draft and the Cards
 * in force leave the tool's registered name uncovered: with its name taken in KIFF, planque gave the tool a free one
 * (`request.tools[].name`), and only that name is this taf's. The vendor name (`tool`) is another connection's in KIFF, so a
 * Card over it never covers this taf's tool, whatever else holds that name. With the registered name.
 */
export function vendorNameOnCard(grant: ApprovalGrant, others: ApprovalGrant[], cards: unknown[], inForce: Set<string>): { action: string; name: string } | undefined {
  const fold = (v: unknown) => (typeof v === "string" ? v.trim().toLowerCase() : "");
  const covered = new Set(inForce);
  const actions: string[] = [];
  for (const raw of cards) {
    const card = raw as { grants?: unknown };
    if (!Array.isArray(card.grants)) continue;
    for (const g of card.grants) {
      const action = (g as { action?: unknown }).action;
      if (typeof action === "string" && fold(action)) { covered.add(fold(action)); actions.push(action); }
    }
  }
  // Each renamed tool's vendor name, to its registered name, the grant being approved first.
  const renamed = new Map<string, string>();
  for (const g of [grant, ...others.filter((o) => o.id !== grant.id)]) {
    for (const t of g.request.tools ?? []) {
      const vendor = fold(t.tool), name = fold(t.name);
      if (vendor && name && vendor !== name && !renamed.has(vendor)) renamed.set(vendor, t.name!.trim());
    }
  }
  for (const action of actions) {
    const name = renamed.get(fold(action));
    if (name && !covered.has(fold(name))) return { action, name };
  }
  return undefined;
}

export function strayAction(grant: ApprovalGrant, others: ApprovalGrant[], cards: unknown[], inForce: Set<string>): string | undefined {
  const fold = (v: unknown) => (typeof v === "string" ? v.trim().toLowerCase() : "");
  const given = new Set(inForce);
  for (const g of [grant, ...others]) {
    for (const t of [...(g.request.tools ?? []), ...((g.connected ?? []) as Array<{ name?: unknown }>)]) {
      const name = fold((t as { name?: unknown }).name);
      if (name) given.add(name);
    }
  }
  for (const raw of cards) {
    const card = raw as { grants?: unknown };
    if (!Array.isArray(card.grants)) continue;
    for (const g of card.grants) {
      const action = fold((g as { action?: unknown }).action);
      if (action && !given.has(action)) return (g as { action: string }).action;
    }
  }
  return undefined;
}

/**
 * The first tool of the same taf's other connected apps (others, as sameTafOf gives them) that neither the draft's Cards
 * nor the agent's Cards in force cover, with that app's vendor: the taf holds those tools too. An app still waiting for its
 * own approval is left to appsWaiting; its tools aren't held yet.
 */
export function uncoveredElsewhere(grant: ApprovalGrant, others: ApprovalGrant[], cards: unknown[], inForce: Set<string>): { tool: string; vendor: string } | undefined {
  for (const g of others) {
    if (g.id === grant.id || g.status !== "connected") continue;
    const tool = uncoveredTool(g, cards, inForce);
    if (tool) return { tool, vendor: vendorOf(g) };
  }
  return undefined;
}

export function uncoveredTool(grant: ApprovalGrant, cards: unknown[], inForce: Set<string>): string | undefined {
  const fold = (v: unknown) => (typeof v === "string" ? v.trim().toLowerCase() : "");
  const covered = new Set(inForce);
  for (const raw of cards) {
    const card = raw as { grants?: unknown };
    if (Array.isArray(card.grants)) for (const g of card.grants) covered.add(fold((g as { action?: unknown }).action));
  }
  for (const t of grant.request.tools ?? []) {
    if (t.setup_reads) continue;
    // By its name in KIFF, which is what a Card covers. A tool with none is left out, never compared by the
    // vendor's own name (tool): planque names every tool when it's requested.
    const name = fold(t.name);
    if (name && !covered.has(name)) return t.title || t.name || name;
  }
  return undefined;
}

/** What the phone sends with Approve: the card's grant and what it rendered. */
export interface ApprovalAsk {
  grant: string;
  shown_digest?: string;
  draft?: string;
  shown_draft?: string;
  /** KIFF's words for the connection's profile, as the phone read and checked them. */
  words?: { profile_sha256: string; words_sha256: string };
  /**
   * The steps this phone can sign beyond connect and cards (`key_and_cards`): tafs cloud never answers an app a
   * step it didn't say it can act on, so an app from before gets the two steps, the flag on or off.
   */
  can?: string[];
}

/** One message to sign, and the call it goes into. */
export interface Signing {
  id: string;
  /**
   * `key_and_cards` (docs/secure-connection.md): the key to the connection server and the Card's confirm to KIFF
   * under one fingerprint. The phone builds both requests itself, with one `signed_at`, and signs `kiff.requests.v1`
   * over their two messages' hashes; `message` is then empty, and `scheme` says so.
   */
  step: "connect" | "cards" | "key_and_cards" | "tools_and_cards";
  /** For `key_and_cards`: what the phone signs, built by it from the two requests it makes. */
  scheme?: "kiff.requests.v1";
  /** The exact bytes the device's key signs, under biometrics. */
  message: string;
  /** What the biometric prompt says. */
  reason: string;
  /**
   * Which key the call takes, a hint for the app (kiff/tafs#494): "window", the device key inside an open
   * approval window does (connecting an app; a draft KIFF says widens nothing), so a tap; "critical", the
   * approval key behind the fingerprint, every time. The service the call goes to decides for itself, and
   * the report's check here refuses a key short of it; the hint only saves the app a round trip.
   */
  key: "window" | "critical";
  /** The connect step: planque's approve, its body as the phone sends it. */
  planque?: { grant: string; body: { digest: string; kiff_profile?: { profile_sha256: string; words_sha256: string } } };
  /** The cards step: KIFF's draft to confirm, at this version. */
  kiff?: { draft: string; version: string };
}

export type Decision =
  | { outcome: "answered"; answer: string; note: string; code?: string }
  | { outcome: "read_again"; note: string; code?: string }
  | { outcome: "sign"; ask: Signing };

/** What a pending ask keeps, so the report is checked against what was asked. */
interface Pending {
  step: "connect" | "cards" | "key_and_cards" | "tools_and_cards";
  grant: string;
  vendor: string;
  draft?: string;
  version?: string;
  made: string;
  /** The key the ask takes (Signing.key); an ask from before the window reads as "window". */
  key?: "window" | "critical";
}

/** An ask waits this long for the phone's report. */
export const ASK_KEPT_MS = 20 * 60_000;

/** The words the apps show, all from here: tafs cloud's catalog, in the approving request's language (words/*.ts). */
/**
 * The end of a connect step's answer when it worked by design and the taf's Card is next (approval.ts): Coach shows the
 * approval again for the Card alone, and the person reads tafs cloud's "Gmail is connected. Next, its Card." instead of a failure.
 */
export const CARD_NEXT = "its Card is next: show the approval again for its Card alone";

export function approvalWords(lang: Lang = "en") {
  const w = wordsIn(lang);
  return {
    outOfDate: w.approval_out_of_date,
    wordsUnread: w.approval_words_unread,
    alreadyApproved: w.approval_already,
    nothingToApprove: w.approval_nothing_new,
    digestMismatch: w.approval_digest_mismatch,
    noGrant: w.approval_no_grant,
    removedTafsConnection: w.approval_removed_tafs_connection,
    otherTafsConnection: w.approval_other_tafs_connection,
    earlierConnection: w.approval_earlier_connection,
    cardsNotIssued: w.approval_cards_not_issued,
    connectedCardNext: w.approval_connected_card_next,
    otherAppFirst: w.approval_other_app_first,
    withItsCard: w.approval_with_its_card,
    draftIs: (status: string) => w.approval_draft_is(w.approval_status(status)),
    noDraft: w.approval_no_draft,
    cardsUnread: w.approval_cards_unread,
    uncovered: w.approval_uncovered,
    stray: w.approval_stray,
    noCard: w.approval_no_card,
    registeredName: w.approval_registered_name,
    lifetimeCap: w.approval_lifetime_cap,
    lifetimeSum: w.approval_lifetime_sum,
    dayTotalTwice: w.approval_day_total_twice,
    countOnAmount: w.approval_count_on_amount,
    rulesOnExceed: w.approval_rules_on_exceed,
    rulesMissing: w.approval_rules_missing,
    rulesUnpinned: w.approval_rules_unpinned,
    rulesFigures: w.approval_rules_figures,
    rulesUnsaid: w.approval_rules_unsaid,
    rulesUnagreed: w.approval_rules_unagreed,
    rulesRequired: w.approval_rules_required,
    onDemandLetsRun: w.approval_on_demand,
    criticalKey: w.approval_critical_key,
    driveKey: w.approval_drive_key,
    windowClosed: w.approval_window_closed,
    signMissing: w.approval_sign_missing,
    signBoth: w.approval_sign_both,
    signInvalid: w.approval_sign_invalid,
    signViewOnly: w.approval_sign_view_only,
    signSession: w.approval_sign_session,
    signPasskey: w.approval_sign_passkey,
    signClock: w.approval_sign_clock,
    signTaken: w.approval_sign_taken,
    signUnchecked: w.approval_sign_unchecked,
    signInClosed: w.approval_sign_in_closed,
    noCode: w.approval_no_code,
    // What came back, in the owner's words, never planque's status: already signed in (its actions come next), or nothing opened.
    // A connection that takes no approval now, in the owner's words, never planque's status: signed in already, or past approving.
    notApprovable: (vendor: string, status: string) => (status === "listed" || status === "signed_in" ? w.approval_listed_already(vendor) : w.approval_not_approvable(vendor)),
    cameBack: (vendor: string, status: string) => (status === "signed_in" || status === "listed" ? w.approval_came_back_signed_in(vendor) : w.approval_came_back(vendor)),
    confirmFirst: w.approval_confirm_first,
    notConnected: (vendor: string) => w.approval_not_connected(vendor),
    failed: (why?: string) => w.approval_failed(why ?? w.approval_failed_default),
    reasonConnect: (vendor: string, taf?: string) => (taf ? w.approval_reason_connect(vendor, taf) : w.approval_reason_connect_unnamed(vendor)),
    reasonCard: (taf?: string) => (taf ? w.approval_reason_card(taf) : w.approval_reason_card_unnamed),
  };
}

/** The words in English: what a test or a caller without a request reads. */
export const WORDS = approvalWords("en");

export class ApprovalError extends Error {
  /** `code`, when set, is what the app acts on beside the words: critical_key_required, window_required. */
  constructor(readonly status: number, message: string, readonly code?: string) {
    super(message);
  }
}

const ID = /^[A-Za-z0-9_.:-]{1,128}$/;
const HEX64 = /^[0-9a-f]{64}$/;

const keyOf = (tenant: string, taf: string, component: string, ask: string) => `${tenant}#approval-ask#${taf}#${component}#${ask}`;

/** Where tafs cloud keeps that a grant is connected, for a later card that names it (docs/secure-connection.md). */
export const connectedKey = (tenant: string, grant: string) => `${tenant}#connected#${grant}`;

/** How long a connected grant's note is kept: a setup's later cards come within it. */
export const CONNECTED_KEPT_MS = 30 * 24 * 3600_000;

/** What a later card says of a connection already made: its vendor, and whether every tool is a read. */
export interface Connected { vendor: string; read_only: boolean }

/**
 * Notes that the connection server says this grant is connected, so a later card that names it says only that,
 * and never repeats the connection's text. Never fails what it rides on: a note not kept is a card as before.
 */
async function noteConnected(store: CoachStore, tenant: string, grant: ApprovalGrant, now: Date): Promise<void> {
  // Connected only: one signed in whose tools wait (`listed`), or one asking for more tools again (`pending`), still asks for
  // something, so a card for it shows what it asks, never "connected". Such a grant's note goes.
  if (grant.status !== "connected") {
    await store.remove(connectedKey(tenant, grant.id)).catch(() => undefined);
    return;
  }
  const note: Connected = { vendor: vendorOf(grant), read_only: readsOnly(grant) };
  await store.put(connectedKey(tenant, grant.id), note, new Date(now.getTime() + CONNECTED_KEPT_MS)).catch(() => undefined);
}

/** The grants planque shows this phone, read with the proof the phone signed and tafs cloud forwards unchanged. */
export async function readGrants(base: string, tenant: string, proof: string, net: Fetch): Promise<ApprovalGrant[]> {
  if (!proof || proof.length > 4096) throw new ApprovalError(503, "Connect this device to read its connected apps");
  let res: Response;
  try {
    res = await net(`${base.replace(/\/$/, "")}/v1/accounts/${encodeURIComponent(tenant)}/grants`, { headers: { accept: "application/json", "Planque-Device-Signature": proof } });
  } catch {
    throw new ApprovalError(502, "Connected apps could not be read; try again");
  }
  if (!res.ok) throw new ApprovalError(502, `Connected apps could not be read (planque answered ${res.status}); try again`);
  const out = (await res.json().catch(() => ({}))) as { grants?: unknown };
  if (!Array.isArray(out.grants)) throw new ApprovalError(502, "Connected apps returned an unreadable list");
  return out.grants.filter((g): g is ApprovalGrant => !!g && typeof g === "object" && typeof (g as ApprovalGrant).id === "string" && typeof (g as ApprovalGrant).status === "string" && !!(g as ApprovalGrant).request);
}

/** The vendor as the owner knows it: planque's name for it, else its server. */
/** The same taf's other live connections: a Card may cover their tools too (two apps, one Card). */
function sameTafOf(grant: ApprovalGrant, grants: ApprovalGrant[]): ApprovalGrant[] {
  return grants.filter((g) => g.id !== grant.id && g.request.agent && g.request.agent === grant.request.agent && !["failed", "disconnected", "revoked"].includes(g.status));
}

/** What Coach hears of an app whose tools still wait, once a Card covering them is issued: which app, by name and grant. */
function waitsLine(w: WaitingApp): string {
  return `${w.name}'s tools (grant ${w.grant}) still wait for their own approval: show ${w.name}'s approval, as its own connection`;
}

function vendorOf(g: ApprovalGrant): string {
  return g.vendor || g.request.server || "the vendor";
}

/**
 * The taf's name for the phone's signing prompt: tafs cloud's taf by that agent first; then, for a taf still
 * being set up and not listed yet, the names it was given (planque's `agent_name`, a draft's label). Never the agent id.
 */
async function nameOf(ctx: DecideContext, agent: string | undefined, ...given: Array<string | undefined>): Promise<string | undefined> {
  const name = await ctx.tafName?.(agent).catch(() => undefined);
  return [name, ...given].map((n) => (typeof n === "string" ? n.trim() : "")).find((n) => n !== "") || undefined;
}

/**
 * The app as the owner knows it ("Stripe" for mcp.stripe.com), for the phone's signing prompt: its server's name, else
 * the grant's vendor. A managed app's server is the sign-in partner's, never the app: its name is the grant's vendor
 * ("Gmail"), as planque keeps it, so the partner's name never reaches the prompt.
 */
function appNameOf(g: ApprovalGrant): string {
  if (g.request.kind === "connector") return typeof g.vendor === "string" ? g.vendor.trim() : "";
  const server = typeof g.request.server === "string" ? g.request.server.trim() : "";
  return vendorName(server) || (server && !server.includes("://") ? vendorName(`https://${server}/`) : "") || g.vendor || "";
}

/** A grant that can be approved and signed in: new, or one its vendor signed out. */
const APPROVABLE = new Set(["pending", "approved", "signed_in", "needs_reconnect"]);

/** Reads what the phone sends with Approve, refusing what isn't an ask. */
export function askOf(body: Record<string, unknown>): ApprovalAsk {
  const grant = typeof body.grant === "string" ? body.grant.trim() : "";
  if (!ID.test(grant)) throw new ApprovalError(400, "send the card's grant");
  const str = (v: unknown) => (typeof v === "string" && v.length <= 256 ? v : undefined);
  const words = body.words && typeof body.words === "object" ? (body.words as Record<string, unknown>) : undefined;
  const ask: ApprovalAsk = { grant };
  if (str(body.shown_digest)) ask.shown_digest = str(body.shown_digest);
  if (str(body.draft)) ask.draft = str(body.draft);
  if (str(body.shown_draft)) ask.shown_draft = str(body.shown_draft);
  if (words && HEX64.test(String(words.profile_sha256)) && HEX64.test(String(words.words_sha256))) {
    ask.words = { profile_sha256: String(words.profile_sha256), words_sha256: String(words.words_sha256) };
  }
  if (Array.isArray(body.can)) ask.can = body.can.filter((x): x is string => typeof x === "string" && x.length <= 32).slice(0, 8);
  return ask;
}

/**
 * The draft's Cards against the rules the owner agreed in the conversation (rules.ts). With no conversation to read (a
 * caller that draws no card), nothing to check. A conversation that can't be read isn't checked on trust: read again.
 * With no rules shown, a draft is approved as before and logged, unless the rules are required (TAFS_RULES_REQUIRED).
 */
async function rulesChecked(grant: ApprovalGrant, others: ApprovalGrant[], cards: unknown[], ctx: DecideContext): Promise<Decision & { code?: string } | undefined> {
  if (!ctx.rulesAgreement) return undefined;
  const W = approvalWords(ctx.lang);
  let agreement: RulesAgreement | undefined;
  try {
    agreement = await ctx.rulesAgreement();
  } catch {
    agreement = undefined;
  }
  if (!agreement) return { outcome: "read_again", note: W.cardsUnread, code: "rules_unread" };
  if (agreement.state === "none") {
    if (ctx.rulesRequired) {
      return { outcome: "answered", answer: `failed: no rules the owner agreed are in this conversation, so what happens past each limit isn't theirs. Show the rules as a notice with rules [{tool, over}] and a confirm with agrees naming it, and draft once the owner tapped agree.`, note: W.rulesRequired, code: "rules_required" };
    }
    console.log("approval rules: none agreed in the conversation");
    return undefined;
  }
  if (agreement.state === "unagreed") {
    return { outcome: "answered", answer: `failed: the rules notice "${agreement.notice}" isn't agreed yet, so the owner is looking at rules they haven't agreed to. Show its confirm with agrees "${agreement.notice}", and draft once the owner tapped agree.`, note: W.rulesUnagreed, code: "rules_unagreed" };
  }
  // A line the owner agreed as kept by the connection must be: each tool it names is pinned in its grant, or nothing is
  // signed, and Coach hears which tool, before any signing request reaches the owner.
  const unpinned = unpinnedConnection([grant, ...others], agreement.connection);
  if (unpinned) {
    return { outcome: "answered", answer: `failed: the rules the owner agreed say the connection keeps "${unpinned.line}"${unpinned.tool ? ` for "${unpinned.tool}"` : ""}, but ${unpinned.tool ? `the connection doesn't pin "${unpinned.tool}"` : "the line names no tool"}. Pin it in the connection's request, or show the rules again with that line kept in its instructions, then draft it again.`, note: W.rulesUnpinned(unpinned.title), code: "rules_unpinned" };
  }
  const off = rulesMismatch([grant, ...others], cards, agreement.rules);
  if (!off) {
    // A limit the owner never agreed, in any window, is never signed (Eval 43927756622: an hour's €50 held, beside the
    // agreed €5 a refund held and €50 a day refused). Coach hears the one figure to drop, and to change nothing else.
    const extra = rulesUnnamedFigures([grant, ...others], cards, agreement.rules, ctx.rulesPerToTheLetter)[0];
    if (extra) {
      const per = (extra.window === "call" ? "each call" : extra.window ? `per ${extra.window}` : "with no window") + (extra.per ? ` for each ${extra.per}` : "");
      return { outcome: "answered", answer: `failed: the draft gives "${extra.tool}" a limit the owner never agreed: ${extra.limit} on ${extra.quantity}, ${per}. The rules they agreed name every limit it has, and a Card carries those alone: no other ceiling, total or count, even as a safety margin. Draft it again without that one, and change nothing else: no new limit, Card or tool in its place.`, note: W.rulesFigures(extra.tool), code: "rules_unagreed_figure" };
    }
    // A figure the notice's words don't say was never agreed: the owner agrees to what they read (Eval 44765665210,
    // invoices-3: "a draft invoice for each one", and the Card held 10 a day and 1 per order). The Cards stay as drafted.
    const unsaid = rulesUnsaid(agreement.rules, agreement.text);
    if (unsaid.length > 0) {
      console.log(`approval rules: figures the notice's words don't say: ${unsaid.join("; ")}`);
      return { outcome: "answered", answer: `failed: the rules notice "${agreement.notice}" the owner agreed doesn't say every limit its rules give, so the owner never read them: ${unsaid.join("; ")}. Show the rules again as a new notice with its confirm, one line per limit with its figure in digits as the Card holds it ("up to 10 a day", "1 per order, ever", "€5 each"), in the owner's language; once they tap agree, draft again with the same Cards, changing nothing in the draft.`, note: W.rulesUnsaid, code: "rules_unsaid" };
    }
    return undefined;
  }
  if (off.kind === "missing") {
    return { outcome: "answered", answer: `failed: the rules the owner agreed say nothing of "${off.tool}", a write the draft covers, so what happens past its limit was never agreed. Show the rules again with "${off.tool}" in them, then draft it again.`, note: W.rulesMissing(off.tool), code: "rules_missing" };
  }
  if (off.kind === "mixed") {
    return { outcome: "answered", answer: `failed: the rules the owner agreed ask past one of "${off.tool}"'s limits and refuse past another, and the draft puts both on one Card, which has one on_exceed. Draft it again with each on its own Card: on_exceed "owner" on the one whose limit asks, "refuse" on the one whose limit refuses.`, note: W.rulesOnExceed(off.tool), code: "rules_on_exceed" };
  }
  if (off.kind === "figure") {
    const each = off.per ? `a total for each ${off.per}, per ${off.window}` : off.window === "call" ? "each call" : "a day's total";
    const where = off.per ? `an aggregate {quantity: "${off.quantity}", window: "${off.window}", per: "${off.per}", limit: ${off.agreed}}`
      : off.window === "call" ? `per_action_max {quantity: "${off.quantity}", max: ${off.agreed}} on its grant` : `an aggregate {quantity: "${off.quantity}", window: "calendar_day", limit: ${off.agreed}} with no per`;
    const has = off.drafted === undefined ? "no Card covering it has that figure" : `a Card covering it has ${off.drafted}`;
    return { outcome: "answered", answer: `failed: the rules the owner agreed give "${off.tool}" ${off.agreed} as ${each} on ${off.quantity}, and ${has}. Draft it again with ${where}, and no other figure for it on any Card.`, note: W.rulesFigures(off.tool), code: "rules_figures" };
  }
  const said = off.over === "ask" ? "asks them past its limit" : "refuses past its limit";
  const want = off.over === "ask" ? "owner" : "refuse";
  return { outcome: "answered", answer: `failed: the rules the owner agreed say "${off.tool}" ${said}, and the draft's Card for it has on_exceed "${off.onExceed}". Draft it again with on_exceed "${want}" on that Card.`, note: W.rulesOnExceed(off.tool), code: "rules_on_exceed" };
}

export interface DecideContext {
  /**
   * Whether a key grant with its Card's draft is asked as one signature (`key_and_cards`): on only once KIFF and the
   * connection server both take `kiff.requests.v1`, as tafs cloud's `TAFS_KEY_AND_CARDS=on` says. Off, the key and
   * the Card are signed apart, as before.
   */
  keyAndCards?: boolean;
  /**
   * Whether a connected grant's added tools with the Card's draft over them are asked as one signature
   * (`tools_and_cards`): on once planque takes the paired approve and declares an addition's tools to KIFF while they wait.
   */
  toolsAndCards?: boolean;
  tenant: string;
  taf: string;
  component: string;
  token: string;
  proof: string;
  store: CoachStore;
  planqueBase: string;
  kiffBase?: string;
  fetch: Fetch;
  now: Date;
  newID: () => string;
  /** The approving request's language (its Accept-Language): the notes and the reasons in an answer are in it. */
  lang?: Lang;
  /** A taf's name by its KIFF agent, as its owner reads it: what the phone's signing prompt names, never the agent id. */
  tafName?: (agent?: string) => Promise<string | undefined>;
  /**
   * Whether an agent is a removed taf's (connections.ts `removedAgent`): archived in KIFF, or the other form of an
   * archived agent's name, and no live taf runs as it. Unset, or unable to read, it says no: nothing is refused on a
   * read that failed.
   */
  removedAgent?: (agent: string, askedOn?: string) => Promise<boolean>;
  /**
   * The Coach conversation the approval card is in. Once a connection's Card is approved in a conversation, no other
   * draft over it is approved in that conversation (approvedDraftKey): Coach never re-drafts after the owner signed.
   */
  session?: string;
  /**
   * When the conversation the card is in began (its session's start, from kiff/agents): a grant asked before it is a
   * half-made connection from an earlier conversation, resumed here only as `resumedRefusal` allows. Unset, no grant is
   * read as resumed (a caller with no conversation); a read that fails is read again, never trusted.
   */
  sessionStarted?: () => Promise<string | undefined>;
  /**
   * Whether this conversation's own connection request answered with the grant (`askedInSession`): within the clocks'
   * margin, a grant this conversation asked for is its own, not an earlier one. Unset, none is.
   */
  askedHere?: (grant: string) => Promise<boolean>;
  /**
   * The rules the owner agreed in the conversation the card is in (rules.ts), read when a draft is checked. Unset, the
   * draft isn't checked against rules (a caller that draws no card); a read that fails is read again, never trusted.
   */
  rulesAgreement?: () => Promise<RulesAgreement>;
  /** A draft with no agreed rules is refused too (TAFS_RULES_REQUIRED=on), once every Coach run sends them. */
  rulesRequired?: boolean;
  /** A total for each item no agreed figure names is refused on a rule that names none too (TAFS_RULES_PER=on), once Coach's rules carry them. */
  rulesPerToTheLetter?: boolean;
}

/** Where the Card a conversation approved for a connection is noted: one draft per connection, per conversation. */
export const approvedDraftKey = (tenant: string, taf: string, session: string, grant: string) => `${tenant}#approved-draft#${taf}#${session}#${grant}`;

/**
 * A draft over a connection whose Card this conversation approved already, as another draft: refused, never signed.
 * Coach found something wrong after the owner signed (a rule pointed at the wrong tool): it says what and stops, and
 * the Card changes later, as a version, never as a second draft in the same set-up (doubled Cards, another approval,
 * and a taf that never starts). The same draft again is the approval it was.
 */
async function redraftRefusal(ask: ApprovalAsk, vendor: string, ctx: DecideContext): Promise<Decision | undefined> {
  if (!ask.draft || !ctx.session) return undefined;
  const noted = (await ctx.store.get(approvedDraftKey(ctx.tenant, ctx.taf, ctx.session, ask.grant))) as { draft?: string } | undefined;
  if (!noted?.draft || noted.draft === ask.draft) return undefined;
  const W = approvalWords(ctx.lang);
  return { outcome: "answered", note: W.alreadyApproved, code: "approved_earlier",
    answer: `failed: ${vendor}'s Card was approved earlier in this conversation (draft ${noted.draft}), so no second draft over it is approved here. If something in it is wrong, tell the owner what, plainly, and stop: it changes later as a new version. Otherwise hand the taf over with the draft they approved.` };
}

/**
 * An approval that would sign over the wrong agent is refused before anything is signed: a grant asked for a taf
 * that was removed (its connection still held), or a Card drafted for another agent than the grant's. 14 Nov: one
 * fingerprint signed a removed taf's Gmail connection with a new taf's Card, and the new taf ran as the removed one.
 * Coach hears what to do instead: connect the app for this taf, reusing the sign-in on a new request.
 */
async function agentRefusal(ask: ApprovalAsk, grant: ApprovalGrant, vendor: string, ctx: DecideContext): Promise<Decision | undefined> {
  const W = approvalWords(ctx.lang);
  const agent = typeof grant.request.agent === "string" ? grant.request.agent : "";
  const askedOn = typeof (grant.request as { created_at?: unknown }).created_at === "string" ? (grant.request as { created_at: string }).created_at : undefined;
  if (agent && ctx.removedAgent && await ctx.removedAgent(agent, askedOn).catch(() => false)) {
    return { outcome: "answered", note: W.removedTafsConnection(vendor), code: "removed_taf",
      answer: `failed: this ${vendor} connection was asked for a taf that was removed, so it can't be approved for another; request ${vendor} again for this taf (reuse its sign-in with a new request), never this grant` };
  }
  if (ask.draft && agent) {
    const draft = await draftInfo(ctx.token, ask.draft, ctx.fetch, ctx.kiffBase ?? KIFF_API, ctx.now).catch(() => undefined);
    if (draft?.agent && draft.agent !== agent) {
      return { outcome: "answered", note: W.otherTafsConnection(vendor), code: "other_taf",
        answer: `failed: the Card draft is for another taf than this ${vendor} connection's; show an approval with this taf's own grant and its draft` };
    }
  }
  return undefined;
}

/** How long a half-made connection from an earlier conversation can be resumed: planque ends one after this anyway. */
export const RESUME_WITHIN_MS = 7 * 24 * 3600_000;

/**
 * planque's and kiff/agents' clocks differ: a grant asked up to this long after the conversation began is still read as
 * an earlier one, unless this conversation's own connection request answered with it (`askedHere`).
 */
export const RESUME_CLOCK_MARGIN_MS = 10_000;

/**
 * Whether a conversation's events hold its own connection request (Coach's `planque` call with `action: "request"`)
 * answered with the grant: the request's result names its id, as a whole token (`g_1` never matches `g_12`).
 */
export function askedInSession(events: ReadonlyArray<{ kind: string; body: unknown }>, grant: string): boolean {
  if (!grant) return false;
  const named = new RegExp(`(^|[^A-Za-z0-9_.:-])${grant.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}($|[^A-Za-z0-9_.:-])`);
  const requests = new Set<string>();
  for (const e of events) {
    const b = (e.body ?? {}) as { tool?: unknown; input?: unknown; call_id?: unknown; text?: unknown; is_error?: unknown };
    if (typeof b.call_id !== "string") continue;
    if (e.kind === "tool_call" && b.tool === "planque" && (b.input as { action?: unknown } | null)?.action === "request") requests.add(b.call_id);
    else if (e.kind === "tool_result" && requests.has(b.call_id) && b.is_error !== true && typeof b.text === "string" && named.test(b.text)) return true;
  }
  return false;
}

/**
 * A half-made connection from an earlier conversation (asked before this one began, still pending or approved and never
 * signed in to) is resumed here only when every one of these holds; otherwise Coach asks for the app again, as a new
 * connection for this taf:
 * - it's this conversation's taf: the card carries this conversation's Card draft, and the draft is for the grant's agent;
 * - it's inside its deadline: asked under seven days ago (planque ends it then);
 * - its tools are this conversation's Card's: every tool it asks for (what the phone signs) is on the draft's Cards;
 * - a fresh tap signs it, as every approval here does: nothing approved earlier is reused.
 * Each refusal tells Coach to ask for the app again for this taf, never naming another taf or the grant. A grant of this
 * conversation, or one connected already (a new version's Card over it), isn't resumed and isn't touched.
 */
async function resumedRefusal(ask: ApprovalAsk, grant: ApprovalGrant, vendor: string, ctx: DecideContext): Promise<Decision | undefined> {
  if (!ctx.sessionStarted || (grant.status !== "pending" && grant.status !== "approved")) return undefined;
  const asked = (grant.request as { created_at?: unknown }).created_at;
  const askedAt = typeof asked === "string" ? Date.parse(asked) : NaN;
  if (Number.isNaN(askedAt)) return undefined;
  let started: string | undefined;
  try {
    started = await ctx.sessionStarted();
  } catch {
    return { outcome: "read_again", note: approvalWords(ctx.lang).outOfDate, code: "session_unread" };
  }
  const startedAt = started ? Date.parse(started) : NaN;
  // A conversation with no start it can read is read again, never taken as this one's.
  if (Number.isNaN(startedAt)) return { outcome: "read_again", note: approvalWords(ctx.lang).outOfDate, code: "session_unread" };
  if (askedAt >= startedAt + RESUME_CLOCK_MARGIN_MS) return undefined;
  // Within the margin after it began, this conversation's own request is its own.
  if (askedAt >= startedAt && ctx.askedHere) {
    let own: boolean;
    try {
      own = await ctx.askedHere(grant.id);
    } catch {
      return { outcome: "read_again", note: approvalWords(ctx.lang).outOfDate, code: "session_unread" };
    }
    if (own) return undefined;
  }
  const W = approvalWords(ctx.lang);
  const again = `request ${vendor} again for this taf as a new connection, never this grant`;
  const refuse = (code: string, why: string): Decision =>
    ({ outcome: "answered", note: W.earlierConnection(vendor), code, answer: `failed: this ${vendor} connection was started in an earlier conversation, and ${why}, so it isn't resumed here; ${again}` });
  if (ctx.now.getTime() - askedAt > RESUME_WITHIN_MS) return refuse("resumed_expired", "it was asked over seven days ago");
  if (!ask.draft) return refuse("resumed_without_card", "it comes back only with this conversation's Card draft over it");
  const draft = await draftInfo(ctx.token, ask.draft, ctx.fetch, ctx.kiffBase ?? KIFF_API, ctx.now).catch(() => undefined);
  const agent = typeof grant.request.agent === "string" ? grant.request.agent : "";
  if (!draft?.agent || !agent || draft.agent !== agent) return refuse("resumed_other_taf", "this conversation's Card draft isn't for its taf");
  if (!draft.cards) return { outcome: "read_again", note: W.cardsUnread, code: "cards_unread" };
  // This conversation's Card alone, not Cards in force from before: what the owner agrees to here is what resumes it.
  const left = uncoveredTool(grant, draft.cards, new Set());
  if (left) return refuse("resumed_beyond_card", `it asks for "${left}", which this conversation's Card doesn't cover`);
  return undefined;
}

/** The steps a phone signs for an approval card. */
export type SignStep = "connect" | "cards" | "key_and_cards" | "tools_and_cards";

/**
 * The step an approval card asks the phone to sign, from the grant as planque says it and what the card and the
 * phone bring: one rule, for `decide` and for the mark the card carries before Approve (`signs`), so the apps draw
 * the Card as signed only on a fingerprint that signs it. Undefined when nothing is signed (answered without one).
 */
export function stepFor(grant: ApprovalGrant, ask: { draft?: string; can?: string[] }, flags: { toolsAndCards?: boolean; keyAndCards?: boolean }): SignStep | undefined {
  if (grant.status === "connected") return ask.draft ? "cards" : undefined;
  if (grant.status === "listed" && !ask.draft) return undefined;
  if (!APPROVABLE.has(grant.status)) return undefined;
  // Tools that wait on a sign-in planque already holds, with the Card's draft over them: one fingerprint
  // (docs/secure-connection.md). A connected grant's added tools, or a held sign-in's first tools (one reused for
  // another taf), declared in KIFF meanwhile: the approve connects at once, with no sign-in between the two halves.
  const added = Array.isArray(grant.connected) && grant.connected.length > 0;
  // Only for a phone that draws the card by its mark (`can: signs`): one that draws the Card only for a connected
  // grant would sign it unseen here, and builds already in people's hands don't update with this rule.
  const heldFirst = grant.signed_in === true && grant.declared === true && ask.can?.includes("signs") === true;
  if (flags.toolsAndCards && ask.can?.includes("tools_and_cards") && !takesKey(grant) && grant.status === "pending" && (added || heldFirst) && ask.draft) return "tools_and_cards";
  // A key grant whose Card is drafted already: the key and the Card under one fingerprint.
  if (flags.keyAndCards && ask.can?.includes("key_and_cards") && takesKey(grant) && ask.draft) return "key_and_cards";
  return "connect";
}

/**
 * Whether Approve on the card is a tap inside an open approval window or always the fingerprint, from the key `decide`
 * then asks with: connecting an app takes the window; the key or added tools signed with their Card take the approval
 * key; a Card alone takes the key KIFF names for its draft (`confirm_key`), the fingerprint until it names one. Undefined
 * when a Card's key isn't known (KIFF's draft wasn't read): the apps then say nothing of the window on that card.
 */
export function takesOf(step: SignStep, confirmKey?: "window" | "critical" | null): "tap" | "fingerprint" | undefined {
  if (step === "connect") return "tap";
  if (step === "cards") return confirmKey === undefined ? undefined : confirmKey === "window" ? "tap" : "fingerprint";
  return "fingerprint";
}

/**
 * What an approval card's fingerprint will sign, for the apps to draw before Approve: the Card, or the connection alone.
 * The connection alone says the Card comes next (`words`) when the card carries the Card's draft, or when it's an app's
 * first connection, before its tools: a Card always follows that one (its draft can only be made over the tools the
 * sign-in brings), so the owner knows a second approval is coming. Never on a sign-in again (its Card is in force) or
 * on tools added to a connected app with no draft.
 */
export function signsOf(step: SignStep, draft: string | undefined, lang: Lang = "en", grant?: Pick<ApprovalGrant, "status" | "connected">): { card: boolean; words?: string } {
  const card = step !== "connect";
  const first = !!grant && grant.status !== "needs_reconnect" && !(Array.isArray(grant.connected) && grant.connected.length > 0);
  return { card, ...(!card && (draft || first) ? { words: wordsIn(lang).approval_card_next } : {}) };
}

/**
 * Decides an approval: what the card needs signed, if anything. Reads planque's
 * grant as the phone, and KIFF's draft with the person's session.
 */
/** A decision as the apps get it: its reason code is the check route's and the log's, never in an app's answer. */
export function withoutCode(d: Decision): Decision {
  if (d.outcome === "sign") return d;
  const { code: _code, ...rest } = d;
  return rest as Decision;
}

/** What the check route answers: the draft passes every check Approve makes before signing, or why not. */
export type DraftCheck = { outcome: "ok"; version: string } | (Exclude<Decision, { outcome: "sign" }> & { code: string });

/**
 * The checks Approve makes on a Card draft before it asks for a signature, with nothing signed, confirmed or kept: the
 * grant as planque gives it to this account (read with the phone's own proof), the draft as KIFF shows it to this
 * person's session, the same agentRefusal and cardsReady as `decide`'s. For the e2e, which confirms the draft itself
 * and so never went through Approve (Eval 43681227913): the gate then measures the checks an owner's app goes through.
 */
export async function checkDraft(ask: { grant: string; draft: string; shown_draft: string }, ctx: DecideContext, read?: ApprovalGrant[]): Promise<DraftCheck> {
  const W = approvalWords(ctx.lang);
  // The grants the caller read already with the same proof (the chat's read), or read now.
  const grants = read ?? await readGrants(ctx.planqueBase, ctx.tenant, ctx.proof, ctx.fetch);
  const grant = grants.find((g) => g.id === ask.grant);
  if (!grant) return { outcome: "answered", answer: `failed: ${W.noGrant}`, note: W.noGrant, code: "no_grant" };
  const vendor = vendorOf(grant);
  const coded = (d: Decision, fallback: string): DraftCheck => (d.outcome === "sign" ? { outcome: "read_again", note: W.outOfDate, code: fallback } : { ...d, code: d.code ?? fallback });
  const resumed = await resumedRefusal(ask, grant, vendor, ctx);
  if (resumed) return coded(resumed, "refused");
  const refused = await agentRefusal(ask, grant, vendor, ctx);
  if (refused) return coded(refused, "refused");
  const checked = await cardsReady(ask, ask.draft, grant, vendor, ctx, sameTafOf(grant, grants));
  if ("outcome" in checked) return coded(checked, "refused");
  if (!("draft" in checked)) return { outcome: "read_again", note: W.outOfDate, code: "out_of_date" };
  return { outcome: "ok", version: checked.draft.version };
}

export async function decide(ask: ApprovalAsk, ctx: DecideContext): Promise<Decision> {
  return withoutCode(await decideCoded(ask, ctx));
}

/**
 * decide, with every answer's reason code kept: for the log of each Approve tap (handler.ts approvalAskLine), never for an
 * app's answer, which decide strips.
 */
export async function decideCoded(ask: ApprovalAsk, ctx: DecideContext): Promise<Decision> {
  const W = approvalWords(ctx.lang);
  const grants = await readGrants(ctx.planqueBase, ctx.tenant, ctx.proof, ctx.fetch);
  const grant = grants.find((g) => g.id === ask.grant);
  if (!grant) return { outcome: "answered", answer: `failed: ${W.noGrant}`, note: W.noGrant, code: "no_grant" };
  const vendor = vendorOf(grant);
  // A half-made connection from an earlier conversation first: its refusals ask for the app again, never another taf's.
  const resumed = await resumedRefusal(ask, grant, vendor, ctx);
  if (resumed) return resumed;
  const refused = await agentRefusal(ask, grant, vendor, ctx);
  if (refused) return refused;
  // The waiting app's approval tapped with no draft, though its connection and the Card go under one approval
  // (splitApproval): never signed apart, whatever the phone showed (a read that drew Approve before the chat's check).
  if (!ask.draft) {
    const split = splitApproval({ grant: grant.id }, await liveWaitingDraft(ctx.store, ctx.tenant, grant.id, (d) => draftInfo(ctx.token, d, ctx.fetch, ctx.kiffBase ?? KIFF_API, ctx.now)));
    if (split) return { outcome: "answered", answer: split, note: W.withItsCard(vendor), code: "split_approval" };
  }
  // The same taf's other connections: a Card may cover their tools too (two apps, one Card).
  const sameTaf = sameTafOf(grant, grants);
  const redraft = await redraftRefusal(ask, vendor, ctx);
  if (redraft) return redraft;
  // A grant asking for something again (tools added once connected) loses its "connected" note, so its card shows what it asks.
  if (grant.status !== "connected") await noteConnected(ctx.store, ctx.tenant, grant, ctx.now);
  if (grant.status === "connected") {
    await noteConnected(ctx.store, ctx.tenant, grant, ctx.now);
    // The second approval: the vendor is connected, and the taf's Cards over its tools wait as a
    // draft. A connection already connected with no Cards left signs nothing, so it isn't approved.
    // The card showed exactly what is connected (its digest): an approval signed earlier whose
    // report was lost. Approve again answers what holds, already approved, as the report would have.
    if (!ask.draft && ask.shown_digest && ask.shown_digest === grant.digest) return { outcome: "answered", answer: `already approved: ${vendor} is connected`, note: W.alreadyApproved, code: "already_approved" };
    if (!ask.draft) return { outcome: "answered", answer: `failed: ${vendor} is already connected; nothing new to approve`, note: W.nothingToApprove(vendor), code: "nothing_to_approve" };
    const checked = await cardsReady(ask, ask.draft, grant, vendor, ctx, sameTaf);
    if ("outcome" in checked) return checked;
    if (!("draft" in checked)) return { outcome: "read_again", note: W.outOfDate, code: "out_of_date" };
    const { draft } = checked;
    const id = ctx.newID();
    // KIFF says which key confirms the draft (kiff/tafs#494); until it does, the fingerprint.
    const key = draft.confirmKey ?? "critical";
    const pending: Pending = { step: "cards", grant: grant.id, vendor, draft: ask.draft, version: draft.version, made: ctx.now.toISOString(), key };
    await ctx.store.put(keyOf(ctx.tenant, ctx.taf, ctx.component, id), pending, new Date(ctx.now.getTime() + ASK_KEPT_MS));
    await noteSending(grant, ctx);
    return { outcome: "sign", ask: { id, step: "cards", message: `tafs.cards.v1|${ctx.tenant}|${ask.draft}|${draft.version}`, reason: W.reasonCard(await nameOf(ctx, draft.agent, draft.label, grant.agent_name)), key, kiff: { draft: ask.draft, version: draft.version } } };
  }
  // Signed in already, its tools listed and waiting for their own approval: an earlier approval
  // went through even if its report didn't reach here (the phone lost it, or it was refused), so
  // Approve again answers what holds, as the report would have: approved, never failed.
  if (grant.status === "listed" && !ask.draft) return { outcome: "answered", answer: `already approved: ${vendor} is signed in; its tools wait for approval`, note: W.alreadyApproved, code: "already_approved" };
  if (!APPROVABLE.has(grant.status)) {
    // Coach hears planque's status and reason, to act on; the person reads plain words (14 Nov: "This connection is listed.").
    const why = `This connection is ${grant.status.replace(/_/g, " ")}${grant.reason ? `: ${grant.reason}` : "."}`;
    return { outcome: "answered", answer: `failed: ${why}`, note: W.notApprovable(vendor, grant.status), code: "not_approvable" };
  }
  // The digest planque signs is the request's: the phone shows Approve only when it matches, and so does tafs cloud.
  let digest = "";
  try { digest = sha256(grant.request); } catch { /* an unreadable request never matches */ }
  if (digest !== grant.digest) return { outcome: "answered", answer: `failed: ${W.digestMismatch}`, note: W.digestMismatch, code: "digest_mismatch" };
  if (grant.digest !== ask.shown_digest) return { outcome: "read_again", note: W.outOfDate, code: "out_of_date" };
  // A connection with a profile is approved only with KIFF's words for exactly that profile, as the owner read them.
  let kiffProfile: { profile_sha256: string; words_sha256: string } | undefined;
  if (grant.request.profile !== undefined) {
    if (!ask.words || ask.words.profile_sha256 !== sha256(grant.request.profile)) return { outcome: "read_again", note: W.wordsUnread, code: "words_unread" };
    kiffProfile = { profile_sha256: ask.words.profile_sha256, words_sha256: ask.words.words_sha256 };
  }
  // A connected grant's added tools, with the Card's draft over them: the addition's approve and the Card under one
  // fingerprint (docs/secure-connection.md), as a key and its Card are, once planque takes the paired approve.
  const step = stepFor(grant, ask, ctx);
  const combined = (step === "tools_and_cards" || step === "key_and_cards") && ask.draft
    ? await cardsReady(ask, ask.draft, grant, vendor, ctx, sameTaf, true) : undefined;
  if (combined && "outcome" in combined) return combined;
  if (step === "tools_and_cards" && ask.draft && combined && "draft" in combined) {
    const checked = combined;
    const id = ctx.newID();
    const pending: Pending = { step: "tools_and_cards", grant: grant.id, vendor, draft: ask.draft, version: checked.draft.version, made: ctx.now.toISOString(), key: "critical" };
    await ctx.store.put(keyOf(ctx.tenant, ctx.taf, ctx.component, id), pending, new Date(ctx.now.getTime() + ASK_KEPT_MS));
    await noteSending(grant, ctx);
    return {
      outcome: "sign",
      ask: {
        id, step: "tools_and_cards", scheme: "kiff.requests.v1", message: "",
        reason: W.reasonConnect(appNameOf(grant) || vendor, await nameOf(ctx, grant.request.agent, grant.agent_name)), key: "critical",
        planque: { grant: grant.id, body: { digest: grant.digest, ...(kiffProfile ? { kiff_profile: kiffProfile } : {}) } },
        kiff: { draft: ask.draft, version: checked.draft.version },
      },
    };
  }
  // A key grant whose Card is drafted already: the key and the Card under one fingerprint (docs/secure-connection.md),
  // once both services take kiff.requests.v1. The phone builds both requests and signs their two hashes.
  if (step === "key_and_cards" && ask.draft && combined && "draft" in combined) {
    const checked = combined;
    const id = ctx.newID();
    const pending: Pending = { step: "key_and_cards", grant: grant.id, vendor, draft: ask.draft, version: checked.draft.version, made: ctx.now.toISOString(), key: "critical" };
    await ctx.store.put(keyOf(ctx.tenant, ctx.taf, ctx.component, id), pending, new Date(ctx.now.getTime() + ASK_KEPT_MS));
    await noteSending(grant, ctx);
    return {
      outcome: "sign",
      ask: {
        id, step: "key_and_cards", scheme: "kiff.requests.v1", message: "",
        reason: W.reasonConnect(appNameOf(grant) || vendor, await nameOf(ctx, grant.request.agent, grant.agent_name)), key: "critical",
        planque: { grant: grant.id, body: { digest: grant.digest, ...(kiffProfile ? { kiff_profile: kiffProfile } : {}) } },
        kiff: { draft: ask.draft, version: checked.draft.version },
      },
    };
  }
  const id = ctx.newID();
  // Connecting an app is routine (kiff/tafs#494): a tap inside an approval window. A card that also carries a
  // draft keeps it here, unsigned: the connection alone is signed, and the report never answers it approved
  // while that draft isn't issued, so Coach asks for its Card rather than hand over a taf no Card covers.
  const pending: Pending = { step: "connect", grant: grant.id, vendor, made: ctx.now.toISOString(), key: "window", ...(ask.draft ? { draft: ask.draft, ...(ask.shown_draft ? { version: ask.shown_draft } : {}) } : {}) };
  await ctx.store.put(keyOf(ctx.tenant, ctx.taf, ctx.component, id), pending, new Date(ctx.now.getTime() + ASK_KEPT_MS));
  await noteSending(grant, ctx);
  return {
    outcome: "sign",
    ask: {
      id, step: "connect", message: `tafs.connect.v1|${ctx.tenant}|${grant.id}|${grant.digest}`, reason: W.reasonConnect(appNameOf(grant) || vendor, await nameOf(ctx, grant.request.agent, grant.agent_name)), key: "window",
      planque: { grant: grant.id, body: { digest: grant.digest, ...(kiffProfile ? { kiff_profile: kiffProfile } : {}) } },
    },
  };
}

/**
 * The grant's tools that send outside the company, as its profile says (or, on an API-key connection, planque's text
 * fields: sendingProfileOf), noted for its taf's agent as the owner is
 * about to sign (gap 14): Coach's result is checked against them. Read as the phone, so only here, and each Approve
 * of the grant replaces its entry, so a later connection's sending tool counts.
 */
async function noteSending(grant: ApprovalGrant, ctx: DecideContext): Promise<void> {
  if (typeof grant.request.agent !== "string" || !grant.request.agent) return;
  await noteSendingOutside(ctx.store, ctx.tenant, grant.request.agent, grant.id, sendingProfileOf(grant.request), ctx.now);
}

/** What the phone reports came of the call it made with the signature. */
export interface Report {
  result: "connected" | "listed" | "confirmed" | "sign_in_closed" | "no_code" | "not_connected" | "failed";
  /** planque's status, for `not_connected`. */
  status?: string;
  /** The error, in the phone's words, for `failed`. */
  why?: string;
}

const RESULTS = new Set(["connected", "listed", "confirmed", "sign_in_closed", "no_code", "not_connected", "failed"]);

export function reportOf(body: Record<string, unknown>): Report {
  const result = typeof body.result === "string" ? body.result : "";
  if (!RESULTS.has(result)) throw new ApprovalError(400, "send what came of the call: connected, listed, confirmed, sign_in_closed, no_code, not_connected or failed");
  const r: Report = { result: result as Report["result"] };
  if (typeof body.status === "string" && body.status.length <= 64) r.status = body.status;
  if (typeof body.why === "string" && body.why.trim()) r.why = body.why.trim().slice(0, 500);
  return r;
}

export interface ReportContext extends DecideContext {
  /**
   * The request as the device signed it: its method, path and raw body, and its KIFF-Device-Signature, or the
   * passkey assertion a computer approves with (KIFF-Passkey-Assertion, as sent): exactly one of the two.
   */
  signed: { method: string; uri: string; body: string; signature: string; passkey?: string };
}

/** KIFF's code for a refused signature (`error`, as its 400s and 403s send it), or "" when it sent none it could read. */
async function kiffCode(res: Response): Promise<string> {
  const body = (await res.json().catch(() => ({}))) as { error?: unknown };
  return typeof body.error === "string" ? body.error : "";
}

/**
 * KIFF's refusal of a device's signature, in the person's words and with KIFF's code, so the app and the person
 * hear why: a view-only device, a session that can't sign for it, a passkey this session doesn't approve with, a
 * clock that's off, or a signature that didn't check out. Said of "this device", whichever it is (a phone, a tablet,
 * a computer), never "this phone". A code tafs cloud doesn't know reads as a signature that didn't check out.
 */
export function signRefusal(status: number, code: string, W: ReturnType<typeof approvalWords>): ApprovalError {
  switch (code) {
    case "view_only": return new ApprovalError(403, W.signViewOnly, code);
    case "device_session_required": return new ApprovalError(403, W.signSession, code);
    case "passkey_not_this_session": return new ApprovalError(403, W.signPasskey, code);
    case "signed_at_out_of_range": return new ApprovalError(400, W.signClock, code);
    default: return new ApprovalError(status === 400 ? 400 : 403, W.signInvalid, "signature_invalid");
  }
}

/**
 * Asks KIFF whether this device signed the report, with the device's own session
 * (`POST /v1/me/device-signature`): tafs cloud holds no device key and checks
 * no signature itself. Each signed report is taken once. KIFF also says which
 * key signed and, for a signature made inside an approval window, whether that
 * window is open (kiff/tafs#494). The report itself is the device key's, as it
 * always was: the fingerprint went on the call it reports (KIFF takes a draft's
 * confirm only from the approval key, planque its approvals as its kind says),
 * and `settle` reads what holds there. What's refused here is a report signed
 * inside a window for a critical ask, since that phone took the window path for
 * a critical call, and a window that has ended.
 */
export async function checkSigned(ctx: Pick<ReportContext, "signed" | "fetch" | "kiffBase" | "token" | "lang"> & Partial<ReportContext>, key: "window" | "critical" | "approval" = "window"): Promise<void> {
  const W = approvalWords(ctx.lang);
  const { signed } = ctx;
  // A computer approves with its passkey (docs/computer-passkeys.md), sent as KIFF took it from the gateway: KIFF
  // checks it in place of a device signature, takes each once, and answers it as the approval key with no window.
  // KIFF takes one or the other, never both, so a request carrying both is refused before KIFF is asked.
  const passkey = signed.passkey ?? "";
  if (signed.signature && passkey) throw new ApprovalError(400, W.signBoth, "signature_twice");
  if (!signed.signature && !passkey) throw new ApprovalError(403, W.signMissing, "signature_missing");
  let res: Response;
  try {
    res = await ctx.fetch(`${ctx.kiffBase ?? KIFF_API}/v1/me/device-signature`, {
      method: "POST",
      headers: { authorization: `Bearer ${ctx.token}`, accept: "application/json", "content-type": "application/json" },
      body: JSON.stringify({ method: signed.method, uri: signed.uri, body_sha256: createHash("sha256").update(signed.body, "utf8").digest("hex"),
        ...(passkey ? { passkey } : { signature: signed.signature }) }),
    });
  } catch {
    throw new ApprovalError(502, W.signUnchecked, "signature_unchecked");
  }
  if (res.status === 403 || res.status === 400) throw signRefusal(res.status, await kiffCode(res), W);
  if (res.status === 409) throw new ApprovalError(409, W.signTaken, "signature_taken");
  if (res.status === 401) throw new KIFFError(401, "KIFF did not accept this sign-in");
  if (!res.ok) throw new ApprovalError(502, W.signUnchecked, "signature_unchecked");
  // KIFF's answer also carries a one-minute device grant (`grant`, `grant_until`): the writes a
  // service like the gateway then makes for the phone. tafs cloud makes none and must not use
  // it, so only `signed` is read; the grant is dropped here and never kept or passed on.
  const out = (await res.json().catch(() => ({}))) as { signed?: unknown; key?: unknown; window?: { open?: unknown } };
  if (out.signed !== true) throw new ApprovalError(403, W.signInvalid, "signature_invalid");
  // Only the approval key, behind the fingerprint: a Coach drive's start (kiff/tafs#544).
  if (key === "approval" && (out.key !== "critical" || out.window)) throw new ApprovalError(403, W.driveKey, "critical_key_required");
  if (!out.window) return; // signed outside a window: as every report before (the device key's, v1)
  if (key === "critical") throw new ApprovalError(403, W.criticalKey, "critical_key_required");
  if (out.key === "device" && out.window.open !== true) throw new ApprovalError(403, W.windowClosed, "window_required");
}

/** The outcome of a reported approval: Coach's answer and the card's note. */
export interface Outcome {
  answer: "approved" | `failed: ${string}`;
  note?: string;
  /**
   * Approved with a connection's tools: their names in KIFF and the agent they're for, so that taf keeps them (Taf.tools)
   * and its dial can name them. A taf moved to a new connection of an app reads with that connection's tools from then on.
   */
  connection?: { agent: string; tools: string[] };
}

/** A connected grant's tools as KIFF names them, and its taf's agent; nothing when the grant names neither. */
function connectionOf(grant: ApprovalGrant | undefined): Pick<Outcome, "connection"> {
  const agent = typeof grant?.request.agent === "string" ? grant.request.agent : "";
  // A tool asked for with no name is planque's `toolKey`: the vendor's name, cut to 59, and the grant's suffix.
  const named = (t: { tool?: string; name?: string }) => t.name || (t.tool && grant ? t.tool.slice(0, 59) + grantSuffix(grant.id) : "");
  const tools = [...new Set((grant?.request.tools ?? []).map(named).filter((n): n is string => !!n))];
  return agent && tools.length ? { connection: { agent, tools } } : {};
}

/**
 * Settles a reported approval: KIFF checks the phone signed the report, then
 * planque or KIFF says what came of the call. `approved` only when the service
 * holds it; else `failed: <why>`, in tafs cloud's words.
 */
export async function settle(askID: string, report: Report, ctx: ReportContext): Promise<Outcome> {
  const key = keyOf(ctx.tenant, ctx.taf, ctx.component, askID);
  const pending = ID.test(askID) ? ((await ctx.store.get(key)) as Pending | undefined) : undefined;
  const outcome = await settleAsk(askID, report, ctx);
  // The Card this conversation approved for the connection: no other draft over it is approved here (redraftRefusal).
  // The waiting app approved with the draft it waited for: its note has done its work (liveWaitingDraft would also see
  // the draft confirmed; this saves the read).
  if (outcome.answer === "approved" && pending?.draft) {
    const note = (await ctx.store.get(waitingDraftKey(ctx.tenant, pending.grant)).catch(() => undefined)) as Partial<WaitingDraft> | undefined;
    if (note?.draft === pending.draft) await ctx.store.remove(waitingDraftKey(ctx.tenant, pending.grant)).catch(() => undefined);
  }
  if (outcome.answer === "approved" && pending?.draft && ctx.session) {
    await ctx.store.put(approvedDraftKey(ctx.tenant, ctx.taf, ctx.session, pending.grant), { draft: pending.draft, at: ctx.now.toISOString() },
      new Date(ctx.now.getTime() + APPROVED_DRAFT_KEPT_MS));
  }
  return outcome;
}

/** How long a conversation's approved Card is remembered: past any set-up's end. */
export const APPROVED_DRAFT_KEPT_MS = 7 * 24 * 3600_000;

async function settleAsk(askID: string, report: Report, ctx: ReportContext): Promise<Outcome> {
  const W = approvalWords(ctx.lang);
  if (!ID.test(askID)) throw new ApprovalError(404, "no approval waits for that answer");
  const key = keyOf(ctx.tenant, ctx.taf, ctx.component, askID);
  const pending = (await ctx.store.get(key)) as Pending | undefined;
  if (!pending || !pending.step) throw new ApprovalError(404, "no approval waits for that answer");
  await checkSigned(ctx, pending.key ?? "window");
  // Taken once, atomically, before Coach hears anything: two signed reports of one ask that
  // arrive together settle it once; the other finds nothing waiting. A report KIFF refused
  // above spends nothing, so the phone can report again.
  if (!(await ctx.store.once(`${key}#taken`, new Date(ctx.now.getTime() + ASK_KEPT_MS)))) throw new ApprovalError(404, "no approval waits for that answer");
  await ctx.store.remove(key);
  if (pending.step === "key_and_cards" || pending.step === "tools_and_cards") {
    // One signature, two requests: approved only when the connection server says connected and KIFF says the
    // Card is issued. A key that went in with a Card that didn't says so, and the card's next tap asks the Card alone.
    const [grants, draft] = await Promise.all([
      readGrants(ctx.planqueBase, ctx.tenant, ctx.proof, ctx.fetch),
      pending.draft ? draftInfo(ctx.token, pending.draft, ctx.fetch, ctx.kiffBase ?? KIFF_API, ctx.now) : Promise.resolve(undefined),
    ]);
    const grant = grants.find((g) => g.id === pending.grant);
    // A key grant has no sign-in to list its tools after: it's connected, or the key didn't go in.
    const connected = grant?.status === "connected";
    if (connected) await noteConnected(ctx.store, ctx.tenant, grant!, ctx.now);
    // Approved only when every app the Card covers is connected: never while another app's tools still wait.
    const waiting = grant && draft?.cards ? appsWaiting(grant, sameTafOf(grant, grants), draft.cards) : [];
    if (connected && draft && issued(draft.status) && waiting.length) return { answer: `failed: ${pending.vendor} is connected and its Card is issued, but ${waitsLine(waiting[0]!)}`, note: W.otherAppFirst(waiting[0]!.name) };
    if (connected && draft && issued(draft.status)) return { answer: "approved", ...connectionOf(grant) };
    if (!connected) {
      const why = report.result === "failed" && report.why ? W.failed(report.why) : W.notConnected(pending.vendor);
      return { answer: `failed: ${why}`, note: why };
    }
    const why = report.result === "failed" && report.why ? report.why : draft ? W.draftIs(draft.status) : W.noDraft;
    return { answer: `failed: ${pending.vendor} is connected; ${W.cardsNotIssued(why)}`, note: W.cardsNotIssued(why) };
  }
  if (pending.step === "cards") {
    const draft = pending.draft ? await draftInfo(ctx.token, pending.draft, ctx.fetch, ctx.kiffBase ?? KIFF_API, ctx.now) : undefined;
    if (draft && issued(draft.status) && draft.cards) {
      const grants = await readGrants(ctx.planqueBase, ctx.tenant, ctx.proof, ctx.fetch);
      const own = grants.find((g) => g.id === pending.grant);
      const waiting = own ? appsWaiting(own, sameTafOf(own, grants), draft.cards) : [];
      if (waiting.length) return { answer: `failed: the Card is issued, but ${waitsLine(waiting[0]!)}`, note: W.otherAppFirst(waiting[0]!.name) };
    }
    if (draft && issued(draft.status)) return { answer: "approved" };
    const why = report.result === "failed" && report.why ? report.why : draft ? W.draftIs(draft.status) : W.noDraft;
    return { answer: `failed: ${W.cardsNotIssued(why)}`, note: W.cardsNotIssued(why) };
  }
  let grant = (await readGrants(ctx.planqueBase, ctx.tenant, ctx.proof, ctx.fetch)).find((g) => g.id === pending.grant);
  // signed_in: planque holds the sign-in's token, unused, until the approving phone confirms it with planque's
  // one-use code; unconfirmed, planque revokes it. So it's "not yet", never approved and never failed: read once
  // more (the phone may have just confirmed), and if it's still waiting, the ask waits again for the phone's
  // report after its confirm, and Coach hears nothing yet (go-to-market job 1, 13 Nov).
  if (grant?.status === "signed_in") {
    grant = (await readGrants(ctx.planqueBase, ctx.tenant, ctx.proof, ctx.fetch)).find((g) => g.id === pending.grant);
    if (grant?.status === "signed_in") {
      await ctx.store.put(key, pending, new Date(ctx.now.getTime() + ASK_KEPT_MS));
      await ctx.store.remove(`${key}#taken`);
      throw new ApprovalError(409, W.confirmFirst, "sign_in_not_confirmed");
    }
  }
  if (grant && (grant.status === "connected" || grant.status === "listed")) {
    await noteConnected(ctx.store, ctx.tenant, grant, ctx.now);
    // The card carried a draft that this signature didn't confirm (the combined step wasn't offered): approved
    // only once KIFF says it's issued. Otherwise the connection is said done and the Card not, as key_and_cards
    // says its half, and Coach shows the approval again, which asks the Card alone.
    if (pending.draft) {
      const draft = await draftInfo(ctx.token, pending.draft, ctx.fetch, ctx.kiffBase ?? KIFF_API, ctx.now);
      // Issued at the version the card showed, when it showed one: another version isn't the Card the owner read.
      const other = !!draft && !!pending.version && !!draft.version && draft.version !== pending.version;
      // By design (connect now, the Card next, as the card said): the connection is connected and its Card is still the
      // draft the card showed. That never reads as a failure: Coach hears to show the Card alone, and the person reads
      // "Gmail is connected. Next, its Card." (CARD_NEXT, approvalAnswerWords).
      if (grant.status === "connected" && draft && draft.status === "draft" && !other) {
        return { answer: `failed: ${pending.vendor} is connected; ${CARD_NEXT}`, note: W.connectedCardNext(pending.vendor) };
      }
      if (!draft || !issued(draft.status) || other) {
        const why = !draft ? W.noDraft : other ? W.outOfDate : W.draftIs(draft.status);
        const done = grant.status === "connected" ? "is connected" : "is signed in";
        return { answer: `failed: ${pending.vendor} ${done}; ${W.cardsNotIssued(why)}; show the approval again for its Card alone`, note: W.cardsNotIssued(why) };
      }
      // Its Card issued, but another app it covers still waits: never approved until that app's tools are too.
      const all = await readGrants(ctx.planqueBase, ctx.tenant, ctx.proof, ctx.fetch);
      const waiting = draft.cards ? appsWaiting(grant, sameTafOf(grant, all), draft.cards) : [];
      if (waiting.length) return { answer: `failed: ${pending.vendor} is connected and its Card is issued, but ${waitsLine(waiting[0]!)}`, note: W.otherAppFirst(waiting[0]!.name) };
    }
    return { answer: "approved", ...connectionOf(grant) };
  }
  const vendor = pending.vendor;
  let why: string;
  switch (report.result) {
    case "sign_in_closed": why = W.signInClosed; break;
    case "no_code": why = W.noCode(vendor); break;
    // planque's approve came back with no sign-in link and not connected: its status, as it answered.
    case "not_connected": why = W.cameBack(vendor, report.status ?? grant?.status ?? "unknown"); break;
    case "failed": why = W.failed(report.why); break;
    default: why = W.notConnected(vendor);
  }
  return { answer: `failed: ${why}`, note: why };
}
