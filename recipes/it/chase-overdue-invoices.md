---
title: Sollecitare le fatture scadute
headline: Fatti pagare senza rincorrere nessuno.
today: Chi paga in ritardo resta in ritardo finché qualcuno non trova il tempo di ricordarglielo.
line: Ricorda ai clienti le fatture scadute secondo un calendario e segnala chi non ha pagato; non muove denaro.
sample:
  - Quattro fatture sono scadute e ancora aperte. Ho scritto un sollecito per ciascuna e non ho inviato nulla.
  - Nessuno è in ritardo di più di 30 giorni. Le fatture più vecchie erano scadute 22 giorni fa.
  - Ogni sollecito inizia con la riga che avvisa che lo ha scritto un'IA, indica la fattura e l'importo, e dice di ignorarlo se è già stata pagata.
  - Non ho modificato nessuna fattura e non ho offerto sconti.
guide:
  - Collega Stripe
  - - Do: Apri il lavoro. Coach ti chiede di collegare Stripe, solo per leggere le tue fatture. Approva.
  - - You see: Stripe indicato come collegato, solo in lettura.
  - Leggi le regole
  - - Do: Leggi le regole che Coach ti mostra e accetta.
  - - You see: Il lavoro non modifica mai una fattura e non offre mai una nota di credito o uno sconto, e ti chiede prima di inviare più di 20 solleciti in un giorno.
  - Approva la Card
  - - Do: Approva la Card per leggere le tue fatture Stripe.
  - - You see: La Card, con il suo limite giornaliero di letture di fatture.
  - Avvialo
  - - Do: Apri la chat del lavoro e chiedigli di partire.
  - - You see: Quante fatture sono scadute, un sollecito scritto per ciascuna e chi ha più di 30 giorni di ritardo. Non viene inviato nulla.
card:
  allows:
    - Solleciti fino a 20 al giorno, al massimo uno a settimana per fattura
  holds:
    - Un sollecito oltre 20 in un giorno
  never:
    - Modificare una fattura
    - Una nota di credito o uno sconto
---
Ogni mattina dei giorni feriali, trova le mie fatture scadute nella mia app di contabilità o di fatturazione e invia a ogni cliente un sollecito cortese dalla mia email, al massimo uno a settimana per fattura e 20 al giorno. Non modificare mai una fattura e non offrire sconti. Dimmi chi è in ritardo di oltre 30 giorni.
