---
id: failed-card-payments
title: Failed card payments
category: money
order: 5
line: When a customer's card payment fails, writes to them with the link to pay, within the Card.
headline: A failed payment doesn't turn into a lost customer.
today: A card that expires or is declined stops a subscription quietly, and the customer never hears why.
connections:
  - stripe
  - gmail | intercom
card:
  allows:
    - Emails or drafts to customers, up to 20 a day
  holds:
    - An email or draft past 20 in a day
  never:
    - A charge or a payment retry
    - A refund
    - A cancelled subscription
---
When a customer's card payment fails at my payment provider, write to them with the link to update their card and pay, once for each failed invoice. At most 20 messages a day; send them, or leave them as drafts where my email can't send. Anything more waits for me. Never charge, retry a payment, refund or cancel a subscription.
