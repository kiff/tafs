import type { CoachStore } from "./coach.js";

/**
 * The AI disclosure, closed structurally (kiff/tafs#476, gap 14). A connection's profile (KIFF's
 * `kiff.profile.v1`, inside the grant's signed request) says what each write does: an operation whose
 * `effect` is `sends` with `resource.outside` sends to people who may be outside the company. Such a
 * tool's every call must carry the disclosure, so a taf that has one names it in `disclose`, and Coach
 * can't leave it out by forgetting.
 *
 * tafs cloud reads grants only on the phone's Approve, with its signed read, while a taf's `disclose`
 * comes later, in Coach's result. So each Approve notes the grant's sending tools by the taf's KIFF
 * agent, one entry per grant replaced on each Approve, and Coach's result is checked against the note.
 * A taf approved before this has no note until its next Approve, and is checked as before.
 */

/** How long a note is kept: past any Card's approval and the result that follows, and refreshed by each Approve. */
export const SENDS_OUTSIDE_KEPT_MS = 400 * 24 * 3600_000;

export const sendsOutsideKey = (tenant: string, agent: string) => `${tenant}#sends-outside#${agent}`;

interface Note { grants: Record<string, string[]>; at: string }

/** The tools a grant's profile says send to people outside the company: each operation's tool, `sends` with `resource.outside`. */
export function sendingOutside(profile: unknown): string[] {
  const ops = profile && typeof profile === "object" ? (profile as { operations?: unknown }).operations : undefined;
  if (!Array.isArray(ops)) return [];
  const out = new Set<string>();
  for (const op of ops as Array<Record<string, unknown>>) {
    const resource = op?.resource as { outside?: unknown } | undefined;
    if (op && op.effect === "sends" && resource?.outside === true && typeof op.tool === "string" && op.tool) out.add(op.tool);
  }
  return [...out].sort();
}

/** Notes a grant's sending tools for its agent, replacing what the last Approve of that grant noted. */
export async function noteSendingOutside(store: CoachStore, tenant: string, agent: string, grant: string, profile: unknown, now: Date): Promise<void> {
  if (!agent || !grant) return;
  const key = sendsOutsideKey(tenant, agent);
  const read = (await store.get(key)) as Note | undefined;
  const grants = { ...(read?.grants ?? {}), [grant]: sendingOutside(profile) };
  await store.put(key, { grants, at: now.toISOString() } satisfies Note, new Date(now.getTime() + SENDS_OUTSIDE_KEPT_MS));
}

/** The tools noted as sending outside for an agent, across its grants; throws when the note can't be read. */
export async function notedSendingOutside(store: CoachStore, tenant: string, agent: string): Promise<string[]> {
  const read = (await store.get(sendsOutsideKey(tenant, agent))) as Note | undefined;
  return [...new Set(Object.values(read?.grants ?? {}).flat())].sort();
}

/**
 * Why a result is refused, or undefined: a noted sending tool the taf has (`tools`, as the result names them;
 * all noted ones when it names none) that `disclosed` doesn't name. The words name each tool, so Coach fixes
 * the draft in one go.
 */
export function undisclosedSending(noted: string[], tools: string[] | undefined, disclosed: string[]): string | undefined {
  const has = tools?.length ? noted.filter((t) => tools.includes(t)) : noted;
  const missing = has.filter((t) => !disclosed.includes(t));
  if (!missing.length) return undefined;
  const names = missing.join(", ");
  return `disclose doesn't name ${names}, which ${missing.length === 1 ? "sends" : "send"} to people outside the company (its connection says so): add ${missing.length === 1 ? "it" : "each"} to disclose with the line it carries, and send the result again`;
}

/** The words when the note can't be read: the result isn't let through, and Coach can send it again. */
export const NOTE_UNREAD = "the taf's connections couldn't be checked for tools that send outside the company just now; send the result again";
