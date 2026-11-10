---
title: Déployer au vert, revenir en arrière au rouge
headline: Arrêtez de surveiller chaque déploiement.
today: Quelqu'un surveille chaque mise en production, et une mauvaise reste en ligne jusqu'à ce qu'il s'en aperçoive.
line: Déploie les changements fusionnés, au vert et approuvés, les surveille et revient sur celui qui échoue ; les migrations demandent toujours.
card:
  allows:
    - Déploiements, jusqu'à 10 par jour
    - Retours en arrière, jusqu'à 5 par jour
  holds:
    - Une migration de base de données, à chaque fois
    - Un déploiement au-delà de 10 dans la journée
  never:
    - Supprimer des données ou de l'infrastructure
---
Déploie mes versions depuis mon hébergeur de code quand les vérifications sont au vert et que le changement est approuvé, et reviens en arrière si les vérifications échouent ensuite. Jusqu'à 10 déploiements par jour. Une migration de base de données attend toujours mon accord. Ne supprime jamais de données ni d'infrastructure.
