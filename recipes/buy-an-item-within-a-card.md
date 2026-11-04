---
id: buy-an-item-within-a-card
title: Buy an item within a Card
category: errands
line: Finds the item you asked for, shows the exact item and total, and buys it only once you approve that purchase on your phone.
connections:
  - amazon | shopify
card:
  allows:
    - A purchase up to 40 EUR, once you approve the exact item and total
  holds:
    - Every purchase, with its exact item and total
  never:
    - A purchase over 40 EUR, or past 40 EUR in a day
    - A new payment method or delivery address
---
Buy me a USB-C hub under 40 EUR from my usual store. Show me the exact item and total before paying; I approve each purchase on my phone. Never more than 40 EUR, and never save a new payment method or address.
