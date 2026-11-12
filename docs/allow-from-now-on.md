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

1. **Allow this from now on** opens a sheet.
2. The sheet shows:
   - **the change**, before and after, in tafs cloud's words: "Refunds up to €150 each" → "up
     to €350 each";
   - **what it would have done** over the last 30 days, from KIFF's record: "With this Card, 4
     more refunds would have gone out on their own (€1,180)", or "Nothing else in the last 30
     days would have gone out on its own";
   - **the held call itself**, with a switch, on by default: "Send this refund too".
3. **One signature** confirms the new Card version, behind the approval key (KIFF decides the
   key: a change that widens a Card in force is `critical`, so it takes the fingerprint).
4. The card answers itself: the call went, or it waits as before if the switch was off.

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
- **What it does:** it decides the agent's calls of the last `days` again, against the draft's
  Cards instead of the Cards in force, from KIFF's own record of those calls. Nothing is sent,
  held or recorded.
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

**`GET /v1/tafs/{id}/holds/{exception}/allow`** returns the offer, or why there's none:

```json
{ "offer": {
    "changes": [{ "mandate_id": "…", "kind": "per_action", "before": 15000, "after": 35000,
                  "unit": "EUR", "words_before": "Refunds up to €150 each", "words_after": "Refunds up to €350 each" }],
    "would": { "calls": 4, "words": "With this Card, 4 more refunds would have gone out on their own (€1,180)" },
    "draft": "drf_…", "confirm_key": "critical", "send_held": true } }
```

or `{ "none": "never" | "unit" | "no_change" | "not_yours", "words": "…" }`.

- It reads the hold from KIFF with the person's session, derives the change (below) from its
  `breaches` alone, never from the taf's words, builds the draft from the agent's Cards in force
  with only that change, submits it with `from_hold`, and asks KIFF for the replay.
- The draft is only a draft: nothing changes until the owner confirms it, and an unconfirmed
  one lapses as any draft does.
- **`POST …/allow`** isn't needed: the app confirms the draft itself, with `hold` when "Send
  this refund too" is on and without it when it's off, as it confirms any draft.

**The narrowest change, by breach** (one change per breach, all of the hold's breaches in one
draft, since a call outside two limits needs both to go on its own):

| Breach | The change |
|---|---|
| `per_action_exceeded` | That per-call limit rises to the call's amount, rounded up (below) |
| `limit_reached`, an amount (a total over its window) | That total rises to what the window had used plus this call, rounded up, for good: unlike Extra room, it doesn't reset |
| `limit_reached`, a count | The count rises to what the window had used plus this call, not rounded. "Each call asks you first" (a count of 0 a day) becomes "1 call a day on its own" |
| `resource_not_on_card` | The value is added to that scope ("this customer", "this account"), as it is: no rounding |
| a unit mismatch (`kind: card_unit`) | No offer (`unit`): the Card counts another currency, and that's Coach's to fix |

**Rounding**, so the owner sees a figure they'd choose: up to the next 50 below 1,000, the next
100 below 10,000, then the next 1,000 (in major units, €320 → €350, €1,240 → €1,300). Never
below the call's own figure, and never more than one step above it. A count is never rounded.

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

- The held-call card gets **Allow this from now on** when the person may manage Cards and
  tafs cloud's offer has no `none`.
- The sheet draws the offer's words as given, the held call, and the "Send this … too" switch.
- One signature: the draft's confirm with the approval key KIFF names (`confirm_key`), with
  `hold: {id, accounting_version}` when the switch is on.
- Before signing, the phone checks the draft's Cards as it does for any draft (`draftInfo`), so
  it signs the Cards it shows.

## Tests

- **Each breach kind yields exactly one change,** and a hold with two breaches yields one draft
  with both.
- **A "never" yields no offer,** and neither does a unit mismatch or an empty change.
- **The change never widens past the held call:** the new figure is at least the call's and at
  most one rounding step above it; a resource adds this value only; no other limit, action or
  scope in the draft differs from the Cards in force.
- **"Would have done" counts only calls inside the new Card** that were held or refused before,
  and never a "never".
- **The held call goes out only with the switch on,** under the confirm's signature; with it
  off, the Card changes and the call still waits.
- **The revision names the change and its source call** (`from_hold`).
- **qa-kiff's negatives:** a change that would widen past the held call is refused by tafs
  cloud's own check before any draft is sent; an offer asked for a "never" answers `none`.
