<img src="docs/brand/tafs-icon-1024.png" alt="" width="96" align="right">

# tafs

An app where AI agents ("tafs") do routine business work: refunds, card
disputes, overdue invoices, new leads. Each taf works under a Card you sign.
Anything over its limits waits for your approval on your phone.

<p align="center">
  <img src="docs/screenshots/hero.png" width="760" alt="tafs on three phones: the list of tafs, held calls waiting for an answer, and Coach">
</p>

For people who run a small business and want routine work done without giving
up the final say.

- **Android:** [Google Play](https://play.google.com/store/apps/details?id=dev.kiff.tafs),
  Android 11 and later, in Europe, the UK and Switzerland.
- **iPhone and iPad:** in testing, on TestFlight, iOS 16 and later.

Both work signed in with KIFF, or with sample data on the phone. Screens:
[docs/screenshots.md](docs/screenshots.md).

Paid in credits, used only when a taf works. There's no free trial. Prices:
[kiff.dev/tafs](https://kiff.dev/tafs). How credits work:
[docs/accounts.md](docs/accounts.md).

## Jobs

Each job comes with the words for Coach and a Card you can change before you
sign. All of them, in 8 languages: [recipes/](recipes/README.md). Or say the
job to Coach in your own words.

| | What the taf does |
|---|---|
| Money | [Answers refund requests and refunds within the Card](recipes/refunds-within-a-limit.md) |
| Money | [Answers card disputes with evidence before the deadline](recipes/chargebacks-with-evidence.md) |
| Money | [Reminds customers of overdue invoices; moves no money](recipes/chase-overdue-invoices.md) |
| Money | [Offers a cancelling subscriber a free month or a discount](recipes/save-a-customer-who-cancels.md) |
| Customers | [Answers order questions from the shop; passes money to you](recipes/answer-questions-about-orders.md) |
| Customers | [Adds each new lead to the CRM and replies within the hour](recipes/follow-up-new-leads.md) |
| Operations | [Books and moves appointments; cancelling always asks](recipes/book-and-move-appointments.md) |
| Operations | [Ships green, approved changes and rolls back a failing one](recipes/ship-when-green.md) |
| Errands | [Files receipts from the mailbox into a folder and a sheet](recipes/file-receipts.md) |

## The Card

Every taf works on a Card: the whole job description. You read it, then you
sign it.

- **Does on its own.** Inside the Card, with a receipt for each piece of work.
- **Asks you first.** Past a limit, one tap on your phone. No answer means no.
- **Never does.** Whatever an email, a customer or the AI itself says.
- **Change it, or stop it, any time.** One tap halts one taf, or all of them.

[KIFF](https://kiff.dev) checks the Card outside the AI, before any call
reaches a tool. Why you can trust it: [docs/](docs/README.md#why-you-can-trust-it).

## How it works

1. **Hand a job to Coach.** Coach asks only what it needs and connects your
   apps. You sign in yourself; the taf never sees your passwords.
2. **Sign the Card.** Coach drafts it; you set the limits and sign on your phone.
3. **The routine gets done.** What's over a limit waits in Needs you.

## Documentation

One page per feature, written from the code: [docs/](docs/README.md). What a
taf runs on: [docs/runtime.md](docs/runtime.md). The API:
[docs/api.md](docs/api.md). Where data lives: [docs/data.md](docs/data.md).

## Building

iOS:

```bash
cd ios
xcodegen generate          # project.yml → Tafs.xcodeproj (not committed)
open Tafs.xcodeproj        # or:
xcodebuild test -project Tafs.xcodeproj -scheme Tafs \
  -destination 'platform=iOS Simulator,name=iPhone 14 Pro'
```

Needs a current Xcode and runs on iOS 16 and later. CI and TestFlight:
[docs/ios-app.md](docs/ios-app.md).

Android:

```bash
cd android
./gradlew :app:testDebugUnitTest          # the unit tests
./gradlew :app:assembleDebug              # a debug build
./gradlew :app:connectedDebugAndroidTest  # the UI tests, on an emulator or phone
```

Needs a JDK 17 or later. Google Play builds come from CI
([docs/android-app.md](docs/android-app.md#building-and-testing)).

## License

[FSL-1.1-ALv2](LICENSE.md), the Functional Source License. You may use, change
and share tafs for anything except a product that competes with it. Each version
becomes Apache 2.0 two years after its release.

## Contributing and project policies

This public repository is source-available under [FSL-1.1-ALv2](LICENSE.md).
The license contains its future Apache-2.0 grant.

- [Contributing and checks](CONTRIBUTING.md)
- [Report a security issue privately](SECURITY.md)
- [Changelog](CHANGELOG.md)
- [Community conduct](CODE_OF_CONDUCT.md)
- [Instructions for coding agents](AGENTS.md)
- [Maintainer and release checklist](docs/maintaining.md)
