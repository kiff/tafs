# "Allow this from now on" on a held call

A held call gets a third answer beside **Approve once** and **Refuse**: **Allow this from now
on**. It offers the narrowest change to the taf's Card that would have let this call go on its
own, shows the change before and after with what it would have done over the last 30 days, and
one signature confirms it. It's never a silent switch: the owner signs a Card change, KIFF keeps
it as a revision, and KIFF goes on deciding every call against the Card it holds, so nothing a
taf writes can widen it. A taf can never change its own Card.

This is the contract. Each lane says "I can build this" before any lane builds.

## What the owner sees

On a held call's card, for someone who manages the account's Cards (`cards.manage`):

1. **Allow this from now on** opens a sheet. The button's showing costs nothing: the app
   decides it from the held call it already has (below), and only a tap makes a draft.
2. The sheet shows:
   - **the change**, before and after, as KIFF's own lines for the draft's Cards (the lines a
     Card approval shows today), with tafs cloud's words as the summary above them: "Refunds up
     to €150 each" → "up to €320 each". The figure is the call's own, the narrowest change; a
     **Round up to €350** choice makes the same change with a figure the owner would pick;
   - **what it would have done** over the last 30 days, from KIFF's record: "With this Card, 4
     more refunds would have gone out on their own (€1,180)", or "Nothing else in the last 30
     days would have gone out on its own";
   - **the held call itself**, with a switch, on by default: "Send this refund too". With the
     switch off, the sheet says the call still waits for its own answer (Approve once or Refuse):
     a held call stays held until it's answered or lapses, whatever the Card says after.
3. **One signature** confirms the new Card version, behind the approval key (KIFF decides the
   key: a change that widens a Card in force is `critical`, so it takes the fingerprint). The
   phone signs the draft `version` it read from KIFF itself, never one tafs cloud handed it.
4. The card answers itself: the call went, or it waits for its answer if the switch was off.

There's no offer when there's nothing narrow to change (below), and the button isn't shown.

## The lanes

| Lane | What it builds |
|---|---|
| **KIFF** | One new read-only route, the replay. Everything else exists. |
| **tafs cloud** | The offer and the draft: derive the narrow change from the hold's breaches, build the draft, write the words in 8 languages. |
| **Apps** | The third answer on the held-call card, the sheet, one signature. They draw tafs cloud's words. |

### KIFF: what exists, and the one new route

**Exists, unchanged:**
- **The held call's read**, `GET /v1/me/card-exceptions/{id}`: its `breaches`, one per Card
  limit the call is outside, each with `mandate_id`, `outcome` (`per_action_exceeded`,
  `limit_reached`, `resource_not_on_card`), `quantity` (`amount` with `unit`, or a count),
  `limit`, `used` and `window` (for a total), `draw` (what this call carries), and for a
  resource, `resource` and `value`; plus `accounting_version`, `kind` and `can_approve_once`.
- **Draft and confirm**, `POST /v1/me/agent-drafts` and its confirm, as Coach's Card changes
  use. A confirm can name a held call (`{"version", "hold": {"id", "accounting_version"}}`), and
  KIFF approves that call under the confirm's signature ([approvals.md](approvals.md)): that's
  how the held call goes out with the same signature.
- **The revision record:** every confirmed draft is a Card revision with who signed it.

**New, the replay:** `POST /v1/me/agent-drafts/{id}/replay` with `{"days": 30}`, read-only.
- **What it does:** it decides again the calls of the last `days` of every agent the draft's
  changed Cards govern (today one taf per Card), against the draft's Cards instead of the Cards
  in force, from KIFF's own record of those calls. Nothing is sent, held or recorded.
- **What it answers:** the calls that were held or refused then and that the draft lets go on
  their own: `{"would_allow": {"calls": 4, "by_quantity": [{"quantity": "amount", "unit":
  "EUR", "total": 118000}]}, "days": 30, "from": "…", "to": "…"}`, amounts in minor units.
- **Never counted:** a call refused outright by a "never" (no draft from this offer changes
  one), and a call the draft would still hold.
- **Who may read it:** whoever may read the draft (`cards.manage` on the account).

**One field on the draft**, so the revision says where it came from:
`"from_hold": "<exception id>"` on `POST /v1/me/agent-drafts`. KIFF keeps it on the revision, so
the receipt reads "changed from a held call", with that call.

### tafs cloud: the offer and the draft

**Whether there's an offer** is the app's to decide, from the held call it already read from
KIFF, with no request: there's one when the person manages Cards (`cards.manage`), the
exception's `kind` is a hold (not `card_unit`), and every breach is `per_action_exceeded`,
`limit_reached` or `resource_not_on_card`. A "never" is refused, not held, so it never shows.

**`POST /v1/tafs/{id}/holds/{exception}/allow`**, on the tap, makes the draft and asks for the
replay. Its body is `{}` for the exact figure, or `{"round": true}` for the rounded one. It
returns the offer, or why there's none:

```json
{ "offer": {
    "changes": [{ "mandate_id": "…", "kind": "per_action", "before": 15000, "after": 32000,
                  "unit": "EUR", "words_before": "Refunds up to €150 each", "words_after": "Refunds up to €320 each",
                  "rounded": false }],
    "would": { "calls": 4, "words": "With this Card, 4 more refunds would have gone out on their own (€1,180)" },
    "draft": "drf_…", "confirm_key": "critical", "send_held": true } }
```

or `{ "none": "never" | "unit" | "no_change" | "not_yours", "words": "…" }`.

- It reads the hold from KIFF with the person's session, derives the change (below) from its
  `breaches` alone, never from the taf's words, builds the draft from the agent's Cards in force
  with only that change, submits it with `from_hold`, and asks KIFF for the replay.
- The draft is only a draft: nothing changes until the owner confirms it, and an unconfirmed
  one lapses as any draft does.
- The app confirms the draft itself, with `hold` when "Send this refund too" is on and without
  it when it's off, as it confirms any draft. tafs cloud has no confirm route.
- tafs cloud checks its own draft before submitting it: it differs from the Cards in force only
  in the mandates `changes` names, and only as the table below allows, with no Card retired and
  none added. A draft that doesn't is
  never sent, and the answer is `none` (`no_change`).

**The narrowest change, by breach** (one change per breach, all of the hold's breaches in one
draft, since a call outside two limits needs both to go on its own):

| Breach | The change |
|---|---|
| `per_action_exceeded` | That per-call limit rises to the call's amount |
| `limit_reached`, an amount (a total over its window) | That total rises to what the window had used plus this call, for good: unlike Extra room, it doesn't reset, and the words say so ("€600 in total a day, from now on") |
| `limit_reached`, a count | The count rises to what the window had used plus this call, not rounded. "Each call asks you first" (a count of 0 a day) becomes "1 call a day on its own" |
| `resource_not_on_card` | The value is added to that scope ("this customer", "this account"), as it is: no rounding |
| a unit mismatch (`kind: card_unit`) | No offer (`unit`): the Card counts another currency, and that's Coach's to fix |

**The figure** is the call's own by default: that's the narrowest change, and the offer says so.
**Rounded** (`{"round": true}`), it goes up to the next 50 below 1,000, the next 100 below
10,000, then the next 1,000 (in major units, €320 → €350, €1,240 → €1,300), and the words say
"rounded up from €320". Never below the call's own figure, and never more than one step above
it. A count and a resource are never rounded.

**No offer** when:
- the call wasn't held but refused by a "never": it isn't a hold, so the card has no third
  answer, and "never" stays never. The owner changes it only through Coach, which explains why
  first;
- the derived change is empty (KIFF holds it for a reason no Card limit names);
- the person can't manage Cards: the button isn't shown.

**Words**, in the 8 languages, tafs cloud's own (`words_before`, `words_after`, `would.words`):
the limit as the Card says it today, the same with the new figure, and the replay's count and
total in the app's money words. A count of 0 says so ("Nothing else in the last 30 days would
have gone out on its own").

### Apps

- The held-call card gets **Allow this from now on** by the app's own rule above, with no
  request. A tap posts `…/allow`; a `none` shows its words in the sheet, with no sign button.
- **The device signs what the person saw:**
  - the phone reads the draft from KIFF itself (`GET /v1/me/agent-drafts/{id}`) and the Cards in
    force for the agent, and shows KIFF's own lines for each changed limit, under tafs cloud's
    summary;
  - it checks that the draft differs from the Cards in force only in the mandates the offer's
    `changes` names. Any other difference, or a limit that isn't what the summary says: no sign
    button, and "This change isn't what was offered". So is a draft that retires a Card, and a
    Card in the draft that revises none in force: this offer never makes either. The draft as
    KIFF returns it says which Card each of its Cards revises, that Card's terms in force, and
    whether it's unchanged, so the phone can compare from the draft alone;
  - it signs the `version` it read.
- One signature: the draft's confirm with the approval key KIFF names, with
  `hold: {id, accounting_version}` when the switch is on.
- **Round up** posts `{"round": true}` and shows the new draft the same way.
- **The app's own labels**, in `app/strings` in 8 languages (dev-android adds them, both apps
  read them): the button, the sheet's title, the switch ("Send this one too"), "Round up to
  {amount}", and "This change isn't what was offered". Every other line is tafs cloud's or
  KIFF's.

## Tests

- **Each breach kind yields exactly one change,** and a hold with two breaches yields one draft
  with both.
- **A "never" yields no offer,** and neither does a unit mismatch or an empty change.
- **The change never widens past the held call:** the new figure is the call's (or, rounded, at
  most one step above it); a resource adds this value only; no other limit, action or
  scope in the draft differs from the Cards in force.
- **"Would have done" counts only calls inside the new Card** that were held or refused before,
  and never a "never".
- **The held call goes out only with the switch on,** under the confirm's signature; with it
  off, the Card changes and the call still waits for its own answer.
- **The phone refuses a draft that isn't what was offered:** one that differs from the Cards in
  force beyond the named mandates, whose changed limit isn't the summary's figure, that retires a
  Card, or that adds one revising nothing, gets no sign button (both apps, and qa-kiff's
  negatives).
- **Showing the button makes no request,** and the exact figure is the default; rounding is
  only on `{"round": true}`.
- **The revision names the change and its source call** (`from_hold`).
- **qa-kiff's negatives:** a change that would widen past the held call is refused by tafs
  cloud's own check before any draft is sent; an offer asked for a "never" answers `none`.
