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
line: Answers refund requests from the support queue and refunds within the Card.
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
Handle refund requests from my help desk. Refund in Stripe up to 150 EUR each…
```

- `id` is the file's name. `category` is `money`, `customers`, `operations` or `errands`.
- `line` says in one line what the taf does. `title` and `line` are what the apps show.
- `connections` lists the vendors the taf needs, one line per connection; alternatives on one
  line, separated by `|`, where one vendor stands in for another. A vendor is named as the
  catalog names it: the slug after `connector:<provider>:` or before `@` in its entry (`gmail`,
  `stripe`), lower case.
- `card` says in plain words what the Card allows, what waits for the person (`holds`) and
  what is refused outright (`never`). These are the owner's choices; the words to Coach carry
  the same limits, and the person edits them before sending.
- The body is the words to say to Coach, in the person's own voice.

tafs cloud serves them at `GET /v1/recipes`, filtered to those whose every connection has at
least one vendor the account's catalog lists
([docs/api.md](../docs/api.md#recipes), [docs/coach.md](../docs/coach.md#recipes)). The files are
read at build time into `runtime/src/recipes.ts`: after changing one, run

```sh
cd runtime && node scripts/recipes.mjs
```

and commit the generated module with it. A test fails when they differ.
