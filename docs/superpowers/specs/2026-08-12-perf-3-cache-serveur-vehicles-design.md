# PERF-3 — Cache serveur des endpoints chauds

*Document de conception — 2026-08-12*

Chantier [PERF-3](../../roadmap.md), pris **avant** son tour : la roadmap le conditionnait au jour
où une mise en ligne se précise, groupé avec SEC-3. Rien ne l'y oblige techniquement, et la
décision a été prise de le faire seul et d'abord — le cache retire l'essentiel de la charge qu'un
quota devrait ensuite borner, donc il change le dimensionnement de SEC-3.

**Le périmètre de la fiche est plus étroit que ce chantier, et son titre est trompeur.** La fiche
dit « Cache HTTP de `/vehicles` — aucun `ETag`/`Cache-Control` ». L'`ETag` en sort (§ 2.1), le
`Cache-Control` y reste mais comme **garde** et non comme optimisation (§ 4), et deux endpoints
s'ajoutent à `/vehicles` (§ 3).

## 1. Objectif et critères de réussite

Rendre le coût CPU des trois endpoints chauds **constant par seconde** au lieu de linéaire en
nombre de requêtes, sans jamais retarder l'affichage d'une donnée fraîche.

Est réussi si, tout appliqué :

1. Le recalcul d'une réponse a lieu au plus une fois par seconde et par clé, quel que soit le
   nombre de clients. Sur `/vehicles`, **la sérialisation aussi** (§ 2.5).
2. Un poll qui publie un instantané neuf est visible **immédiatement**, pas à la seconde suivante.
   C'est la propriété que les IT existants exigent déjà (§ 2.2).
3. `./mvnw verify` est vert, avec plus de tests qu'avant (104 UT + 45 IT au 2026-08-11).
4. `mapidf.cache.hits` / `mapidf.cache.misses` permettent de constater le gain en production, sans
   test de charge (§ 6).
5. Aucun changement visible côté front. Le contrat JSON des trois endpoints est **inchangé** au
   champ près.

## 2. Ce qui décide de la conception

### 2.1 Un `ETag` ne mordrait pas

Les trois endpoints recalculent leur réponse à `Instant.now()`
([VehiclesController.java:34](../../../backend/src/main/java/com/mapidf/controllers/vehicles/VehiclesController.java#L34),
[DisruptionsController.java:40](../../../backend/src/main/java/com/mapidf/controllers/disruptions/DisruptionsController.java#L40),
`StationsController.departures`). Le corps diffère donc à chaque appel **par construction**, et un
validateur calculé dessus changerait à chaque appel : un client qui poll toutes les 4 s
(`VEHICLE_POLL_MS = 4000`, [config.ts:2](../../../frontend/src/api/config.ts#L2)) n'obtiendrait
jamais de 304.

Le cache économise du **CPU**, l'`ETag` économiserait de la **bande passante** : deux gestes qui ne
se déclenchent pas dans les mêmes conditions, que la fiche range dans la même ligne. Seul le
premier est retenu, la cible étant explicitement le CPU.

### 2.2 La validité d'une entrée ne peut pas dépendre du seul temps

[VehiclesControllerIT.java:99-108](../../../backend/src/test/java/com/mapidf/controllers/vehicles/VehiclesControllerIT.java#L99-L108)
appelle `pollOnce(...)` puis `GET /vehicles` dans la même seconde, et attend la donnée du poll qui
vient d'avoir lieu. Les trois IT de contrôleur suivent ce motif, `setup()` injectant déjà un
instantané avant chaque test.

**Un cache à TTL pur casserait ces tests**, de façon intermittente selon l'endroit où tombe la
frontière de seconde. Ce n'est pas une fragilité de test à contourner : c'est le comportement voulu
en production. Un instantané frais coûte un appel PRIM par minute ; le retarder d'une seconde
serait gratuit à peu près partout sauf là.

D'où le rejet de Spring Cache + Caffeine, examiné puis écarté : ces bibliothèques savent
**expirer**, pas **observer une source**. Il aurait fallu passer une seconde quantifiée en clé de
toute façon, plus une éviction manuelle depuis les pollers — plus de machinerie pour moins de
justesse, et deux dépendances que le pom n'a pas
([pom.xml:19-37](../../../backend/pom.xml#L19-L37)).

### 2.3 L'identité de référence est le bon signal, et sa faute est du bon côté

`RtSnapshot` et `DisruptionSnapshot` sont des **records**, donc porteurs d'un `equals` structurel.
La comparaison retenue est pourtant `==`, pour deux raisons :

- `pollOnce` publie toujours une instance neuve (`snapshot.set(fresh)`), donc l'identité suffit à
  détecter un poll ;
- un `equals` structurel parcourrait en profondeur des maps de ~705 courses à chaque requête —
  précisément le coût qu'on veut supprimer.

Surtout, **le sens de l'erreur est le bon** : deux instances distinctes mais égales provoquent un
recalcul inutile, jamais une réponse périmée. Un test du § 5 verrouille ce choix, en rougissant si
quelqu'un « corrige » `==` en `equals`.

### 2.4 Les pollers ne sont pas les seules sources

Les trois endpoints lisent aussi `registry.current()`, qui republie un `NetworkSnapshot` à chaque
refresh GTFS (un par jour). Il entre dans la clé, sinon un rechargement du réseau resterait
invisible jusqu'à la seconde suivante — anodin en soi, mais c'est le genre d'exception qui se paie
plus tard, et l'inclure ne coûte qu'une référence de plus dans la liste.

### 2.5 La sérialisation vaut d'être cachée sur `/vehicles`, et seulement là

**Estimations, pas mesures** — le backend n'était pas joignable au moment du cadrage. Elles sont
assez tranchées pour décider, et le § 8 dit comment les confirmer sans que le design en dépende.

Le calcul, par véhicule : trois `extractPoint` (position et cap) plus des `indexOf` qui sont de
simples recherches dans une map préconstruite
([PositionEngine.java:124](../../../backend/src/main/java/com/mapidf/position/PositionEngine.java#L124),
[:182-183](../../../backend/src/main/java/com/mapidf/position/PositionEngine.java#L182-L183)).
`extractPoint` parcourt les segments de la branche, soit ~220 points en moyenne (8 110 points pour
37 polylignes). Quelques microsecondes par véhicule → **~3 à 8 ms pour 705 trains**.

La sérialisation : 705 DTO de 11 champs dont **trois `Instant` chacun**, soit ~2 100 formatages
ISO-8601, qui en sont le gros → **~1 à 3 ms**.

Le même ordre de grandeur, donc — et non deux ordres d'écart comme l'intuition le suggère. Or les
deux termes ne se comportent pas pareil : le calcul est payé **une fois par seconde**, la
sérialisation **une fois par requête**. En notant `C` le premier et `S` le second :

| Charge | Cache de l'objet (`C/s + N·S`) | Cache des octets (`C/s + S/s`) |
|---|---|---|
| 1 client (0,25 req/s) | ~5 ms/s | ~5 ms/s |
| 40 clients (10 req/s) | ~25 ms/s | ~7 ms/s |
| 400 clients (100 req/s) | ~205 ms/s | ~7 ms/s |

La bascule est là où `N·S` dépasse `C`, vers **une dizaine de clients simultanés**. C'est
l'argument des octets : le cache d'objet laisse le coût **linéaire** en clients, celui des octets le
rend **plat**.

**Ce raisonnement ne vaut que pour `/vehicles`.** `/disruptions` sert une poignée d'éléments,
`/stations/{id}/departures` quelques dizaines de passages — deux ordres de grandeur sous les 705
véhicules. Leur sérialisation est en microsecondes, et y payer le `byte[]` (perte de la forme de la
réponse dans la signature, friction QUA-7 du § 9) n'achèterait rien. D'où la répartition du § 3.

## 3. Architecture retenue

Un composant unique, dans `com.mapidf.controllers.support` — utilisé par les seuls contrôleurs, et
le nom du paquet le dit :

```java
public final class ResponseCache<K, V> {

    public ResponseCache(Clock clock, String name, MeterRegistry meters) { … }

    /** Entrée unique — pour un endpoint sans paramètre. */
    public V get(List<Object> sources, Function<Instant, V> compute) { … }

    /** Une entrée par clé. */
    public V get(K key, List<Object> sources, Function<Instant, V> compute) { … }
}
```

**Le composant est générique sur la valeur cachée, pas fixé à `byte[]`.** C'est ce qui rend le
choix « octets ou objet » local à chaque contrôleur, et réversible sans toucher au cache : si la
mesure du § 8 montrait que `/vehicles` n'en a pas besoin — ou qu'un autre endpoint en a besoin —
c'est un paramètre de type et un lambda qui changent, pas le composant.

Une entrée retenue est `{sources, seconde, valeur}`. Elle est réutilisée si et seulement si
**chaque source est la même instance, dans le même ordre**, *et* que `now` tombe dans la même
seconde. Sinon, recalcul et remplacement.

### Clés et sources par endpoint

| Endpoint | Sources | Clé | `V` caché |
|---|---|---|---|
| `/vehicles` | `RtSnapshot`, `NetworkSnapshot` | aucune | `byte[]` (§ 2.5) |
| `/disruptions` | `DisruptionSnapshot`, `NetworkSnapshot` | aucune | `DisruptionsResponse` |
| `/stations/{id}/departures` | `RtSnapshot`, `DisruptionSnapshot`, `NetworkSnapshot` | `stationId` | `DeparturesResponse` |

Seul `/vehicles` cache des octets, parce que seul lui a une charge utile de ~705 objets. Les deux
autres cachent leur record : signature typée conservée, forme de la réponse toujours lisible par un
générateur de schéma (§ 9).

**Un seul chemin de code, quelle que soit la variante** : le support est toujours une
`ConcurrentHashMap`, et la surcharge sans clé délègue à celle avec clé en passant une sentinelle
privée. Pas d'`AtomicReference` en parallèle de la map pour le cas global — deux supports pour deux
surcharges seraient deux fois plus de code à maintenir correct pour aucun gain mesurable sur une
map d'une entrée.

La map de `/stations/{id}/departures` est **bornée par construction** : `requireStation(id)` rejette
un identifiant inconnu *avant* d'atteindre le cache, donc au plus une entrée par station du registry
(321 aujourd'hui, quelques kilo-octets d'octets sérialisés). Aucune éviction à écrire, et aucun
vecteur de saturation par identifiant forgé.

### Concurrence : on accepte un calcul en double

Deux requêtes simultanées sur une entrée absente peuvent calculer toutes les deux, la seconde
écrasant la première. C'est délibéré : la parade évidente — `ConcurrentHashMap.compute` — tient le
verrou du bin pendant tout le calcul, donc pendant les ~705 interpolations JTS. Elle bloquerait les
autres clés tombant dans le même bin et, sur cette map, elle échangerait un doublon rare contre une
contention certaine. Les deux calculs rendent de toute façon une valeur équivalente : le doublon
coûte du CPU, jamais de la justesse.

### Trois choix d'API, au service de la réutilisation

- **`List<Object>` plutôt que varargs** : le `Function` doit rester le dernier paramètre pour que le
  lambda se lise au bon endroit au site d'appel. `List.of(rt, network)` est immuable, ordonné, et se
  compare index par index avec un contrôle de taille préalable.
- **Une surcharge sans clé**, pour que les deux endpoints globaux n'aient pas à passer un `null`.
- **Pas un bean Spring.** Chaque contrôleur construit le sien dans son constructeur, avec le `Clock`
  injecté : c'est un détail d'implémentation du contrôleur, pas un état partagé à câbler, et la
  classe reste instanciable partout. Conséquence assumée : les trois contrôleurs perdent
  `@AllArgsConstructor` au profit d'un constructeur explicite, Lombok ne sachant pas initialiser un
  champ à partir d'un autre.

### Le `Clock`

Le projet n'en a aucun — les trois contrôleurs appellent `Instant.now()` en dur. On ajoute un bean
`Clock.systemUTC()` dans une classe de configuration dédiée. Le cache le détient et **passe au
calcul le `now` qui a servi de clé** : sans ça, on mettrait en cache sous la seconde *N* un calcul
fait à *N+ε*. C'est aussi ce qui rend la frontière de seconde testable **sans jamais dormir**. La
troncature porte sur l'`Instant`, donc le fuseau n'entre pas en jeu.

## 4. Forme d'un contrôleur, et `Cache-Control`

```java
@GetMapping("/vehicles")
public ResponseEntity<byte[]> vehicles() {
    RtSnapshot rt = poller.current();
    NetworkSnapshot network = registry.current();
    byte[] body = cache.get(List.of(rt, network),
        now -> json.writeValueAsBytes(build(rt, network, now)));
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .cacheControl(CacheControl.noStore())
        .body(body);
}
```

**Les instances de source sont lues une fois, avant l'appel**, et servent à la fois de clé et
d'entrée au calcul. Les relire dans le lambda ouvrirait une fenêtre : un poll survenu entre la
lecture de la clé et le calcul ferait mettre en cache une réponse fraîche sous l'identité de
l'ancien instantané — périmée jusqu'au **poll** suivant, et non jusqu'à la seconde suivante.

Les deux autres endpoints ont la **même forme, sans `ObjectMapper`** : le lambda rend directement le
record, et le type de retour est `ResponseEntity<DisruptionsResponse>` /
`ResponseEntity<DeparturesResponse>`. L'enveloppe `ResponseEntity` est nécessaire dans les trois cas
— c'est elle qui porte le `Cache-Control` ci-dessous — mais elle **préserve** le type paramétré, donc
seul `/vehicles` perd la forme de sa réponse dans sa signature.

`json.writeValueAsBytes` ne demande aucune plomberie d'exception, celles de Jackson 3 étant non
vérifiées. Le code du projet le confirme déjà : `RealtimePoller.parse` ouvre un `JsonParser` sans
déclarer le moindre `throws`
([RealtimePoller.java:200](../../../backend/src/main/java/com/mapidf/rt/RealtimePoller.java#L200)).

**`Cache-Control: no-store` sur les trois endpoints.** C'est un garde, pas une optimisation : sans
en-tête, un proxy intermédiaire peut cacher un 200 de façon heuristique et figer les trains chez
tous les clients — exactement le risque que
[NetworkController.java:52-56](../../../backend/src/main/java/com/mapidf/controllers/network/NetworkController.java#L52-L56)
documente pour un réseau vide.

Ce choix **empêche délibérément** le micro-cache nginx envisagé au § 8 : `proxy_cache` respecte
`no-store`, et l'activer demanderait un `proxy_ignore_headers Cache-Control` ou un passage à
`max-age=1, public`. C'est voulu. Passer à `max-age=1, public` aujourd'hui activerait une
péremption de ~2 s (deux caches de 1 s en série) chez n'importe quel intermédiaire, sans qu'on
l'ait décidé ni mesuré. `no-store` garde le comportement d'aujourd'hui déterministe et fait du
micro-cache un choix explicite, d'une ligne, au moment de SEC-3.

## 5. Filet de tests

Le composant se teste en unitaire pur, avec une horloge avancée à la main — donc **sans jamais
dormir**. En TDD : chaque test écrit avant son implémentation.

| # | Ce qui est vérifié | Ce qui rougirait |
|---|---|---|
| 1 | Mêmes sources, même seconde, **appels séquentiels** → le calcul n'est invoqué qu'une fois, et la même valeur est rendue | Un cache qui ne cache pas |
| 2 | Une source remplacée par une instance **égale mais neuve** → recalcul | Un `==` « corrigé » en `equals` (§ 2.3) |
| 3 | Seconde suivante, sources inchangées → recalcul | Une entrée sans péremption |
| 4 | Deux clés distinctes → entrées indépendantes | Une map dégénérée en entrée unique |
| 5 | Le `now` reçu par le calcul est celui qui sert de clé | Le décalage clé/calcul du § 3 |
| 6 | `hits` et `misses` comptent juste | Une métrique décorative |

**Les IT existants sont le filet principal**, et ils n'ont pas à être modifiés : les trois suivent
le motif `pollOnce` puis `GET` dans la même seconde (§ 2.2), donc ils rougissent sur un cache mal
invalidé. C'est le seul endroit du chantier où un test préexistant couvre le risque n°1.

S'ajoutent, par endpoint :

- `Content-Type: application/json` et `Cache-Control: no-store` ;
- deux `GET` séparés par un `pollOnce` d'`asOf` différent → deux `asOf` différents dans les
  réponses. C'est l'invalidation prouvée de bout en bout, pas seulement en unitaire.

## 6. Observabilité

Le cache publie `mapidf.cache.hits` et `mapidf.cache.misses`, tagués par le `name` reçu au
constructeur (`vehicles`, `disruptions`, `departures`). C'est **la seule façon de savoir que PERF-3
fait son travail** : l'effet est invisible autrement, et le mesurer autrement demanderait un test
de charge que le projet n'a pas. Même logique que
[QUA-2](2026-07-29-multi-ligne-metro-design.md) — une mesure qui répond à une question qu'on se
posera, exposée par `/actuator/prometheus` avec les autres.

## 7. Ordre d'exécution

1. Bean `Clock` + `ResponseCache` avec ses six tests unitaires (TDD, aucun contrôleur touché).
2. `/vehicles` : constructeur explicite, `ResponseEntity<byte[]>`, IT d'en-têtes et d'invalidation.
3. `/disruptions`, puis `/stations/{id}/departures` — le second introduit la variante à clé.
4. `./mvnw verify` complet, et vérification que les trois IT préexistants sont **restés** verts
   sans retouche.

## 8. Hors périmètre, et pourquoi

- **`ETag` / 304.** Ne mordrait pas à cette granularité (§ 2.1). À reprendre seulement si la
  bande passante devient la cible, ce qu'elle n'est pas ici.
- **Micro-cache nginx (`proxy_cache_valid 1s`).** Examiné, reporté à **SEC-3**, pas abandonné.
  Trois raisons : il ne couvre que le trafic passant par nginx, alors que le backend reste joignable
  directement sur `:8100` et que `npm run dev` proxifie par Vite (cache absent en dev, présent en
  prod) ; il ne sait pas exprimer « ne cache pas un instantané pas prêt » ; et il n'*observe* pas le
  snapshot, donc il servirait l'ancienne réponse jusqu'à expiration. Surtout, c'est de la
  **configuration pure, sans couplage au code** : le reporter ne coûte rien, et il rejoint
  naturellement `limit_req` — même fichier, même préoccupation de bordure, même décision
  d'hébergement en attente.
- **La mesure du partage entre calcul JTS et sérialisation, comme préalable bloquant.** Le § 2.5
  raisonne sur des estimations. Elle reste faisable **pendant** l'implémentation, quand on est déjà
  dans `/vehicles`, et sans démarrer l'appli : un test jetable qui construit 705 `VehicleDto`
  synthétiques et chronomètre `writeValueAsBytes` donne `S` ; `C` se lit en chronométrant
  `computeAll` sur la fixture. Le design ne dépend pas du résultat — le composant est générique sur
  `V` (§ 3), donc un démenti se solde par un paramètre de type et un lambda.
- **PERF-4 (interpolation côté client).** C'est la vraie réponse au fait que la source ne bouge
  qu'à 60 s pendant que le front poll à 4 s. Effort L, et ce chantier ne l'empêche pas.

## 9. Conséquences sur la documentation

- **Roadmap** : PERF-3 passe à `fait`, avec la mention que l'`ETag` en a été écarté et que le
  micro-cache nginx a rejoint SEC-3. La ligne SEC-3 gagne cette part.
- **QUA-7 (OpenAPI)** : **un seul** endpoint perd la forme de sa réponse dans sa signature,
  `/vehicles` (`ResponseEntity<byte[]>`). Les deux autres passent à `ResponseEntity<T>`, qui préserve
  le type paramétré et reste lisible par un générateur de schéma. `VehiclesResponse` reste la source
  de vérité, à déclarer à la main au générateur le jour de QUA-7 — c'est le seul prix du chantier, et
  il se paie ailleurs.
- **CLAUDE.md** : rien à ajouter si le design tient. Le piège « un TTL pur casse les IT de
  contrôleur » vit dans cette spec et dans le test n°2, qui le rend actif.
