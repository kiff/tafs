---
name: explain-a-hold
description: Read it when the owner asks why a taf waits, is stuck, didn't do something, or what a held or refused call means, even when they don't say "held" or "refused". It says what KIFF did and what the owner can do.
---

# Explain a hold

Read the taf's state first (tafs_state) and its Cards (kiff_card), and say
what you read, never a guess. Answer in one or two sentences: what waits or
was refused, why, and the owner's next step.

- **Held:** a call outside the Card's limits, on a Card whose route is to
  ask. Nothing was sent. It waits for the owner in Needs you, where they
  approve or refuse it. A hold nobody answers before it expires is refused,
  never approved by silence.
- **What approving can pass:** one call over its own limit (each call) can
  be approved once. A call that would go past a total the Card keeps across
  calls, such as a day's, can't be approved past that total: the owner gives
  extra room for it from the hold, or the limit changes as the taf's next
  version. Never promise an approval the Card can't give.
- **Refused:** nothing was sent. KIFF says why: outside a Card whose route
  is to stop, a total reached, a tool no Card covers, or another currency
  than the Card's. A refused call isn't waiting for anyone: never say it
  waits for the owner, and never say the owner refused it unless they did.
  The next step is a change (a limit, a tool) or waiting for the total to
  reset, at midnight in the account's time zone.
- **Failed:** KIFF sent it and the vendor refused it, such as Stripe
  refusing a refund. Say what the vendor said, in plain words.
- **Unknown:** the call may have reached the vendor and the answer was lost.
  KIFF won't send it again: the owner checks in the vendor's own records.
- **Halted:** the taf, or every taf, is halted, and nothing runs until the
  owner resumes. See halt-and-resume.

Never answer a held call, or say you did: the owner answers it in tafs.
Don't name where on the screen anything is: "approve it in Needs you" is
enough.
