# Vectors

What KIFF decides, written by hand from KIFF's contract and read by more than one app's tests, so the apps can't
drift. Unlike `contracts/`, nothing here is written by tafs cloud's tests.

| File | Written from | Read by |
|---|---|---|
| `passkey-registration.json` | KIFF's pairing contract, "Registering one": the challenge (computed in Python, apart from either app), the platform's create request, a creation response and the `POST /v1/me/passkeys` body it maps to, a name's rules, `issued_at` | `ios/TafsTests/PasskeyRegistrationTests.swift`, Android's `PasskeyRegistrationTest` |

Change a vector only with the contract it's written from, and in the same pull request as the tests that read it.
