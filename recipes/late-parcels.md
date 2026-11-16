---
id: late-parcels
title: Late parcels
category: customers
order: 5
line: When a parcel's tracking stalls or it goes back to the sender, tells the customer before they ask, within the Card.
headline: The customer hears about a late parcel from you first.
today: A parcel stuck in transit turns into a worried email, then a complaint, then a refund request.
connections:
  - shippo
  - gmail | intercom | zendesk
card:
  allows:
    - Messages to the customer, up to 30 a day
  holds:
    - A message past 30 in a day
  never:
    - A new label
    - A refund
---
Check my parcels' tracking every morning. When a parcel hasn't moved for 3 working days, or is going back to me, write to the customer to say so and that I'm on it. At most 30 messages a day; send them, or leave them as drafts where my email can't send. Anything more waits for me. Never buy a label, refund, or promise a refund or a new delivery date.
