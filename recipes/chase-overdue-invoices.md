---
id: chase-overdue-invoices
title: Chase overdue invoices
category: money
order: 3
line: Reminds customers of overdue invoices on a schedule and reports who hasn't paid; moves no money.
headline: Get paid without chasing.
today: Late payers stay late until someone finds the time to remind them.
connections:
  - quickbooks | zohobooks | moneybird | stripe
  - gmail | outlook
proved: billing
sample:
  - Four invoices are overdue and still open. I drafted a reminder for each and sent nothing.
  - Nobody is more than 30 days late. The oldest invoices were due 22 days ago.
  - Each reminder starts with the AI disclosure line, names the invoice and the amount, and says to ignore it if it's already paid.
  - I changed no invoice and offered no discount.
guide:
  - Connect Stripe
  - - Do: Open the job. Coach asks you to connect Stripe, to read your invoices only. Approve it.
  - - You see: Stripe listed as connected, for reading only.
  - Read the rules
  - - Do: Read the rules Coach shows you, and agree.
  - - You see: The job never edits an invoice and never offers a credit note or a discount, and it asks you first before sending more than 20 reminders in a day.
  - Approve the Card
  - - Do: Approve the Card for reading your Stripe invoices.
  - - You see: The Card, with its daily limit on invoice reads.
  - Run it
  - - Do: Open the job's chat and ask it to run.
  - - You see: How many invoices are overdue, a reminder drafted for each, and who is more than 30 days late. Nothing is sent.
card:
  allows:
    - Reminders up to 20 a day, at most one a week per invoice
  holds:
    - A reminder past 20 in a day
  never:
    - Editing an invoice
    - A credit note or a discount
---
Each weekday morning, find my overdue invoices in my accounting or billing app and send a polite reminder to each customer from my email, at most one a week per invoice and 20 a day. Never change an invoice or offer a discount. Tell me who is more than 30 days late.
