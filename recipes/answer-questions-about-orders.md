---
id: answer-questions-about-orders
title: Answer questions about orders
category: customers
line: Answers "where is my order" questions from the help desk by reading the shop, and passes anything about money to you.
connections:
  - gorgias | intercom | gmail
  - shopify
card:
  allows:
    - Replies up to 50 a day
  holds:
    - A reply past 50 in a day
  never:
    - An order edit or cancellation
    - A refund
---
Answer customer questions about their orders from my help desk, using Shopify to look them up. Reply up to 50 times a day. Never change an order and never refund; pass those to me.
