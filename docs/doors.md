# Doors

A door is a place where work reaches a taf from outside the app, with the answer
going back through the same door. A taf can have an email address, a WhatsApp
number, and a door on any app the deployment's connector catalog connects. The
owner opens, pairs and closes doors in the taf's **Doors** tab.

## The rules

- **Anyone can knock. Only keys give work. Only you say yes to what's held.**
- A **key** is a sender the owner paired with a door. The owner's own sign-in email
  is paired with an email door from the start.
- A key counts only when the door proves the message is theirs:
  - **email**: the receiving server's sender check passes (DMARC, or DKIM signed by
    the From domain, with DMARC not failing), and From names one mailbox. A spoofed
    From is treated as anyone else;
  - **WhatsApp**: WhatsApp gives the sender's number;
  - **a connector app**: the app gives who wrote (a workspace member, an account).
    Apps where anyone can type any name, like a web form, can't prove who wrote:
    their doors hold no keys, and everything there knocks.
- A key's message becomes a chat message for the taf, through the same path as the
  app's chat. The taf works under its Cards, with holds and Face ID unchanged, and
  everything is in the Record as usual.
- Anyone else's message is a **knock**: information, never instructions. The owner
  is told who wrote and what they want, and answers **Do it**, **Pair** or
  **Ignore**. Do it starts a run from the owner's words, which come first, with the
  message after them, fenced by a line its sender can't guess, as information. Pair makes the sender a key for their next message, never this one.
- The door itself only ever writes to the key who wrote, in the same thread. Writing
  to anyone else is the taf's own action, through KIFF, under its Card, like any
  other tool call.
- Every reply says it comes from an AI assistant.

## Limits

- 20 messages a door a day. Past that, the door rests until the next day (UTC) and
  the owner is told once.
- A message longer than 20,000 characters is cut, and the reply says so.
- v1 reads text only. A message with attachments is answered with a line saying
  they weren't opened.
- A message delivered twice starts nothing twice: each message id is remembered 30
  days. A message older than 48 hours is refused, so a delivery captured and sent
  again later starts nothing.
- A removed email address is retired for a year, never given to another taf, so mail
  from its old senders can't reach someone else.
- A reply the provider doesn't take is tried again, up to three times, when the owner
  next reads the chat or the doors or a message comes in. So is one whose run's note
  was lost. Each reply is sent once, however many notes come.
- Replies in one email thread, or from one WhatsApp number, go on in the same chat.

## What's kept

The message's text is stored like a chat message, in kiff/agents. tafs cloud reads
an email once, for its text and the sender check's result, and keeps nothing else of
it: no raw headers, no HTML, no attachments. [data.md](data.md) lists every item.

## How it's built

`runtime/src/cloud/doors/`:

| File | What it does |
|---|---|
| `model.ts` | Doors, keys, knocks, replies; the limits; what is stored where |
| `apps.ts` | The apps a door can open on, as data. Connector apps come from the deployment's config |
| `doors.ts` | A message's way in (door, once, the day's limit, key or knock), the owner's routes, and the reply once the run ends |
| `inbound.ts` | The webhooks: each checks its provider's signature before reading anything |
| `email.ts`, `whatsapp.ts`, `catalog.ts` | The providers |
| `text.ts` | The text a person wrote: quoted history and signatures dropped, HTML read for its words, the cut |

A door's run asks kiff/agents to announce when it ends (`/v1/runs/ended`, as Coach's
tasks do). tafs cloud reads the run back and sends its answer through the door.

### Setting up a deployment

Nothing here is on until the deployment sets it up. tafs cloud reads:

- `DOORS_PUBLIC_URL`: tafs cloud's own URL. Doors are off without it. kiff/agents
  must allow `<DOORS_PUBLIC_URL>/v1/runs/ended` as a notify URL.
- `DOORS_EMAIL_DOMAIN`: the domain the email doors' addresses are on. The email
  provider receives its mail and signs each delivery to `/v1/doors/email`.
- `DOORS_SECRET_ID`: one secret, JSON, with the providers' keys:
  `email_api_key`, `email_webhook_secret`; `whatsapp_app_id`, `whatsapp_app_secret`,
  `whatsapp_config_id`, `whatsapp_verify_token`; `catalog_api_key`,
  `catalog_webhook_secret`, `catalog_base_url`, `catalog_auth_configs` (app id to the
  catalog's sign-in setup). A provider whose keys aren't there isn't offered.
- `DOORS_WHATSAPP_REGION`: where WhatsApp stores a number's message content, set at
  its registration.
- `DOORS_APPS`: the connector apps, JSON. Each names its catalog trigger, where its
  event keeps the sender, the text and the thread, and the tool the taf answers with:

```json
[{
  "id": "slack", "label": "Slack", "kind": "connector", "proof": "proven",
  "how": "The workspace signs people in",
  "event": {
    "trigger": "<the catalog's new-message trigger>",
    "sender": "user", "name": "user_name", "text": "text", "thread": "thread_ts", "id": "ts",
    "reply": { "tool": "slack_send_message", "to": "channel", "thread": "thread_ts" }
  }
}]
```

A connector door's answer is a tool call by the taf, so the taf needs that tool on
its Card, connected through KIFF's gateway. Without it, the answer stays in the chat.

## WhatsApp

**What the owner does.** In Doors, turn the dial to Add a door, then WhatsApp. tafs
opens WhatsApp's signup: the owner signs in with their business, picks or adds the
number, and comes back to the app, which finishes the connection with their session.
Then they pair the numbers that may give work (their own, colleagues'). Everyone else
knocks.

**One number per business.** The number is the owner's business's own, under its own
WhatsApp Business account, doing that business's work. tafs never puts many
businesses on one shared number.

**Verification.** WhatsApp asks the business to verify itself before its number can
write to many people. Until then a new number has low limits. Verification can take
from days to weeks, so an owner who wants WhatsApp should start it early.

**The 24-hour window.** WhatsApp takes a free-form reply only within 24 hours of the
person's last message. A door's reply is sent within that window or not at all: the
chat then says it wasn't sent and why. Writing first, or after the window, needs a
template WhatsApp approved in advance. Doors never do that; a taf that must, does it
as its own tool call through KIFF.

**Where content is stored.** WhatsApp keeps message content in the region a number is
registered with. tafs registers each number with the deployment's region, from its
first registration; changing it later means registering the number again.

**Risks.**
- WhatsApp's rules on AI on the platform change. A door is the business's own
  assistant doing the business's own work, on the business's number.
- A person who loses their phone keeps their number; a key is a number, not a person.
  The owner can unpair at any time.
- WhatsApp may refuse a reply after the window, or limit a new number.

**Size.** Built: the webhook, pairing, the signup's server side and page, replies
within the window. Left: the business's verification (the owner's), templates for
writing first (not planned for doors).

## Live calls (design, not built)

**What the owner gets.** A phone number for a taf. Someone calls; the taf answers in
a live voice, says first that it is an AI assistant working for the business, and
works the call through its normal run, under its Cards.

**A number per taf or per account.** Per taf keeps doors simple: one number, one job.
Per account with a menu ("refunds, press 1") saves numbers when an account has many
tafs. Start per taf.

**Number rules by country.** Many countries ask for proof of a local address or a
company before giving a number, and some limit which numbers can be used by whom.
Registering a number is the owner's, with their company's documents, country by
country. Start with the countries owners are in.

**The call and the run.** A live voice layer talks with the caller and hands the work
to the taf's run as a chat message from the door, as any door does. The run's answer
comes back as speech. Long work ends the call with "I'll write to you" only when the
caller is a key with another door (email or WhatsApp).

**Who is calling.** A caller's number can be faked and a voice can be cloned. So a call
door holds no keys by default: every call is a knock, live. The owner can pair a
number for low-risk work only, and holds still apply.

**Holds during a call.** When the run hits a held call, the taf says "One moment, I'm
checking with the owner", and the owner gets the hold in the Dynamic Island and
answers with Face ID. If there's no answer within a short wait, the taf says it will
follow up, and the hold stays in Needs you. The caller can ask; only the owner says
yes.

**Recording.** The taf says at the start that the call is recorded and why, before
anything else. The transcript is kept like a chat; the audio isn't kept. Where a
country asks for consent beyond that, the call door isn't offered there.

**From the owner.** The number's registration and its documents; which tafs get a
number; which callers, if any, are paired.

**Risks.** Voice cloning and spoofed numbers (so no keys by default); a hold the owner
can't answer in time (the caller waits, then the taf follows up); recording rules by
country.

**Size.** Large: a live voice layer, the hand-off to runs, holds in a call, and number
registration country by country.
