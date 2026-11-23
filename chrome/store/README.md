# Chrome Web Store paperwork (draft, prep only)

What is ready for the day the owner registers the developer account. **Nothing here is submitted**, and nothing in it
runs: it is text.

| File | What it is |
|---|---|
| `listing/<code>.md` | The store listing in one language: name, short description (at most 132 characters, Chrome's limit) and the long description. English and the 39 other languages the apps ship (40 files, one per `shipped` language in `app/strings/languages.json`). |
| `privacy-practices.md` | The answers for the dashboard's Privacy practices tab: single purpose, each permission, remote code, data use, the three certifications, with the judgment calls for the reviewers. |
| `check.py` | `python3 chrome/store/check.py`: every shipped language has a listing, the short description is within 132 characters and is the manifest's own where the extension has the language, no placeholder is left, the privacy link is there. |
| `screenshots/*.png` | The listing's screenshots, 1280×800 (the store's size): pairing, a taf at work, and a step handed to you ("Your turn"). Each is the extension's own side panel, next to a made-up shop (`books.example`), with made-up tafs and messages: no real account, site or person. |
| `screenshots.cjs` | `node chrome/store/screenshots.cjs` (with Playwright and its Chromium) draws them again: the side panel from `chrome/sidepanel`, in English from `_locales/en`, with a stand-in for Chrome's APIs that holds only the scenes' made-up state. Run it after the side panel changes. |

## Where the words come from

- **The short description** is the extension's own `description` message (`chrome/_locales/<lang>/messages.json`) for
  the ten languages the extension has (de, es, fr, it, ja, ko, nl, pl, pt-PT, pt-BR), so the store and the toolbar say
  the same thing. The other 29 are written here, in the app's terms, at most 132 characters.
- **The long description** is the same lines in every language, from `docs/chrome.md` and the tafs Privacy page's
  "Your browser (Chrome)" item: how it pairs, what it reads, what it never does, what is kept and for how long. The
  path to pairing is each language's own app words (`settings.title`, `chrome.title`, `chrome.pair`), and "Share this
  page" is the extension's own label where it has one.
- **First drafts:** no native speaker has read them, as with every language the apps ship (fixes go forward).

## One thing to decide: the store takes a listing only for a language the extension has

The Chrome Web Store dashboard lets a developer edit a listing in the languages whose `_locales/<code>/messages.json`
the extension holds (Chrome's i18n docs say each store language is one of the extension's `_locales` folders, and
developers report the dashboard offers only those; confirm it on the dashboard). The extension has ten today. The other 29 listings here can be pasted into the dashboard only
after the extension has those `_locales` folders, and the extension's own locale test wants every locale to have
English's keys (about 85 messages). That is one more translation job (the side panel's words, in the app's terms), and
this draft does not include it: say so and it is the next piece. Chrome falls back to English for a language the store
doesn't list, so nothing breaks without it.

The dashboard's own list of accepted languages is longer than the docs' list and is only shown once the developer
account exists, so it is checked then. Languages the Chrome Web Store may not offer as a listing language at all
(az, hy, ka, sq, mk, mn, ne, km, lo, among ours) keep their text here for the day it does, and read English in the
store until then.

## The package

`python3 chrome/store/package.py` writes `tafs-chrome-<version>.zip` from `chrome/` (the manifest at the zip's root, then
`_locales`, `icons`, `sidepanel` and `src`; never the tests, this folder or `package.json`). It is the same bytes every time
for the same files, and it prints the zip's size and SHA-256 and the SHA-256 of `manifest.json` (64 lower-case hex, the form
planque's allowlist keeps). `--check` only checks that every file the manifest names and every file a script imports is in the
package. `.github/workflows/chrome-package.yml` runs both, builds twice and compares, and keeps the zip as the run's artifact.
It holds no key and uploads nothing: **the upload and the submission are the owner's.**

**One thing to settle before the hash goes in planque's allowlist.** The extension sends the SHA-256 of the `manifest.json`
it is running (`chrome/src/sw.js`). The Chrome Web Store re-packages an upload and, as far as I know, adds fields to the
manifest it serves (an `update_url`), so the hash a Store install sends may differ from the zip's. It is not knowable
from the zip: the first Store build is installed (a private or trusted-testers listing) and the hash read from what planque
refuses it with, or from the extension, and that value is what is allowlisted. Confirm this on the first install.
