---
title: Rilasciare col verde, tornare indietro col rosso
headline: Smetti di sorvegliare ogni rilascio.
today: Qualcuno sorveglia ogni rilascio, e uno difettoso resta online finché qualcuno non se ne accorge.
line: Rilascia le modifiche unite, verdi e approvate, le sorveglia e annulla quella che fallisce; le migrazioni chiedono sempre.
card:
  allows:
    - Rilasci, fino a 10 al giorno
    - Ripristini, fino a 5 al giorno
  holds:
    - Una migrazione del database, ogni volta
    - Un rilascio oltre 10 in un giorno
  never:
    - Eliminare dati o infrastruttura
---
Rilascia le mie versioni dal mio hosting del codice quando i controlli sono verdi e la modifica è approvata, e torna indietro se i controlli falliscono dopo. Fino a 10 rilasci al giorno. Una migrazione del database aspetta sempre me. Non eliminare mai dati o infrastruttura.
