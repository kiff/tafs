---
title: Uitrollen bij groen, terugdraaien bij rood
headline: Stop met elke uitrol in de gaten houden.
today: Iemand houdt elke release in de gaten, en een slechte blijft online tot iemand het merkt.
line: Rolt samengevoegde, groene en goedgekeurde wijzigingen uit, houdt ze in de gaten en draait een mislukte terug; migraties vragen altijd.
card:
  allows:
    - Uitrollen, tot 10 per dag
    - Terugdraaiingen, tot 5 per dag
  holds:
    - Een databasemigratie, elke keer
    - Een uitrol boven 10 op één dag
  never:
    - Data of infrastructuur verwijderen
---
Rol mijn releases uit vanuit mijn codehost als de checks groen zijn en de wijziging is goedgekeurd, en draai terug als de checks daarna falen. Tot 10 uitrollen per dag. Een databasemigratie wacht altijd op mij. Verwijder nooit data of infrastructuur.
