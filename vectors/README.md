# Vectors

What KIFF decides, written by hand from KIFF's contract and read by more than one app's tests, so the apps can't
drift. Unlike `contracts/`, nothing here is written by tafs cloud's tests.

| File | Written from | Read by |
|---|---|---|
| `chrome-pairing.json` | docs/chrome.md, "Pairing": a pairing's secret, the extension's and the phone's keys (fixed bytes), the extension's proof, `chrome_key_sha256`, the phone's proof, and the bytes the phone signs; the extension's view-only KIFF device offer (`kiff_offer`, a real P-256 key) and the proof that covers it | `chrome/test/vectors.test.js`, `ios/TafsTests/ChromePairingTests.swift`, and Android's once it pairs a Chrome |
| `chrome-grant.json` | docs/chrome.md, "Steps": a browser grant's sites and one sensitive site, and the bytes the phone signs for each (`tafs-chrome/sites/v1`, `tafs-chrome/sensitive/v1`) when the owner approves the grant | `chrome/test/vectors.test.js`, `ios/TafsTests/ChromePairingTests.swift`, and Android's |
| `chrome-sensitive.json` | `chrome/src/sites.js` `SENSITIVE`: the sites and the labels the extension blocks unless the phone allows them, and how sample hosts read | `chrome/test/vectors.test.js`, `ios/TafsTests/ChromePairingTests.swift`, and Android's |
| `passkey-registration.json` | KIFF's pairing contract, "Registering one": the challenge (computed in Python, apart from either app), the platform's create request, a creation response and the `POST /v1/me/passkeys` body it maps to, a name's rules, `issued_at` | `ios/TafsTests/PasskeyRegistrationTests.swift`, Android's `PasskeyRegistrationTest` |

Change a vector only with the contract it's written from, and in the same pull request as the tests that read it.
