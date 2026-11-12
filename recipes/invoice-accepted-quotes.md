---
id: invoice-accepted-quotes
title: Invoice accepted quotes
category: money
order: 5
line: Drafts the invoice for each quote a customer accepted, from the quote's own lines, and sends it once you approve it.
headline: No accepted quote goes uninvoiced.
today: An accepted quote waits until someone finds the time to turn it into an invoice.
connections:
  - quickbooks | zohobooks | moneybird
card:
  allows:
    - Invoice drafts from accepted quotes, up to 20 a day
  holds:
    - Sending an invoice to a customer, each one
  never:
    - Changing a quote's lines or prices
    - A discount or a credit note
    - Deleting an invoice
---
Each weekday morning, find the quotes my customers accepted in my accounting app that have no invoice yet. Draft an invoice for each one with the quote's own customer, lines and prices, nothing else, up to 20 a day. Each invoice waits for my approval before it goes to the customer. Never change a quote, never offer a discount or a credit note, and never delete an invoice.
