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

Write a file again only through its writer: `UPDATE_CONTRACTS=1 npx vitest run` in `runtime/` (one file of `{about, answer}` per answer through `runtime/test/contract.ts`). Never
by hand: a hand edit is a contract no code keeps.

A change here runs the runtime, iOS and Android workflows (`contracts/**` is in all three paths), so a file written again is read by
both apps' tests on the same pull request, and a hand edit fails its writer.
