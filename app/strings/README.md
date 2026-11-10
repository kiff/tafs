# The apps' words

Every line the iPhone, iPad and Android apps write themselves is here once, by key, in each
language. Both apps' files are generated from these, so a fix is made once and the two apps
can't drift. The servers' lines are not here: tafs cloud and KIFF keep their own catalogs.
The terms, the voice and the rules are in [docs/languages.md](../../docs/languages.md).

## The files

- **`en.json`** is the source and the list of keys.
- **`de.json`, `fr.json`, `es.json`, `it.json`, `pt.json`, `nl.json`, `pl.json`** hold the
  same keys in each language.
- **`languages.json`** lists the languages (`all`) and the ones the apps ship (`shipped`). A
  shipped language must have every key. Only shipped languages are generated.

## A line

```json
"needs_you.approve_once": "Approve once"
```

- **A key** is `area.meaning`, lower case with underscores, named by what the line says and
  never by the screen it's on. Both apps use the same key for the same line.
- **A value** inside a line is a named placeholder: `"{name} is waiting on you"`. A language
  may put its placeholders in any order.
- **A count** is an object with the plural forms the language has (CLDR: `zero`, `one`,
  `two`, `few`, `many`, `other`), on `{count}`:

```json
"work.requests_need_you": {
  "one": "{count} request needs you",
  "other": "{count} requests need you"
}
```

  Polish has `one`, `few`, `many` and `other`.

## The generated files

`python3 app/strings/generate.py` writes:

- **iPhone and iPad:** `ios/Tafs/Localizable.xcstrings`, and `ios/Tafs/Design/Words.swift`
  with a typed accessor for each key (`Words.NeedsYou.approveOnce`,
  `Words.Work.requestsNeedYou(count:)`). A key that isn't here doesn't compile.
  A count has one catalog entry per plural form (`work.requests_need_you|one`), and the
  catalog's own `_plural` entry says which form a number takes in the app's language.
- **Android:** its resources and typed accessors, written by `android.py` beside the
  generator. A key that isn't here doesn't compile there either.

**Nobody edits the generated files by hand.** `python3 app/strings/generate.py --check`
fails when one differs from what the generator writes, and CI runs it.

## The checks

`python3 -m unittest discover -s app/strings` checks, over these files:

- every key is well formed, and no two keys collide once written for an app;
- a shipped language has every key, and no language has a key `en.json` lacks;
- a line has the same placeholders in every language;
- a count has the plural forms its language needs, and `other`;
- a shipped language's line isn't left in English;
- a line that is exactly a glossary term is that term in every language
  (`docs/languages.md`, Terms).
- no English is left in the iPhone and iPad app's code (`ios_literals.py`).

## English left in code

`python3 app/strings/ios_literals.py` lists the string literals in `ios/Tafs` that read as
English and aren't data. Each literal is judged on its own: one that is compared
(`== "declined"`) is data, and the words beside it on the same line are not. Both sides of a
ternary, an array of words and a count made in code (`"\(n) thing\(n == 1 ? "" : "s")"`) are
all found. The test fails while it lists anything. A single lowercase word with no value beside it (`"installed"`)
can't be told from an id, so it isn't flagged: review those by eye.

What must stay in code is kept by a rule in `ios_literals.py` (a compared value, a key, a URL, a
header, a signed message) or by name in `ios_kept.json`, with the reason: the sample's names,
words for dictation, an answer sent to tafs cloud, a markdown link, a developer-only line. A
kept entry that is no longer in its file fails the test too.
