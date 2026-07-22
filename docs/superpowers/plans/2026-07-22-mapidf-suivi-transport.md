# MapIDF — Suivi transport temps réel — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Afficher les véhicules de la ligne 9 du métro parisien se déplaçant en quasi temps réel sur une carte interactive.

**Architecture:** Backend Spring Boot qui poll les flux IDFM/PRIM (GTFS statique + GTFS-RT), stocke le réseau en PostGIS, et calcule les positions des véhicules (GPS réel projeté sur le tracé, ou interpolation horaire quand le GPS manque). Le front React+MapLibre poll un snapshot toutes les ~4 s et anime les marqueurs par tween `requestAnimationFrame` le long du tracé connu localement.

**Tech Stack:** Java 21, Spring Boot 3.3.x, PostgreSQL 16 + PostGIS 3.4, Flyway, Hibernate Spatial + JTS, `org.mobilitydata:gtfs-realtime-bindings`, Apache Commons CSV, Testcontainers ; React 18 + Vite + TypeScript, MapLibre GL JS.

## Global Constraints

- **Java 21**, **Spring Boot 3.3.x**, build **Maven**.
- **Clé API PRIM** lue exclusivement depuis la variable d'environnement `PRIM_API_KEY` — jamais commitée, jamais renvoyée au front.
- **Ligne de référence MVP = métro ligne 9** ; l'identifiant de ligne reste paramétrable (`app.line.id`).
- Le front n'appelle **que** le backend (préfixe `/api`). Aucun appel direct à IDFM depuis le navigateur.
- Coordonnées : ordre **`[lng, lat]`** dans les payloads GeoJSON-like renvoyés au front (convention MapLibre) ; SRID **4326** en base.
- Le calcul de position du chemin chaud (`PositionEngine`) est **pur, déterministe** (instant `t` injecté) et **ne touche pas la base**.
- TDD strict : test qui échoue → implémentation minimale → test qui passe → commit.

---

### Task 1: Scaffold backend + infra locale (Postgres/PostGIS, Actuator)

Deliverable : l'application démarre, se connecte à une base PostGIS, `/actuator/health` répond `UP`.

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/src/main/java/com/mapidf/MapIdfApplication.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/docker-compose.yml`
- Create: `backend/src/test/java/com/mapidf/SmokeTest.java`

**Interfaces:**
- Produces: application Spring Boot bootable ; base `mapidf` accessible via JDBC ; profil de test avec Testcontainers.

- [ ] **Step 1: `pom.xml` avec les dépendances**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.4</version>
    <relativePath/>
  </parent>
  <groupId>com.mapidf</groupId>
  <artifactId>mapidf-backend</artifactId>
  <version>0.1.0</version>
  <properties>
    <java.version>21</java.version>
    <hibernate-spatial.version>6.5.2.Final</hibernate-spatial.version>
  </properties>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>
    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
    <dependency><groupId>org.hibernate.orm</groupId><artifactId>hibernate-spatial</artifactId><version>${hibernate-spatial.version}</version></dependency>
    <dependency><groupId>org.locationtech.jts</groupId><artifactId>jts-core</artifactId><version>1.19.0</version></dependency>
    <dependency><groupId>org.mobilitydata</groupId><artifactId>gtfs-realtime-bindings</artifactId><version>0.0.8</version></dependency>
    <dependency><groupId>org.apache.commons</groupId><artifactId>commons-csv</artifactId><version>1.11.0</version></dependency>
    <!-- test -->
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>postgresql</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: `docker-compose.yml` (PostGIS)**

```yaml
services:
  db:
    image: postgis/postgis:16-3.4
    environment:
      POSTGRES_DB: mapidf
      POSTGRES_USER: mapidf
      POSTGRES_PASSWORD: mapidf
    ports: ["5432:5432"]
    volumes: ["dbdata:/var/lib/postgresql/data"]
volumes:
  dbdata:
```

- [ ] **Step 3: `application.yml`**

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/mapidf
    username: mapidf
    password: mapidf
  jpa:
    hibernate.ddl-auto: validate
    properties.hibernate.dialect: org.hibernate.spatial.dialect.postgis.PostgisPG10Dialect
  flyway:
    enabled: true
management:
  endpoints.web.exposure.include: health,info,metrics
app:
  line:
    id: "9"        # métro ligne 9 (identifiant logique interne, mappé sur le route_id réel en Task 2)
  prim:
    api-key: ${PRIM_API_KEY:}
```

- [ ] **Step 4: classe d'application**

```java
package com.mapidf;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MapIdfApplication {
    public static void main(String[] args) { SpringApplication.run(MapIdfApplication.class, args); }
}
```

- [ ] **Step 5: test smoke (contexte se charge sur PostGIS Testcontainers)**

```java
package com.mapidf;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class SmokeTest {
    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgis/postgis:16-3.4")
        .withDatabaseName("mapidf").withUsername("mapidf").withPassword("mapidf");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
    }

    @Test void contextLoads() { }
}
```

- [ ] **Step 6: lancer le test → doit passer**

Run: `cd backend && ./mvnw test -Dtest=SmokeTest`
Expected: PASS (Flyway trouve 0 migration pour l'instant, contexte OK).

- [ ] **Step 7: commit**

```bash
git add backend/
git commit -m "feat(backend): scaffold Spring Boot + PostGIS + Actuator"
```

---

### Task 2: Spike de vérification PRIM (livrable = valeurs réelles vérifiées)

Deliverable : un fichier de config documenté contenant les URLs, en-têtes et identifiants **réels** de l'API PRIM et de la ligne 9. Ce n'est pas du code jetable : les tâches suivantes consomment ces valeurs par nom.

**Files:**
- Create: `backend/docs/prim-integration.md`
- Modify: `backend/src/main/resources/application.yml`

- [ ] **Step 1: créer un compte PRIM et une clé**

Sur `https://prim.iledefrance-mobilites.fr` : créer un compte, générer un jeton API. Le noter dans un gestionnaire de secrets (PAS dans le repo). Exporter : `export PRIM_API_KEY=...`.

- [ ] **Step 2: identifier et vérifier au `curl` les 3 ressources, documenter dans `prim-integration.md`**

Renseigner ces 5 valeurs vérifiées (remplacer les `<...>` par ce que retourne le catalogue PRIM) :

```markdown
# Intégration PRIM — valeurs vérifiées le <date>

- En-tête d'authentification : `apikey: $PRIM_API_KEY`   (confirmer le nom exact de l'en-tête)
- GTFS statique (zip) — URL de téléchargement : <url>
- GTFS-RT VehiclePositions — URL : <url>
- GTFS-RT TripUpdates — URL : <url>
- GTFS-RT ServiceAlerts — URL : <url>
- route_id GTFS de la ligne 9 (relevé dans routes.txt du GTFS) : <route_id>
```

Vérifier chaque URL, ex. :
`curl -H "apikey: $PRIM_API_KEY" -o /tmp/rt.pb "<url VehiclePositions>" && ls -l /tmp/rt.pb`
Expected : fichier non vide.

- [ ] **Step 3: reporter les valeurs dans `application.yml`**

```yaml
app:
  prim:
    api-key: ${PRIM_API_KEY:}
    auth-header: "apikey"                 # nom d'en-tête vérifié en Step 2
    gtfs-static-url: "<url GTFS zip>"
    vehicle-positions-url: "<url VP>"
    trip-updates-url: "<url TU>"
  line:
    id: "9"
    gtfs-route-id: "<route_id relevé>"    # route_id réel de la ligne 9
    color: "#D5C900"
```

- [ ] **Step 4: commit (sans secret)**

```bash
git add backend/docs/prim-integration.md backend/src/main/resources/application.yml
git commit -m "docs(backend): valeurs d'intégration PRIM vérifiées + config ligne 9"
```

---

### Task 3: Schéma PostGIS (migration Flyway)

Deliverable : les tables du réseau existent avec colonnes géométriques.

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__network_schema.sql`
- Create: `backend/src/test/java/com/mapidf/MigrationTest.java`

**Interfaces:**
- Produces: tables `route`, `stop`, `trip`, `stop_time`, `route_shape` (colonnes ci-dessous, consommées par Task 4).

- [ ] **Step 1: migration**

```sql
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE route (
    id          TEXT PRIMARY KEY,      -- route_id GTFS
    short_name  TEXT NOT NULL,
    color       TEXT,
    geom        geometry(LineString, 4326) NOT NULL   -- tracé de la ligne
);

CREATE TABLE stop (
    id    TEXT PRIMARY KEY,            -- stop_id GTFS
    name  TEXT NOT NULL,
    geom  geometry(Point, 4326) NOT NULL
);

CREATE TABLE trip (
    id         TEXT PRIMARY KEY,       -- trip_id GTFS
    route_id   TEXT NOT NULL REFERENCES route(id),
    headsign   TEXT,
    direction  SMALLINT
);

CREATE TABLE stop_time (
    trip_id        TEXT NOT NULL REFERENCES trip(id),
    stop_id        TEXT NOT NULL REFERENCES stop(id),
    stop_sequence  INT  NOT NULL,
    arrival_sec    INT  NOT NULL,      -- secondes depuis minuit (peut dépasser 86400)
    departure_sec  INT  NOT NULL,
    PRIMARY KEY (trip_id, stop_sequence)
);

CREATE INDEX idx_stop_time_trip ON stop_time(trip_id);
CREATE INDEX idx_trip_route ON trip(route_id);
```

- [ ] **Step 2: test — la migration s'applique et PostGIS est actif**

```java
package com.mapidf;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class MigrationTest {
    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgis/postgis:16-3.4")
        .withDatabaseName("mapidf").withUsername("mapidf").withPassword("mapidf");
    @DynamicPropertySource static void p(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
    }
    @Autowired JdbcTemplate jdbc;

    @Test void tablesExist() {
        Integer n = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_name IN ('route','stop','trip','stop_time')",
            Integer.class);
        assertThat(n).isEqualTo(4);
    }
}
```

- [ ] **Step 3: run → PASS**

Run: `cd backend && ./mvnw test -Dtest=MigrationTest`
Expected: PASS.

- [ ] **Step 4: commit**

```bash
git add backend/src/main/resources/db/migration backend/src/test/java/com/mapidf/MigrationTest.java
git commit -m "feat(backend): schéma PostGIS du réseau (Flyway V1)"
```

---

### Task 4: Chargement du GTFS statique de la ligne 9 dans PostGIS

Deliverable : `GtfsStaticService.load()` parse un zip GTFS, filtre la ligne 9, et peuple les 5 tables.

**Files:**
- Create: `backend/src/main/java/com/mapidf/gtfs/GtfsStaticService.java`
- Create: `backend/src/main/java/com/mapidf/gtfs/GtfsStaticLoader.java`
- Create: `backend/src/test/java/com/mapidf/gtfs/GtfsStaticLoaderTest.java`
- Create: `backend/src/test/resources/gtfs-mini.zip` (fixture : 1 route, 3 stops, 1 shape, 1 trip, 3 stop_times)

**Interfaces:**
- Consumes: `app.line.gtfs-route-id`, `app.prim.gtfs-static-url` (Task 2).
- Produces: `GtfsStaticLoader.loadFromZip(InputStream, String routeId)` peuple la base ; `GtfsStaticService` cache en mémoire le `LineString` du tracé (JTS) exposé par `getRouteGeometry()` → utilisé par Task 7.

- [ ] **Step 1: fixture GTFS minimale**

Créer `gtfs-mini.zip` contenant `routes.txt`, `stops.txt`, `shapes.txt`, `trips.txt`, `stop_times.txt`. Contenu (route_id `TEST9`, tracé rectiligne ouest→est sur 3 points, 3 arrêts, 1 trip partant à 08:00:00) :

```
# routes.txt
route_id,route_short_name,route_color
TEST9,9,D5C900
# stops.txt
stop_id,stop_name,stop_lat,stop_lon
S1,Alpha,48.850,2.300
S2,Beta,48.850,2.310
S3,Gamma,48.850,2.320
# shapes.txt
shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence
SH9,48.850,2.300,1
SH9,48.850,2.310,2
SH9,48.850,2.320,3
# trips.txt
route_id,trip_id,trip_headsign,direction_id,shape_id
TEST9,T1,Gamma,0,SH9
# stop_times.txt
trip_id,stop_id,stop_sequence,arrival_time,departure_time
T1,S1,1,08:00:00,08:00:00
T1,S2,2,08:05:00,08:05:00
T1,S3,3,08:10:00,08:10:00
```

- [ ] **Step 2: test qui échoue — le loader peuple la base**

```java
package com.mapidf.gtfs;
import com.mapidf.MapIdfApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = MapIdfApplication.class)
@Testcontainers
class GtfsStaticLoaderTest {
    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgis/postgis:16-3.4")
        .withDatabaseName("mapidf").withUsername("mapidf").withPassword("mapidf");
    @DynamicPropertySource static void p(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
    }
    @Autowired GtfsStaticLoader loader;
    @Autowired JdbcTemplate jdbc;

    @Test void loadsLineIntoDb() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-mini.zip")) {
            loader.loadFromZip(in, "TEST9");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stop", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stop_time WHERE trip_id='T1'", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT ST_NumPoints(geom) FROM route WHERE id='TEST9'", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT arrival_sec FROM stop_time WHERE trip_id='T1' AND stop_sequence=2", Integer.class)).isEqualTo(8*3600 + 5*60);
    }
}
```

- [ ] **Step 3: run → FAIL** (`GtfsStaticLoader` n'existe pas)

Run: `cd backend && ./mvnw test -Dtest=GtfsStaticLoaderTest`
Expected: FAIL (compilation / bean manquant).

- [ ] **Step 4: implémenter `GtfsStaticLoader`**

```java
package com.mapidf.gtfs;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.locationtech.jts.geom.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipInputStream;

@Component
public class GtfsStaticLoader {
    private final JdbcTemplate jdbc;
    private final GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 4326);

    public GtfsStaticLoader(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void loadFromZip(InputStream zipIn, String routeId) throws IOException {
        Map<String, List<CSVRecord>> files = readZip(zipIn);
        // routes
        CSVRecord route = files.get("routes.txt").stream()
            .filter(r -> r.get("route_id").equals(routeId)).findFirst()
            .orElseThrow(() -> new IllegalStateException("route absente: " + routeId));
        // shape via trip.shape_id
        List<CSVRecord> trips = files.get("trips.txt").stream()
            .filter(r -> r.get("route_id").equals(routeId)).toList();
        String shapeId = trips.get(0).get("shape_id");
        LineString line = buildShape(files.get("shapes.txt"), shapeId);

        jdbc.update("DELETE FROM stop_time"); jdbc.update("DELETE FROM trip");
        jdbc.update("DELETE FROM stop"); jdbc.update("DELETE FROM route");

        jdbc.update("INSERT INTO route(id,short_name,color,geom) VALUES (?,?,?,ST_GeomFromText(?,4326))",
            routeId, route.get("route_short_name"), safe(route, "route_color"), line.toText());

        Set<String> tripIds = new HashSet<>();
        for (CSVRecord t : trips) {
            tripIds.add(t.get("trip_id"));
            jdbc.update("INSERT INTO trip(id,route_id,headsign,direction) VALUES (?,?,?,?)",
                t.get("trip_id"), routeId, safe(t, "trip_headsign"),
                Integer.parseInt(safe(t, "direction_id", "0")));
        }
        // stops référencés par les stop_times de ces trips
        List<CSVRecord> stopTimes = files.get("stop_times.txt").stream()
            .filter(r -> tripIds.contains(r.get("trip_id"))).toList();
        Set<String> stopIds = new HashSet<>();
        stopTimes.forEach(r -> stopIds.add(r.get("stop_id")));
        for (CSVRecord s : files.get("stops.txt")) {
            if (!stopIds.contains(s.get("stop_id"))) continue;
            jdbc.update("INSERT INTO stop(id,name,geom) VALUES (?,?,ST_SetSRID(ST_MakePoint(?,?),4326))",
                s.get("stop_id"), s.get("stop_name"),
                Double.parseDouble(s.get("stop_lon")), Double.parseDouble(s.get("stop_lat")));
        }
        for (CSVRecord r : stopTimes) {
            jdbc.update("INSERT INTO stop_time(trip_id,stop_id,stop_sequence,arrival_sec,departure_sec) VALUES (?,?,?,?,?)",
                r.get("trip_id"), r.get("stop_id"), Integer.parseInt(r.get("stop_sequence")),
                toSec(r.get("arrival_time")), toSec(r.get("departure_time")));
        }
    }

    private LineString buildShape(List<CSVRecord> shapes, String shapeId) {
        List<CSVRecord> pts = new ArrayList<>(shapes.stream()
            .filter(r -> r.get("shape_id").equals(shapeId)).toList());
        pts.sort(Comparator.comparingInt(r -> Integer.parseInt(r.get("shape_pt_sequence"))));
        Coordinate[] cs = pts.stream()
            .map(r -> new Coordinate(Double.parseDouble(r.get("shape_pt_lon")),
                                     Double.parseDouble(r.get("shape_pt_lat"))))
            .toArray(Coordinate[]::new);
        return gf.createLineString(cs);
    }

    static int toSec(String hms) {
        String[] p = hms.split(":");
        return Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 + Integer.parseInt(p[2]);
    }
    private static String safe(CSVRecord r, String col) { return safe(r, col, null); }
    private static String safe(CSVRecord r, String col, String def) {
        return r.isMapped(col) && !r.get(col).isBlank() ? r.get(col) : def;
    }

    private Map<String, List<CSVRecord>> readZip(InputStream zipIn) throws IOException {
        Map<String, List<CSVRecord>> out = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(zipIn)) {
            var entry = zis.getNextEntry();
            while (entry != null) {
                String name = entry.getName();
                if (name.endsWith(".txt")) {
                    byte[] bytes = zis.readAllBytes();
                    try (var reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
                        var parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                            .setTrim(true).build().parse(reader);
                        out.put(name, parser.getRecords());
                    }
                }
                entry = zis.getNextEntry();
            }
        }
        return out;
    }
}
```

- [ ] **Step 5: run → PASS**

Run: `cd backend && ./mvnw test -Dtest=GtfsStaticLoaderTest`
Expected: PASS.

- [ ] **Step 6: `GtfsStaticService` — orchestration (download + cache tracé)**

```java
package com.mapidf.gtfs;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.WKTReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;

@Service
public class GtfsStaticService {
    private final GtfsStaticLoader loader;
    private final JdbcTemplate jdbc;
    private final String staticUrl, routeId, apiKey, authHeader;
    private volatile LineString routeGeometry;

    public GtfsStaticService(GtfsStaticLoader loader, JdbcTemplate jdbc,
            @Value("${app.prim.gtfs-static-url:}") String staticUrl,
            @Value("${app.line.gtfs-route-id:}") String routeId,
            @Value("${app.prim.api-key:}") String apiKey,
            @Value("${app.prim.auth-header:apikey}") String authHeader) {
        this.loader = loader; this.jdbc = jdbc; this.staticUrl = staticUrl;
        this.routeId = routeId; this.apiKey = apiKey; this.authHeader = authHeader;
    }

    @Scheduled(initialDelay = 0, fixedRateString = "PT24H")
    public void refresh() {
        try {
            var req = HttpRequest.newBuilder(URI.create(staticUrl)).header(authHeader, apiKey).GET().build();
            var resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofInputStream());
            loader.loadFromZip(resp.body(), routeId);
            cacheGeometry();
        } catch (Exception e) {
            throw new IllegalStateException("Échec refresh GTFS statique", e);
        }
    }

    void cacheGeometry() throws Exception {
        String wkt = jdbc.queryForObject("SELECT ST_AsText(geom) FROM route WHERE id=?", String.class, routeId);
        this.routeGeometry = (LineString) new WKTReader().read(wkt);
    }

    public LineString getRouteGeometry() { return routeGeometry; }
}
```

- [ ] **Step 7: commit**

```bash
git add backend/src/main/java/com/mapidf/gtfs backend/src/test
git commit -m "feat(backend): chargement GTFS statique ligne 9 vers PostGIS + cache tracé JTS"
```

---

### Task 5: Endpoint `GET /api/lines/{id}/shape`

Deliverable : le front peut récupérer le tracé + arrêts.

**Files:**
- Create: `backend/src/main/java/com/mapidf/api/LineController.java`
- Create: `backend/src/main/java/com/mapidf/api/dto/ShapeResponse.java`
- Create: `backend/src/main/java/com/mapidf/api/NetworkQueryService.java`
- Create: `backend/src/test/java/com/mapidf/api/LineControllerShapeTest.java`

**Interfaces:**
- Consumes: base peuplée (Task 4), `app.line.color`.
- Produces: `NetworkQueryService.getShape(lineId)` → `ShapeResponse(lineId, color, double[][] shape, List<StopDto> stops)`. `StopDto(id, name, lat, lng)`.

- [ ] **Step 1: DTOs**

```java
package com.mapidf.api.dto;
import java.util.List;
public record ShapeResponse(String lineId, String color, double[][] shape, List<StopDto> stops) {
    public record StopDto(String id, String name, double lat, double lng) {}
}
```

- [ ] **Step 2: test d'intégration qui échoue**

```java
package com.mapidf.api;
import com.mapidf.MapIdfApplication;
import com.mapidf.gtfs.GtfsStaticLoader;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = MapIdfApplication.class)
@AutoConfigureMockMvc
@Testcontainers
class LineControllerShapeTest {
    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgis/postgis:16-3.4")
        .withDatabaseName("mapidf").withUsername("mapidf").withPassword("mapidf");
    @DynamicPropertySource static void p(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
        r.add("app.line.id", () -> "TEST9");
        r.add("app.line.gtfs-route-id", () -> "TEST9");
    }
    @Autowired GtfsStaticLoader loader;
    @Autowired MockMvc mvc;

    @BeforeEach void seed() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-mini.zip")) { loader.loadFromZip(in, "TEST9"); }
    }

    @Test void returnsShapeAndStops() throws Exception {
        mvc.perform(get("/api/lines/TEST9/shape"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.lineId").value("TEST9"))
           .andExpect(jsonPath("$.shape.length()").value(3))
           .andExpect(jsonPath("$.stops.length()").value(3))
           .andExpect(jsonPath("$.stops[0].name").value("Alpha"));
    }
}
```

- [ ] **Step 3: run → FAIL**

Run: `cd backend && ./mvnw test -Dtest=LineControllerShapeTest`
Expected: FAIL (404 / beans manquants).

- [ ] **Step 4: `NetworkQueryService`**

```java
package com.mapidf.api;
import com.mapidf.api.dto.ShapeResponse;
import com.mapidf.api.dto.ShapeResponse.StopDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class NetworkQueryService {
    private final JdbcTemplate jdbc;
    private final String color;
    public NetworkQueryService(JdbcTemplate jdbc, @Value("${app.line.color:#000000}") String color) {
        this.jdbc = jdbc; this.color = color;
    }
    public ShapeResponse getShape(String lineId) {
        List<double[]> pts = jdbc.query(
            "SELECT ST_X((d).geom) x, ST_Y((d).geom) y FROM " +
            "(SELECT ST_DumpPoints(geom) d FROM route WHERE id=?) s ORDER BY (d).path[1]",
            (rs, i) -> new double[]{ rs.getDouble("x"), rs.getDouble("y") }, lineId);
        double[][] shape = pts.toArray(double[][]::new);
        List<StopDto> stops = jdbc.query(
            "SELECT id,name,ST_Y(geom) lat,ST_X(geom) lng FROM stop ORDER BY name",
            (rs, i) -> new StopDto(rs.getString("id"), rs.getString("name"),
                                   rs.getDouble("lat"), rs.getDouble("lng")));
        return new ShapeResponse(lineId, color, shape, stops);
    }
}
```

- [ ] **Step 5: `LineController`**

```java
package com.mapidf.api;
import com.mapidf.api.dto.ShapeResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/lines")
public class LineController {
    private final NetworkQueryService network;
    public LineController(NetworkQueryService network) { this.network = network; }

    @GetMapping("/{id}/shape")
    public ShapeResponse shape(@PathVariable String id) { return network.getShape(id); }
}
```

- [ ] **Step 6: run → PASS ; commit**

Run: `cd backend && ./mvnw test -Dtest=LineControllerShapeTest` → PASS
```bash
git add backend/src/main/java/com/mapidf/api backend/src/test/java/com/mapidf/api
git commit -m "feat(backend): endpoint GET /api/lines/{id}/shape"
```

---

### Task 6: Poller GTFS-RT (snapshot temps réel en mémoire)

Deliverable : `RealtimePoller` récupère et parse les feeds protobuf, expose un snapshot immuable thread-safe.

**Files:**
- Create: `backend/src/main/java/com/mapidf/rt/RtSnapshot.java`
- Create: `backend/src/main/java/com/mapidf/rt/RealtimePoller.java`
- Create: `backend/src/test/java/com/mapidf/rt/RealtimePollerParseTest.java`
- Create: `backend/src/test/resources/vehicle-positions.pb` (fixture protobuf générée en Step 1)

**Interfaces:**
- Consumes: `app.prim.vehicle-positions-url`, `app.prim.trip-updates-url` (Task 2).
- Produces: `RtSnapshot` = `record RtSnapshot(Instant asOf, Map<String,VehiclePos> positions, Map<String,Integer> delaysByTrip)` avec `record VehiclePos(String tripId, double lat, double lng, Float bearing)`. `RealtimePoller.current()` → `RtSnapshot` (jamais null, snapshot vide au démarrage). Consommé par Task 7/8.

- [ ] **Step 1: générer la fixture protobuf**

Écrire un petit main jetable (ou test `@Disabled`) qui sérialise un `FeedMessage` GTFS-RT avec 1 entité VehiclePosition (trip `T1`, position `lat=48.850,lng=2.305`, bearing 90) vers `src/test/resources/vehicle-positions.pb`. Utiliser `com.google.transit.realtime.GtfsRealtime`.

```java
// utilitaire de génération (à exécuter une fois, puis supprimer)
var fm = GtfsRealtime.FeedMessage.newBuilder();
fm.getHeaderBuilder().setGtfsRealtimeVersion("2.0")
  .setIncrementality(GtfsRealtime.FeedHeader.Incrementality.FULL_DATASET).setTimestamp(1_600_000_000L);
var e = fm.addEntityBuilder().setId("v1");
var vp = e.getVehicleBuilder();
vp.getTripBuilder().setTripId("T1");
vp.getPositionBuilder().setLatitude(48.850f).setLongitude(2.305f).setBearing(90f);
Files.write(Path.of("src/test/resources/vehicle-positions.pb"), fm.build().toByteArray());
```

- [ ] **Step 2: test de parsing qui échoue**

```java
package com.mapidf.rt;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RealtimePollerParseTest {
    @Test void parsesVehiclePositions() throws Exception {
        byte[] vp = getClass().getResourceAsStream("/vehicle-positions.pb").readAllBytes();
        RtSnapshot snap = RealtimePoller.parse(vp, new byte[0], java.time.Instant.ofEpochSecond(1_600_000_000L));
        assertThat(snap.positions()).containsKey("T1");
        assertThat(snap.positions().get("T1").lat()).isEqualTo(48.850, org.assertj.core.data.Offset.offset(1e-4));
        assertThat(snap.positions().get("T1").bearing()).isEqualTo(90f);
    }
}
```

- [ ] **Step 3: run → FAIL**

Run: `cd backend && ./mvnw test -Dtest=RealtimePollerParseTest`
Expected: FAIL.

- [ ] **Step 4: implémenter `RtSnapshot` + `RealtimePoller`**

```java
package com.mapidf.rt;
import java.time.Instant;
import java.util.Map;
public record RtSnapshot(Instant asOf, Map<String, VehiclePos> positions, Map<String, Integer> delaysByTrip) {
    public record VehiclePos(String tripId, double lat, double lng, Float bearing) {}
    public static RtSnapshot empty() { return new RtSnapshot(Instant.EPOCH, Map.of(), Map.of()); }
}
```

```java
package com.mapidf.rt;
import com.google.transit.realtime.GtfsRealtime.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class RealtimePoller {
    private final String vpUrl, tuUrl, apiKey, authHeader;
    private final AtomicReference<RtSnapshot> snapshot = new AtomicReference<>(RtSnapshot.empty());
    private final HttpClient http = HttpClient.newHttpClient();

    public RealtimePoller(@Value("${app.prim.vehicle-positions-url:}") String vpUrl,
                          @Value("${app.prim.trip-updates-url:}") String tuUrl,
                          @Value("${app.prim.api-key:}") String apiKey,
                          @Value("${app.prim.auth-header:apikey}") String authHeader) {
        this.vpUrl = vpUrl; this.tuUrl = tuUrl; this.apiKey = apiKey; this.authHeader = authHeader;
    }

    public RtSnapshot current() { return snapshot.get(); }

    @Scheduled(fixedRateString = "${app.prim.poll-interval:PT10S}")
    public void poll() {
        try {
            byte[] vp = fetch(vpUrl);
            byte[] tu = fetch(tuUrl);
            snapshot.set(parse(vp, tu, Instant.now()));
        } catch (Exception e) {
            // dégradation gracieuse : on conserve le dernier snapshot (voir Task 9)
        }
    }

    private byte[] fetch(String url) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(url)).header(authHeader, apiKey).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    static RtSnapshot parse(byte[] vpBytes, byte[] tuBytes, Instant asOf) throws Exception {
        Map<String, RtSnapshot.VehiclePos> positions = new HashMap<>();
        if (vpBytes.length > 0) {
            for (FeedEntity e : FeedMessage.parseFrom(vpBytes).getEntityList()) {
                if (!e.hasVehicle()) continue;
                VehiclePosition v = e.getVehicle();
                if (!v.hasTrip() || !v.hasPosition()) continue;
                String tripId = v.getTrip().getTripId();
                Position p = v.getPosition();
                Float bearing = p.hasBearing() ? p.getBearing() : null;
                positions.put(tripId, new RtSnapshot.VehiclePos(tripId, p.getLatitude(), p.getLongitude(), bearing));
            }
        }
        Map<String, Integer> delays = new HashMap<>();
        if (tuBytes.length > 0) {
            for (FeedEntity e : FeedMessage.parseFrom(tuBytes).getEntityList()) {
                if (!e.hasTripUpdate()) continue;
                TripUpdate tu = e.getTripUpdate();
                int delay = tu.getStopTimeUpdateList().stream()
                    .filter(TripUpdate.StopTimeUpdate::hasArrival)
                    .mapToInt(s -> s.getArrival().getDelay()).findFirst()
                    .orElse(tu.hasDelay() ? tu.getDelay() : 0);
                delays.put(tu.getTrip().getTripId(), delay);
            }
        }
        return new RtSnapshot(asOf, positions, delays);
    }
}
```

- [ ] **Step 5: run → PASS ; commit**

Run: `cd backend && ./mvnw test -Dtest=RealtimePollerParseTest` → PASS
```bash
git add backend/src/main/java/com/mapidf/rt backend/src/test/java/com/mapidf/rt backend/src/test/resources/vehicle-positions.pb
git commit -m "feat(backend): poller GTFS-RT + snapshot temps réel thread-safe"
```

---

### Task 7: `PositionEngine` — calcul pur des positions (cœur, sans DB)

Deliverable : fonction déterministe qui produit les positions des véhicules à un instant `t`.

**Files:**
- Create: `backend/src/main/java/com/mapidf/position/TripSchedule.java`
- Create: `backend/src/main/java/com/mapidf/position/Vehicle.java`
- Create: `backend/src/main/java/com/mapidf/position/PositionEngine.java`
- Create: `backend/src/test/java/com/mapidf/position/PositionEngineTest.java`

**Interfaces:**
- Consumes: `LineString` (Task 4 `getRouteGeometry()`), `RtSnapshot` (Task 6), horaires.
- Produces:
  - `record TripSchedule(String tripId, String headsign, List<StopPassage> passages)` ; `record StopPassage(String stopId, String stopName, int departureSec, double distanceAlongLine)`.
  - `record Vehicle(String tripId, double lat, double lng, double bearing, int delaySec, String headsign, String nextStop, Source source)` ; `enum Source { REALTIME, INTERPOLATED }`.
  - `PositionEngine.computeAll(LineString line, List<TripSchedule> trips, RtSnapshot rt, int nowSecOfDay)` → `List<Vehicle>`.

- [ ] **Step 1: records `TripSchedule`, `StopPassage`, `Vehicle`**

```java
package com.mapidf.position;
import java.util.List;
public record TripSchedule(String tripId, String headsign, List<StopPassage> passages) {
    public record StopPassage(String stopId, String stopName, int departureSec, double distanceAlongLine) {}
}
```

```java
package com.mapidf.position;
public record Vehicle(String tripId, double lat, double lng, double bearing,
                      int delaySec, String headsign, String nextStop, Source source) {
    public enum Source { REALTIME, INTERPOLATED }
}
```

- [ ] **Step 2: tests qui échouent (interpolation, hors service, snap GPS)**

```java
package com.mapidf.position;
import com.mapidf.position.TripSchedule.StopPassage;
import com.mapidf.rt.RtSnapshot;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PositionEngineTest {
    // ligne horizontale de (2.30,48.85) à (2.32,48.85) ; longueur ~0.02° en lon
    static LineString line() {
        var gf = new GeometryFactory(new PrecisionModel(), 4326);
        return gf.createLineString(new Coordinate[]{
            new Coordinate(2.300, 48.850), new Coordinate(2.310, 48.850), new Coordinate(2.320, 48.850)});
    }
    // trip: S1@08:00 (dist 0), S2@08:10 (dist 0.010), S3@08:20 (dist 0.020) en degrés-lon
    static TripSchedule trip() {
        return new TripSchedule("T1", "Gamma", List.of(
            new StopPassage("S1", "Alpha", 8*3600,       0.000),
            new StopPassage("S2", "Beta",  8*3600+600,   0.010),
            new StopPassage("S3", "Gamma", 8*3600+1200,  0.020)));
    }
    PositionEngine engine = new PositionEngine();

    @Test void interpolatesHalfwayBetweenStops() {
        // 08:05 : mi-chemin S1→S2 → lon ≈ 2.305
        var vs = engine.computeAll(line(), List.of(trip()), RtSnapshot.empty(), 8*3600+300);
        assertThat(vs).hasSize(1);
        Vehicle v = vs.get(0);
        assertThat(v.source()).isEqualTo(Vehicle.Source.INTERPOLATED);
        assertThat(v.lng()).isCloseTo(2.305, within(1e-3));
        assertThat(v.lat()).isCloseTo(48.850, within(1e-4));
        assertThat(v.nextStop()).isEqualTo("Beta");
        assertThat(v.bearing()).isCloseTo(90.0, within(5.0)); // plein est
    }

    @Test void excludesTripOutsideServiceWindow() {
        var before = engine.computeAll(line(), List.of(trip()), RtSnapshot.empty(), 7*3600);   // avant départ
        var after  = engine.computeAll(line(), List.of(trip()), RtSnapshot.empty(), 9*3600);   // après arrivée
        assertThat(before).isEmpty();
        assertThat(after).isEmpty();
    }

    @Test void appliesDelayShiftingPositionBackward() {
        // retard 300s : à 08:05 le véhicule est "en retard" → position d'un véhicule qui serait à 08:00 → au départ S1
        var rt = new RtSnapshot(Instant.EPOCH, Map.of(), Map.of("T1", 300));
        var vs = engine.computeAll(line(), List.of(trip()), rt, 8*3600+300);
        assertThat(vs.get(0).lng()).isCloseTo(2.300, within(1e-3));
        assertThat(vs.get(0).delaySec()).isEqualTo(300);
    }

    @Test void usesRealGpsSnappedToLineWhenAvailable() {
        // GPS un peu au nord de la ligne, vers lon 2.315 → snap sur la ligne à lat 48.850
        var rt = new RtSnapshot(Instant.EPOCH,
            Map.of("T1", new RtSnapshot.VehiclePos("T1", 48.851, 2.315, 80f)), Map.of());
        var vs = engine.computeAll(line(), List.of(trip()), rt, 8*3600+300);
        assertThat(vs.get(0).source()).isEqualTo(Vehicle.Source.REALTIME);
        assertThat(vs.get(0).lat()).isCloseTo(48.850, within(1e-4)); // projeté sur la ligne
        assertThat(vs.get(0).lng()).isCloseTo(2.315, within(1e-3));
    }
}
```

- [ ] **Step 3: run → FAIL**

Run: `cd backend && ./mvnw test -Dtest=PositionEngineTest`
Expected: FAIL.

- [ ] **Step 4: implémenter `PositionEngine` (JTS `LengthIndexedLine`)**

```java
package com.mapidf.position;
import com.mapidf.position.TripSchedule.StopPassage;
import com.mapidf.rt.RtSnapshot;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
public class PositionEngine {

    public List<Vehicle> computeAll(LineString line, List<TripSchedule> trips,
                                    RtSnapshot rt, int nowSecOfDay) {
        LengthIndexedLine indexed = new LengthIndexedLine(line);
        double geomLength = line.getLength();               // longueur en degrés (cohérent avec distanceAlongLine)
        List<Vehicle> out = new ArrayList<>();
        for (TripSchedule trip : trips) {
            Vehicle v = compute(indexed, geomLength, trip, rt, nowSecOfDay);
            if (v != null) out.add(v);
        }
        return out;
    }

    private Vehicle compute(LengthIndexedLine indexed, double geomLength, TripSchedule trip,
                            RtSnapshot rt, int nowSecOfDay) {
        int delay = rt.delaysByTrip().getOrDefault(trip.tripId(), 0);
        int effectiveNow = nowSecOfDay - delay;             // retard => on "recule" l'horloge du véhicule
        List<StopPassage> ps = trip.passages();
        int first = ps.get(0).departureSec();
        int last = ps.get(ps.size() - 1).departureSec();
        if (effectiveNow < first || effectiveNow > last) return null;   // hors fenêtre de service

        // GPS réel prioritaire
        RtSnapshot.VehiclePos gps = rt.positions().get(trip.tripId());
        if (gps != null) {
            double idx = indexed.project(new Coordinate(gps.lng(), gps.lat()));
            Coordinate snapped = indexed.extractPoint(idx);
            double bearing = gps.bearing() != null ? gps.bearing() : bearingAt(indexed, geomLength, idx);
            return new Vehicle(trip.tripId(), snapped.y, snapped.x, bearing, delay,
                trip.headsign(), nextStopName(ps, distToStopIndex(ps, idx)), Vehicle.Source.REALTIME);
        }

        // interpolation le long du tracé
        double dist = interpolateDistance(ps, effectiveNow);
        Coordinate pos = indexed.extractPoint(dist);
        double bearing = bearingAt(indexed, geomLength, dist);
        String next = nextStopName(ps, dist);
        return new Vehicle(trip.tripId(), pos.y, pos.x, bearing, delay,
            trip.headsign(), next, Vehicle.Source.INTERPOLATED);
    }

    private double interpolateDistance(List<StopPassage> ps, int t) {
        for (int i = 0; i < ps.size() - 1; i++) {
            StopPassage a = ps.get(i), b = ps.get(i + 1);
            if (t >= a.departureSec() && t <= b.departureSec()) {
                double frac = (double) (t - a.departureSec()) / (b.departureSec() - a.departureSec());
                return a.distanceAlongLine() + frac * (b.distanceAlongLine() - a.distanceAlongLine());
            }
        }
        return ps.get(ps.size() - 1).distanceAlongLine();
    }

    private String nextStopName(List<StopPassage> ps, double dist) {
        for (StopPassage p : ps) if (p.distanceAlongLine() > dist + 1e-9) return p.stopName();
        return ps.get(ps.size() - 1).stopName();
    }

    private double distToStopIndex(List<StopPassage> ps, double dist) { return dist; } // alias lisibilité

    private double bearingAt(LengthIndexedLine indexed, double geomLength, double dist) {
        double d1 = Math.max(0, dist - 1e-5);
        double d2 = Math.min(geomLength, dist + 1e-5);
        Coordinate a = indexed.extractPoint(d1), b = indexed.extractPoint(d2);
        double angle = Math.toDegrees(Math.atan2(b.x - a.x, b.y - a.y)); // 0=Nord, 90=Est
        return (angle + 360) % 360;
    }
}
```

- [ ] **Step 5: run → PASS**

Run: `cd backend && ./mvnw test -Dtest=PositionEngineTest`
Expected: PASS (les 4 cas).

- [ ] **Step 6: commit**

```bash
git add backend/src/main/java/com/mapidf/position backend/src/test/java/com/mapidf/position
git commit -m "feat(backend): PositionEngine pur (interpolation + snap GPS, JTS)"
```

---

### Task 8: Endpoint `GET /api/lines/{id}/vehicles`

Deliverable : le front reçoit la liste des véhicules calculés à l'instant courant.

**Files:**
- Create: `backend/src/main/java/com/mapidf/api/dto/VehiclesResponse.java`
- Create: `backend/src/main/java/com/mapidf/position/ScheduleProvider.java`
- Modify: `backend/src/main/java/com/mapidf/api/LineController.java`
- Create: `backend/src/test/java/com/mapidf/api/LineControllerVehiclesTest.java`

**Interfaces:**
- Consumes: `PositionEngine` (Task 7), `GtfsStaticService.getRouteGeometry()` (Task 4), `RealtimePoller.current()` (Task 6).
- Produces: `ScheduleProvider.getSchedules(routeId)` → `List<TripSchedule>` (lit la base, calcule `distanceAlongLine` de chaque arrêt via `ST_LineLocatePoint`). `VehiclesResponse(Instant asOf, List<Vehicle> vehicles)`.

- [ ] **Step 1: `VehiclesResponse`**

```java
package com.mapidf.api.dto;
import com.mapidf.position.Vehicle;
import java.time.Instant;
import java.util.List;
public record VehiclesResponse(Instant asOf, List<Vehicle> vehicles) {}
```

- [ ] **Step 2: test d'intégration qui échoue**

```java
package com.mapidf.api;
import com.mapidf.MapIdfApplication;
import com.mapidf.gtfs.GtfsStaticService;
import com.mapidf.gtfs.GtfsStaticLoader;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = MapIdfApplication.class)
@AutoConfigureMockMvc
@Testcontainers
class LineControllerVehiclesTest {
    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgis/postgis:16-3.4")
        .withDatabaseName("mapidf").withUsername("mapidf").withPassword("mapidf");
    @DynamicPropertySource static void p(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
        r.add("app.line.id", () -> "TEST9");
        r.add("app.line.gtfs-route-id", () -> "TEST9");
    }
    @Autowired GtfsStaticLoader loader;
    @Autowired GtfsStaticService staticService;
    @Autowired MockMvc mvc;

    @BeforeEach void seed() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-mini.zip")) { loader.loadFromZip(in, "TEST9"); }
        staticService.cacheGeometry();
    }

    @Test void returnsVehiclesEnvelope() throws Exception {
        // sans forcer l'instant, on vérifie surtout la forme de la réponse (peut être 0 véhicule hors 08:00-08:20)
        mvc.perform(get("/api/lines/TEST9/vehicles"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.asOf").exists())
           .andExpect(jsonPath("$.vehicles").isArray());
    }
}
```

- [ ] **Step 3: run → FAIL**

Run: `cd backend && ./mvnw test -Dtest=LineControllerVehiclesTest`
Expected: FAIL.

- [ ] **Step 4: `ScheduleProvider` (distance de chaque arrêt le long du tracé)**

```java
package com.mapidf.position;
import com.mapidf.position.TripSchedule.StopPassage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ScheduleProvider {
    private final JdbcTemplate jdbc;
    public ScheduleProvider(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<TripSchedule> getSchedules(String routeId) {
        // distanceAlongLine : fraction [0..1] de ST_LineLocatePoint × longueur du tracé (en degrés) pour rester
        // cohérent avec PositionEngine qui indexe par longueur géométrique.
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT st.trip_id, t.headsign, st.stop_id, s.name, st.stop_sequence, st.departure_sec, " +
            "       ST_LineLocatePoint(r.geom, s.geom) * ST_Length(r.geom) AS dist " +
            "FROM stop_time st " +
            "JOIN trip t ON t.id = st.trip_id " +
            "JOIN route r ON r.id = t.route_id " +
            "JOIN stop s ON s.id = st.stop_id " +
            "WHERE t.route_id = ? ORDER BY st.trip_id, st.stop_sequence", routeId);

        Map<String, List<StopPassage>> byTrip = new LinkedHashMap<>();
        Map<String, String> headsigns = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String tripId = (String) row.get("trip_id");
            headsigns.putIfAbsent(tripId, (String) row.get("headsign"));
            byTrip.computeIfAbsent(tripId, k -> new ArrayList<>()).add(new StopPassage(
                (String) row.get("stop_id"), (String) row.get("name"),
                ((Number) row.get("departure_sec")).intValue(),
                ((Number) row.get("dist")).doubleValue()));
        }
        List<TripSchedule> out = new ArrayList<>();
        byTrip.forEach((tripId, passages) -> out.add(new TripSchedule(tripId, headsigns.get(tripId), passages)));
        return out;
    }
}
```

- [ ] **Step 5: étendre `LineController` avec `/vehicles`**

```java
package com.mapidf.api;
import com.mapidf.api.dto.ShapeResponse;
import com.mapidf.api.dto.VehiclesResponse;
import com.mapidf.gtfs.GtfsStaticService;
import com.mapidf.position.*;
import com.mapidf.rt.RealtimePoller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.List;

@RestController
@RequestMapping("/api/lines")
public class LineController {
    private final NetworkQueryService network;
    private final ScheduleProvider schedules;
    private final PositionEngine engine;
    private final GtfsStaticService staticService;
    private final RealtimePoller poller;
    private final String routeId;
    private final ZoneId zone = ZoneId.of("Europe/Paris");

    public LineController(NetworkQueryService network, ScheduleProvider schedules, PositionEngine engine,
                          GtfsStaticService staticService, RealtimePoller poller,
                          @Value("${app.line.gtfs-route-id:}") String routeId) {
        this.network = network; this.schedules = schedules; this.engine = engine;
        this.staticService = staticService; this.poller = poller; this.routeId = routeId;
    }

    @GetMapping("/{id}/shape")
    public ShapeResponse shape(@PathVariable String id) { return network.getShape(id); }

    @GetMapping("/{id}/vehicles")
    public VehiclesResponse vehicles(@PathVariable String id) {
        var line = staticService.getRouteGeometry();
        var trips = schedules.getSchedules(routeId);
        var rt = poller.current();
        LocalTime now = LocalTime.now(zone);
        int secOfDay = now.toSecondOfDay();
        List<Vehicle> vs = (line == null) ? List.of() : engine.computeAll(line, trips, rt, secOfDay);
        return new VehiclesResponse(Instant.now(), vs);
    }
}
```

- [ ] **Step 6: run → PASS ; commit**

Run: `cd backend && ./mvnw test -Dtest=LineControllerVehiclesTest` → PASS
```bash
git add backend/src/main/java/com/mapidf backend/src/test/java/com/mapidf/api/LineControllerVehiclesTest.java
git commit -m "feat(backend): endpoint GET /api/lines/{id}/vehicles + ScheduleProvider"
```

---

### Task 9: Résilience du poller + métriques Actuator

Deliverable : en cas d'échec IDFM, le dernier snapshot est conservé ; l'âge du snapshot et le nb de véhicules sont exposés en métriques.

**Files:**
- Modify: `backend/src/main/java/com/mapidf/rt/RealtimePoller.java`
- Create: `backend/src/main/java/com/mapidf/rt/RtMetrics.java`
- Create: `backend/src/test/java/com/mapidf/rt/RealtimePollerResilienceTest.java`

**Interfaces:**
- Consumes: `RtSnapshot`, Micrometer `MeterRegistry`.
- Produces: métriques `mapidf.rt.snapshot.age.seconds` (gauge), `mapidf.rt.poll.failures` (counter). `RealtimePoller.pollOnce(fetcher)` testable (injection d'un fetcher).

- [ ] **Step 1: test qui échoue — un échec de fetch ne remplace pas le snapshot**

```java
package com.mapidf.rt;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class RealtimePollerResilienceTest {
    @Test void keepsLastSnapshotOnFetchFailure() throws Exception {
        var reg = new SimpleMeterRegistry();
        var poller = new RealtimePoller("", "", "", "apikey");
        poller.attachMetrics(reg);
        // 1er poll OK via fetcher injecté : renvoie un snapshot avec T1
        byte[] vp = RealtimePollerParseTestData.vehiclePositionsForT1();
        poller.pollOnce((url) -> vp.length == 0 ? new byte[0] : vp, Instant.ofEpochSecond(100));
        assertThat(poller.current().positions()).containsKey("T1");
        // 2e poll : le fetcher jette → snapshot conservé, compteur d'échec incrémenté
        poller.pollOnce((url) -> { throw new RuntimeException("IDFM down"); }, Instant.ofEpochSecond(200));
        assertThat(poller.current().positions()).containsKey("T1");
        assertThat(reg.counter("mapidf.rt.poll.failures").count()).isEqualTo(1.0);
    }
}
```

Ajouter le helper de test `backend/src/test/java/com/mapidf/rt/RealtimePollerParseTestData.java` :

```java
package com.mapidf.rt;
import com.google.transit.realtime.GtfsRealtime.*;
class RealtimePollerParseTestData {
    static byte[] vehiclePositionsForT1() {
        var fm = FeedMessage.newBuilder();
        fm.getHeaderBuilder().setGtfsRealtimeVersion("2.0")
          .setIncrementality(FeedHeader.Incrementality.FULL_DATASET).setTimestamp(100L);
        var e = fm.addEntityBuilder().setId("v1");
        var vp = e.getVehicleBuilder();
        vp.getTripBuilder().setTripId("T1");
        vp.getPositionBuilder().setLatitude(48.850f).setLongitude(2.305f).setBearing(90f);
        return fm.build().toByteArray();
    }
}
```

- [ ] **Step 2: run → FAIL** (`attachMetrics`, `pollOnce`, `Fetcher` absents)

Run: `cd backend && ./mvnw test -Dtest=RealtimePollerResilienceTest`
Expected: FAIL.

- [ ] **Step 3: refactorer `RealtimePoller` pour l'injection + métriques**

Remplacer le corps de `poll()`/`fetch()` par une abstraction `Fetcher` testable et un snapshot conservé en cas d'échec :

```java
    // dans RealtimePoller :
    @FunctionalInterface public interface Fetcher { byte[] get(String url) throws Exception; }

    private io.micrometer.core.instrument.Counter failures;

    public void attachMetrics(io.micrometer.core.instrument.MeterRegistry reg) {
        this.failures = reg.counter("mapidf.rt.poll.failures");
        reg.gauge("mapidf.rt.snapshot.age.seconds", snapshot,
            ref -> java.time.Duration.between(ref.get().asOf(), java.time.Instant.now()).getSeconds());
    }

    @org.springframework.beans.factory.annotation.Autowired
    void bindMetrics(io.micrometer.core.instrument.MeterRegistry reg) { attachMetrics(reg); }

    @Scheduled(fixedRateString = "${app.prim.poll-interval:PT10S}")
    public void poll() {
        pollOnce(this::fetch, java.time.Instant.now());
    }

    void pollOnce(Fetcher fetcher, java.time.Instant asOf) {
        try {
            byte[] vp = fetcher.get(vpUrl);
            byte[] tu = fetcher.get(tuUrl);
            snapshot.set(parse(vp, tu, asOf));
        } catch (Exception e) {
            if (failures != null) failures.increment();   // dégradation gracieuse : dernier snapshot conservé
        }
    }
```

(Le `fetch(String)` existant reste comme implémentation de `Fetcher` : `private byte[] fetch(String url) throws Exception {...}` inchangé.)

- [ ] **Step 4: run → PASS**

Run: `cd backend && ./mvnw test -Dtest=RealtimePollerResilienceTest`
Expected: PASS.

- [ ] **Step 5: vérifier l'ensemble de la suite backend + commit**

Run: `cd backend && ./mvnw test`
Expected: PASS (toutes classes).
```bash
git add backend/src
git commit -m "feat(backend): résilience poller (dernier snapshot conservé) + métriques Actuator"
```

---

### Task 10: Scaffold frontend (Vite + React + TS + MapLibre)

Deliverable : carte MapLibre centrée sur Paris s'affiche, proxy `/api` vers le backend.

**Files:**
- Create: `frontend/package.json`, `frontend/vite.config.ts`, `frontend/tsconfig.json`, `frontend/index.html`
- Create: `frontend/src/main.tsx`, `frontend/src/App.tsx`, `frontend/src/map/MapView.tsx`
- Create: `frontend/src/api/config.ts`

**Interfaces:**
- Produces: composant `<MapView>` montant une carte MapLibre ; base URL API = `/api` (proxy Vite en dev).

- [ ] **Step 1: `package.json`**

```json
{
  "name": "mapidf-frontend",
  "private": true,
  "type": "module",
  "scripts": { "dev": "vite", "build": "tsc -b && vite build", "preview": "vite preview" },
  "dependencies": { "maplibre-gl": "^4.7.1", "react": "^18.3.1", "react-dom": "^18.3.1" },
  "devDependencies": {
    "@types/react": "^18.3.11", "@types/react-dom": "^18.3.0", "@types/geojson": "^7946.0.14",
    "@vitejs/plugin-react": "^4.3.2", "typescript": "^5.6.2", "vite": "^5.4.8"
  }
}
```

- [ ] **Step 2: `vite.config.ts` (proxy /api)**

```ts
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
export default defineConfig({
  plugins: [react()],
  server: { proxy: { "/api": "http://localhost:8080" } },
});
```

- [ ] **Step 3: `index.html`, `tsconfig.json`, `main.tsx`, `config.ts`**

`index.html` :
```html
<!doctype html><html lang="fr"><head><meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>MapIDF — Ligne 9</title></head>
<body><div id="root"></div><script type="module" src="/src/main.tsx"></script></body></html>
```

`tsconfig.json` :
```json
{ "compilerOptions": { "target": "ES2020", "lib": ["ES2020","DOM","DOM.Iterable"],
  "module": "ESNext", "moduleResolution": "bundler", "jsx": "react-jsx",
  "strict": true, "noEmit": true, "skipLibCheck": true }, "include": ["src"] }
```

`src/api/config.ts` :
```ts
export const API_BASE = "/api";
export const LINE_ID = "9";
export const VEHICLE_POLL_MS = 4000;
```

`src/main.tsx` :
```tsx
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "maplibre-gl/dist/maplibre-gl.css";
import App from "./App";
createRoot(document.getElementById("root")!).render(<StrictMode><App /></StrictMode>);
```

- [ ] **Step 4: `MapView.tsx` (carte de base) + `App.tsx`**

`src/map/MapView.tsx` :
```tsx
import { useEffect, useRef } from "react";
import maplibregl, { Map as MlMap } from "maplibre-gl";

export function useMap(container: React.RefObject<HTMLDivElement>) {
  const mapRef = useRef<MlMap | null>(null);
  useEffect(() => {
    if (!container.current || mapRef.current) return;
    mapRef.current = new maplibregl.Map({
      container: container.current,
      style: "https://demotiles.maplibre.org/style.json", // fond de démo ; remplacer par un fond vectoriel dédié en prod
      center: [2.34, 48.86],
      zoom: 11,
    });
    return () => { mapRef.current?.remove(); mapRef.current = null; };
  }, [container]);
  return mapRef;
}
```

`src/App.tsx` :
```tsx
import { useRef } from "react";
import { useMap } from "./map/MapView";
export default function App() {
  const container = useRef<HTMLDivElement>(null);
  useMap(container);
  return <div ref={container} style={{ position: "absolute", inset: 0 }} />;
}
```

- [ ] **Step 5: lancer et vérifier visuellement**

Run: `cd frontend && npm install && npm run dev`
Expected: `http://localhost:5173` affiche une carte centrée sur Paris.

- [ ] **Step 6: commit**

```bash
git add frontend/
git commit -m "feat(frontend): scaffold Vite+React+TS+MapLibre, carte de base"
```

---

### Task 11: Affichage du tracé et des arrêts (`useLineShape`)

Deliverable : la ligne 9 et ses arrêts sont dessinés sur la carte.

**Files:**
- Create: `frontend/src/api/types.ts`
- Create: `frontend/src/api/lines.ts`
- Create: `frontend/src/map/useLineShape.ts`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: `GET /api/lines/{id}/shape`.
- Produces: `fetchShape(lineId): Promise<ShapeResponse>` ; `useLineShape(map, lineId)` dessine une couche ligne `line-shape` + une couche cercles `stops`.

- [ ] **Step 1: types**

```ts
export interface ShapeResponse {
  lineId: string; color: string;
  shape: [number, number][];               // [lng,lat]
  stops: { id: string; name: string; lat: number; lng: number }[];
}
export interface VehiclesResponse {
  asOf: string;
  vehicles: {
    tripId: string; lat: number; lng: number; bearing: number;
    delaySec: number; headsign: string; nextStop: string;
    source: "REALTIME" | "INTERPOLATED";
  }[];
}
```

- [ ] **Step 2: client API**

```ts
import { API_BASE } from "./config";
import type { ShapeResponse, VehiclesResponse } from "./types";
export async function fetchShape(lineId: string): Promise<ShapeResponse> {
  const r = await fetch(`${API_BASE}/lines/${lineId}/shape`);
  if (!r.ok) throw new Error(`shape ${r.status}`);
  return r.json();
}
export async function fetchVehicles(lineId: string): Promise<VehiclesResponse> {
  const r = await fetch(`${API_BASE}/lines/${lineId}/vehicles`);
  if (!r.ok) throw new Error(`vehicles ${r.status}`);
  return r.json();
}
```

- [ ] **Step 3: `useLineShape`**

```ts
import { useEffect } from "react";
import type { Map as MlMap } from "maplibre-gl";
import { fetchShape } from "../api/lines";

export function useLineShape(map: MlMap | null, lineId: string) {
  useEffect(() => {
    if (!map) return;
    let cancelled = false;
    fetchShape(lineId).then((shape) => {
      if (cancelled) return;
      const draw = () => {
        if (map.getSource("line-shape")) return;
        map.addSource("line-shape", {
          type: "geojson",
          data: { type: "Feature", properties: {},
            geometry: { type: "LineString", coordinates: shape.shape } },
        });
        map.addLayer({ id: "line-shape", type: "line", source: "line-shape",
          paint: { "line-color": shape.color, "line-width": 4 } });
        map.addSource("stops", {
          type: "geojson",
          data: { type: "FeatureCollection", features: shape.stops.map((s) => ({
            type: "Feature", properties: { name: s.name },
            geometry: { type: "Point", coordinates: [s.lng, s.lat] } })) },
        });
        map.addLayer({ id: "stops", type: "circle", source: "stops",
          paint: { "circle-radius": 5, "circle-color": "#fff",
            "circle-stroke-color": shape.color, "circle-stroke-width": 2 } });
      };
      if (map.isStyleLoaded()) draw(); else map.once("load", draw);
    });
    return () => { cancelled = true; };
  }, [map, lineId]);
}
```

- [ ] **Step 4: brancher dans `App.tsx`**

```tsx
import { useRef } from "react";
import { useMap } from "./map/MapView";
import { useLineShape } from "./map/useLineShape";
import { LINE_ID } from "./api/config";
export default function App() {
  const container = useRef<HTMLDivElement>(null);
  const map = useMap(container);
  useLineShape(map.current, LINE_ID);
  return <div ref={container} style={{ position: "absolute", inset: 0 }} />;
}
```

- [ ] **Step 5: vérifier visuellement (backend lancé + base chargée)**

Run: backend démarré (`./mvnw spring-boot:run` avec `PRIM_API_KEY` défini) puis `cd frontend && npm run dev`.
Expected: le tracé de la ligne 9 et ses arrêts s'affichent.

- [ ] **Step 6: commit**

```bash
git add frontend/src
git commit -m "feat(frontend): affichage tracé + arrêts de la ligne (useLineShape)"
```

---

### Task 12: Véhicules animés (`useVehicles` + tween RAF)

Deliverable : des marqueurs glissent le long du tracé, mis à jour toutes les ~4 s, orientés selon le cap.

**Files:**
- Create: `frontend/src/map/VehicleLayer.ts`
- Create: `frontend/src/map/useVehicles.ts`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: `fetchVehicles` (Task 11), couche carte.
- Produces: `useVehicles(map, lineId)` qui poll et anime. Animation par interpolation linéaire lat/lng entre position affichée et nouvelle cible sur `VEHICLE_POLL_MS`.

- [ ] **Step 1: `VehicleLayer` — source GeoJSON de points + tween**

```ts
import type { Map as MlMap, GeoJSONSource } from "maplibre-gl";
import type { VehiclesResponse } from "../api/types";

type V = VehiclesResponse["vehicles"][number];
interface Anim { from: [number, number]; to: [number, number]; bearing: number; start: number; v: V; }

export class VehicleLayer {
  private anims = new Map<string, Anim>();
  private raf = 0;
  constructor(private map: MlMap, private durationMs: number) { this.ensureLayer(); }

  private ensureLayer() {
    const add = () => {
      if (this.map.getSource("vehicles")) return;
      this.map.addSource("vehicles", { type: "geojson", data: this.fc([]) });
      this.map.addLayer({
        id: "vehicles", type: "circle", source: "vehicles",
        paint: {
          "circle-radius": 7,
          "circle-color": ["case", ["==", ["get", "source"], "REALTIME"], "#e30613", "#f7a600"],
          "circle-stroke-color": "#fff", "circle-stroke-width": 2,
          "circle-opacity": ["case", ["==", ["get", "source"], "INTERPOLATED"], 0.7, 1.0],
        },
      });
    };
    if (this.map.isStyleLoaded()) add(); else this.map.once("load", add);
  }

  private fc(features: GeoJSON.Feature[]): GeoJSON.FeatureCollection {
    return { type: "FeatureCollection", features };
  }

  update(vehicles: V[], now: number) {
    const seen = new Set<string>();
    for (const v of vehicles) {
      seen.add(v.tripId);
      const prev = this.anims.get(v.tripId);
      const current = prev ? this.pointAt(prev, now) : [v.lng, v.lat] as [number, number];
      this.anims.set(v.tripId, { from: current, to: [v.lng, v.lat], bearing: v.bearing, start: now, v });
    }
    for (const id of [...this.anims.keys()]) if (!seen.has(id)) this.anims.delete(id);
    this.startLoop();
  }

  private pointAt(a: Anim, now: number): [number, number] {
    const t = Math.min(1, (now - a.start) / this.durationMs);
    return [a.from[0] + (a.to[0] - a.from[0]) * t, a.from[1] + (a.to[1] - a.from[1]) * t];
  }

  private startLoop() {
    if (this.raf) return;
    const step = (now: number) => {
      const src = this.map.getSource("vehicles") as GeoJSONSource | undefined;
      if (src) {
        const features = [...this.anims.values()].map((a) => {
          const [lng, lat] = this.pointAt(a, now);
          return { type: "Feature", properties: {
            tripId: a.v.tripId, source: a.v.source, bearing: a.bearing,
            headsign: a.v.headsign, nextStop: a.v.nextStop, delaySec: a.v.delaySec },
            geometry: { type: "Point", coordinates: [lng, lat] } } as GeoJSON.Feature;
        });
        src.setData(this.fc(features));
      }
      this.raf = requestAnimationFrame(step);
    };
    this.raf = requestAnimationFrame(step);
  }

  destroy() { if (this.raf) cancelAnimationFrame(this.raf); this.raf = 0; this.anims.clear(); }
}
```

- [ ] **Step 2: `useVehicles`**

```ts
import { useEffect } from "react";
import type { Map as MlMap } from "maplibre-gl";
import { fetchVehicles } from "../api/lines";
import { VEHICLE_POLL_MS } from "../api/config";
import { VehicleLayer } from "./VehicleLayer";

export function useVehicles(map: MlMap | null, lineId: string) {
  useEffect(() => {
    if (!map) return;
    const layer = new VehicleLayer(map, VEHICLE_POLL_MS);
    let timer: number;
    const tick = async () => {
      try {
        const res = await fetchVehicles(lineId);
        layer.update(res.vehicles, performance.now());
      } catch { /* on garde l'affichage courant */ }
      timer = window.setTimeout(tick, VEHICLE_POLL_MS);
    };
    tick();
    return () => { window.clearTimeout(timer); layer.destroy(); };
  }, [map, lineId]);
}
```

- [ ] **Step 3: brancher dans `App.tsx`**

Ajouter `useVehicles(map.current, LINE_ID);` après `useLineShape(...)`.

- [ ] **Step 4: vérifier visuellement**

Run: backend + `npm run dev`.
Expected: des points glissent le long de la ligne 9 ; rouges = position GPS réelle, orange semi-transparent = interpolé.

- [ ] **Step 5: commit**

```bash
git add frontend/src
git commit -m "feat(frontend): véhicules animés (tween RAF) + distinction realtime/interpolé"
```

---

### Task 13: Panneau de détails au clic sur un véhicule

Deliverable : cliquer un véhicule affiche destination, prochain arrêt, retard, source.

**Files:**
- Create: `frontend/src/ui/VehiclePanel.tsx`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: événement `click` sur la couche `vehicles`.
- Produces: `<VehiclePanel vehicle={...} onClose={...}/>`.

- [ ] **Step 1: `VehiclePanel`**

```tsx
interface Props {
  vehicle: { headsign: string; nextStop: string; delaySec: number; source: string } | null;
  onClose: () => void;
}
export function VehiclePanel({ vehicle, onClose }: Props) {
  if (!vehicle) return null;
  const delay = Math.round(vehicle.delaySec / 60);
  return (
    <div style={{ position: "absolute", top: 12, right: 12, width: 260, padding: 16,
      background: "#fff", borderRadius: 8, boxShadow: "0 2px 12px rgba(0,0,0,.2)", font: "14px sans-serif" }}>
      <button onClick={onClose} style={{ float: "right", border: "none", background: "none", cursor: "pointer" }}>✕</button>
      <h3 style={{ margin: "0 0 8px" }}>→ {vehicle.headsign}</h3>
      <p style={{ margin: "4px 0" }}>Prochain arrêt : <b>{vehicle.nextStop}</b></p>
      <p style={{ margin: "4px 0" }}>Retard : {delay > 0 ? `+${delay} min` : "à l'heure"}</p>
      <p style={{ margin: "4px 0", color: "#666" }}>
        Position : {vehicle.source === "REALTIME" ? "GPS temps réel" : "estimée (horaire)"}
      </p>
    </div>
  );
}
```

- [ ] **Step 2: gérer le clic dans `App.tsx`**

```tsx
import { useRef, useState, useEffect } from "react";
import { useMap } from "./map/MapView";
import { useLineShape } from "./map/useLineShape";
import { useVehicles } from "./map/useVehicles";
import { VehiclePanel } from "./ui/VehiclePanel";
import { LINE_ID } from "./api/config";

export default function App() {
  const container = useRef<HTMLDivElement>(null);
  const map = useMap(container);
  const [selected, setSelected] = useState<any>(null);
  useLineShape(map.current, LINE_ID);
  useVehicles(map.current, LINE_ID);

  useEffect(() => {
    const m = map.current; if (!m) return;
    const onClick = (e: any) => { setSelected(e.features?.[0]?.properties ?? null); };
    m.on("click", "vehicles", onClick);
    return () => { m.off("click", "vehicles", onClick); };
  }, [map.current]);

  return (
    <>
      <div ref={container} style={{ position: "absolute", inset: 0 }} />
      <VehiclePanel vehicle={selected} onClose={() => setSelected(null)} />
    </>
  );
}
```

- [ ] **Step 3: vérifier visuellement**

Run: backend + `npm run dev`.
Expected: clic sur un véhicule → panneau avec destination/prochain arrêt/retard/source.

- [ ] **Step 4: commit**

```bash
git add frontend/src
git commit -m "feat(frontend): panneau de détails véhicule au clic"
```

---

### Task 14: Packaging & déploiement (Docker, front servi, monitoring)

Deliverable : `docker compose up` lance PostGIS + backend ; le front buildé est servi ; healthcheck OK.

**Files:**
- Create: `backend/Dockerfile`
- Create: `frontend/Dockerfile`
- Create: `docker-compose.yml` (racine, full stack)
- Create: `README.md` (racine)

**Interfaces:**
- Consumes: artefacts des tâches précédentes, `PRIM_API_KEY` d'environnement.

- [ ] **Step 1: `backend/Dockerfile`**

```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./mvnw -q -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
```

- [ ] **Step 2: `frontend/Dockerfile` (build statique servi par nginx, proxy /api)**

```dockerfile
FROM node:20 AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```

Créer `frontend/nginx.conf` :
```nginx
server {
  listen 80;
  location /api/ { proxy_pass http://backend:8080; }
  location / { root /usr/share/nginx/html; try_files $uri /index.html; }
}
```

- [ ] **Step 3: `docker-compose.yml` racine**

```yaml
services:
  db:
    image: postgis/postgis:16-3.4
    environment: { POSTGRES_DB: mapidf, POSTGRES_USER: mapidf, POSTGRES_PASSWORD: mapidf }
    volumes: ["dbdata:/var/lib/postgresql/data"]
  backend:
    build: ./backend
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/mapidf
      SPRING_DATASOURCE_USERNAME: mapidf
      SPRING_DATASOURCE_PASSWORD: mapidf
      PRIM_API_KEY: ${PRIM_API_KEY}
    depends_on: [db]
    ports: ["8080:8080"]
  frontend:
    build: ./frontend
    depends_on: [backend]
    ports: ["8081:80"]
volumes:
  dbdata:
```

- [ ] **Step 4: `README.md` (racine) — démarrage**

```markdown
# MapIDF — suivi temps réel de la ligne 9

## Démarrage
1. `export PRIM_API_KEY=<votre clé PRIM>`
2. `docker compose up --build`
3. Front : http://localhost:8081 — API : http://localhost:8080/api/lines/9/vehicles
4. Santé backend : http://localhost:8080/actuator/health

## Développement
- Backend : `cd backend && ./mvnw spring-boot:run`
- Front : `cd frontend && npm run dev` (proxy /api → :8080)
- Tests backend : `cd backend && ./mvnw test`
```

- [ ] **Step 5: vérifier le stack complet**

Run: `export PRIM_API_KEY=... && docker compose up --build`
Expected: `curl localhost:8080/actuator/health` → `{"status":"UP"}` ; front sur :8081 affiche la carte animée.

- [ ] **Step 6: commit**

```bash
git add backend/Dockerfile frontend/Dockerfile frontend/nginx.conf docker-compose.yml README.md
git commit -m "chore: packaging Docker full-stack + README"
```

---

## Notes de fin

- **Fond de carte** : `demotiles.maplibre.org` est un fond de démonstration. Pour la prod, prévoir un fond vectoriel dédié (ex. clé MapTiler ou tuiles auto-hébergées) — ne change que le champ `style` de `MapView.tsx`.
- **Fréquences de poll** : front 4 s (`VEHICLE_POLL_MS`), backend 10 s (`app.prim.poll-interval`) — à ajuster selon les quotas réels PRIM relevés en Task 2.
- **Évolutions post-MVP** (spec §10) : multi-lignes, bascule SSE, historisation. L'archi PostGIS et le `PositionEngine` pur y sont prêts.
