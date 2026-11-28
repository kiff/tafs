# Purchases: the held purchase on the phone

A taf that buys something does it within its Card. When the Card's mode is **Ask me each time**,
or a purchase goes past a limit of **Buy within these limits**, KIFF holds it, and the owner
answers it on the phone. This page covers the apps' half. KIFF builds the case, decides it,
issues the single-use card and signs the receipt. The phone draws KIFF's words and signs the
owner's answer.

The contract is [contracts/purchase.json](../contracts/purchase.json): the case, its states,
the approval the apps draw, the answer's signed bytes and their test vector.

**Built on Android.** The iPhone and iPad and the Mac follow, from the same contract. The receipt
in the Record comes next.

## The held purchase

KIFF serves a held purchase as it serves any held call (`GET /v1/me/card-exceptions`), plus one
field, **`purchase`**: the contract's `approval.value`, `{case_id, version, digest, words}`.

- **Every line is KIFF's.** The card draws `words` as they came:
  - the shop (`title`) and its `subtitle`;
  - the total, large, with its `total_note` and the `delivery` address under it;
  - the items and charges, behind **Show more** (`show_more`);
  - the `price_rule`;
  - what the card allows (`grant`);
  - when it buys (`buys_now`);
  - the Card's `reason`, as the badge;
  - `nothing_until`, in place of the app's "Nothing is sent until you answer".

  The app writes none of its own, and passes each line through the same filter as any held call's
  words (no direction characters, control characters as spaces).
- **Show more is closed each time** the card is drawn for a case version. The answer signs the
  version, so approving with the lines closed approves exactly those lines.
- **Not drawn on a purchase:** a held call's own amount and summary, the taf's words ("The taf
  says"), Details, and a Chrome step.

## Answering it

- **Approve once and Refuse only.** No Extra room, no next steps, no "Allow this from now on"
  (`HoldAllow.allowable` is false for a purchase). KIFF refuses any other answer to a purchase.
- **Approve once is the approval key, behind the fingerprint.** Its prompt is KIFF's `prompt`
  ("Buy from Papeterie Lumière · €84.20").
- **Approve once works only from the purchase's own card.** The card hands the model the case
  it drew (`LocalApprovePurchase`). `AppModel.answer` approves only when that's the case held,
  and never from a presigned answer, a Card's confirmation or an install. From any other screen
  (a taf's page, the brief), Approve once on a purchase signs nothing.
- **Refuse is the device key,** as for any hold.
- **What's signed.** `kiff.answer.v1`'s lines as for any hold, then one more line,
  `purchase:<case_id>:<version>:<digest>` (`PurchaseHold.line`, `KiffClient.answerMessage`'s
  `purchase`). Every answer carries it, Refuse included, so the signature binds this case's
  digest. A purchase never has a browser line. KIFF refuses an answer whose line isn't the case's
  current version and digest.

## A block that can't be read

`PurchaseHold.read` reads the block so that one bad block never hides the account's other held
calls:

- **The case's id, version or digest isn't one** (an id outside `[A-Za-z0-9_-]{1,128}`, a version
  below 1, a digest that isn't 64 lower-case hex): no answer can be signed. The card says "This
  purchase can't be read, so it can't be approved." (`purchase.unreadable`) and offers nothing.
- **The case is read but its words aren't** (no `title`, `total` or `prompt`, or an item without
  a name or amount): the card says the same, and offers **Refuse** only, signed with the case's
  line.
- **No block, or `null`:** any other held call, drawn as before.

## Tests

- `PurchaseHoldTest`:
  - the answer message is the contract's vector, byte for byte, and its SHA-256;
  - approving from the card signs that vector with the approval key, behind KIFF's prompt;
  - approving without the case drawn, with another version, or as a confirmation signs nothing;
  - Extra room and next steps are refused, and a purchase is never "allowable";
  - Refuse signs the case's line;
  - unreadable words are refused only, and an unreadable case isn't answered;
  - a hold without `purchase` is as before.
- `PurchaseCardTest` (Robolectric): the card draws the contract's words with the items closed until
  Show more, offers Approve once and Refuse only, sends the case it drew, and offers Refuse only
  for unreadable words.
