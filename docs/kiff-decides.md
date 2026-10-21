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

## On a real phone (2026-10-20 and 21)

The owner's own run, on an iPad with TestFlight builds 429 and the one after
it, against Stripe in test mode. The taf was *Refunds assistant*. Its Card
allowed a refund of up to €100 a call and €1,000 a day, and anything over the
call limit was held for the owner. Three test payments: A €20, B €150 and
C €150. Each answer was the owner's, given in the app with Touch ID, which
signs KIFF's `kiff.answer.v1` with the device's key. Times are UTC.

| When | Asked for | KIFF | The owner, on the device | Stripe |
|---|---|---|---|---|
| 20 Oct 20:25 | refund B in full, €150 | **held**: over the €100 call limit | **approved once** at 20:32, signed | one refund, €150 (`re_…tqw`) |
| 20 Oct 20:38 | refund A in full, €20 | inside the Card: sent | none needed | one refund, €20 (`re_…WYZ`) |
| 20 Oct 20:40 | refund C in full, €150 | **held**: over the call limit | **refused** at 20:42, signed | nothing |
| 20 Oct 22:37 | refund C in full, €150, again | **held** | **refused**, signed | nothing |
| 20 Oct 22:40 | refund C in full, €150, again | **held** | **approved once** at 22:41, signed | one refund, €150 (`re_…lm2`) |

**What it shows:**
- **Nothing over the Card reached Stripe without the owner's answer.** Each
  of the four €150 calls was held. The two the owner approved each went out
  once, and the two they refused sent nothing.
- **The approved refunds count.** The Card's day went from €1,000 to €850
  after B, €830 after A, and €680 after C.
- **The answer is the device's.** The app answers only by signing on the
  device; an answer without the signature is refused (kiff-cloud #1218), and
  the nightly checks that refusal every night.

**What the run found, and what changed** (kiff/tafs#138):
- **The taf went on 27 s (B) and 100 s (C) after the answer.** tafs cloud
  crashed while it pushed to the phones, before handing the answer to
  kiff/agents (#158). Then the run waited for kiff/agents' minute sweep
  (#161, kiff/agents#155). After those fixes, the reply came 8 to 9 s after
  the answer (22:41).
- **The taf gave a reason for the owner's refusal that the owner didn't
  give** ("I asked for €150 without first checking"), and said the call
  waited "in the tafs app". Since #155 it says the owner refused it and
  nothing was sent, and that the call waits for their approval.

The Stripe refund ids are cut to their last characters, as every id on this
page.

## Before the phone: the first held call (2026-10-16)

The first live held call of a taf on kiff/agents, answered on the web, not the
phone. It was a €50 refund, over a €20 call limit, against Stripe in test mode.
- **11:34 UTC:** KIFF held it, and the run stored itself as paused.
- **11:36:** the owner approved it once on app.kiff.dev.
- **11:46:** the run collected it, sent it once, and completed. With no alert
  target registered, the answer reached the run when the hold's 10 minutes
  were up. With the app's push registration, the run goes on when KIFF's
  signed alert arrives, not at the hold's end.
