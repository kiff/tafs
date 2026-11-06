/**
 * Hands KIFF's alert to kiff/agents' alerts function, as KIFF signed it.
 * Two of its answers ask for the alert again, and fail here so tafs cloud
 * answers 502 (409 for an early answer, NotPausedYet) and KIFF delivers it
 * again:
 *
 * - 401: the alert didn't check against the secret kiff/agents has for the
 *   account, or it has none. tafs cloud checked the signature and wrote the
 *   secret that signed it just before, so this is that write being replaced
 *   by a concurrent alert's (another target), or not read yet: the retry
 *   writes this alert's secret again.
 * - 404: no run has paused on the hold yet. The owner can answer before the
 *   run's pause is stored; kiff/agents answers 404 only within its
 *   PauseWindow of the decision, and 200 after it, so the retries end.
 *
 * Any other 4xx is final (an alert kiff/agents doesn't take): logged, and KIFF
 * isn't asked again. A 5xx or no answer fails too.
 */
/** kiff/agents' answers that ask for the alert again. */
const AGAIN = new Set([401, 404]);

/**
 * kiff/agents' 404 for a hold no run has paused on yet: expected, since the owner can answer before the
 * run's pause is stored. tafs cloud asks KIFF to send it again with a 409 and a warning, never a 502 and an
 * error: nothing failed, the answer is early.
 */
export class NotPausedYet extends Error {}
const NOT_PAUSED = "no run has paused on this hold yet";

/** The run an answer left waiting for nothing more, as kiff/agents names it (kiffalert.Ready). */
export interface ReadyRun {
  id: string;
  agent: string;
}

/**
 * The 200 to the answer that leaves a run ready names it, so tafs cloud can
 * continue it at once instead of leaving it to kiff/agents' sweep
 * (kiff/tafs#138). Any other 200 names nothing.
 */
export async function forwardAlert(doFetch: typeof fetch, url: string, signature: string, raw: string): Promise<ReadyRun | undefined> {
  const res = await doFetch(url, {
    method: "POST",
    headers: { "content-type": "application/json", "kiff-signature": signature },
    body: raw,
    signal: AbortSignal.timeout(10_000),
  });
  if (res.ok) {
    const named = await res.json().catch(() => undefined) as { run?: { id?: unknown; agent?: unknown } } | undefined;
    const run = named?.run;
    return typeof run?.id === "string" && run.id && typeof run.agent === "string" && run.agent ? { id: run.id, agent: run.agent } : undefined;
  }
  const text = (await res.text().catch(() => "")).slice(0, 200);
  if (res.status >= 400 && res.status < 500 && !AGAIN.has(res.status)) {
    console.error(`alert: kiff/agents answered ${res.status}: ${text}`);
    return;
  }
  if (res.status === 404 && text.includes(NOT_PAUSED)) throw new NotPausedYet(`kiff/agents answered 404: ${text.trim()}`);
  throw new Error(`kiff/agents answered ${res.status}: ${text}`);
}
