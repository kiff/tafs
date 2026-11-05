---
id: chargebacks-with-evidence
title: Chargebacks with evidence
category: money
line: Answers card disputes with the order, delivery proof and the customer's messages before the deadline; accepts small ones with weak evidence.
connections:
  - stripe
  - shopify | gorgias
card:
  allows:
    - Evidence submitted for disputes up to 500 EUR
    - A loss accepted up to 100 EUR each and 300 EUR a day
  holds:
    - Evidence for a dispute over 500 EUR
    - A loss over 100 EUR, or past 300 EUR in a day
  never:
    - A message to the customer or their bank
---
Answer the card disputes at my payment provider with evidence before each deadline, using my shop or help desk for the order, delivery proof and messages. Submit evidence for disputes up to 500 EUR; accept a loss only up to 100 EUR. Over that, wait for me. Never contact the customer or their bank.
