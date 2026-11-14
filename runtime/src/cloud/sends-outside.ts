import type { CoachStore } from "./coach.js";

/**
 * The AI disclosure, closed structurally (kiff/tafs#476, gap 14). A connection's profile (KIFF's
 * `kiff.profile.v1`, inside the grant's signed request) says what each write does: an operation whose
 * `effect` is `sends` with `resource.outside` sends to people who may be outside the company, and one
 * whose `effect` is `none_until_approved` drafts what reaches them once the owner sends it (a draft
 * email, a draft invoice). Such a tool's every call must carry the disclosure, so a taf that has one
 * names it in `disclose`, and Coach can't leave it out by forgetting.
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

/**
 * The tools a grant's profile says reach people outside the company: each operation's tool that `sends` with
 * `resource.outside`, or that drafts (`none_until_approved`), since a draft reaches them as written once the owner
 * sends it.
 */
export function sendingOutside(profile: unknown): string[] {
  const ops = profile && typeof profile === "object" ? (profile as { operations?: unknown }).operations : undefined;
  if (!Array.isArray(ops)) return [];
  const out = new Set<string>();
  for (const op of ops as Array<Record<string, unknown>>) {
    const resource = op?.resource as { outside?: unknown } | undefined;
    const reaches = (op?.effect === "sends" && resource?.outside === true) || op?.effect === "none_until_approved";
    if (op && reaches && typeof op.tool === "string" && op.tool) out.add(op.tool);
  }
  return [...out].sort();
}

/** How long a note's write holds its claim, at most: a write that died lets the next one in after this. */
export const NOTE_CLAIM_MS = 10_000;

/** How much sooner than its expiry a write stops giving its claim back: the store's whole seconds, and room to spare. */
export const NOTE_RELEASE_MARGIN_MS = 2_000;

/** How many times a note's write waits for another's claim before it gives up, and how long each wait grows by. */
const NOTE_TRIES = 6;
const NOTE_WAIT_MS = 50;

/**
 * Notes a grant's sending tools for its agent, replacing what the last Approve of that grant noted and keeping every
 * other grant's. The read, merge and write hold a claim on the note (`once`), so two Approves of the same taf at
 * once can't lose one of their entries (agentgabo on #606); one that finds the claim held waits and tries again,
 * and past its tries throws, so the Approve fails and is tapped again rather than noting nothing.
 *
 * It runs as tafs cloud answers "sign", before the owner signs: a note can only make Coach's result checked more
 * strictly, never less, so an Approve the owner never completes costs nothing, and the report keeps no write.
 */
export async function noteSendingOutside(store: CoachStore, tenant: string, agent: string, grant: string, profile: unknown, now: Date,
  wait: (ms: number) => Promise<void> = (ms) => new Promise((done) => setTimeout(done, ms)), clock: () => number = Date.now): Promise<void> {
  if (!agent || !grant) return;
  const key = sendsOutsideKey(tenant, agent);
  const claim = `${key}#claim`;
  for (let i = 0; i < NOTE_TRIES; i++) {
    // The claim's life is counted from when it's taken, on the same clock as the release below (agentgabo on #608).
    const held = clock();
    if (await store.once(claim, new Date(held + NOTE_CLAIM_MS))) {
      try {
        const read = (await store.get(key)) as Note | undefined;
        const grants = { ...(read?.grants ?? {}), [grant]: sendingOutside(profile) };
        await store.put(key, { grants, at: now.toISOString() } satisfies Note, new Date(now.getTime() + SENDS_OUTSIDE_KEPT_MS));
      } finally {
        // Given back only while it's still this write's: past its 10 seconds another write may hold it now, and the
        // store can't remove only its own, so it's left to expire (agentgabo on #607).
        // The store compares expiries in whole seconds, so a claim can be taken over up to a second early: the margin
        // keeps a write that ran long from removing a claim that may be another's by then.
        if (clock() - held < NOTE_CLAIM_MS - NOTE_RELEASE_MARGIN_MS) await store.remove(claim).catch(() => undefined);
      }
      return;
    }
    await wait(NOTE_WAIT_MS * (i + 1));
  }
  throw new Error("another approval of this taf is noting its connections; approve again");
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
  return `disclose doesn't name ${names}, which ${missing.length === 1 ? "reaches" : "reach"} people outside the company, a draft once it's sent included (its connection says so): add ${missing.length === 1 ? "it" : "each"} to disclose with the line it carries, and send the result again`;
}

/** The words when the note can't be read: the result isn't let through, and Coach can send it again. */
export const NOTE_UNREAD = "the taf's connections couldn't be checked for tools that send outside the company just now; send the result again";
