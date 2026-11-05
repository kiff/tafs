---
id: refunds-within-a-limit
title: Refunds within a limit
category: money
line: Answers refund requests from the support queue and refunds in the payment provider, within the Card.
connections:
  - gorgias | intercom | gmail
  - stripe
card:
  allows:
    - Refunds up to 150 EUR each and 2,000 EUR a day, back to the original payment
    - Store credit as a coupon, up to 20 a day
  holds:
    - A refund over 150 EUR, or past 2,000 EUR in a day
    - A coupon past 20 in a day
  never:
    - A payout
    - A refund to another payment method
---
Handle refund requests from my help desk. Refund in my payment provider up to 150 EUR each and 2,000 EUR a day, only back to the original payment. Anything bigger waits for me. Offer store credit when a customer would take it.
