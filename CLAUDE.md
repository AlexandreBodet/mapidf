# CLAUDE.md — guide de travail pour l'IA sur MapIDF

Ce fichier est chargé automatiquement au début de chaque session Claude Code dans ce
repo. Il complète le [README](README.md) (qui explique *comment lancer*) en décrivant
*comment travailler ici* : conventions, vérifications, et surtout les pièges non-évidents
qui nous ont déjà coûté du temps. Garde-le **concis** — il coûte du contexte à chaque
session. Pour le détail, suis les liens vers les docs.

## En deux mots

Appli perso de **suivi temps réel des transports d'Île-de-France sur une carte**.
MVP = **métro ligne 9** (mono-ligne, identifiant paramétrable). Backend Spring Boot
(proxy PRIM + moteur de positions) + PostGIS ; frontend React + MapLibre GL qui poll le
backend toutes les ~4 s et interpole les positions au `requestAnimationFrame`.

Le métro n'a **pas de GPS** : les positions sont **estimées** par interpolation à partir
des horaires temps réel SIRI (prochain arrêt + heure estimée), pas mesurées.

## Commandes

```bash
# Backend (depuis backend/)
./mvnw verify          # build + tous les tests, DONT les IT Testcontainers — la vérif de référence
./mvnw test            # tests unitaires seuls (plus rapide)
./mvnw spring-boot:run # API :8000 (context-path /api), Actuator :9000

# Frontend (depuis frontend/)
npm run dev            # Vite, proxy /api → :8000
npm run build          # build de prod — sert de vérif (pas de tests unitaires front)

# Tout en Docker (depuis la racine)
docker compose up --build   # front :8080, api :8000, actuator :9000
```

**Cycle de vie des apps** : ne suppose pas que le backend/front/Docker sont à toi à
démarrer ou arrêter — demande, ou vérifie, avant. Certains devs les gèrent via leur IDE.

## Conventions de code

- **Spring Boot 4.1 / Java 25 / Lombok.** Records pour les DTO immuables.
- **Jackson 3** (`tools.jackson.databind`, pas `com.fasterxml`). Sur un `JsonNode`,
  utilise **`.asString()`**, pas `.asText()` (qui n'existe plus).
- **TDD** : écris le test qui échoue avant l'implémentation (cf. skill superpowers).
- Conventions Java/Spring maison : voir le projet de référence Steamulo.
- Secrets : `PRIM_API_KEY` vit dans **`.env` (gitignoré) — à ne JAMAIS commiter.**
  `.env.example` documente les variables attendues.

## Configuration de la ligne suivie

Le MVP est mono-ligne, piloté par `app.line.*` dans
[application.yml](backend/src/main/resources/application.yml) :
`gtfs-route-id` (route GTFS), `siri-line-ref` (LineRef SIRI temps réel), `color`. Le
`LINE_ID` côté front n'est qu'un libellé d'URL (`/api/lines/{id}/...`), pas la résolution
de la ligne. Le GTFS IDFM complet (~109 Mo) est filtré **en streaming** par le loader pour
ne garder que la ligne cible (pas d'OOM).

## Données temps réel — pièges à connaître (IMPORTANT)

La source est le endpoint SIRI-ET **`estimated-timetable`** de PRIM (en-tête `apikey`).
Un seul appel couvre **tout le réseau** en JSON → le coût quota est indépendant du nombre
de lignes. Détails et structure exacte : [backend/docs/prim-integration.md](backend/docs/prim-integration.md).

Ce qui n'est **pas** intuitif dans le flux, et qui a déjà causé des bugs :

- **Les `EstimatedCall` ne sont PAS triés** (pas de champ `Order`, 1 à 22 appels par
  course). Ne jamais prendre `path(0)` comme prochain arrêt → prendre le **plus tôt à
  venir**. C'est ce que fait `PositionEngine`.
- **Aucun `RecordedCalls`** : les arrêts passés sont absents. Un train en marche a donc
  souvent **tous ses appels dans le futur** ; ne pas en conclure qu'il n'est pas parti.
- `OriginRef` est souvent `null` ; ~1/3 des courses n'ont qu'un seul appel (terminus
  lointain) → mal plaçables (limitation connue, voir plus bas).
- **Décision produit ferme : PAS de seuil d'ETA pour masquer un train.** Un seuil ferait
  disparaître les trains lors des perturbations de trafic — exactement ce qu'on veut voir.
  Tout filtrage doit s'appuyer sur un **signal non temporel** (fiabilité du placement).

## Où sont les décisions et l'historique

- **Specs & plans par feature** : [docs/superpowers/specs/](docs/superpowers/specs/) et
  [docs/superpowers/plans/](docs/superpowers/plans/).
- **Intégration PRIM (structure des données, quotas, choix)** :
  [backend/docs/prim-integration.md](backend/docs/prim-integration.md).
- **Journal de décisions / tickets post-MVP** : `.superpowers/sdd/progress.md`
  (⚠️ gitignoré, présent seulement en local).

## Limitations connues (ne pas re-débugguer sans lire d'abord)

- **Courses à un seul appel = terminus lointain** (~1/3 du flux) : train mal placé et ETA
  aberrante (ex. Billancourt→Pont de Sèvres annoncé à 13 min). À traiter par un signal
  non temporel — **jamais** par un seuil d'ETA (cf. décision ci-dessus). Ticket ouvert.
- L'étiquette « prochain arrêt » peut sauter une station absente du flux SIRI : c'est
  cosmétique (trou de données), la position reste correcte.
