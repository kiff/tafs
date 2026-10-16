/**
 * Hands KIFF's alert to kiff/agents' alerts function, as KIFF signed it.
 *
 * tafs cloud has already checked the signature with the account's secrets, so
 * a 401 means kiff/agents checked it with another secret: another target's,
 * written in between by a concurrent alert. That fails, so KIFF delivers the
 * alert again and the retry writes this alert's secret first. Any other 4xx
 * is final (an alert kiff/agents doesn't take): logged, and KIFF isn't asked
 * again. A 5xx or no answer fails too.
 */
export async function forwardAlert(doFetch: typeof fetch, url: string, signature: string, raw: string): Promise<void> {
  const res = await doFetch(url, {
    method: "POST",
    headers: { "content-type": "application/json", "kiff-signature": signature },
    body: raw,
    signal: AbortSignal.timeout(10_000),
  });
  if (res.ok) return;
  const text = (await res.text().catch(() => "")).slice(0, 200);
  if (res.status >= 400 && res.status < 500 && res.status !== 401) {
    console.error(`alert: kiff/agents answered ${res.status}: ${text}`);
    return;
  }
  throw new Error(`kiff/agents answered ${res.status}: ${text}`);
}
