# Recipes

A recipe is a job worth giving a taf, plus the words to say to Coach to get it. A new person
opens Coach to this list instead of a blank page; tapping one fills Coach's message box with
the recipe's words, which they can edit before sending. Coach builds the taf from catalog
connections and a Card, as for any other request: there is no recipe-specific code anywhere.
Recipes are suggestions, not finished products: each is tried in Coach on a test account to find
where Coach falls short, and every gap gets a generic fix (a connection, how Coach picks tools, a
Card rule), never recipe-specific code. The five starter jobs tafs began with are recipes here.

One file per recipe, `<id>.md`, with a front matter and the words to say to Coach as the body:

```
---
id: refunds-within-a-limit
title: Refunds within a limit
category: money
order: 1
line: Answers refund requests from the support queue and refunds within the Card.
headline: No refund waits overnight.
today: A refund that waits can turn into a chargeback, with a fee you pay even if you win.
connections:
  - gorgias | intercom | gmail
  - stripe
card:
  allows:
    - Refunds up to 150 EUR each and 2,000 EUR a day, back to the original payment
  holds:
    - A refund over 150 EUR, or past 2,000 EUR in a day
  never:
    - A payout, or a refund to another method
---
Handle refund requests from my help desk. Refund in my payment provider up to 150 EUR each…
```

- `id` is the file's name. `category` is `money`, `customers`, `operations` or `errands`.
- `order` is the recipe's place in its category, from 1: the apps and kiff.dev/tafs show them
  in that order (refunds leads Money). Two recipes in one category never share a place.
- `line` says in one line what the taf does. `title` and `line` are what the apps show.
- `headline` names the problem the taf ends, in the person's terms: Home leads with it for an
  account with no taf. `today` says in one line what that problem costs today, in words, never a
  figure (a test checks it has no digits).
- `connections` lists the vendors the taf needs, one line per connection; alternatives on one
  line, separated by `|`, where one vendor stands in for another. A vendor is named as the
  catalog names it: the slug after `connector:<provider>:` or before `@` in its entry (`gmail`,
  `stripe`), lower case.
- `card` says in plain words what the Card allows, what waits for the person (`holds`) and
  what is refused outright (`never`). These are the owner's choices; the words to Coach carry
  the same limits, and the person edits them before sending.
- The body is the words to say to Coach, in the person's own voice. **It names the kind of
  app, never a brand** ("my accounting app", "my email", "my help desk"): an app sold in one
  country isn't in another, and the account may already have a different one. Coach finds the
  apps by those words and proposes the ones the account has first. The brands live only in
  `connections`, which filter the list to what the catalog can connect.

## Languages

Each recipe's words are in the eight languages the apps speak: English in `<id>.md`, and each
other language in `<lang>/<id>.md` (`de`, `fr`, `es`, `it`, `pt`, `nl`, `pl`). A translation has
`title`, `headline`, `today`, `line`, `card` and the words to say to Coach, nothing else: `id`,
`category` and `connections` are English's alone. Its Card lists have as many lines as English's
and say the same limits; amounts are written as the language writes them (`2.000 EUR`, `2 000 EUR`).
The words to say to Coach use the person's own voice in that language, and the app's register
(formal in French and Portuguese, as the apps are). A recipe missing a language, or a translation
whose Card lists don't match, fails the script and the test.

## Serving

tafs cloud serves them at `GET /v1/recipes`, in the person's language (`Accept-Language`),
filtered to those whose every connection has at least one vendor the account's catalog lists
([docs/api.md](../docs/api.md#recipes), [docs/coach.md](../docs/coach.md#recipes)). The files are
read at build time into `runtime/src/recipes.ts`: after changing one, run

```sh
cd runtime && node scripts/recipes.mjs
```

and commit the generated module with it. A test fails when they differ.
