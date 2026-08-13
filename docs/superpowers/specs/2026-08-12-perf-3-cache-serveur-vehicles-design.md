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

1. Le recalcul d'une réponse a lieu au plus une fois par seconde et par clé **en régime
   séquentiel, aux doublons de concurrence près** — des requêtes simultanées sur une entrée
   absente calculent toutes les deux, par choix (§ « Concurrence » du § 3), et à chaque bascule de
   seconde toutes les requêtes en vol manquent ensemble. Sur `/vehicles`, **la sérialisation
   aussi** (§ 2.5).
2. Un poll qui publie un instantané neuf est visible **immédiatement**, pas à la seconde suivante.
   C'est la propriété que les IT existants exigent déjà (§ 2.2).
3. `./mvnw verify` est vert, avec plus de tests qu'avant (104 UT + 45 IT au 2026-08-11 ; **atteint :
   114 UT + 53 IT**, dont six tests venus de la revue finale).
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

Le calcul, par véhicule : trois `extractPoint` (position et cap) plus des `indexOf` qui sont de
simples recherches dans une map préconstruite
([PositionEngine.java:124](../../../backend/src/main/java/com/mapidf/position/PositionEngine.java#L124),
[:182-183](../../../backend/src/main/java/com/mapidf/position/PositionEngine.java#L182-L183)).
`extractPoint` parcourt les segments de la branche, soit ~220 points en moyenne (8 110 points pour
37 polylignes). Quelques microsecondes par véhicule → **~3 à 8 ms pour 705 trains** : c'est
l'estimation de départ de ce terme, noté `C`. **Aucune sonde JUnit ne pouvait la vérifier** — il y
faudrait une fixture de réseau réaliste, la `branchedLine()` de `PositionEngineTest` ne portant
qu'une poignée de points par branche, et le coût d'`extractPoint` y serait sans rapport avec celui
de production. Ce raisonnement reste vrai ; la mesure est venue **d'ailleurs** : en chronométrant
l'endpoint réel et en séparant hits et misses par le compteur de cache (plus bas dans ce §), ce qui
ne demande aucune fixture.

La sérialisation, notée `S` : 705 DTO de 11 champs dont **deux `Instant` chacun** (`expectedTime`
et `recordedAt`, [VehiclesResponse.java:22-24](../../../backend/src/main/java/com/mapidf/controllers/vehicles/VehiclesResponse.java#L22-L24)),
soit ~1 410 formatages ISO-8601, qui en sont le gros. L'estimation de départ — ~1 à 3 ms — comptait
**trois** `Instant` par DTO, donc un tiers de travail de trop : voilà **une part de l'écart** avec
le relevé ci-dessous. Mesure du **2026-08-13**, par une sonde jetable (705 `VehicleDto`
synthétiques, `writeValueAsBytes` en régime chaud, 15 lots de 200 appels après 3 000 de chauffe) :

> **`S` ≈ 0,9 ms par appel**, pour une charge utile **synthétique** de **174 ko** — sur **neuf
> exécutions** :
> médiane des neuf médianes **0,902 ms**, minimum absolu 0,734 ms, exécution la plus chargée à
> 1,382 ms de médiane. Médianes des neuf exécutions, dans l'ordre de tir : **1,382 / 0,902 /
> 0,928 / 1,176 / 0,750 / 0,908 / 0,790 / 0,805 / 0,754 ms** ; charge utile identique aux neuf.

**Cette charge utile est sous-estimée.** Sur le réseau réel, `curl -w "%{size_download}"` sur
`GET /api/vehicles` rend **224 796 octets, soit 219 ko** : la sonde en annonçait **174**, soit 45 ko
de moins — un cinquième de la charge réelle, qui vaut **1,26×** la synthétique (identifiants et
libellés y sont plus longs). Le nombre de DTO et d'`Instant`, lui, est le même. Le `S` de la sonde
est donc un **plancher** pour la vraie sérialisation, pas une valeur transposable telle quelle : ce
qui suit l'encadre au lieu de le reprendre.

**L'estimation tient, à sa borne basse.** Elle n'est ni confirmée au milieu de sa fourchette ni
démentie : la valeur mesurée est ~3× sous le haut de la fourchette. Son rapport à `C` — la seule
chose dont dépende la conception — se lit sur la mesure ci-dessous : `S` y vaut entre le **sixième
et le neuvième** de `C`, donc `N·S` dépasse `C` dès quelques dizaines de clients. (Trois détails de la mesure :
Jackson 3 écrit les `Instant` en ISO-8601 **sans configuration**, `WRITE_DATES_AS_TIMESTAMPS` étant
désactivé par défaut, donc la sonde mesure bien le format servi ; sans les 3 000 appels de chauffe
le chiffre est plusieurs fois trop élevé ; et la dispersion **entre** exécutions reste réelle —
d'où les neuf répétitions, une seule aurait pu rendre n'importe quoi entre 0,75 et 1,38. C'est une
machine de développement, pas un hôte de production.)

**`C + S` mesuré sur le réseau réel, le 2026-08-13.** Ce qu'une sonde JUnit ne pouvait pas faire,
une salve HTTP le fait : **700 requêtes séquentielles** sur `GET /api/vehicles` contre le backend de
développement en marche, réseau complet (16 lignes, ~705 véhicules), chronométrées par
`curl -w "%{time_total}"`. Hits et misses se séparent **par le compteur** :
`mapidf_cache_misses_total{cache="vehicles"}`, relevé avant et après la salve, donne **8 misses sur
700 requêtes** ; les 8 temps les plus longs forment un groupe serré — **8,6 à 12,4 ms** — nettement
détaché du reste.

| | Temps de réponse |
|---|---|
| Miss — calcul + sérialisation + transport | **10,3 ms** de moyenne (n = 8) |
| Hit — transport seul | **1,65 ms** de moyenne (n = 692) |
| Écart = **`C + S`**, net du transport | **8,7 ms** |

**L'estimation de `C` tient, mais par le haut** : 8,7 ms mesurés contre 4 à 9 ms estimés (3 à 8 pour
`C`, 0,9 pour `S`). Elle n'est ni démentie ni confirmée en son milieu — elle est validée de justesse,
par le sommet de sa fourchette.

**Ce que cette méthode ne sait pas faire**, à écrire noir sur blanc :

- elle **ne sépare pas** `C` de `S`, seulement leur somme — toute décomposition écrite plus bas est
  **dérivée**, jamais mesurée ;
- machine de développement, boucle locale, et le backend servait en parallèle le front de
  l'utilisateur : il y a du **bruit concurrent** ;
- « les 8 plus lents sont les 8 misses » est une **inférence**. Elle tient sur deux faits : le
  compte correspond exactement au delta du compteur, et l'écart entre le 8ᵉ temps (8,6 ms) et le 9ᵉ
  est net.

Les deux termes ne se comportent pas pareil : le calcul est payé **une fois par seconde**, la
sérialisation **une fois par requête**. Seule leur **somme** est mesurée, d'où la façon de lire le
tableau. La colonne « octets » vaut directement la mesure : `(C+S)/s` = **8,7 ms/s**, sans aucune
décomposition. La colonne « objet », elle, exige `S` seul, donc une **déduction** — `C` = 8,7 − `S`,
avec `S` **encadré** plutôt que fixé :

- borne basse **0,90 ms** — la sonde telle quelle, si le coût ne tient qu'au nombre de champs et aux
  1 410 formatages ISO-8601, invariants entre charge synthétique et charge réelle ;
- borne haute **1,14 ms** — la même remise à l'échelle des 219 ko réels (0,902 × 219/174), si le
  coût suit le volume écrit.

Soit **`C` entre 7,56 et 7,80 ms**, dérivé. En notant `N` le débit en requêtes par seconde :

| Charge | Cache de l'objet — `C/s + N·S` (dérivé) | Cache des octets — `(C+S)/s` (mesuré) |
|---|---|---|
| 1 client (0,25 req/s) | 7,8 à 8,0 ms/s | **8,7 ms/s** |
| 40 clients (10 req/s) | 16,8 à 19,0 ms/s | **8,7 ms/s** |
| 400 clients (100 req/s) | 98 à 122 ms/s | **8,7 ms/s** |

La colonne `(C+S)/s` est une **borne basse idéalisée** : elle suppose qu'une seule requête paie
le calcul par seconde. À chaque bascule de seconde, les requêtes en vol manquent toutes ensemble et
recalculent en parallèle — d'autant plus nombreuses que le débit est élevé. À 100 req/s le terme
réel est donc un petit multiple de `C` par seconde, pas exactement `C`. Ça ne déplace pas la
bascule (le cache d'objet subit la même chose sur son terme `C`), mais « plat » veut dire plat au
bruit de concurrence près.

La bascule est là où `N·S` dépasse `C`, soit `N` > (8,7 − `S`)/`S` : **6,6 à 8,7 req/s — 27 à 35
clients** au poll de 4 s. Bien plus tard que la « dizaine » annoncée avant mesure, et la fourchette
s'est **resserrée sur la moitié haute** des « 13 à 36 clients » d'avant celle-ci. Elle a surtout
changé de source d'incertitude : ce n'est plus `C` — sa somme avec `S` est maintenant mesurée —
mais la façon dont `S` se transpose de la charge synthétique à la charge réelle. C'est quand même
l'argument des octets, et il ne dépend pas du chiffre exact : le cache d'objet laisse le coût
**linéaire** en clients, celui des octets le rend **plat**. Ce que la mesure change, c'est le moment
où ça compte, pas le sens.

**Un hit coûte encore 1,65 ms, et le cache n'y peut rien** : les 219 ko partent sur le réseau à
chaque requête. Le cache supprime le calcul, pas le transport. C'est exactement le reste que
**PERF-4** (§ 8) irait chercher : envoyer le segment une fois par minute et laisser le front
interpoler. À noter que ce coût-là n'entre dans aucune colonne du tableau ci-dessus, qui ne compte
que le travail supprimé par le cache.

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

La map de `/stations/{id}/departures` **ne peut pas être saturée par un identifiant forgé** : la
station est résolue *avant* d'atteindre le cache et un identifiant absent du réseau courant lève,
donc aucune entrée ne naît d'un identifiant inventé (321 stations aujourd'hui).

Ce n'était pas une borne pour autant, et la première version de ce paragraphe en sous-estimait le
prix de trois ordres de grandeur : une entrée ne retient pas que sa **valeur** (là, oui, quelques
kilo-octets de records) — elle retient aussi une **référence forte vers ses instantanés source**,
dont un `RtSnapshot` de ~705 courses et quelques milliers d'appels, soit **~1 Mo**. Le poller en
publie un neuf toutes les 60 s : une station interrogée puis jamais rouverte épinglait donc celui de
sa dernière visite jusqu'à la fin du processus. ~20 Mo pour 20 stations parcourues, ~300 Mo au
plafond des 321 — une régression depuis zéro, relevée en revue finale.

D'où la purge : **à chaque défaut, toute entrée dont la seconde n'est pas la seconde courante est
retirée**. Elle ne coûte aucun hit, une entrée d'une autre seconde ne pouvant de toute façon plus en
produire, et elle est en O(nombre de clés) sur un défaut — 321 au pire. Elle règle du même coup le
cas de la station disparue d'un refresh GTFS, qu'aucune éviction ne couvrait. Ce qui reste retenu
est donc borné par les seules clés visitées dans la seconde courante.

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

**Effet de bord assumé du `contentType` : la négociation de contenu est court-circuitée.** Dès
qu'une `ResponseEntity` porte un Content-Type concret, Spring ne consulte plus l'en-tête `Accept`.
Un `Accept: application/xml` sur les trois endpoints rend donc **200 JSON** là où il rendait 406.
C'est bénin — aucun client du projet ne demande autre chose que du JSON — et l'en-tête est
**indispensable** sur `/vehicles` : sans lui, un `byte[]` sort en `application/octet-stream`, que
nginx ne gzippe pas. Conséquence à connaître : `/network`, qui ne pose pas cet en-tête
([NetworkController](../../../backend/src/main/java/com/mapidf/controllers/network/NetworkController.java)),
**diverge** — la négociation y reste en vigueur. Aligner les quatre endpoints est possible ; ce
n'est pas un correctif de ce chantier.

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
| 7 | Deux sources, **seule la seconde change** → recalcul | Une comparaison qui s'arrête à l'index 0 — donc qui ignore le `NetworkSnapshot` du § 2.4 |
| 8 | Un défaut à la seconde suivante **purge** les entrées des secondes précédentes | Les entrées qui épinglent leurs instantanés source (§ 3) |
| 9 | Un défaut sur une autre clé, **même seconde**, laisse les entrées voisines intactes | Une purge trop large, qui échangerait la fuite contre un cache qui ne cache plus |

Les tests 7 à 9 sont venus de la revue finale. Le 7 a été vérifié par une **mutation de contrôle** :
`sameInstances` réduit à `cached.get(0) == current.get(0)` passait les six tests d'origine et ne
rougit que sur celui-là.

**Ce qui est vraiment le filet côté IT, ce sont les tests à deux `GET`** — pas les IT préexistants,
contrairement à ce que ce paragraphe affirmait. Chaque `@BeforeEach` rejoue `publishFromDatabase()`,
donc republie un `NetworkSnapshot` neuf, et l'invalidation par identité se déclenche de toute façon
entre deux méthodes de test : un cache purement TTL n'y serait détecté que si deux **corps** de test
tombaient dans la même seconde murale, ce que rien ne garantit. Mesuré en mutant `sameInstances` en
`return true` : trois tests de `StationsControllerIT` rougissent — mais deux d'entre eux par le seul
effet d'un enchaînement rapide, pas par construction.

S'ajoutent donc, par endpoint :

- `Content-Type: application/json` et `Cache-Control: no-store` ;
- deux `GET` séparés par un `pollOnce` d'instantané différent → deux réponses différentes. C'est
  l'invalidation prouvée de bout en bout, avec des millisecondes entre les deux requêtes et non le
  hasard d'un enchaînement de méthodes. Les trois endpoints en ont un
  (`/stations/{id}/departures` l'a reçu en revue finale, il manquait) ;
- **un** IT qui prouve qu'un hit *se produit* : deux `GET` identiques, puis une assertion sur
  `mapidf.cache.hits`. Aucun autre test ne le faisait — vérifié en mutant `sameInstances` en
  `return false`, les cinq autres tests de `VehiclesControllerIT` restent verts. Le bean `Clock`
  étant l'horloge système, la tentative n'est retenue que si la seconde murale n'a pas changé de
  part et d'autre des deux requêtes ; sinon le test retente. Le verdict est donc déterministe, et
  exact (un poll ouvre chaque tentative, donc la première requête est toujours un défaut).

Enfin, `StationsControllerTest` (unitaire, horloge figée) vérifie que la station servie est tirée de
l'instantané qui entre dans la clé : avec un registry qui republie entre deux lectures, la variante
à deux lectures servait encore le nom d'avant le refresh à une requête entièrement postérieure.

## 6. Observabilité

Le cache publie `mapidf.cache.hits` et `mapidf.cache.misses`, tagués par le `name` reçu au
constructeur (`vehicles`, `disruptions`, `departures`). C'est **la seule façon de savoir que PERF-3
fait son travail** : l'effet est invisible autrement, et le mesurer autrement demanderait un test
de charge que le projet n'a pas. Même logique que
[QUA-2](2026-07-29-multi-ligne-metro-design.md) — une mesure qui répond à une question qu'on se
posera, exposée par `/actuator/prometheus` avec les autres.

**Deux métriques existantes changent de sémantique**, sans que leur nom bouge :
`mapidf.position.unplaced` et `mapidf.position.branch.unresolved` s'incrémentaient une fois par
**requête** (une toutes les 4 s par client, soit ~10/s à 40 clients) ; elles s'incrémentent
maintenant une fois par **calcul**, soit ~1/s, quel que soit le nombre de clients. C'est un
progrès — elles mesurent enfin la donnée et non le trafic, ce qu'un garde-fou de
réseau doit faire — mais leurs **ordres de grandeur historiques ne sont plus comparables** : une
chute de ces compteurs après PERF-3 ne veut pas dire que le placement s'est amélioré. Noté aussi
dans `CLAUDE.md`, où elles sont présentées comme le garde-fou observable du réseau.

## 7. Ordre d'exécution

1. Bean `Clock` + `ResponseCache` avec ses six premiers tests unitaires (TDD, aucun contrôleur
   touché) ; les tests 7 à 9 du § 5 sont venus après, avec la revue finale.
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
- **La mesure du partage entre calcul JTS et sérialisation, comme préalable bloquant.** Elle a bien
  eu lieu, mais **après** l'implémentation et sans que le design en dépende — le composant est
  générique sur `V` (§ 3), donc un démenti se serait soldé par un paramètre de type et un lambda.
  `S` est mesuré par sonde, et **`C + S` par chronométrage de l'endpoint réel** (§ 2.5). Ce qui
  reste hors de portée, c'est le **partage** des deux termes : par sonde, il demanderait `computeAll`
  sur une fixture de réseau réaliste — celle de `PositionEngineTest` mesurerait des branches de
  quelques points, pas les ~220 de production —, un chantier en soi qui ne changerait aucune
  décision de celui-ci. D'où un `C` déduit par différence, et signalé comme tel partout.
- **L'adoption complète du bean `Clock`.** Il n'est adopté qu'à moitié : `RealtimePoller`
  (`inServiceNow`, via `LocalTime.now(PARIS)`) et `LineCoverageGuard` appellent toujours l'horloge
  murale en dur. Relevé en revue finale, consigné, non corrigé — ce chantier n'avait besoin d'une
  horloge injectable que dans le cache, et étendre l'injection touche du code de poll que rien ici
  ne fait bouger.
- **PERF-4 (interpolation côté client).** C'est la vraie réponse au fait que la source ne bouge
  qu'à 60 s pendant que le front poll à 4 s. C'est aussi la seule façon d'attaquer les **1,65 ms**
  que coûte encore un hit (§ 2.5) : le transport des 219 ko, que le cache ne supprime pas. Effort L,
  et ce chantier ne l'empêche pas.

## 9. Conséquences sur la documentation

- **Roadmap** : PERF-3 passe à `fait`, avec la mention que l'`ETag` en a été écarté et que le
  micro-cache nginx a rejoint SEC-3. La ligne SEC-3 gagne cette part.
- **QUA-7 (OpenAPI)** : **un seul** endpoint perd la forme de sa réponse dans sa signature,
  `/vehicles` (`ResponseEntity<byte[]>`). Les deux autres passent à `ResponseEntity<T>`, qui préserve
  le type paramétré et reste lisible par un générateur de schéma. `VehiclesResponse` reste la source
  de vérité, à déclarer à la main au générateur le jour de QUA-7 — c'est le seul prix du chantier, et
  il se paie ailleurs.
- **CLAUDE.md** : une ligne, finalement — le déplacement de sémantique de
  `mapidf.position.unplaced` et `mapidf.position.branch.unresolved` (§ 6), là où elles sont
  présentées comme le garde-fou observable du réseau. Le piège « un TTL pur casse les IT de
  contrôleur », lui, vit dans cette spec et dans le test n°2, qui le rend actif.
