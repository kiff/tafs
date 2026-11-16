---
id: payouts-matched-to-the-bank
title: Payouts matched to the bank
category: money
order: 6
line: Each day, matches every payout from the payment provider to its credit in the bank, and tells you what doesn't match.
headline: Know that every payout reached the bank.
today: Payouts are checked against the bank by hand, if at all, and a missing one is found weeks later.
connections:
  - qonto
  - stripe
card:
  allows:
    - Reading the bank account's transactions and the payment provider's payouts
  holds:
  never:
    - A payment, transfer or refund
    - A change in the bank account or the payment provider
---
Every morning, match each payout from my payment provider to its credit in my bank account, and tell me which payouts haven't arrived or don't match the amount. Only read: never move money or change anything in either account.
