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

## Keeping it right

- When the sign-in steps change (the tick, the sheet, the cards), change "How to try it" with them: reviewers follow it literally. K5's walk ([onboarding.md](onboarding.md)) is the check that the steps hold.
- If a new feature a reviewer could reach needs its own account or setting, say it here; the Docs row on the release board is **App Store reviewer notes**.
- Facts here must match the Privacy page and the App Privacy answers (see the App Store privacy labels row): name, email, content, purchases, a device key and name registered with KIFF.
