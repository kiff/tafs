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

Write a file again only through its writer: `UPDATE_CONTRACTS=1 npx vitest run` in `runtime/` (one file of `{about, answer}` per answer through `runtime/test/contract.ts`). Never
by hand: a hand edit is a contract no code keeps.

A change here runs the runtime, iOS and Android workflows (`contracts/**` is in all three paths), so a file written again is read by
both apps' tests on the same pull request, and a hand edit fails its writer.
