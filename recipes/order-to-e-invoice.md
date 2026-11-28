---
id: order-to-e-invoice
title: Turn an order into an e-invoice
category: money
order: 7
line: Builds the client invoice from an order or a timesheet in your email or files; you approve each one before your business account issues it as an e-invoice.
headline: Invoice the day the order comes in.
today: An invoice typed by hand goes out late, and a late invoice is paid late.
connections:
  - gmail | outlook | googledrive
  - qonto
card:
  allows:
    - Draft client invoices from an order or a timesheet, up to 20 a day
    - Add a client the order names, or update its details
  holds:
    - Finalising and sending an invoice, each one
    - An invoice that lacks a detail the law requires, such as the client's company number
  never:
    - A payment or a transfer
    - Cancelling or deleting an invoice
    - A discount the order doesn't have
---
When an order or a timesheet arrives in my email or my files, draft the client invoice in my business account, with the client, the lines and the amounts as the order gives them, and the details an e-invoice needs: the client's company number, the delivery address if it differs, and whether it's goods, services or both. Add the client if it's new. Ask me before finalising and sending each invoice, and when a required detail is missing. Never make a payment, cancel or delete an invoice, or add a discount the order doesn't have. Tell me which orders you couldn't read.
