import { randomInt } from "node:crypto";
import type { Fetch } from "../kiff.js";
import type { Arrival } from "./model.js";

/**
 * The WhatsApp door: one business number per account, the business's own,
 * connected through WhatsApp's signup flow. WhatsApp proves who wrote by their
 * number. It takes a free-form reply only within 24 hours of the person's
 * last message; a door's reply is always within that window or not sent.
 * Message content is stored in the region the number is registered with
 * (config), from its first registration.
 */

const GRAPH = "https://graph.facebook.com/v21.0";

export interface WhatsAppClient {
  /** The signup's code, exchanged for the business's token. */
  exchange(code: string): Promise<string>;
  /** WhatsApp sends this app the business account's messages. */
  subscribe(wabaID: string, token: string): Promise<void>;
  /** The number takes messages through the platform, its content stored in `region`. */
  register(phoneNumberID: string, token: string, pin: string, region: string | undefined): Promise<void>;
  /** The number as people dial it. */
  display(phoneNumberID: string, token: string): Promise<string>;
  send(phoneNumberID: string, token: string, to: string, text: string, replyTo?: string): Promise<void>;
}

export class WhatsAppError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

export function whatsappClient(app: { id: string; secret: string }, doFetch: Fetch = fetch, base = GRAPH): WhatsAppClient {
  const call = async (method: string, path: string, token: string | undefined, body?: unknown) => {
    const res = await doFetch(`${base}/${path}`, {
      method,
      headers: { ...(token ? { authorization: `Bearer ${token}` } : {}), accept: "application/json", ...(body ? { "content-type": "application/json" } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
    const out = (await res.json().catch(() => ({}))) as Record<string, unknown>;
    if (!res.ok) {
      const message = typeof (out.error as { message?: unknown })?.message === "string" ? (out.error as { message: string }).message : `WhatsApp answered ${res.status}`;
      throw new WhatsAppError(res.status, message);
    }
    return out;
  };
  return {
    async exchange(code) {
      const q = new URLSearchParams({ client_id: app.id, client_secret: app.secret, code });
      const out = await call("GET", `oauth/access_token?${q}`, undefined);
      if (typeof out.access_token !== "string" || !out.access_token) throw new WhatsAppError(502, "WhatsApp gave no token");
      return out.access_token;
    },
    async subscribe(wabaID, token) {
      await call("POST", `${encodeURIComponent(wabaID)}/subscribed_apps`, token);
    },
    async register(phoneNumberID, token, pin, region) {
      await call("POST", `${encodeURIComponent(phoneNumberID)}/register`, token, {
        messaging_product: "whatsapp", pin, ...(region ? { data_localization_region: region } : {}),
      });
    },
    async display(phoneNumberID, token) {
      const out = await call("GET", `${encodeURIComponent(phoneNumberID)}?fields=display_phone_number`, token);
      return typeof out.display_phone_number === "string" ? out.display_phone_number : phoneNumberID;
    },
    async send(phoneNumberID, token, to, text, replyTo) {
      await call("POST", `${encodeURIComponent(phoneNumberID)}/messages`, token, {
        messaging_product: "whatsapp", recipient_type: "individual", to, type: "text",
        text: { body: text, preview_url: false }, ...(replyTo ? { context: { message_id: replyTo } } : {}),
      });
    },
  };
}

/** A six-digit two-step PIN for a number's registration. */
export function newPIN(): string {
  return String(randomInt(0, 1_000_000)).padStart(6, "0");
}

/**
 * The messages in a WhatsApp webhook, as arrivals. Delivery and read statuses
 * aren't messages and are left out. A message that isn't text (a photo, a
 * voice note) arrives with its caption, if any, and counts as an attachment.
 */
export function whatsappArrivals(raw: unknown): Arrival[] {
  const out: Arrival[] = [];
  const body = (raw ?? {}) as { entry?: Array<{ changes?: Array<{ field?: string; value?: Record<string, unknown> }> }> };
  for (const entry of body.entry ?? []) {
    for (const change of entry.changes ?? []) {
      const v = change.value ?? {};
      const phoneNumberID = (v.metadata as { phone_number_id?: unknown })?.phone_number_id;
      if (typeof phoneNumberID !== "string") continue;
      const contacts = Array.isArray(v.contacts) ? (v.contacts as Array<{ wa_id?: unknown; profile?: { name?: unknown } }>) : [];
      for (const m of (Array.isArray(v.messages) ? v.messages : []) as Array<Record<string, unknown>>) {
        const from = typeof m.from === "string" ? m.from.replace(/\D/g, "") : "";
        const id = typeof m.id === "string" ? m.id : "";
        if (!from || !id) continue;
        const contact = contacts.find((c) => c.wa_id === m.from);
        const type = typeof m.type === "string" ? m.type : "";
        const media = m[type] as { caption?: unknown } | undefined;
        const text = type === "text"
          ? String((m.text as { body?: unknown })?.body ?? "")
          : typeof media?.caption === "string" ? media.caption : "";
        const ts = Number(m.timestamp);
        out.push({
          route: `whatsapp:${phoneNumberID}`,
          id,
          from,
          ...(typeof contact?.profile?.name === "string" ? { name: contact.profile.name } : {}),
          proven: true,
          text: text.trim(),
          attachments: type === "text" ? 0 : 1,
          thread: { message_id: id },
          threadKey: from,
          at: Number.isFinite(ts) && ts > 0 ? new Date(ts * 1000).toISOString() : new Date().toISOString(),
        });
      }
    }
  }
  return out;
}

/**
 * The page that runs WhatsApp's signup for a taf, served by tafs cloud and
 * opened by the app. It hands the result back to the app, which finishes the
 * connection with the owner's own session; the page itself can do nothing.
 */
export function signupPage(opts: { appID: string; configID: string; taf: string; back: string }): string {
  const cfg = JSON.stringify({ appID: opts.appID, configID: opts.configID, taf: opts.taf, back: opts.back }).replace(/</g, "\\u003c");
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Connect WhatsApp</title>
<style>body{font:17px -apple-system,system-ui,sans-serif;background:#FBF7EE;color:#1B1726;margin:0;padding:48px 24px;text-align:center}
button{font:600 17px -apple-system,system-ui,sans-serif;border:0;border-radius:16px;padding:16px 24px;color:#fff;background:linear-gradient(120deg,#FF9A2E,#FF4F7B 55%,#2F6BFF)}
p{color:#4F4A5E;max-width:30em;margin:16px auto}</style></head><body>
<h1>Connect WhatsApp</h1>
<p>Sign in with the business that owns the number. Your taf answers on that number, and only people you pair can give it work.</p>
<button id="go">Continue</button><p id="note"></p>
<script>
const C=${cfg};let ids={};
window.addEventListener("message",e=>{if(!/facebook\\.com$/.test(new URL(e.origin).hostname))return;try{const d=JSON.parse(e.data);if(d.type==="WA_EMBEDDED_SIGNUP"&&d.event==="FINISH")ids=d.data||{}}catch{}});
window.fbAsyncInit=()=>FB.init({appId:C.appID,autoLogAppEvents:true,xfbml:false,version:"v21.0"});
document.getElementById("go").onclick=()=>FB.login(r=>{const code=r&&r.authResponse&&r.authResponse.code;
if(!code){document.getElementById("note").textContent="Not connected. You can try again.";return}
const q=new URLSearchParams({taf:C.taf,code,phone_number_id:ids.phone_number_id||"",waba_id:ids.waba_id||""});location.href=C.back+"?"+q},
{config_id:C.configID,response_type:"code",override_default_response_type:true,extras:{setup:{},sessionInfoVersion:"3"}});
</script><script async defer crossorigin="anonymous" src="https://connect.facebook.net/en_US/sdk.js"></script></body></html>`;
}
