# Durcissement + refactor perf du rendu (pré-multi-ligne)

Date : 2026-07-28
Statut : validé (design), prêt pour le plan d'implémentation

## Contexte

Passe globale d'investigation menée avant d'ouvrir le multi-ligne (MVP mono-ligne
métro 9 fonctionnel). L'audit a confirmé que **le schéma relationnel est déjà
multi-ligne** ; les verrous restants sont côté loader/config/wiring (traités **plus tard**,
au vrai chantier multi-ligne). Cette spec couvre les deux lots à faire **maintenant** :

- **Lot 1 — durcissement** : correctifs de robustesse et fuites (bloquants + importants +
  mineurs) identifiés par l'audit, tous à faible risque et fort ROI.
- **Lot 2 — perf du rendu** : refonte de la boucle `requestAnimationFrame` de
  `VehicleLayer`, prérequis connu avant de passer à des milliers de véhicules.

Une seule spec, un seul plan d'implémentation en **deux phases** (Lot 1 puis Lot 2) : les
deux lots partagent `VehicleLayer.ts` et imposent un ordre (le lot 1 y touche la garde NaN
et `destroy()`, le lot 2 réécrit la boucle par-dessus).

Vérification vérifiée sur le flux PRIM réel (ligne 9, 47 courses) : timestamps toujours en
`Z`, quais SIRI propres à chaque sens (0 arrêt partagé Aller/Retour), 17/47 courses à appel
unique et 28/47 sans arrêt passé — ces limites de **données** relèvent du chantier A
(abandonné) et ne sont **pas** dans le périmètre de cette spec.

## Hors périmètre (explicite)

- Chantier A (fiabilité du placement, courses à appel unique / sans arrêt passé) : abandonné.
- Refonte multi-ligne (loader multi-routes, dédup stops de correspondance, config en liste,
  résolution `{id}` dans le contrôleur, `Map<routeId, …>`) : chantier séparé, plus tard.
- `pickDirection` — le fallback `candidates.getFirst()` quand aucun terminus ne matche est
  inoffensif aujourd'hui (quais propres à chaque sens, vérifié). À revoir au multi-ligne.
- Décision produit ferme rappelée : **jamais** de seuil d'ETA pour masquer un train.

## Lot 1 — Durcissement

### Backend — robustesse de l'ingestion temps réel

1. **Timeouts HTTP.** `RealtimePoller` : `connectTimeout` sur le `HttpClient` et
   `.timeout(Duration.ofSeconds(10))` sur la requête (bien < l'intervalle de poll 60 s) ;
   passer `@Scheduled(fixedRate…)` → `fixedDelay` pour sérialiser les polls (pas de
   chevauchement/rafale de connexions si un poll traîne). `GtfsStaticService` : ajouter un
   `connectTimeout` sur son `HttpClient` (le téléchargement GTFS ~109 Mo ne doit pas pouvoir
   pendre indéfiniment au démarrage). Pas de `.timeout` court sur ce dernier (gros download
   légitimement long).
2. **Isolation des courses au parse.** `RealtimePoller.parse` : envelopper `toJourney(journey)`
   dans un try/catch — une course/horodatage malformé est **loggé et ignoré**, le reste du
   snapshot survit. Aujourd'hui une seule `DateTimeParseException` remonte jusqu'à `pollOnce`
   et fait perdre **tout** le snapshot (bénin en mono-ligne, grave en réseau complet).
3. **`stopKey` robuste.** `PositionEngine.stopKey` extrait le **dernier groupe de chiffres**
   (`\d+`) au lieu de supprimer tous les non-chiffres → résiste à un id à préfixe numérique
   (`IDFM:StopPoint:59:463221`). Sur les ids réels ligne 9 (`STIF:StopPoint:Q:463221:`,
   `IDFM:463221`, un seul groupe) le résultat est identique — pas de régression.
4. **Repli `journeyRef` sans collision.** `RealtimePoller.toJourney` : si
   `DatedVehicleJourneyRef` est absent, replier sur un identifiant composite
   (`lineRef` + `directionRef` + `destination` + heure du 1ᵉʳ appel), pas sur le seul
   `stopRef` — sinon deux courses distinctes peuvent partager le même `tripId` et le front
   les fusionne.

### Backend — perf / optim

5. **Cache du `LineSchedule`.** Mémoriser le `LineSchedule` par `routeId` (le calcul recharge
   aujourd'hui **tous les `stop_times` de la ligne** à chaque `/vehicles`, ~toutes les 4 s et
   par client, pour une donnée qui ne change qu'au reload GTFS). Cache invalidé au reload GTFS,
   sur le modèle de `routeGeometry` déjà caché dans `GtfsStaticService`. L'invalidation est
   déclenchée dans `GtfsStaticService.refresh()` (après `loadFromZip`).
6. **Requête arrêts distincts pour `getShape`.** Ajouter une requête repository dédiée
   renvoyant les `Stop` distincts d'une route (au lieu de charger tous les `stop_times` pour
   en tirer `.map(getStop).distinct()`). Le contrat `/shape` est inchangé.
7. **Migration Flyway V3.** Index `stop(parent_station)` et `stop_time(stop_id)` — coût nul en
   mono-ligne, prépare le multi-ligne. Aucune modification d'entité.

### Frontend — correctness / fuites

8. **Annulation des fetch de departures.** `App` : un `AbortController` par cycle de fetch de
   departures — annule la requête obsolète **et** ignore sa réponse (couvre la race au clic
   station et l'absence d'annulation). Le rafraîchissement périodique du panneau passe de
   `setInterval` à un `setTimeout` **récursif** (pas d'empilement si un fetch dépasse
   l'intervalle).
9. **Nettoyage complet.** `VehicleLayer.destroy()` retire les 3 couches, la source `vehicles`
   et l'image `vehicle-arrow` (avec gardes `getLayer`/`getSource`/`hasImage`), **plus** le
   listener `map.move` ajouté au lot 2. `useLineShape` : retirer au cleanup les 4 handlers
   `mouseenter`/`mouseleave` posés sur `stops`/`stops-labels` (référence stable requise pour
   `off`).
10. **Garde NaN.** Les véhicules dont `lat`, `lng` ou `bearing` ne sont pas finis sont rejetés
    avant d'entrer dans `update()` (protège `distanceMeters`/`pointAt` et le rendu MapLibre).

### Frontend — mineurs

11. **Label trompeur.** `VehiclePanel` : remplacer « GPS temps réel » par un libellé sans GPS
    (positions estimées ; il n'y a pas de GPS en métro). Le libellé « estimé » reste.
12. **Clé de liste stable.** `StopPanel` : `key={p.journeyRef}` au lieu de `key={i}`.
13. **Double fetch `/shape` supprimé.** `useLineShape` expose la couleur de la ligne (callback
    `onColor?` appelé une fois la réponse `/shape` reçue) ; `App` consomme cette couleur et
    **supprime** son `fetchShape` dédié à la couleur. Une seule requête `/shape` au chargement.

## Lot 2 — Refactor perf de la boucle rAF

Réécriture de la boucle de rendu de `VehicleLayer` (la logique de tween, de snap, de suivi
caméra et l'orientation des flèches restent fonctionnellement identiques). Quatre leviers :

### 2.a Throttle du rendu

`setData` est appelé **au plus** une fois par `RENDER_INTERVAL_MS` (≈ 66 ms, ~15 fps) au lieu
d'une fois par frame (~60 fps). Le tween reste calculé sur l'horloge rAF ; seule la
reconstruction + `setData` est throttlée. Le métro étant lent, le rendu reste fluide. Constante
nommée, ajustable.

### 2.b Arrêt à l'idle + réveil événementiel

La boucle rAF **s'arrête** quand toutes les anims sont arrivées (`t ≥ 1`) et `!follow` (plus
rien à animer). Elle est **réveillée** par :
- `update()` (nouveau poll → nouvelles positions à tweener) ;
- `setFollow(true)` (le suivi caméra doit re-suivre) ;
- un listener `map.move` (throttlé) : en veille, un pan/zoom utilisateur doit re-déclencher un
  rendu unique pour ré-évaluer le culling (2.d) et repositionner les halos.

`setSelected`/`setHighlighted` ne réveillent **pas** la boucle d'animation : ils appliquent le
feature-state (2.c) et déclenchent un rendu ponctuel throttlé.

### 2.c Sélection / surlignage en feature-state

`selected` et `highlighted` **sortent des propriétés de feature** et passent en
`map.setFeatureState`. La source `vehicles` déclare `promoteId: "tripId"` (ids de feature
stables). Conséquences :
- Les couches `vehicles-halo` et `vehicles-highlight` deviennent **permanentes** (plus de
  `filter` sur une propriété) : leur visibilité est pilotée par la **peinture**, ex.
  `circle-opacity: ["case", ["boolean", ["feature-state","selected"], false], 1, 0]`.
  ⚠️ Gotcha MapLibre assumé : les **filtres** ne lisent pas le feature-state, mais la
  **peinture** oui — d'où l'opacité plutôt qu'un `setFilter`.
- Changer de sélection/surlignage ne reconstruit plus la `FeatureCollection` : un simple
  `setFeatureState` (+ rendu throttlé pour le halo). Découple la sélection du tween.

### 2.d Culling viewport

Seuls les véhicules dont la position courante tombe dans les bornes visibles (avec une marge,
ex. bounds élargies de ~20 %) sont inclus dans la `FeatureCollection` envoyée à `setData`.
Invariants :
- Les **anims sont maintenues pour tous les véhicules** (le tween d'un véhicule survit à une
  sortie puis rentrée d'écran — pas de saut).
- Après chaque `setData`, le **feature-state est ré-appliqué** pour l'id sélectionné et les ids
  surlignés présents dans le jeu rendu (le feature-state peut être perdu quand une feature
  quitte puis réintègre la source). Implémentation : `removeFeatureState({source:"vehicles"})`
  puis `setFeatureState` pour les quelques ids concernés.

Gain nul en mono-ligne (tout tient à l'écran), **décisif** à l'échelle réseau.

## Découpage en unités

- **Backend Lot 1** : (1) timeouts + `fixedDelay` ; (2) isolation parse ; (3) `stopKey` ;
  (4) repli `journeyRef` ; (5) cache `LineSchedule` + invalidation ; (6) requête arrêts
  distincts ; (7) migration V3.
- **Frontend Lot 1** : (8) `AbortController` + `setTimeout` récursif ; (9) `destroy`/cleanup
  handlers ; (10) garde NaN ; (11) label ; (12) clé de liste ; (13) fetch `/shape` unique.
- **Frontend Lot 2** : (2.a→2.d) réécriture de la boucle `VehicleLayer` en un seul bloc
  cohérent (throttle + idle/réveil + feature-state + culling), par-dessus un lot 1 terminé.

Chaque unité backend est testable indépendamment (voir vérification). Le lot 2 est validé au
`npm run build` + contrôle visuel.

## Vérification

- **Backend** : `./mvnw verify` (build + IT Testcontainers). Nouveaux tests unitaires ciblés :
  - (1) requête construite avec timeout / comportement `fixedDelay` (au minimum : le client
    porte bien un timeout — vérifiable sans réseau).
  - (2) `parse` : une course avec un horodatage invalide est ignorée, les courses valides du
    même flux sont conservées.
  - (3) `stopKey` : cas `STIF:StopPoint:Q:463221:`, `IDFM:463221`, et id à préfixe numérique.
  - (4) `toJourney` : deux courses sans `DatedVehicleJourneyRef` et 1ᵉʳ arrêt identique
    obtiennent des `journeyRef` distincts.
  - (5)/(6) couverts par les IT existants (`LineControllerVehiclesIT`, `LineControllerShapeIT`)
    + un test que le cache renvoie la même instance tant que le GTFS n'a pas été rechargé.
  - (7) `SchemaIT` valide la présence des index.
- **Frontend** : pas de tests unitaires (convention projet) → `npm run build` + contrôle visuel
  utilisateur :
  - Lot 1 : cliquer rapidement deux stations n'affiche pas le mauvais panneau ; changer de
    sélection de nombreuses fois ne dégrade rien (pas de fuite) ; libellés/curseurs corrects.
  - Lot 2 : mouvement fluide malgré le throttle ; sélection/halo instantanés ; carte au repos
    ne consomme plus de CPU (boucle arrêtée) ; pan/zoom met à jour le culling sans artefact.

## Notes de mise en œuvre

- Ordre imposé : **Lot 1 puis Lot 2**. `destroy()` (unité 9) prévoit dès sa réécriture le
  retrait du listener `map.move` que le lot 2 introduira.
- Aucune modification de contrat d'API (`/shape`, `/vehicles`, `/departures` inchangés).
- Commits en français, préfixe conventionnel, trailer
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
