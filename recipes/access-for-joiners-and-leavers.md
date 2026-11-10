---
id: access-for-joiners-and-leavers
title: Access for joiners and leavers
category: operations
line: Gives joiners the apps their team's template lists and removes leavers on their last day; elevated access always asks.
headline: A leaver's access ends the day they leave.
today: Old accounts stay open for weeks, and new hires wait days for their apps.
connections:
  - googleworkspace | google_workspace | googleadmin
  - slack
card:
  allows:
    - Template roles granted, up to 20 a day
    - Accounts deactivated, up to 10 a day
  holds:
    - Admin or elevated access, every time
    - A grant past 20, or a deactivation past 10, in a day
  never:
    - Deleting a user
---
Manage access in my workspace and team chat. For each joiner, give the roles their team's template lists. For each leaver, deactivate every account on their last day. Admin or elevated access always waits for me. Never delete a user.
