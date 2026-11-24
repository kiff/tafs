# Screenshots

The apps with sample data, light and dark. The README's hero is
[screenshots/hero.png](screenshots/hero.png): Tafs on iPhone, then Needs you and Coach on Android.

## iPhone and iPad

From iOS CI's `screenshots` artifact, full set (a tag's build, or a manual run of the iOS
workflow with `screenshots: full` and `upload` off, once no other manual run is in the
queue; a pull request that changes a screen takes a key set). See [ios-app.md](ios-app.md).

Both sets end with the smallest iPhone any available iOS runtime supports, by screen height (an
iPhone SE where there is one), made for the run. They shoot the welcome, the tafs list and Needs you,
in light and dark (`small-…`), each look from a fresh install. The step fails if no iPhone can be
made, and warns if the smallest is taller than an SE. The welcome has no scroll view, so these show
that its drawing shrinks and leaves both buttons on the screen. The key set also takes the welcome on
the largest iPhone (`1-welcome`).

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

## iPad reference

**The look every desktop app follows** (macOS, Windows, Linux, the Mac): the iPad with the
sidebar a signed-in account has, in sample mode, light and dark, at the 13-inch iPad's full
size (2064 × 2752). They're in [screenshots/ipad/](screenshots/ipad/) as `<screen>.png` and
`<screen>-dark.png`:

| Screen | Light | Dark |
|---|---|---|
| Overview | [overview](screenshots/ipad/overview.png) | [dark](screenshots/ipad/overview-dark.png) |
| Needs you | [needs-you](screenshots/ipad/needs-you.png) | [dark](screenshots/ipad/needs-you-dark.png) |
| Record | [record](screenshots/ipad/record.png) | [dark](screenshots/ipad/record-dark.png) |
| Calendar, day | [calendar](screenshots/ipad/calendar.png) | [dark](screenshots/ipad/calendar-dark.png) |
| Calendar, week | [calendar-week](screenshots/ipad/calendar-week.png) | [dark](screenshots/ipad/calendar-week-dark.png) |
| Account | [account](screenshots/ipad/account.png) | [dark](screenshots/ipad/account-dark.png) |
| Account › Alerts | [alerts](screenshots/ipad/alerts.png) | [dark](screenshots/ipad/alerts-dark.png) |
| A taf's page (Activity) | [taf](screenshots/ipad/taf.png) | [dark](screenshots/ipad/taf-dark.png) |
| A taf's Card | [taf-card](screenshots/ipad/taf-card.png) | [dark](screenshots/ipad/taf-card-dark.png) |
| A taf's chat | [taf-chat](screenshots/ipad/taf-chat.png) | [dark](screenshots/ipad/taf-chat-dark.png) |
| A taf's Setup | [taf-setup](screenshots/ipad/taf-setup.png) | [dark](screenshots/ipad/taf-setup-dark.png) |
| Plan & usage | [usage](screenshots/ipad/usage.png) | [dark](screenshots/ipad/usage-dark.png) |
| Connect apps | [connect-apps](screenshots/ipad/connect-apps.png) | [dark](screenshots/ipad/connect-apps-dark.png) |
| One app's connections | [connect-app](screenshots/ipad/connect-app.png) | [dark](screenshots/ipad/connect-app-dark.png) |
| Account › Devices | [devices](screenshots/ipad/devices.png) | [dark](screenshots/ipad/devices-dark.png) |
| New taf (the job gallery) | [new-taf](screenshots/ipad/new-taf.png) | [dark](screenshots/ipad/new-taf-dark.png) |
| Coach, empty | [coach](screenshots/ipad/coach.png) | [dark](screenshots/ipad/coach-dark.png) |

<p>
  <img src="screenshots/ipad/overview.png" width="340" alt="iPad Overview: the sidebar, then what needs you and each taf's line">
  <img src="screenshots/ipad/overview-dark.png" width="340" alt="iPad Overview in dark">
</p>

- **Where they come from:** iOS CI's iPad reference pass (`ref-<screen>[-dark]` in the
  `screenshots` artifact of a run with `screenshots: reference`, or the end of `full`), never
  made by hand. To refresh them, run that and replace these files.
- **Portrait for now.** The CI simulator doesn't turn the device, so they're portrait. The
  sidebar, rows, type and colours are the same either way.
- **No keyboard focus.** The iPad runs with no hardware keyboard, so no focus box is drawn.
- **The chosen row's fill:** a screen opened by launch argument doesn't select its sidebar row
  the way a tap does, so these show none. The iPad's code draws a grey fill on the chosen row.
- **Not here yet:** Coach mid-conversation. Its launch argument (`coach-chat`) opens Coach
  empty in CI, so the sample conversation isn't drawn yet.

## Apple Watch

For the App Store listing, which needs at least one Apple Watch screenshot while the iPhone app carries the watch app.
On a Mac with Xcode, `ios/scripts/watch-store-shots.sh [folder]` makes a watch simulator of an App Store size (an Ultra
where the newest watchOS runtime has one, else a 46 or 45 mm), runs `WatchChecksUITests/testStoreScreenshots` against the
demo phone (`-watch-demo`, Debug builds only), and writes five screens: `watch-1-coach` (Coach's home with its unread
line), `watch-2-inbox`, `watch-3-held-call` (Approve on your phone, and Refuse), `watch-4-tafs` and `watch-5-taf` (a
taf's page). It prints each one's size and warns if it isn't one App Store Connect takes. The test is skipped in the
`watch` job. Nothing is uploaded: they go into the listing by hand, in the Apple Watch tab of the iOS version.
Without a Mac, a manual run of the iOS workflow with `watch_shots` on (and `upload` off) makes them on a runner, as the
`watch-store-shots` artifact.

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
