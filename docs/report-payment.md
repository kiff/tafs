# Report this payment

A person who sees a payment they didn't expect reports it from its receipt. KIFF marks the receipt reported and keeps it, and the
taf that made the payment is halted until the person has looked. It's the halt the Halt button sets, so the taf's page and
the Record show it as halted, and resuming it stays behind the phone ([approvals.md](approvals.md), halting).

## Who sees the button

tafs cloud decides, and the apps only draw what it sends. A taf's Activity entry (`GET /v1/tafs/{id}/activity`) and a Record
entry (`GET /v1/record`) carry `report: {trace_id, at}` only when all of these hold (`runtime/src/cloud/activity.ts`, `reportOf`):

- KIFF says the call is a payment: `payment: true` on its activity row. Its tool moves money by its owner-approved profile, and
  the call was sent.
- It has a receipt (`receipt.trace_id`).
- It isn't reported yet: no `receipt.reported_at`.
- The reader is an owner or admin (`cards.manage`), as KIFF's report route asks.

A reported payment's entry carries `reported_at` instead, and no `report`.

## What the person sees

On the receipt sheet that a Record or Activity line opens (Android `ReceiptSheet`, iOS `ReceiptView`), the button
**Report this payment** opens a sheet:

- the title, "Report this payment";
- "Report a payment you didn't expect: {taf} paid {amount} on {date}. We halt {taf} until you've looked." `{amount}` is the
  entry's `amount_text`. `{date}` is `report.at`, when the payment was made, written by the app in its own language.
- **Report and halt {taf}**, and **Cancel**.

Once tafs cloud takes it: "Reported. {taf} is halted and the receipt is kept. If money left your account, contact your card issuer
too." If it doesn't go through: "Couldn't send the report. Nothing changed. Try again." Sending again is safe.

The sheet names no payee, because KIFF keeps none: a checkout's address is kept only as a hash. It shows no `about` either,
since a taf's own words don't belong on a fraud report. There's no note field: tafs keeps no free text from a report. The
words are in `app/strings/*/record.json` (`record.report.*`), in every language the apps ship.

## The route

`POST /v1/record/{trace_id}/report`, with no body, for an owner or admin. No signature, since it only takes authority away.

1. tafs cloud asks KIFF: `POST /v1/receipts/{trace_id}/report` with the person's session. KIFF marks the receipt reported, keeps
   it through test-account pruning, and halts the call's taf with its existing halt (planque confirms it). Account deletion still
   removes the receipt.
2. tafs cloud halts the taf in kiff/agents too, so no run of it starts. It tells the account's phones, and logs the halt in
   Activity (`GET /v1/events`), as `POST /v1/tafs/{id}/halt` does.
3. It answers `{reported_at, taf?, halt}`. `halt` is the view `GET /v1/tafs/{id}/halt` gives (`halting` until KIFF's gateway,
   planque and kiff/agents all hold it).

Reporting again gives the same answer and the first `reported_at`; a taf resumed in between is halted again. `409` when the
call isn't a payment, or when KIFF can't tell which taf made it (nothing is reported). `404` when there's no such receipt in
this account. `400` for an id that isn't readable. `403` for anyone who isn't an owner or admin.

## Tests

- tafs cloud, `runtime/test/cloud.test.ts`, "reporting a payment the owner didn't expect (Q26)":
  - who gets the offer, on Activity and the Record;
  - the report and the halt in KIFF and kiff/agents;
  - reporting again;
  - a call that isn't a payment, or isn't there, halting nothing.
- Android, `ReportPaymentTest`:
  - the offer as decoded;
  - the tap sent with no signature, and the taf shown halted;
  - a failed report changing nothing.
- iOS, `ReportPaymentTests`.
