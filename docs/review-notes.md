# App Review notes

What goes in App Store Connect's **App Review Information** (Notes) for the iPhone and iPad app and the Mac app, which are
one app (bot.tafs.app, universal purchase). **One product description for both**, so a reviewer on either platform reads the
same thing; the per-platform lines below are only what differs. The demo account's address and password are **never in
this repository**: they stay in App Store Connect's own fields. Entered when a build that carries the in-app sign-in
(onboarding, `Onboarding.on`, [onboarding.md](onboarding.md)) goes to review; until then the live notes describe the older
sign-in.

## The notes (English)

```
tafs lets a person hand routine jobs to AI agents ("tafs") that work inside limits the person sets. Examples: support tickets, lead follow-up and late parcels. Each taf works within a Card the person issues: what it may do, with which app, and how much. Anything that matters waits in Needs you until the person approves it on their own device (Face ID or Touch ID; on the Mac, Touch ID or the phone). Nothing is sent or changed by a taf past its Card.

How to try it
1. Without an account: on the welcome screen, "Try it with sample data" shows the app with sample content. Nothing is sent anywhere in sample mode.
2. With the demo account (entered in the fields above; it has no live payment or business connections): tick the box that agrees to the Terms, tap Continue, and KIFF's sign-in opens in a sheet inside the app. Choose email, type the demo account's address and password. Signing in with a code sent by email is off. After signing in, three short cards explain tafs, the Card and Needs you; then Home shows Ask Coach, which sets up a taf in conversation.
3. Needs you: open it from Home. Approve once, Next step and Refuse each answer a held action; the sample held action sends nothing until approved.

In-app purchases to review: Solo (monthly, 500 credits) and credit packs of 500, 1,500 and 5,000 (consumable, never expire). Restore Purchases is on the Solo screen. A run of a taf uses credits for the AI it used.

AI and data: a taf's work uses an AI provider (Anthropic) as a sub-processor. The person's content is not used to train models, and run data is deleted after 30 days. Sub-processors are listed at kiff.dev/subprocessors. Sample mode sends nothing.

Chrome: Account > Chrome pairs the person's own Chrome with the tafs Chrome extension, which is a separate product on the Chrome Web Store. The row appears only where the service offers it and is not needed to review the app.

Legal: Apple's standard EULA, the Terms (kiff.dev/terms) and the Privacy Policy (kiff.dev/tafs/privacy) are linked in the app and in the description.
```

## What differs by platform

- **Mac (Mac Catalyst):** the same text. Add: *"On the Mac, Needs you can be approved with Touch ID or on the person's phone; a Mac without Touch ID approves on the phone."* (`onboarding.control_lead_mac`). No other difference in the notes.
- **iPad:** the same text; the wide layout shows Overview beside the list.

## Google Play (Android phones, tablets and Wear OS)

What goes in Play Console's **App content › App access** for dev.kiff.tafs: "All or some functionality is restricted",
with the demo account's address and password in Play's own credential fields, **never in this repository**, as on the
App Store. The demo account is the store reviewer account, named here by role only. The same product description as
above, with these differences, since a Play reviewer follows the steps literally:

- **Approving:** with the phone's screen lock (fingerprint, face or PIN) in place of Face ID or Touch ID
  (`onboarding_control_lead_android`).
- **Signing in:** the same tick, then Continue; KIFF's sign-in opens in a browser tab inside the app (a Custom Tab), and
  the app comes back by itself once signed in (`AppSignIn`, behind `BuildConfig.K5`, on in store builds). Then the same
  three cards and Home.
- **Purchases:** Solo and the credit packs are Play Billing products at Play's prices; Restore purchases shows only while
  Play knows of a purchase KIFF hasn't recorded ([android-app.md](android-app.md)).
- **Wear OS:** the watch app isn't standalone. It needs the phone app signed in on a paired phone, and has no sign-in of
  its own ([watch-wear.md](watch-wear.md)). On the watch: "Ask tafs" (the microphone), Needs you (each held action, with
  **Approve on your phone**) and Your tafs. Without the phone it says so.
- **Microphone:** asked for only when the person taps the microphone. Speech is recognized on the device and only the
  text is sent.

The Play notes (English), for App access's instructions field:

```
tafs lets a person hand routine jobs to AI agents ("tafs") that work inside limits the person sets. Examples: support tickets, lead follow-up and late parcels. Each taf works within a Card the person issues: what it may do, with which app, and how much. Anything that matters waits in Needs you until the person approves it with their phone's screen lock (fingerprint, face or PIN). Nothing is sent or changed by a taf past its Card.

How to try it
1. Without an account: on the welcome screen, "Try it with sample data" shows the app with sample content. Nothing is sent anywhere in sample mode.
2. With the demo account (in the fields above; it has no live payment or business connections): tick the box that agrees to the Terms, tap Continue, and KIFF's sign-in opens in a browser tab inside the app. Choose email, type the demo account's address and password; the app comes back by itself. Signing in with a code sent by email is off. Three short cards explain tafs, the Card and Needs you; then Home shows Ask Coach, which sets up a taf in conversation.
3. Needs you: open it from Home. Approve once and Refuse each answer a held action; the sample held action sends nothing until approved.

Wear OS: the watch app needs this app signed in on the paired phone; it has no sign-in of its own. It shows Ask tafs, Needs you (approved on the phone) and Your tafs.

In-app products: Solo (monthly, 500 credits) and credit packs of 500, 1,500 and 5,000 (consumable, never expire). A run of a taf uses credits for the AI it used.

AI and data: a taf's work uses an AI provider (Anthropic) as a sub-processor. The person's content is not used to train models, and run data is deleted after 30 days. Sub-processors are listed at kiff.dev/subprocessors. Speech for the microphone is recognized on the device. Sample mode sends nothing.

Chrome: Account > Chrome pairs the person's own Chrome with the tafs Chrome extension, which is a separate product on the Chrome Web Store. The row appears only where the service offers it and is not needed to review the app.

Legal: the Terms (kiff.dev/terms) and the Privacy Policy (kiff.dev/tafs/privacy) are linked in the app and on the store listing.
```

## Keeping it right

- When the sign-in steps change (the tick, the sheet, the cards), change "How to try it" with them: reviewers follow it literally. K5's walk ([onboarding.md](onboarding.md)) is the check that the steps hold.
- If a new feature a reviewer could reach needs its own account or setting, say it here; the Docs rows on the release board are **App Store reviewer notes** and **Play reviewer notes**.
- The Play notes' facts must match Play's **Data safety** form too: email, purchases, in-app messages, photos and files the person attaches, app interactions, and device IDs (push token, device key, device name); no audio (recognized on the device), location or advertising ID.
- Facts here must match the Privacy page and the App Privacy answers (see the App Store privacy labels row): name, email, content, purchases, a device key and name registered with KIFF.
