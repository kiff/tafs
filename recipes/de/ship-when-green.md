---
title: Bei Grün ausliefern, bei Rot zurückrollen
headline: Schluss mit dem Babysitten von Deploys.
today: Jemand beobachtet jedes Release, und ein schlechtes bleibt online, bis es jemand merkt.
line: Liefert zusammengeführte, grüne und freigegebene Änderungen aus, beobachtet sie und rollt eine fehlerhafte zurück; Migrationen fragen immer.
card:
  allows:
    - Deploys, bis zu 10 am Tag
    - Rollbacks, bis zu 5 am Tag
  holds:
    - Eine Datenbankmigration, jedes Mal
    - Ein Deploy über 10 an einem Tag
  never:
    - Daten oder Infrastruktur löschen
---
Liefere meine Releases aus meinem Code-Host aus, wenn die Checks grün sind und die Änderung freigegeben ist, und rolle zurück, wenn die Checks danach fehlschlagen. Bis zu 10 Deploys am Tag. Eine Datenbankmigration wartet immer auf mich. Lösche nie Daten oder Infrastruktur.
