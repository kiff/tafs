---
id: book-and-move-appointments
title: Book and move appointments
category: operations
line: Books, moves and confirms appointments by email against the calendar; cancelling always asks.
connections:
  - gmail | outlook
  - googlecalendar | outlook
card:
  allows:
    - Events booked or moved, up to 20 a day
    - Confirmations by email, up to 20 a day
  holds:
    - A cancellation, every time
    - A change past 20 in a day
  never:
    - Deleting an event
---
Handle appointment requests that come to my Gmail: book a free slot in my Google Calendar, confirm by email, and move one when a customer asks. Up to 20 changes a day. Cancelling always waits for me.
