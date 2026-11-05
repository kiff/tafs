---
id: save-a-customer-who-cancels
title: Save a customer who cancels
category: money
line: When a subscriber cancels, offers a free month or a small discount, within the Card, and records the outcome.
connections:
  - stripe
  - gmail | outlook | intercom
card:
  allows:
    - A coupon up to 20% off, up to 10 a day
    - Messages to the customer, up to 10 a day
  holds:
    - A coupon past 10 in a day
  never:
    - A refund
    - A plan change the customer didn't ask for
---
When a customer cancels their subscription at my payment provider, offer them one free month or up to 20% off for three months by email, and apply it if they accept. At most 10 offers a day. Anything more waits for me. Never refund here.
