# tafs

**They taf. You kiff.**

tafs is an iPhone app where a business hands jobs to AI agents, called tafs, and
gives each one a Card: the limits it works inside. Inside the Card a taf acts and
leaves a receipt. Outside it, the taf waits for the owner's answer on their phone.
Revoked, it stops at once.

Cards are enforced by [KIFF](https://kiff.dev): every action a taf takes passes
through KIFF's gateway, which checks the Card before any call reaches a tool.

## The first five jobs

Each is a consequential job companies want agents to do and are afraid to let
them do.

| Taf | What it does | Card, for example |
|---|---|---|
| Refunds | Settles refund and credit requests from the support queue | ≤ $150 each, $2,000 a day; payouts never |
| Bill pay | Pays vendor bills that match a purchase order | ≤ $5,000 each, $25,000 a week; wires always ask; bank detail changes never |
| Access | Gives joiners their apps, removes leavers | Approved roles only; elevated access always asks; admin and deleting users never |
| Deploys | Ships fixes when CI is green, rolls back | 10 deploys a day; migrations always ask; dropping data never |
| Chargebacks | Answers card disputes with evidence | Evidence ≤ $500 each; accepting a loss ≤ $100 each |

## Status

Version 0.1, a working iPhone app on sample data:

- Start a taf: say the job, review the drafted Card, issue it with Face ID.
- Tafs propose actions; each is allowed, held for you, or refused by its Card.
- Answer held actions with Approve once, Return first or Refuse. Each answer is
  signed on the phone (Secure Enclave on a device; a software key in the Simulator).
- Pause or revoke a taf; the Record shows everything, newest first.

In this version the Card is checked on the phone. Next: sign in with KIFF, held
actions from KIFF (`/v1/me/card-exceptions`), Cards from `/v1/me/mandates`, and
the tafs runtime that runs each taf with the Claude Agent SDK.

## Build

```bash
cd ios
xcodegen generate          # project.yml → Tafs.xcodeproj (not committed)
open Tafs.xcodeproj        # or:
xcodebuild test -project Tafs.xcodeproj -scheme Tafs \
  -destination 'platform=iOS Simulator,name=iPhone 14 Pro'
```

Needs Xcode 14.2 or later and iOS 16. The code is written to move to Swift 6
language mode; App Store builds will come from CI with a current Xcode.

## License

To be added after legal review. The intent is FSL-1.1-ALv2 (source-available,
each version Apache 2.0 two years after release). Until then, all rights reserved.
