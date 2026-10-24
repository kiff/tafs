import type { Fetch } from "../kiff.js";
import type { Arrival } from "./model.js";
import { address, displayName, htmlText, ownText } from "./text.js";

/**
 * The email door's provider: it receives mail for the tafs domain, tells
 * tafs cloud with a signed webhook naming the message, and sends the replies.
 * The webhook carries no body: tafs cloud reads the message once, takes its
 * text and the sender check's result, and keeps nothing else of it (no raw
 * headers, no HTML).
 */

/** A received email, as much as the door reads. */
export interface ReceivedEmail {
  id: string;
  from: string;
  to: string[];
  subject: string;
  messageID: string;
  text: string | null;
  html: string | null;
  /** Threading headers only: References and In-Reply-To. */
  references: string;
  inReplyTo: string;
  attachments: number;
  /** The sender check, computed by the receiving server, not read from the message. */
  auth: { spf: string; dkim: string; dmarc: string } | null;
  created: string;
}

export interface OutgoingEmail {
  from: string;
  to: string;
  subject: string;
  text: string;
  inReplyTo?: string;
  references?: string;
  /** The provider sends one email per key, however often tafs cloud asks. */
  idempotencyKey: string;
}

export interface EmailProvider {
  received(id: string): Promise<ReceivedEmail>;
  send(email: OutgoingEmail): Promise<void>;
}

export class EmailError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

const API = "https://api.resend.com";

/** The provider's HTTP API, with the deployment's key. */
export function emailProvider(apiKey: string, doFetch: Fetch = fetch, base = API): EmailProvider {
  const headers = { authorization: `Bearer ${apiKey}`, accept: "application/json" };
  return {
    async received(id) {
      if (!/^[A-Za-z0-9-]{1,100}$/.test(id)) throw new EmailError(400, "not an email id");
      const res = await doFetch(`${base}/emails/receiving/${id}`, { headers });
      if (!res.ok) throw new EmailError(res.status, `the email provider answered ${res.status}`);
      return parseReceived(await res.json());
    },
    async send(e) {
      const res = await doFetch(`${base}/emails`, {
        method: "POST",
        headers: { ...headers, "content-type": "application/json", "idempotency-key": e.idempotencyKey },
        body: JSON.stringify({
          from: e.from, to: [e.to], subject: e.subject, text: e.text,
          ...(e.inReplyTo ? { headers: { "In-Reply-To": e.inReplyTo, References: e.references || e.inReplyTo } } : {}),
        }),
      });
      if (!res.ok) throw new EmailError(res.status, `the email provider answered ${res.status}`);
    },
  };
}

/** The provider's answer, read defensively: a field it doesn't send is empty. */
export function parseReceived(raw: unknown): ReceivedEmail {
  const r = (raw ?? {}) as Record<string, unknown>;
  const s = (v: unknown) => (typeof v === "string" ? v : "");
  const list = (v: unknown) => (Array.isArray(v) ? v.filter((x): x is string => typeof x === "string") : typeof v === "string" ? [v] : []);
  const headers = (typeof r.headers === "object" && r.headers !== null ? r.headers : {}) as Record<string, unknown>;
  const header = (name: string) => {
    const found = Object.entries(headers).find(([k]) => k.toLowerCase() === name);
    return found ? s(Array.isArray(found[1]) ? found[1][0] : found[1]) : "";
  };
  const auth = r.authentication as Record<string, unknown> | null | undefined;
  return {
    id: s(r.id),
    from: s(r.from),
    to: [...list(r.to), ...list(r.received_for)],
    subject: s(r.subject),
    messageID: s(r.message_id) || header("message-id"),
    text: typeof r.text === "string" ? r.text : null,
    html: typeof r.html === "string" ? r.html : null,
    references: header("references"),
    inReplyTo: header("in-reply-to"),
    attachments: Array.isArray(r.attachments) ? r.attachments.filter((a) => (a as { content_disposition?: unknown })?.content_disposition !== "inline").length : 0,
    auth: auth && typeof auth === "object" ? { spf: s(auth.spf), dkim: s(auth.dkim), dmarc: s(auth.dmarc) } : null,
    created: s(r.created_at),
  };
}

/**
 * Whether the email is the sender's own: DMARC passed, or DKIM passed (the
 * provider says "pass" only when the signing domain is the From domain, so it
 * is aligned) and DMARC didn't fail. SPF alone isn't: it checks the envelope
 * sender, not From. No result is no proof.
 */
export function provenSender(auth: ReceivedEmail["auth"]): { proven: boolean; spoofed: boolean } {
  if (!auth) return { proven: false, spoofed: false };
  // A failed DMARC is never outweighed: whatever else passed, it wasn't for the From domain.
  if (auth.dmarc === "fail") return { proven: false, spoofed: true };
  return { proven: auth.dmarc === "pass" || auth.dkim === "pass", spoofed: false };
}

/**
 * The email as a door's arrival, to the first address it was sent to on the
 * tafs domain; undefined when none is.
 */
export function emailArrival(e: ReceivedEmail, domain: string): Arrival | undefined {
  const d = domain.toLowerCase();
  const to = e.to.map(address).find((a) => a.endsWith(`@${d}`));
  if (!to) return undefined;
  const from = address(e.from);
  if (!from) return undefined;
  // A From naming several mailboxes proves none of them: it can only knock.
  const many = (e.from.match(/@/g) ?? []).length > 1;
  const checked = provenSender(e.auth);
  const proven = checked.proven && !many;
  const spoofed = checked.spoofed;
  const body = ownText(e.text ?? htmlText(e.html ?? ""));
  const refs = e.references.split(/\s+/).filter(Boolean);
  return {
    route: `email:${to}`,
    id: e.messageID || e.id,
    from,
    ...(displayName(e.from) ? { name: displayName(e.from) } : {}),
    proven,
    ...(spoofed ? { spoofed } : {}),
    ...(e.subject ? { subject: e.subject.slice(0, 300) } : {}),
    text: body,
    attachments: e.attachments,
    thread: { message_id: e.messageID, references: [...refs, e.messageID].filter(Boolean).join(" "), subject: e.subject },
    // A thread is named by its first message: the root of References, else this one.
    threadKey: refs[0] || e.inReplyTo || e.messageID || e.id,
    at: e.created || new Date().toISOString(),
  };
}

/** "Re: <subject>", once. */
export function replySubject(subject: string | undefined): string {
  const s = (subject ?? "").trim();
  if (!s) return "Re: your message";
  return /^re:/i.test(s) ? s : `Re: ${s}`;
}

