---
id: ship-when-green
title: Ship when green, roll back when red
category: operations
line: Deploys merged, green and approved changes, watches them, and rolls back a failing one; migrations always ask.
connections:
  - github
card:
  allows:
    - Deploys up to 10 a day
    - Rollbacks up to 5 a day
  holds:
    - A database migration, every time
    - A deploy past 10 in a day
  never:
    - Deleting data or infrastructure
---
Deploy my releases from my code host when the checks are green and the change is approved, and roll back if the checks fail after. Up to 10 deploys a day. A database migration always waits for me. Never delete data or infrastructure.
