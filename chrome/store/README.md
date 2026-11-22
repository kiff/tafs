# Chrome Web Store paperwork (draft, prep only)

What is ready for the day the owner registers the developer account. **Nothing here is submitted**, and nothing in it
runs: it is text.

| File | What it is |
|---|---|
| `listing/<code>.md` | The store listing in one language: name, short description (at most 132 characters, Chrome's limit) and the long description. English and the 39 other languages the apps ship (40 files, one per `shipped` language in `app/strings/languages.json`). |
| `privacy-practices.md` | The answers for the dashboard's Privacy practices tab: single purpose, each permission, remote code, data use, the three certifications, with the judgment calls for the reviewers. |
| `check.py` | `python3 chrome/store/check.py`: every shipped language has a listing, the short description is within 132 characters and is the manifest's own where the extension has the language, no placeholder is left, the privacy link is there. |

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
