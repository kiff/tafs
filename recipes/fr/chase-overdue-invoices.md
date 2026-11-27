---
title: Relancer les factures impayées
headline: Soyez payé sans relancer.
today: Les mauvais payeurs restent en retard jusqu'à ce que quelqu'un trouve le temps de les relancer.
line: Relance les clients pour les factures en retard selon un calendrier et indique qui n'a pas payé ; ne déplace aucun argent.
sample:
  - Quatre factures sont en retard et toujours ouvertes. J'ai rédigé une relance pour chacune et je n'ai rien envoyé.
  - Personne n'a plus de 30 jours de retard. Les plus anciennes factures étaient dues il y a 22 jours.
  - Chaque relance commence par la mention qu'une IA l'a rédigée, indique la facture et le montant, et précise qu'on peut l'ignorer si elle est déjà payée.
  - Je n'ai modifié aucune facture et je n'ai offert aucune remise.
guide:
  - Connecter Stripe
  - - Do: Ouvrez la tâche. Coach vous demande de connecter Stripe, en lecture seule pour vos factures. Approuvez.
  - - You see: Stripe indiqué comme connecté, en lecture seule.
  - Lire les règles
  - - Do: Lisez les règles que Coach vous montre, puis acceptez.
  - - You see: La tâche ne modifie jamais une facture et ne propose jamais d'avoir ni de remise, et elle vous demande d'abord avant d'envoyer plus de 20 relances dans une journée.
  - Approuver la Card
  - - Do: Approuvez la Card.
  - - You see: La Card : ce que la tâche fait seule, ce qu'elle vous demande d'abord et ce qu'elle ne fait jamais.
  - Lancer
  - - Do: Ouvrez la conversation de la tâche et demandez-lui de se lancer.
  - - You see: Combien de factures sont en retard, une relance rédigée pour chacune et qui a plus de 30 jours de retard. Rien n'est envoyé : aucune appli de messagerie n'est connectée.
card:
  allows:
    - Relances jusqu'à 20 par jour, au plus une par semaine et par facture
  holds:
    - Une relance au-delà de 20 dans la journée
  never:
    - Modifier une facture
    - Un avoir ou une remise
---
Chaque matin de semaine, trouve mes factures en retard dans mon logiciel de comptabilité ou de facturation et envoie à chaque client une relance polie depuis mon e-mail, au plus une par semaine et par facture et 20 par jour. Ne modifie jamais une facture et ne propose pas de remise. Dis-moi qui a plus de 30 jours de retard.
