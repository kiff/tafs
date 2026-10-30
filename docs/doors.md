# Doors

A door is a place where work reaches a taf from outside the app, with the answer
going back through the same door. A taf can have an email address and a WhatsApp
number. A taf has no door until its owner turns one on in its **Doors** tab, where
they also pair senders and close doors. The app shows Doors only where the
deployment offers a door; WhatsApp is offered only once it's set up.

## The rules

- **Anyone can knock. Only keys give work. Only you say yes to what's held.**
- A **key** is a sender the owner paired with a door. The owner's own sign-in email
  is paired with an email door from the start. In the list of keys, **You** marks only
  the key that is the signed-in person's own email; anyone else's key shows its name or
  address.
- A key counts only when the door proves the message is theirs:
  - **email**: the receiving server's sender check passes (DMARC, or DKIM signed by
    the From domain, with DMARC not failing), and From names one mailbox. A spoofed
    From is treated as anyone else;
  - **WhatsApp**: WhatsApp gives the sender's number.
- A key's message becomes a chat message for the taf, through the same path as the
  app's chat. The taf works under its Cards, with holds and Face ID unchanged, and
  everything is in the Record as usual.
- Anyone else's message is a **knock**: information, never instructions. The owner
  is told who wrote and what they want, and answers **Do it**, **Pair** or
  **Ignore**. Do it starts a run from the owner's words, which come first, with the
  message after them, fenced by a line its sender can't guess, as information.
  Pair makes the sender a key for their next message, never this one. That message
  still waits (`paired: true`), and the apps show it as "Paired · Do it or Ignore this
  message", not as a stranger waiting on the owner.
- The door itself only ever writes to the key who wrote, in the same thread. Writing
  to anyone else is the taf's own action, through KIFF, under its Card, like any
  other tool call.
- Every reply says it comes from an AI assistant.
- Only the people who manage the taf see its **Doors** tab, its door conversations, the knocks waiting
  for an answer, and who wrote in Activity, strangers' text included. Someone who only
  uses the taf sees their own chats, and Activity without the sender. A knock's alert
  goes to the phones of people who manage Cards. Only someone who manages Cards turns
  doors on or off, pairs senders and answers knocks.
- A message delivered twice starts nothing twice, per sender; a thread goes on in its
  own sender's chat only.

## Limits

- Two daily caps a door, counted apart (days in UTC):
  - 20 messages from the owner and paired senders;
  - 10 from anyone else, so strangers can't use up the owner's day.
  Past either cap, what comes in is dropped until the next day. The owner gets one
  line for it, which counts how many more were dropped that day, and one alert.
- A message longer than 20,000 characters is cut, and the reply says so.
- v1 reads text only. A message with attachments is answered with a line saying
  they weren't opened.
- A message delivered twice starts nothing twice: each message id is remembered 30
  days. A message older than 48 hours is refused, so a delivery captured and sent
  again later starts nothing.
- A removed email address is retired for a year, never given to another taf, so mail
  from its old senders can't reach someone else.
- A message is one request in kiff/agents (`dedup_key`, `source`): sent again after tafs cloud lost
  kiff/agents' answer, it gets the run it started, never a second, and one reply.
- A reply the provider doesn't take is tried again after 5, 20 and 60 minutes, by the
  sweep that runs every 5 minutes (`sweepDoorReplies`). After the last try the chat
  says it wasn't sent. A try isn't sent before its time, even when the owner reads the
  chat. A reply whose run's note was lost is sent when the owner next reads the chat or
  the doors, or a message comes in. Each reply is sent once, however many notes come.
- Replies in one email thread, or from one WhatsApp number, go on in the same chat.

## Where results go

A taf's results go to the places set for it (`PUT /v1/tafs/{id}/results`):
- **`door_reply`:** the answer to the sender, through the door it came in on, on the same thread.
  A taf that never set its places has only this.
- **`app`:** an alert to the phones of the people who manage the taf, never with the taf's words.
  It says who was answered, and through which door, only once the door's reply went out. While the
  reply waits for its retry, the alert waits too. If the reply is given up on, the alert says it
  wasn't sent and that it can be sent again from the taf.
- **`owner_email`:** an email to the owner, at the address their email door pairs, with the
  taf's answer. It isn't sent when the owner wrote the message, since they get the reply.

Each place is sent once (`settle` in `doors.ts`). A place that fails is tried again alone on the
same waits as the door's reply, so a reply already sent isn't sent again while the owner's email
waits. A door's reply given up on after its last try is kept for 30 days, and the owner can
send it again from the taf's page (**Send again**). That sends the taf's answer once more, with new
tries, and does no work.

## What's kept

The message's text is stored like a chat message, in kiff/agents. tafs cloud reads
an email once, for its text and the sender check's result, and keeps nothing else of
it: no raw headers, no HTML, no attachments. [data.md](data.md) lists every item.

## How it's built

`runtime/src/cloud/doors/`:

| File | What it does |
|---|---|
| `model.ts` | Doors, keys, knocks, replies; the limits; what is stored where |
| `apps.ts` | The apps a door can open on: email and WhatsApp |
| `doors.ts` | A message's way in (door, once, the day's caps, key or knock), the owner's routes, and the reply once the run ends |
| `inbound.ts` | The webhooks: each checks its provider's signature before reading anything |
| `email.ts`, `whatsapp.ts` | The providers |
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
  `whatsapp_config_id`, `whatsapp_verify_token`. A provider whose keys aren't there
  isn't offered.
- `DOORS_WHATSAPP_REGION`: where WhatsApp stores a number's message content, set at
  its registration.

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

## The door's words

Each door carries `words` (`doorWords` in `doors.ts`): `state` (open, closed, or resting until
tomorrow, with its address), `tap` (what a tap on it does), and `settings` (that paired senders and
today's limits are below). The apps show them as they are.

## Answering someone outside

A door's message reaches the taf with a first line saying who wrote and that the answer goes back
to someone outside the company whom the owner lets give it work (`doorMessage`): a paired sender, since a
stranger's message is a knock and never starts a run. It tells the taf to do what they ask within its job, reads included, and to answer
with what they asked for in plain words. A paired sender asking for the last three charges gets them;
refusing them would make the door useless for reads. When something failed, it says only that it
can't be done now and the owner will follow up: never an error, a code, a tool, an id, or how anything is connected. The details stay in the owner's chat.
