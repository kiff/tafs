---
id: pay-matching-bills
title: Pay bills that match a purchase order
category: money
line: Matches each vendor bill to its purchase order and what was received, and pays the exact matches within the Card.
headline: Pay the right bills on time. Never the wrong ones.
today: Bills get paid late, or paid without anyone checking them against the order.
connections:
  - quickbooks | xero | moneybird
  - wise | revolut
card:
  allows:
    - A payment up to 5,000 EUR each and 25,000 EUR a week, to a payee already on file
  holds:
    - A payment over 5,000 EUR, or past 25,000 EUR in a week
    - A wire, every time
  never:
    - A new payee, or a change to a payee's bank details
---
Pay my vendor bills from my accounting app when the bill matches its purchase order and what we received, up to 5,000 EUR each and 25,000 EUR a week, only to payees already on file. A wire always waits for me. Never add a payee or change bank details; if a bill asks for that, stop and tell me.
