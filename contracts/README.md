# Contracts

What one side of a join answers, written by that side's own code, and read by the other side's tests from the same file. A
change to an answer's shape fails the test that writes it until the file is written again, then fails in whichever reader can't
read it: in CI, in seconds, never first on a phone or in an Eval.

| File | Written by | Read by |
|---|---|---|
| `ai-consent.json` | `runtime/test/contract_apps.test.ts` (tafs cloud's handler) | `ios/TafsTests/ContractTests.swift`, `android/app/src/test/kotlin/bot/tafs/app/ContractAppsTest.kt` |
| `setup-start.json`, `setup-start-draft-alone.json` | `runtime/test/cloud.test.ts` (Coach's first start) | the same two |
| `setup-time-zone.json` | `runtime/test/cloud.test.ts` (a new account's zone) | the same two |
| `recipes-home.json` | `runtime/test/cloud.test.ts` (Home's part of the recipes) | the same two |
| `taf-activity.json` | `runtime/test/activity.test.ts` (a taf's Activity) | the desktop apps (their Activity tab); the phones once they read it |
| `taf-setup.json` | `runtime/test/cloud.test.ts` (a taf's Setup tab, one Card-unit exception) | the desktop apps (their Setup tab); the phones once they read it |
| `taf-setup-connected.json`, `taf-setup-reconnect.json` | `runtime/test/cloud.test.ts` (a taf's Setup tab with Gmail connected, then with its sign-in expired and Connect again) | the same |
| `taf-runs.json` | `runtime/test/cloud.test.ts` (a taf's runs: one stopped on an error, one with none) | the desktop apps (their Overview's last result) |
| `taf-run-report.json`, `taf-run-report-running.json`, `taf-run-report-terminated.json` | `runtime/test/cloud.test.ts` (one run's report: ended with its count from the record, still working, stopped on an error) | the same |
| `tafs.json`, `tafs-paused.json` | `runtime/test/cloud.test.ts` (the tafs list: one live taf with its next run and clock, then paused) | the desktop apps (their Overview, Your tafs and a taf's page) |
| `home.json`, `home-paused.json` | `runtime/test/cloud.test.ts` (Home's list: the same taf with its refund Card, its state, work line and health, then paused) | the same; the phones once they read it |
| `setup-coach.json` | `runtime/test/cloud.test.ts` (`GET /v1/setup` once the account has its Coach) | the desktop apps (their Coach conversation); the phones once they read it |
| `coach-chats.json`, `coach-conversation.json` | `runtime/test/cloud.test.ts` (Coach's chats, and one chat: a reply with its work, a refused connection, the owner's words answering a questions card, an approval and a result answered, and a line Coach hasn't read) | the same |
| `conversation-failed.json` | `runtime/test/chat.test.ts` (a taf's chat once the run a card's answer resumed was refused by the AI provider: `failed` with its code and words, neither at work nor waiting) | the desktop apps and the phones, which draw `failed.words` as the chat's last line, once per run |
| `coach-wider.json` | `runtime/test/approval.test.ts` (Coach's chat read with the device's planque proof: an approval card whose connection planque couldn't narrow, marked `wider: {words}`) | `ios/TafsTests/ContractTests.swift`, `android/app/src/test/kotlin/bot/tafs/app/ContractAppsTest.kt`; every desktop app that can approve a card (it draws the line over the card's buttons) |
| `connect-apps.json`, `connect-apps-search.json`, `connect-apps-next.json` | `runtime/test/cloud.test.ts` (Connect apps: the first page with the account's own apps and a provider's next page to ask for, a search, and that next page) | the desktop apps (their Connect apps); the phones once they read it |
| `coach-wording.json` | by hand, as data: Coach's wording rules in the 8 languages, kept equal to `runtime/src/cloud/coach-wording.ts` by `runtime/test/coach-wording.test.ts` | Setup's definition (`setupdef.ts`, `wording`), which kiff/agents' `show` refuses a card by, and the Eval's plan gate, beside its own list |
| `connect-apps-removed.json` | `runtime/test/cloud.test.ts` (Connect apps with the phone's signed grants read: a removed taf's connections, one kept for a live taf's Card and one Clear ends) | the same |
| `files-list.json`, `files-file.json`, `files-download.json` | `runtime/test/files.test.ts` (Files: the account's delivered files with their taf's name, one with its plain-text preview, and its one-minute download) | the phones' and iPad's Files, once they read it |
| `doors-api.json` | `runtime/test/doors-api.test.ts` (a taf's doors with one From another application: the fields older builds require, its counts, last request and keys by name and last four) | the phones' and iPad's Doors (row 4's screens), and the older builds' plain door |
| `doors-api-draft.json` | `runtime/test/doors.test.ts` ("Coach's draft": the door Coach drafted, read back for the owner's yes, and the body the apps sign and send to make it) | the phones' and iPad's card for Coach's door draft (`show {type: "door"}`) |

The iPhone and iPad read every file their own reads decode (`ios/TafsTests/ContractTests.swift`): Activity, Setup, runs and
their reports, the tafs list, Home, `/v1/setup`, Coach's chats and conversation, and Connect apps, each as the type its
read decodes, with the same decoder.

Write a file again only through its writer: `UPDATE_CONTRACTS=1 npx vitest run` in `runtime/` (one file of `{about, answer}` per answer through `runtime/test/contract.ts`). Never
by hand: a hand edit is a contract no code keeps.

A change here runs the runtime, iOS and Android workflows (`contracts/**` is in all three paths), so a file written again is read by
both apps' tests on the same pull request, and a hand edit fails its writer.
