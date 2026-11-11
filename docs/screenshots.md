# Screenshots

The apps with sample data, light and dark. The README's hero is
[screenshots/hero.png](screenshots/hero.png): Tafs on iPhone, then Needs you and Coach on Android.

## iPhone and iPad

From iOS CI's `screenshots` artifact, full set (a tag's build, or a manual run of the iOS
workflow with `screenshots: full` and `upload` off, once no other manual run is in the
queue; a pull request that changes a screen takes a key set). See [ios-app.md](ios-app.md).

<p>
  <img src="screenshots/ios/welcome.png" width="200" alt="Welcome: They taf. You kiff.">
  <img src="screenshots/ios/tafs.png" width="200" alt="Tafs: three working, two need you, a held migration on top">
  <img src="screenshots/ios/needs-you.png" width="200" alt="Needs you: held calls, each with Approve once and Refuse">
  <img src="screenshots/ios/record.png" width="200" alt="Record: a chip for each taf and a filter by kind">
  <img src="screenshots/ios/taf.png" width="200" alt="A taf's page: what's left today, then its chat">
  <img src="screenshots/ios/card.png" width="200" alt="A taf's Card">
  <img src="screenshots/ios/chat.png" width="200" alt="Chat: ask a taf what it did, or tell it what to do differently">
  <img src="screenshots/ios/setup.png" width="200" alt="A taf's Setup: the job, Starts when, Results go to and what it remembers">
  <img src="screenshots/ios/starts-when.png" width="200" alt="Starts when: its schedule, app events and doors">
  <img src="screenshots/ios/results-go-to.png" width="200" alt="Results go to: where a taf's results are delivered">
  <img src="screenshots/ios/account.png" width="200" alt="Account">
  <img src="screenshots/ios/alerts.png" width="200" alt="Account › Alerts">
</p>

In dark:

<p>
  <img src="screenshots/ios/tafs-dark.png" width="200" alt="Tafs in dark">
  <img src="screenshots/ios/needs-you-dark.png" width="200" alt="Needs you in dark">
  <img src="screenshots/ios/taf-dark.png" width="200" alt="A taf's page in dark">
  <img src="screenshots/ios/setup-dark.png" width="200" alt="Setup in dark">
  <img src="screenshots/ios/record-dark.png" width="200" alt="Record in dark">
  <img src="screenshots/ios/account-dark.png" width="200" alt="Account in dark">
</p>

On iPad:

<p>
  <img src="screenshots/ios/ipad-tafs.png" width="340" alt="iPad: the list of tafs beside a taf's chat, with the bar as a rail">
  <img src="screenshots/ios/ipad-needs-you.png" width="340" alt="Needs you on iPad">
  <img src="screenshots/ios/ipad-setup.png" width="340" alt="A taf's Setup on iPad">
  <img src="screenshots/ios/ipad-setup-dark.png" width="340" alt="Setup on iPad in dark">
</p>

## Android

The sample on a phone, except Coach: the sample has none, so Coach is a signed-in account's,
opened on a new conversation with nothing sent and no account details on screen. More Android
screens, with what each shows, are in [android-app.md](android-app.md).

<p>
  <img src="screenshots/android/tafs.png" width="200" alt="Tafs: three working, two need you">
  <img src="screenshots/android/needs-you.png" width="200" alt="Needs you: a migration and an €8,400 payment held, each with Approve once with your screen lock, an alternative and Refuse">
  <img src="screenshots/android/setup.png" width="200" alt="Refunds' Setup: the job, when it starts, and what it remembers, each note with Forget">
  <img src="screenshots/android/coach.png" width="200" alt="Coach on a new conversation: Ask anything about your tafs, with Improve for each taf">
  <img src="screenshots/android/record.png" width="200" alt="Record: a chip for each taf and a filter by kind, today's entries newest first">
</p>

## Keeping them current

A screenshot that no longer matches its screen is replaced, not kept beside the new one. iOS's
come from CI; Android's from the UI tests' synthetic data or an emulator's sample
([android-app.md](android-app.md#building-and-testing)).
