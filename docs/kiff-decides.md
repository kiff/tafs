# KIFF decides every taf action

tafs says a taf proposes and KIFF decides. This page shows it on real runs, and
names what keeps showing it. It is written from runs, not from intent. Every
id in it is masked. (kiff/tafs#31)

## What it rests on

- **One way out.** A taf reaches the company's tools only through KIFF's
  gateway. kiff/agents gives an agent its gateway's tools and its own memory,
  nothing else, and tafs' definition names only KIFF's gateway
  (`runtime/test/tafdef.test.ts`, "reaches the company's tools only through
  KIFF's gateway").
- **No key in the taf.** The taf's KIFF key sits in the tenant's secrets.
  kiff/agents reads it by reference (`key_ref`), and the model never sees it.
- **The owner's answer is the phone's.** A held call is answered on the
  owner's phone: Face ID signs KIFF's `kiff.answer.v1` message (this hold,
  this answer, the hold's accounting version, the time) with the phone's key.
  A session bound to that phone can't answer without the signature
  (kiff-cloud #1218).

## Every night: held, then answered on the phone

kiff/agents' nightly end-to-end test (`cmd/e2e`, kiff/agents#109) runs it
against production KIFF and planque, and a stand-in payments vendor. It signs
answers the way the app signs them. The run below is 2026-10-18's, by hand,
before the first nightly.

**The setup,** in the owner's words to Setup: "A single refund over 5 euros
(500 cents) should come to me to approve on my phone first. Over a day, 50
euros at most." Setup proposed the Card in those terms, and asked how the day's
limit should end. The owner chose that it should come to them too. The owner
approved the Card on the phone.

**Then the owner asked the taf for four refunds:**

| Asked for | KIFF | The owner, on the phone | The vendor | The taf said |
|---|---|---|---|---|
| €3 | inside the Card: sent | none needed | one refund, €3 | "I refunded 3.00 EUR (300 cents) on charge …" |
| €900 | **held**: over the call limit and the day's | **refused**, signed | nothing | "KIFF is holding it for your approval, and nothing has been sent" (before the answer) |
| €8 | **held**: over the call limit | **approved once**, signed | one refund, €8 | "it was over my 5.00 EUR per-refund limit, so it was held until you approved it" |
| €9 | **held**: over the call limit | **refused**, signed | nothing | "it went to you for approval, you rejected it, and nothing was refunded" |

**What the test checks, each night:**
- **Only the phone answers.** Before each signed answer, the same answer is
  sent unsigned, and KIFF refuses it (403 `signature_required`).
- **A held call pauses the run, and nothing is sent until the owner answers.**
  The run continues with KIFF's outcome once the answer is recorded.
- **The vendor received exactly two refunds:** €3, inside the Card, and €8,
  which the owner approved, each once. It received nothing for the €9 the owner
  refused or the €900.
- **No key, token or claim secret is in any recorded event.**

The test's public log shows only its steps, with every id masked.

## On a real phone

The issue asks for a real phone. Once the nightly is green, one run on the
TestFlight build closes it: a held refund approved on the device, then one
refused. Its receipts go here.

| When | What |
|---|---|
| | *(to come: the owner's run on the device)* |

## Before the phone: the first held call (2026-10-16)

The first live held call of a taf on kiff/agents, answered on the web, not the
phone. It was a €50 refund, over a €20 call limit, against Stripe in test mode.
- **11:34 UTC:** KIFF held it, and the run stored itself as paused.
- **11:36:** the owner approved it once on app.kiff.dev.
- **11:46:** the run collected it, sent it once, and completed. With no alert
  target registered, the answer reached the run when the hold's 10 minutes
  were up. With the app's push registration, the run goes on when KIFF's
  signed alert arrives, not at the hold's end.
