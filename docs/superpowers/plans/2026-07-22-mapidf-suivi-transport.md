# MapIDF — Suivi transport temps réel — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Afficher les véhicules de la ligne 9 du métro parisien se déplaçant en quasi temps réel sur une carte interactive.

**Architecture:** Backend Spring Boot qui poll le temps réel IDFM/PRIM (GTFS statique open data + SIRI-ET `requete-ligne`), stocke le réseau en PostGIS (types géométriques JTS mappés par Hibernate Spatial), et calcule une position estimée par course (prochain arrêt + ETA SIRI interpolés sur le tracé via les horaires GTFS). Le front React+MapLibre poll un snapshot toutes les ~4 s et anime les marqueurs par tween `requestAnimationFrame` le long du tracé connu localement.

**Tech Stack:** Java 25, Spring Boot 4.1.0, PostgreSQL 16 + PostGIS 3.4, Flyway, Hibernate Spatial + JTS, Lombok, **SIRI-ET JSON parsé via Jackson** (fourni par `spring-boot-starter-web`), Apache Commons CSV, Testcontainers ; React 18 + Vite + TypeScript, MapLibre GL JS.

> **Note source temps réel (vérifiée en Task 2, cf. `backend/docs/prim-integration.md`)** : IDFM n'expose pas de GTFS-RT « clé en main ». Le temps réel est en **SIRI Lite** via `GET /marketplace/requete-ligne?LineRef=STIF:Line::C01379:` (ligne 9 = `C01379`, en-tête `apikey`). Chaque course (`EstimatedVehicleJourney`) ne donne que son **prochain arrêt** + `ExpectedDepartureTime` + destination. La position est donc **estimée** (jamais un GPS brut) par interpolation ETA + géométrie/horaires GTFS.

## Global Constraints

Conventions alignées sur `/home/abodet/workspace/steamulo/spring-boot-starter-v2` (CLAUDE.md) :

- **Java 25**, **Spring Boot 4.1.0**, build **Maven** (parent `spring-boot-starter-parent:4.1.0`).
- **Package racine** : `com.mapidf`. Structure : `controllers/<feature>/`, `data/entity`, `data/repositories`, `data/enums`, `data/dto`, `configurations/properties`, `exceptions`, `gtfs`, `rt`, `position`.
- **Lombok partout** : `@Value @Builder` pour DTO réponses (immutables, champs `final`) ; `@Getter @ToString @NoArgsConstructor @AllArgsConstructor @Builder @EqualsAndHashCode` pour entités.
- **Injection par constructeur** via `@AllArgsConstructor` ; **jamais** `@Autowired` sur un champ.
- **UUID en PK** (`@GeneratedValue(strategy = GenerationType.UUID)`, `gen_random_uuid()` en base). Les identifiants GTFS sont des colonnes **uniques** (`gtfs_id`), pas des PK ; relations via `@ManyToOne`.
- **`context-path: /api`** (dans `application.yml`) → les `@RequestMapping` n'incluent **pas** `/api`. API port **8000**, Actuator port **9000**.
- **`@Transactional`** sur méthodes de service qui écrivent ; `@Transactional(readOnly = true)` sur les lectures ; **jamais** sur un contrôleur.
- **Enums** : comparaison avec `==`, jamais `.equals()`.
- **Erreurs** : `throw new ApiException(HttpStatus, ErrorCode[, cause])`. Ne **pas** `log.error` dans un service avant de relancer (`ApiExceptionHandler` logge déjà).
- **Style** : 4 espaces, accolades **toujours** (même mono-instruction), pas de commentaire sauf si le POURQUOI n'est pas évident.
- **Clé API PRIM** lue depuis l'environnement `PRIM_API_KEY` — jamais commitée, jamais renvoyée au front.
- **Ligne de référence MVP = métro ligne 9** ; identifiant paramétrable (`app.line.gtfs-route-id`).
- **Tests** : annotation composée **`@MapIdfTest`** (`@SpringBootTest` + `@ActiveProfiles("test")` + `@Import(TestcontainersConfiguration.class)` + `@Transactional`). **`@AutoConfigureMockMvc` n'existe pas en Boot 4** → monter MockMvc à la main : `MockMvcBuilders.webAppContextSetup(wac).build()`.
- Coordonnées : ordre **`[lng, lat]`** dans les payloads (convention MapLibre) ; SRID **4326** en base.
- Le `PositionEngine` est **pur, déterministe** (instant `t` injecté) et **ne touche pas la base**.
- TDD strict : test qui échoue → implémentation minimale → test qui passe → commit.

---

### Task 1: Scaffold backend + infra + socle technique

Deliverable : l'app démarre sur PostGIS (Testcontainers en test), `/actuator/health` = `UP`, socle d'exceptions en place.

**Files:**
- Create: `backend/pom.xml`, `backend/lombok.config`
- Create: `backend/src/main/java/com/mapidf/MapIdfApplication.java`
- Create: `backend/src/main/resources/application.yml`, `backend/src/main/resources/application-test.yml`
- Create: `backend/docker-compose.yml`
- Create: `backend/src/main/java/com/mapidf/data/enums/ErrorCode.java`
- Create: `backend/src/main/java/com/mapidf/data/dto/ErrorResponse.java`
- Create: `backend/src/main/java/com/mapidf/exceptions/ApiException.java`
- Create: `backend/src/main/java/com/mapidf/exceptions/ApiExceptionHandler.java`
- Create: `backend/src/test/java/com/mapidf/MapIdfTest.java`
- Create: `backend/src/test/java/com/mapidf/TestcontainersConfiguration.java`
- Create: `backend/src/test/java/com/mapidf/SmokeIT.java`

**Interfaces:**
- Produces: `@MapIdfTest`, `TestcontainersConfiguration` (bean `@ServiceConnection` PostGIS), `ApiException(HttpStatus, ErrorCode[, Throwable])`, `ErrorCode`, `ErrorResponse`.

- [ ] **Step 1: `pom.xml`** (calqué sur le starter, sans les modules auth/mail/quartz)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.0</version>
    <relativePath/>
  </parent>
  <groupId>com.mapidf</groupId>
  <artifactId>mapidf-backend</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <name>MapIDF-Backend</name>
  <properties>
    <java.version>25</java.version>
  </properties>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-flyway</artifactId></dependency>
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>
    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
    <dependency><groupId>org.hibernate.orm</groupId><artifactId>hibernate-spatial</artifactId></dependency>
    <dependency><groupId>org.locationtech.jts</groupId><artifactId>jts-core</artifactId></dependency>
    <dependency><groupId>org.apache.commons</groupId><artifactId>commons-csv</artifactId><version>1.11.0</version></dependency>
    <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><optional>true</optional></dependency>

    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>testcontainers-postgresql</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>testcontainers-junit-jupiter</artifactId><scope>test</scope></dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
        <configuration>
          <annotationProcessorPaths>
            <path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId></path>
          </annotationProcessorPaths>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>
        <configuration><argLine>@{argLine} -XX:+EnableDynamicAgentLoading</argLine></configuration>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId><artifactId>maven-failsafe-plugin</artifactId>
        <configuration>
          <reportsDirectory>${project.build.directory}/surefire-reports</reportsDirectory>
          <argLine>@{argLine} -XX:+EnableDynamicAgentLoading</argLine>
        </configuration>
        <executions>
          <execution>
            <id>failsafe-integration-tests</id>
            <phase>integration-test</phase>
            <goals><goal>integration-test</goal><goal>verify</goal></goals>
          </execution>
        </executions>
      </plugin>
      <plugin>
        <groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId><version>0.8.15</version>
        <executions>
          <execution><id>prepare-agent</id><goals><goal>prepare-agent</goal></goals></execution>
          <execution><id>report</id><phase>verify</phase><goals><goal>report</goal></goals></execution>
        </executions>
      </plugin>
      <plugin>
        <groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId>
        <configuration><excludes><exclude><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId></exclude></excludes></configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

> Générer le wrapper Maven : `mvn -N wrapper:wrapper -Dmaven=3.9.9` (ou copier `.mvn/`, `mvnw`, `mvnw.cmd` depuis le starter). Toutes les commandes ci-dessous supposent `./mvnw` présent.

- [ ] **Step 2: `lombok.config`** (identique au starter)

```
config.stopBubbling = true
lombok.addLombokGeneratedAnnotation = true
lombok.anyConstructor.addConstructorProperties = true
```

- [ ] **Step 3: `docker-compose.yml`**

```yaml
services:
  db:
    image: postgis/postgis:18-3.6
    environment: { POSTGRES_DB: mapidf, POSTGRES_USER: mapidf, POSTGRES_PASSWORD: mapidf }
    ports: ["5432:5432"]
    volumes: ["dbdata:/var/lib/postgresql"]
volumes:
  dbdata:
```

- [ ] **Step 4: `application.yml` et `application-test.yml`**

`application.yml` :
```yaml
server:
  servlet:
    context-path: /api
  port: 8000
management:
  server:
    port: 9000
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: always
spring:
  threads:
    virtual:
      enabled: true
  application:
    name: MapIDF
  datasource:
    url: jdbc:postgresql://localhost:5432/mapidf
    username: mapidf
    password: mapidf
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    show-sql: false
  flyway:
    enabled: true
    locations: classpath:db/migration
app:
  line:
    gtfs-route-id: ""        # route_id GTFS ligne 9 (probable "IDFM:C01379"), renseigné en Task 2
    siri-line-ref: "STIF:Line::C01379:"   # LineRef SIRI de la ligne 9 (confirmé en Task 2)
    color: "#D5C900"
  prim:
    api-key: ${PRIM_API_KEY:}
    auth-header: "apikey"
    gtfs-static-url: ""      # dataset open data offre-horaires-tc-gtfs-idfm (renseigné en Task 2)
    realtime-base-url: "https://prim.iledefrance-mobilites.fr/marketplace/requete-ligne"
    poll-interval: PT10S
```

`application-test.yml` (le datasource est fourni par Testcontainers `@ServiceConnection` ; `realtime-base-url` vide → poller no-op, `gtfs-static-url` vide → refresh no-op) :
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
app:
  prim:
    realtime-base-url: ""
  line:
    gtfs-route-id: "TEST9"
    siri-line-ref: "STIF:Line::C01379:"

- [ ] **Step 5: classe d'application**

```java
package com.mapidf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class MapIdfApplication {
    public static void main(String[] args) {
        SpringApplication.run(MapIdfApplication.class, args);
    }
}
```

- [ ] **Step 6: socle d'exceptions** (`ErrorCode`, `ErrorResponse`, `ApiException`, `ApiExceptionHandler`)

```java
package com.mapidf.data.enums;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    LINE_NOT_FOUND("Line not found"),
    BAD_REQUEST("Invalid request"),
    INTERNAL_ERROR("Internal server error");

    @NonNull
    private final String description;
}
```

```java
package com.mapidf.data.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mapidf.data.enums.ErrorCode;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {
    Instant timestamp;
    int status;
    ErrorCode errorCode;
    String path;
}
```

```java
package com.mapidf.exceptions;

import com.mapidf.data.enums.ErrorCode;
import lombok.Getter;
import lombok.NonNull;
import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final ErrorCode errorCode;

    public ApiException(@NonNull HttpStatus httpStatus, @NonNull ErrorCode errorCode) {
        super(errorCode.getDescription());
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public ApiException(@NonNull HttpStatus httpStatus, @NonNull ErrorCode errorCode, Throwable cause) {
        super(errorCode.getDescription(), cause);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }
}
```

```java
package com.mapidf.exceptions;

import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.mapidf.data.dto.ErrorResponse;
import com.mapidf.data.enums.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    private static ErrorResponse write(HttpServletResponse response, HttpStatus status,
                                        ErrorCode errorCode, HttpServletRequest request) {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON.toString());
        return ErrorResponse.builder()
            .timestamp(Instant.now())
            .status(status.value())
            .errorCode(errorCode)
            .path(request.getRequestURI())
            .build();
    }

    @ExceptionHandler(ApiException.class)
    public ErrorResponse handleApiException(HttpServletRequest request, HttpServletResponse response, ApiException ex) {
        if (ex.getHttpStatus().is5xxServerError()) {
            log.error("Server error [{}]: {}", ex.getErrorCode(), ex.getMessage(), ex);
        } else {
            log.debug("Client error [{}]: {}", ex.getErrorCode(), ex.getMessage());
        }
        return write(response, ex.getHttpStatus(), ex.getErrorCode(), request);
    }

    @ExceptionHandler(Exception.class)
    public ErrorResponse handleUnexpected(HttpServletRequest request, HttpServletResponse response, Exception ex) {
        log.error("Unexpected error", ex);
        return write(response, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, request);
    }
}
```

- [ ] **Step 7: `TestcontainersConfiguration` + `@MapIdfTest`**

```java
package com.mapidf;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("mapidf").withUsername("mapidf").withPassword("mapidf");
    }
}
```

```java
package com.mapidf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MapIdfTest {
}
```

- [ ] **Step 8: `SmokeIT`**

```java
package com.mapidf;

import org.junit.jupiter.api.Test;

@MapIdfTest
class SmokeIT {
    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 9: run → PASS**

Run: `cd backend && ./mvnw test -Dtest=SmokeIT`
Expected: PASS (contexte se charge, PostGIS Testcontainers démarré ; aucune migration encore).

- [ ] **Step 10: commit**

```bash
git add backend/
git commit -m "feat(backend): scaffold Boot 4.1/Java 25 + PostGIS + socle exceptions + @MapIdfTest"
```

---

### Task 2: Spike de vérification PRIM + `@ConfigurationProperties`

Deliverable : URLs/en-tête/`route_id` réels vérifiés et typés dans des `@ConfigurationProperties`.

**Files:**
- Create: `backend/docs/prim-integration.md`
- Create: `backend/src/main/java/com/mapidf/configurations/properties/PrimProperties.java`
- Create: `backend/src/main/java/com/mapidf/configurations/properties/LineProperties.java`
- Modify: `backend/src/main/resources/application.yml`

**Interfaces:**
- Produces: `PrimProperties(apiKey, authHeader, gtfsStaticUrl, realtimeBaseUrl, pollInterval)` ; `LineProperties(gtfsRouteId, siriLineRef, color)`. Consommés par Tasks 4/6/8.

> **⚠️ Spike déjà réalisé** (2026-07-22) : clé PRIM créée et stockée dans `.env`,
> endpoints vérifiés au `curl`, valeurs consignées dans
> `backend/docs/prim-integration.md` (déjà commité). Il ne reste ici qu'à créer les
> deux records de config et reporter les valeurs dans `application.yml`.

- [ ] **Step 1 (fait) : clé PRIM** dans `.env` (`PRIM_API_KEY`). En-tête d'auth confirmé : **`apikey`**.

- [ ] **Step 2 (fait) : valeurs vérifiées** — cf. `backend/docs/prim-integration.md` :
  - Ligne 9 = `C01379` → `siri-line-ref: STIF:Line::C01379:`
  - Temps réel : `GET /marketplace/requete-ligne?LineRef=...` (SIRI-ET JSON, 200 OK)
  - Reste à relever : URL du zip GTFS statique open data (`offre-horaires-tc-gtfs-idfm`)
    et le `route_id` GTFS de la ligne 9 (probable `IDFM:C01379`).

Vérif type (déjà passée) :
`curl -H "apikey: $PRIM_API_KEY" "https://prim.iledefrance-mobilites.fr/marketplace/requete-ligne?LineRef=STIF:Line::C01379:" | head -c 200`
Expected : JSON SIRI (`{"Siri":{"ServiceDelivery":...`).

- [ ] **Step 3: `PrimProperties` + `LineProperties`**

```java
package com.mapidf.configurations.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.prim")
public record PrimProperties(
    String apiKey,
    String authHeader,
    String gtfsStaticUrl,
    String realtimeBaseUrl,
    Duration pollInterval
) {
}
```

```java
package com.mapidf.configurations.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.line")
public record LineProperties(
    String gtfsRouteId,
    String siriLineRef,
    String color
) {
}
```

- [ ] **Step 4: reporter les valeurs vérifiées dans `application.yml`** (champs `app.prim.*` et `app.line.gtfs-route-id`), puis commit (sans secret)

```bash
git add backend/docs/prim-integration.md backend/src/main/java/com/mapidf/configurations backend/src/main/resources/application.yml
git commit -m "feat(backend): ConfigurationProperties PRIM/ligne + valeurs vérifiées"
```

---

### Task 3: Schéma PostGIS (Flyway) + entités JPA + repositories

Deliverable : schéma créé et **validé** par Hibernate contre les entités.

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__network_schema.sql`
- Create: `backend/src/main/java/com/mapidf/data/entity/{Route,Stop,Trip,StopTime}.java`
- Create: `backend/src/main/java/com/mapidf/data/repositories/{RouteRepository,StopRepository,TripRepository,StopTimeRepository}.java`
- Create: `backend/src/test/java/com/mapidf/data/SchemaIT.java`

**Interfaces:**
- Produces: entités `Route(id, gtfsId, shortName, color, geom:LineString)`, `Stop(id, gtfsId, name, geom:Point)`, `Trip(id, gtfsId, route, headsign, direction)`, `StopTime(id, trip, stop, stopSequence, arrivalSec, departureSec)`. Repositories `JpaRepository`. `RouteRepository.findByGtfsId(String)`, `StopTimeRepository.findScheduleByRouteGtfsId(String)`.

- [ ] **Step 1: migration `V1__network_schema.sql`**

```sql
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE route (
    id          UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    gtfs_id     TEXT NOT NULL UNIQUE,
    short_name  TEXT NOT NULL,
    color       TEXT,
    geom        geometry(LineString, 4326) NOT NULL
);

CREATE TABLE stop (
    id       UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    gtfs_id  TEXT NOT NULL UNIQUE,
    name     TEXT NOT NULL,
    geom     geometry(Point, 4326) NOT NULL
);

CREATE TABLE trip (
    id        UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    gtfs_id   TEXT NOT NULL UNIQUE,
    route_id  UUID NOT NULL REFERENCES route(id),
    headsign  TEXT,
    direction SMALLINT
);

CREATE TABLE stop_time (
    id             UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    trip_id        UUID NOT NULL REFERENCES trip(id),
    stop_id        UUID NOT NULL REFERENCES stop(id),
    stop_sequence  INT  NOT NULL,
    arrival_sec    INT  NOT NULL,
    departure_sec  INT  NOT NULL,
    UNIQUE (trip_id, stop_sequence)
);

CREATE INDEX idx_trip_route ON trip(route_id);
CREATE INDEX idx_stop_time_trip ON stop_time(trip_id);
```

- [ ] **Step 2: entités JPA + Lombok**

```java
package com.mapidf.data.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.locationtech.jts.geom.LineString;

@Getter
@ToString
@Entity
@Table(name = "route")
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
public class Route {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "gtfs_id")
    private String gtfsId;

    @Column(name = "short_name")
    private String shortName;

    @Column(name = "color")
    private String color;

    @Column(name = "geom", columnDefinition = "geometry(LineString,4326)")
    private LineString geom;
}
```

```java
package com.mapidf.data.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.locationtech.jts.geom.Point;

@Getter
@ToString
@Entity
@Table(name = "stop")
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
public class Stop {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "gtfs_id")
    private String gtfsId;

    @Column(name = "name")
    private String name;

    @Column(name = "geom", columnDefinition = "geometry(Point,4326)")
    private Point geom;
}
```

```java
package com.mapidf.data.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Getter
@ToString
@Entity
@Table(name = "trip")
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "gtfs_id")
    private String gtfsId;

    @ManyToOne
    @JoinColumn(name = "route_id")
    @ToString.Exclude
    private Route route;

    @Column(name = "headsign")
    private String headsign;

    @Column(name = "direction")
    private Short direction;
}
```

```java
package com.mapidf.data.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Getter
@ToString
@Entity
@Table(name = "stop_time")
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
public class StopTime {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "trip_id")
    @ToString.Exclude
    private Trip trip;

    @ManyToOne
    @JoinColumn(name = "stop_id")
    @ToString.Exclude
    private Stop stop;

    @Column(name = "stop_sequence")
    private int stopSequence;

    @Column(name = "arrival_sec")
    private int arrivalSec;

    @Column(name = "departure_sec")
    private int departureSec;
}
```

- [ ] **Step 3: repositories**

```java
package com.mapidf.data.repositories;

import java.util.Optional;
import java.util.UUID;

import com.mapidf.data.entity.Route;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteRepository extends JpaRepository<Route, UUID> {
    Optional<Route> findByGtfsId(String gtfsId);
}
```

```java
package com.mapidf.data.repositories;

import java.util.UUID;

import com.mapidf.data.entity.Stop;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StopRepository extends JpaRepository<Stop, UUID> {
}
```

```java
package com.mapidf.data.repositories;

import java.util.UUID;

import com.mapidf.data.entity.Trip;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripRepository extends JpaRepository<Trip, UUID> {
}
```

```java
package com.mapidf.data.repositories;

import java.util.List;
import java.util.UUID;

import com.mapidf.data.entity.StopTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StopTimeRepository extends JpaRepository<StopTime, UUID> {

    @Query("""
        SELECT st FROM StopTime st
        JOIN FETCH st.trip t
        JOIN FETCH st.stop s
        WHERE t.route.gtfsId = :routeId
        ORDER BY t.gtfsId, st.stopSequence
        """)
    List<StopTime> findScheduleByRouteGtfsId(@Param("routeId") String routeId);
}
```

- [ ] **Step 4: test qui échoue puis passe — schéma validé + repositories câblés**

```java
package com.mapidf.data;

import com.mapidf.MapIdfTest;
import com.mapidf.data.repositories.RouteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;

@MapIdfTest
class SchemaIT {

    @Autowired
    RouteRepository routeRepository;

    @Test
    void schemaValidatesAndRepositoryWorks() {
        assertThat(routeRepository.findByGtfsId("UNKNOWN")).isEmpty();
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=SchemaIT`
Expected: PASS (si le contexte échoue sur la validation de la géométrie, forcer `columnDefinition` — déjà fait — et vérifier que `hibernate-spatial` est bien au classpath).

- [ ] **Step 5: commit**

```bash
git add backend/src/main/resources/db backend/src/main/java/com/mapidf/data backend/src/test/java/com/mapidf/data
git commit -m "feat(backend): schéma PostGIS + entités JPA (Hibernate Spatial) + repositories"
```

---

### Task 4: Chargement du GTFS statique de la ligne 9

Deliverable : `GtfsStaticLoader.loadFromZip(in, routeId)` peuple les tables ; `GtfsStaticService` cache le `LineString` du tracé.

**Files:**
- Create: `backend/src/main/java/com/mapidf/gtfs/GtfsStaticLoader.java`
- Create: `backend/src/main/java/com/mapidf/gtfs/GtfsStaticService.java`
- Create: `backend/src/test/resources/gtfs-mini.zip`
- Create: `backend/src/test/java/com/mapidf/gtfs/GtfsStaticLoaderIT.java`

**Interfaces:**
- Consumes: repositories (Task 3), `PrimProperties`, `LineProperties`.
- Produces: `GtfsStaticLoader.loadFromZip(InputStream, String routeId)` ; `GtfsStaticService.getRouteGeometry()` → `LineString` (cache mémoire), `GtfsStaticService.cacheGeometry()`.

- [ ] **Step 1: fixture `gtfs-mini.zip`**

Créer le zip avec 5 fichiers (route_id `TEST9`, tracé ouest→est 3 points, 3 arrêts, 1 trip à 08:00) :

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

Commande de création (depuis un dossier temporaire contenant les 5 `.txt`) :
`zip gtfs-mini.zip routes.txt stops.txt shapes.txt trips.txt stop_times.txt` puis copier dans `src/test/resources/`.

- [ ] **Step 2: test qui échoue**

```java
package com.mapidf.gtfs;

import com.mapidf.MapIdfTest;
import com.mapidf.data.entity.Route;
import com.mapidf.data.repositories.RouteRepository;
import com.mapidf.data.repositories.StopRepository;
import com.mapidf.data.repositories.StopTimeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;

@MapIdfTest
class GtfsStaticLoaderIT {

    @Autowired GtfsStaticLoader loader;
    @Autowired RouteRepository routeRepository;
    @Autowired StopRepository stopRepository;
    @Autowired StopTimeRepository stopTimeRepository;

    @Test
    void loadsLineIntoDb() throws Exception {
        try (var in = getClass().getResourceAsStream("/gtfs-mini.zip")) {
            loader.loadFromZip(in, "TEST9");
        }
        Route route = routeRepository.findByGtfsId("TEST9").orElseThrow();
        assertThat(route.getGeom().getNumPoints()).isEqualTo(3);
        assertThat(stopRepository.count()).isEqualTo(3);
        assertThat(stopTimeRepository.findScheduleByRouteGtfsId("TEST9")).hasSize(3);
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=GtfsStaticLoaderIT`
Expected: FAIL (bean absent).

- [ ] **Step 3: implémenter `GtfsStaticLoader`**

```java
package com.mapidf.gtfs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import com.mapidf.data.entity.Route;
import com.mapidf.data.entity.Stop;
import com.mapidf.data.entity.StopTime;
import com.mapidf.data.entity.Trip;
import com.mapidf.data.repositories.RouteRepository;
import com.mapidf.data.repositories.StopRepository;
import com.mapidf.data.repositories.StopTimeRepository;
import com.mapidf.data.repositories.TripRepository;
import lombok.AllArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@AllArgsConstructor
public class GtfsStaticLoader {

    private static final int SRID = 4326;

    private final RouteRepository routeRepository;
    private final StopRepository stopRepository;
    private final TripRepository tripRepository;
    private final StopTimeRepository stopTimeRepository;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), SRID);

    @Transactional
    public void loadFromZip(InputStream zipIn, String routeId) throws IOException {
        Map<String, List<CSVRecord>> files = readZip(zipIn);

        stopTimeRepository.deleteAllInBatch();
        tripRepository.deleteAllInBatch();
        stopRepository.deleteAllInBatch();
        routeRepository.deleteAllInBatch();

        CSVRecord routeRecord = files.get("routes.txt").stream()
            .filter(r -> r.get("route_id").equals(routeId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("route absente: " + routeId));

        List<CSVRecord> tripRecords = files.get("trips.txt").stream()
            .filter(r -> r.get("route_id").equals(routeId))
            .toList();
        String shapeId = tripRecords.getFirst().get("shape_id");

        Route route = routeRepository.save(Route.builder()
            .gtfsId(routeId)
            .shortName(routeRecord.get("route_short_name"))
            .color(safe(routeRecord, "route_color"))
            .geom(buildShape(files.get("shapes.txt"), shapeId))
            .build());

        Map<String, Trip> tripsByGtfsId = new HashMap<>();
        for (CSVRecord t : tripRecords) {
            Trip trip = tripRepository.save(Trip.builder()
                .gtfsId(t.get("trip_id"))
                .route(route)
                .headsign(safe(t, "trip_headsign"))
                .direction(Short.parseShort(safe(t, "direction_id", "0")))
                .build());
            tripsByGtfsId.put(trip.getGtfsId(), trip);
        }

        List<CSVRecord> stopTimeRecords = files.get("stop_times.txt").stream()
            .filter(r -> tripsByGtfsId.containsKey(r.get("trip_id")))
            .toList();

        Map<String, Stop> stopsByGtfsId = new HashMap<>();
        for (CSVRecord s : files.get("stops.txt")) {
            String stopId = s.get("stop_id");
            boolean referenced = stopTimeRecords.stream().anyMatch(r -> r.get("stop_id").equals(stopId));
            if (!referenced) {
                continue;
            }
            Stop stop = stopRepository.save(Stop.builder()
                .gtfsId(stopId)
                .name(s.get("stop_name"))
                .geom(geometryFactory.createPoint(new Coordinate(
                    Double.parseDouble(s.get("stop_lon")), Double.parseDouble(s.get("stop_lat")))))
                .build());
            stopsByGtfsId.put(stopId, stop);
        }

        for (CSVRecord r : stopTimeRecords) {
            stopTimeRepository.save(StopTime.builder()
                .trip(tripsByGtfsId.get(r.get("trip_id")))
                .stop(stopsByGtfsId.get(r.get("stop_id")))
                .stopSequence(Integer.parseInt(r.get("stop_sequence")))
                .arrivalSec(toSeconds(r.get("arrival_time")))
                .departureSec(toSeconds(r.get("departure_time")))
                .build());
        }
    }

    private LineString buildShape(List<CSVRecord> shapes, String shapeId) {
        List<CSVRecord> points = new ArrayList<>(shapes.stream()
            .filter(r -> r.get("shape_id").equals(shapeId))
            .toList());
        points.sort(Comparator.comparingInt(r -> Integer.parseInt(r.get("shape_pt_sequence"))));
        Coordinate[] coordinates = points.stream()
            .map(r -> new Coordinate(
                Double.parseDouble(r.get("shape_pt_lon")), Double.parseDouble(r.get("shape_pt_lat"))))
            .toArray(Coordinate[]::new);
        return geometryFactory.createLineString(coordinates);
    }

    static int toSeconds(String hms) {
        String[] parts = hms.split(":");
        return Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Integer.parseInt(parts[2]);
    }

    private static String safe(CSVRecord record, String column) {
        return safe(record, column, null);
    }

    private static String safe(CSVRecord record, String column, String defaultValue) {
        if (record.isMapped(column) && !record.get(column).isBlank()) {
            return record.get(column);
        }
        return defaultValue;
    }

    private Map<String, List<CSVRecord>> readZip(InputStream zipIn) throws IOException {
        Map<String, List<CSVRecord>> out = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(zipIn)) {
            var entry = zis.getNextEntry();
            while (entry != null) {
                if (entry.getName().endsWith(".txt")) {
                    byte[] bytes = zis.readAllBytes();
                    try (var reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
                        var parser = CSVFormat.DEFAULT.builder()
                            .setHeader().setSkipHeaderRecord(true).setTrim(true).build()
                            .parse(reader);
                        out.put(entry.getName(), parser.getRecords());
                    }
                }
                entry = zis.getNextEntry();
            }
        }
        return out;
    }
}
```

- [ ] **Step 4: run → PASS**

Run: `cd backend && ./mvnw test -Dtest=GtfsStaticLoaderIT`
Expected: PASS.

- [ ] **Step 5: `GtfsStaticService` (download + cache tracé, no-op si URL vide)**

```java
package com.mapidf.gtfs;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.mapidf.configurations.properties.LineProperties;
import com.mapidf.configurations.properties.PrimProperties;
import com.mapidf.data.repositories.RouteRepository;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.LineString;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class GtfsStaticService {

    private final GtfsStaticLoader loader;
    private final RouteRepository routeRepository;
    private final PrimProperties prim;
    private final LineProperties line;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private volatile LineString routeGeometry;

    public GtfsStaticService(GtfsStaticLoader loader, RouteRepository routeRepository,
                             PrimProperties prim, LineProperties line) {
        this.loader = loader;
        this.routeRepository = routeRepository;
        this.prim = prim;
        this.line = line;
    }

    @Scheduled(initialDelay = 0, fixedRateString = "P1D")
    public void refresh() {
        if (prim.gtfsStaticUrl() == null || prim.gtfsStaticUrl().isBlank()) {
            log.info("[GTFS] URL statique non configurée, refresh ignoré");
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(prim.gtfsStaticUrl()))
                .header(prim.authHeader(), prim.apiKey())
                .GET()
                .build();
            HttpResponse<java.io.InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            loader.loadFromZip(response.body(), line.gtfsRouteId());
            cacheGeometry();
            log.info("[GTFS] Réseau ligne {} rechargé", line.gtfsRouteId());
        } catch (Exception e) {
            log.error("[GTFS] Échec du refresh statique", e);
        }
    }

    public void cacheGeometry() {
        this.routeGeometry = routeRepository.findByGtfsId(line.gtfsRouteId())
            .map(r -> r.getGeom())
            .orElse(null);
    }

    public LineString getRouteGeometry() {
        return routeGeometry;
    }
}
```

- [ ] **Step 6: commit**

```bash
git add backend/src/main/java/com/mapidf/gtfs backend/src/test/java/com/mapidf/gtfs backend/src/test/resources/gtfs-mini.zip
git commit -m "feat(backend): chargement GTFS statique ligne 9 + cache tracé JTS"
```

---

### Task 5: Endpoint `GET /api/lines/{id}/shape`

Deliverable : le front récupère tracé + arrêts.

**Files:**
- Create: `backend/src/main/java/com/mapidf/controllers/lines/ShapeResponse.java`
- Create: `backend/src/main/java/com/mapidf/controllers/lines/LineController.java`
- Create: `backend/src/main/java/com/mapidf/services/NetworkQueryService.java`
- Create: `backend/src/test/java/com/mapidf/controllers/lines/LineControllerShapeIT.java`

**Interfaces:**
- Consumes: repositories, `LineProperties`.
- Produces: `NetworkQueryService.getShape(String gtfsRouteId)` → `ShapeResponse`. `ShapeResponse(lineId, color, double[][] shape, List<StopDto> stops)` avec `StopDto(id, name, lat, lng)`.

- [ ] **Step 1: DTO `ShapeResponse` (`@Value @Builder`)**

```java
package com.mapidf.controllers.lines;

import java.util.List;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ShapeResponse {

    String lineId;
    String color;
    double[][] shape;
    List<StopDto> stops;

    @Value
    @Builder
    public static class StopDto {
        String id;
        String name;
        double lat;
        double lng;
    }
}
```

- [ ] **Step 2: test qui échoue** (MockMvc monté à la main — pas de `@AutoConfigureMockMvc`)

```java
package com.mapidf.controllers.lines;

import com.mapidf.MapIdfTest;
import com.mapidf.gtfs.GtfsStaticLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@MapIdfTest
class LineControllerShapeIT {

    @Autowired WebApplicationContext wac;
    @Autowired GtfsStaticLoader loader;
    MockMvc mockMvc;

    @BeforeEach
    void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        try (var in = getClass().getResourceAsStream("/gtfs-mini.zip")) {
            loader.loadFromZip(in, "TEST9");
        }
    }

    @Test
    void returnsShapeAndStops() throws Exception {
        mockMvc.perform(get("/lines/TEST9/shape"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lineId").value("TEST9"))
            .andExpect(jsonPath("$.shape.length()").value(3))
            .andExpect(jsonPath("$.stops.length()").value(3));
    }
}
```

> Note : `context-path=/api` n'est **pas** appliqué par MockMvc `webAppContextSetup` → on cible `/lines/...` (sans `/api`).

Run: `cd backend && ./mvnw test -Dtest=LineControllerShapeIT`
Expected: FAIL.

- [ ] **Step 3: `NetworkQueryService`**

```java
package com.mapidf.services;

import java.util.List;

import com.mapidf.controllers.lines.ShapeResponse;
import com.mapidf.controllers.lines.ShapeResponse.StopDto;
import com.mapidf.data.entity.Route;
import com.mapidf.data.entity.StopTime;
import com.mapidf.data.enums.ErrorCode;
import com.mapidf.data.repositories.RouteRepository;
import com.mapidf.data.repositories.StopTimeRepository;
import com.mapidf.exceptions.ApiException;
import lombok.AllArgsConstructor;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@AllArgsConstructor
public class NetworkQueryService {

    private final RouteRepository routeRepository;
    private final StopTimeRepository stopTimeRepository;

    @Transactional(readOnly = true)
    public ShapeResponse getShape(String gtfsRouteId) {
        Route route = routeRepository.findByGtfsId(gtfsRouteId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.LINE_NOT_FOUND));

        double[][] shape = new double[route.getGeom().getNumPoints()][];
        Coordinate[] coordinates = route.getGeom().getCoordinates();
        for (int i = 0; i < coordinates.length; i++) {
            shape[i] = new double[]{coordinates[i].x, coordinates[i].y};
        }

        List<StopDto> stops = stopTimeRepository.findScheduleByRouteGtfsId(gtfsRouteId).stream()
            .map(StopTime::getStop)
            .distinct()
            .map(s -> StopDto.builder()
                .id(s.getGtfsId())
                .name(s.getName())
                .lat(s.getGeom().getY())
                .lng(s.getGeom().getX())
                .build())
            .toList();

        return ShapeResponse.builder()
            .lineId(gtfsRouteId)
            .color(route.getColor())
            .shape(shape)
            .stops(stops)
            .build();
    }
}
```

- [ ] **Step 4: `LineController`**

```java
package com.mapidf.controllers.lines;

import com.mapidf.services.NetworkQueryService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/lines")
@AllArgsConstructor
public class LineController {

    private final NetworkQueryService networkQueryService;

    @GetMapping("/{id}/shape")
    public ShapeResponse shape(@PathVariable String id) {
        return networkQueryService.getShape(id);
    }
}
```

- [ ] **Step 5: run → PASS ; commit**

Run: `cd backend && ./mvnw test -Dtest=LineControllerShapeIT` → PASS
```bash
git add backend/src/main/java/com/mapidf/controllers backend/src/main/java/com/mapidf/services backend/src/test/java/com/mapidf/controllers
git commit -m "feat(backend): endpoint GET /api/lines/{id}/shape"
```

---

### Task 6: Poller SIRI-ET (snapshot temps réel en mémoire)

Deliverable : `RealtimePoller` récupère `requete-ligne` (SIRI-ET JSON), le parse via Jackson, et expose un snapshot immuable thread-safe des courses live.

**Files:**
- Create: `backend/src/main/java/com/mapidf/rt/RtSnapshot.java`
- Create: `backend/src/main/java/com/mapidf/rt/RealtimePoller.java`
- Create: `backend/src/test/java/com/mapidf/rt/RealtimePollerParseTest.java`
- Create: `backend/src/test/java/com/mapidf/rt/RtFixtures.java`

**Interfaces:**
- Consumes: `PrimProperties`, `LineProperties`, `ObjectMapper` (bean Spring).
- Produces: `RtSnapshot(Instant asOf, List<LiveJourney> journeys)`, `RtSnapshot.LiveJourney(String journeyRef, String directionRef, String destination, String nextStopRef, Instant expectedTime)`, `RtSnapshot.empty()`. `RealtimePoller.current()` (jamais null), `RealtimePoller.parse(ObjectMapper mapper, byte[] json, Instant asOf)`.

- [ ] **Step 1: fixture SIRI-ET JSON (test)** — extrait minimal fidèle à la réponse réelle de la ligne 9

```java
package com.mapidf.rt;

import java.nio.charset.StandardCharsets;

final class RtFixtures {

    private RtFixtures() {
    }

    // Une course : prochain arrêt "STIF:StopPoint:Q:2:", ETA 14:05:00Z, destination "Gamma", direction "0"
    static byte[] siriLineNineSample() {
        String json = """
            {"Siri":{"ServiceDelivery":{"ResponseTimestamp":"2026-07-22T14:00:00.000Z",
              "EstimatedTimetableDelivery":[{"EstimatedJourneyVersionFrame":[{
                "EstimatedVehicleJourney":[{
                  "LineRef":{"value":"STIF:Line::C01379:"},
                  "DirectionRef":{"value":"0"},
                  "DatedVehicleJourneyRef":{"value":"J1"},
                  "DestinationName":[{"value":"Gamma"}],
                  "EstimatedCalls":{"EstimatedCall":[{
                    "StopPointRef":{"value":"STIF:StopPoint:Q:2:"},
                    "ExpectedDepartureTime":"2026-07-22T14:05:00.000Z",
                    "DestinationDisplay":[{"value":"Gamma"}],
                    "DepartureStatus":"ON_TIME"
                  }]}
                }]
              }]}]
            }}}
            """;
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 2: test de parsing qui échoue**

```java
package com.mapidf.rt;

import java.time.Instant;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RealtimePollerParseTest {

    @Test
    void parsesEstimatedTimetable() throws Exception {
        RtSnapshot snapshot = RealtimePoller.parse(
            new ObjectMapper(), RtFixtures.siriLineNineSample(), Instant.parse("2026-07-22T14:00:00Z"));

        assertThat(snapshot.journeys()).hasSize(1);
        RtSnapshot.LiveJourney journey = snapshot.journeys().getFirst();
        assertThat(journey.journeyRef()).isEqualTo("J1");
        assertThat(journey.directionRef()).isEqualTo("0");
        assertThat(journey.destination()).isEqualTo("Gamma");
        assertThat(journey.nextStopRef()).isEqualTo("STIF:StopPoint:Q:2:");
        assertThat(journey.expectedTime()).isEqualTo(Instant.parse("2026-07-22T14:05:00Z"));
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=RealtimePollerParseTest`
Expected: FAIL.

- [ ] **Step 3: `RtSnapshot` + `RealtimePoller`**

```java
package com.mapidf.rt;

import java.time.Instant;
import java.util.List;

public record RtSnapshot(Instant asOf, List<LiveJourney> journeys) {

    public record LiveJourney(String journeyRef, String directionRef, String destination,
                              String nextStopRef, Instant expectedTime) {
    }

    public static RtSnapshot empty() {
        return new RtSnapshot(Instant.EPOCH, List.of());
    }
}
```

```java
package com.mapidf.rt;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.mapidf.configurations.properties.LineProperties;
import com.mapidf.configurations.properties.PrimProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RealtimePoller {

    @FunctionalInterface
    public interface Fetcher {
        byte[] get(String url) throws Exception;
    }

    private final PrimProperties prim;
    private final LineProperties line;
    private final ObjectMapper objectMapper;
    private final AtomicReference<RtSnapshot> snapshot = new AtomicReference<>(RtSnapshot.empty());
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public RealtimePoller(PrimProperties prim, LineProperties line, ObjectMapper objectMapper) {
        this.prim = prim;
        this.line = line;
        this.objectMapper = objectMapper;
    }

    public RtSnapshot current() {
        return snapshot.get();
    }

    @Scheduled(fixedRateString = "${app.prim.poll-interval}")
    public void poll() {
        if (prim.realtimeBaseUrl() == null || prim.realtimeBaseUrl().isBlank()
            || line.siriLineRef() == null || line.siriLineRef().isBlank()) {
            return;
        }
        pollOnce(this::fetch, Instant.now());
    }

    void pollOnce(Fetcher fetcher, Instant asOf) {
        try {
            String url = prim.realtimeBaseUrl()
                + "?LineRef=" + URLEncoder.encode(line.siriLineRef(), StandardCharsets.UTF_8);
            snapshot.set(parse(objectMapper, fetcher.get(url), asOf));
        } catch (Exception e) {
            log.warn("[RT] Échec du poll, snapshot conservé: {}", e.getMessage());
        }
    }

    private byte[] fetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header(prim.authHeader(), prim.apiKey())
            .GET()
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    static RtSnapshot parse(ObjectMapper mapper, byte[] json, Instant asOf) throws Exception {
        if (json == null || json.length == 0) {
            return new RtSnapshot(asOf, List.of());
        }
        JsonNode journeysNode = mapper.readTree(json)
            .path("Siri").path("ServiceDelivery")
            .path("EstimatedTimetableDelivery").path(0)
            .path("EstimatedJourneyVersionFrame").path(0)
            .path("EstimatedVehicleJourney");

        List<RtSnapshot.LiveJourney> journeys = new ArrayList<>();
        for (JsonNode journey : journeysNode) {
            JsonNode calls = journey.path("EstimatedCalls").path("EstimatedCall");
            JsonNode call = calls.isArray() ? calls.path(0) : calls;
            String stopRef = call.path("StopPointRef").path("value").asText(null);
            String eta = call.path("ExpectedDepartureTime")
                .asText(call.path("ExpectedArrivalTime").asText(null));
            if (stopRef == null || eta == null) {
                continue;
            }
            String journeyRef = journey.path("DatedVehicleJourneyRef").path("value").asText(stopRef);
            String directionRef = journey.path("DirectionRef").path("value").asText("");
            String destination = firstValue(journey.path("DestinationName"));
            journeys.add(new RtSnapshot.LiveJourney(
                journeyRef, directionRef, destination, stopRef, Instant.parse(eta)));
        }
        return new RtSnapshot(asOf, journeys);
    }

    private static String firstValue(JsonNode node) {
        if (node.isArray() && !node.isEmpty()) {
            return node.get(0).path("value").asText("");
        }
        return node.path("value").asText("");
    }
}
```

- [ ] **Step 4: run → PASS ; commit**

Run: `cd backend && ./mvnw test -Dtest=RealtimePollerParseTest` → PASS
```bash
git add backend/src/main/java/com/mapidf/rt backend/src/test/java/com/mapidf/rt
git commit -m "feat(backend): poller SIRI-ET JSON + snapshot temps réel thread-safe"
```

---

### Task 7: `PositionEngine` — calcul pur des positions (cœur, sans DB)

Deliverable : fonction déterministe qui, pour chaque course live (prochain arrêt + ETA), calcule une position estimée le long du tracé.

**Files:**
- Create: `backend/src/main/java/com/mapidf/position/StopOnLine.java`
- Create: `backend/src/main/java/com/mapidf/position/DirectionSchedule.java`
- Create: `backend/src/main/java/com/mapidf/position/LineSchedule.java`
- Create: `backend/src/main/java/com/mapidf/position/Vehicle.java`
- Create: `backend/src/main/java/com/mapidf/position/PositionEngine.java`
- Create: `backend/src/test/java/com/mapidf/position/PositionEngineTest.java`

**Interfaces:**
- Consumes: `LineString` (Task 4), `RtSnapshot` (Task 6).
- Produces:
  - `record StopOnLine(String stopKey, String stopName, double distanceAlongLine, int scheduledSec)` — `stopKey` = identifiant normalisé (chiffres uniquement) commun SIRI/GTFS.
  - `record DirectionSchedule(String terminusName, List<StopOnLine> stops)` — arrêts ordonnés dans le sens.
  - `record LineSchedule(List<DirectionSchedule> directions)`.
  - `record Vehicle(String tripId, double lat, double lng, double bearing, int delaySec, String headsign, String nextStop, Source source)` ; `enum Source { REALTIME, INTERPOLATED }`.
  - `PositionEngine.computeAll(LineString line, LineSchedule schedule, RtSnapshot rt, Instant now, int nowSecOfDay)` → `List<Vehicle>`.
  - `PositionEngine.stopKey(String rawRef)` (static) — normalise `STIF:StopPoint:Q:463221:` → `463221`.

- [ ] **Step 1: records**

```java
package com.mapidf.position;

public record StopOnLine(String stopKey, String stopName, double distanceAlongLine, int scheduledSec) {
}
```

```java
package com.mapidf.position;

import java.util.List;

public record DirectionSchedule(String terminusName, List<StopOnLine> stops) {
}
```

```java
package com.mapidf.position;

import java.util.List;

public record LineSchedule(List<DirectionSchedule> directions) {
}
```

```java
package com.mapidf.position;

public record Vehicle(String tripId, double lat, double lng, double bearing,
                      int delaySec, String headsign, String nextStop, Source source) {

    public enum Source {
        REALTIME, INTERPOLATED
    }
}
```

- [ ] **Step 2: tests qui échouent** (interpolation ETA, ETA dépassée, départ terminus, arrêt inconnu, choix du sens)

```java
package com.mapidf.position;

import java.time.Instant;
import java.util.List;

import com.mapidf.rt.RtSnapshot;
import com.mapidf.rt.RtSnapshot.LiveJourney;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PositionEngineTest {

    private static final Instant NOW = Instant.parse("2026-07-22T08:05:00Z");
    private static final int NOW_SOD = 8 * 3600 + 300; // 08:05:00

    private final PositionEngine engine = new PositionEngine();

    private static LineString line() {
        GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 4326);
        return gf.createLineString(new Coordinate[]{
            new Coordinate(2.300, 48.850), new Coordinate(2.310, 48.850), new Coordinate(2.320, 48.850)});
    }

    // sens "Gamma" : Alpha(0) → Beta(0.010) → Gamma(0.020), départs 08:00 / 08:10 / 08:20
    private static LineSchedule towardGamma() {
        return new LineSchedule(List.of(new DirectionSchedule("Gamma", List.of(
            new StopOnLine("1", "Alpha", 0.000, 8 * 3600),
            new StopOnLine("2", "Beta", 0.010, 8 * 3600 + 600),
            new StopOnLine("3", "Gamma", 0.020, 8 * 3600 + 1200)))));
    }

    private static RtSnapshot rtWith(LiveJourney journey) {
        return new RtSnapshot(NOW, List.of(journey));
    }

    @Test
    void interpolatesTowardNextStopUsingEta() {
        // prochain arrêt Beta, ETA dans 300 s, segment Alpha→Beta = 600 s → à mi-chemin (lng 2.305)
        LiveJourney j = new LiveJourney("J1", "0", "Gamma", "STIF:StopPoint:Q:2:", NOW.plusSeconds(300));

        List<Vehicle> vehicles = engine.computeAll(line(), towardGamma(), rtWith(j), NOW, NOW_SOD);

        assertThat(vehicles).hasSize(1);
        Vehicle v = vehicles.getFirst();
        assertThat(v.source()).isEqualTo(Vehicle.Source.INTERPOLATED);
        assertThat(v.tripId()).isEqualTo("J1");
        assertThat(v.lng()).isCloseTo(2.305, within(1e-3));
        assertThat(v.lat()).isCloseTo(48.850, within(1e-4));
        assertThat(v.nextStop()).isEqualTo("Beta");
        assertThat(v.headsign()).isEqualTo("Gamma");
        assertThat(v.bearing()).isCloseTo(90.0, within(5.0));
        assertThat(v.delaySec()).isEqualTo(0);
    }

    @Test
    void clampsToNextStopWhenEtaAlreadyPassed() {
        LiveJourney j = new LiveJourney("J1", "0", "Gamma", "STIF:StopPoint:Q:2:", NOW.minusSeconds(100));

        Vehicle v = engine.computeAll(line(), towardGamma(), rtWith(j), NOW, NOW_SOD).getFirst();

        assertThat(v.lng()).isCloseTo(2.310, within(1e-3)); // arrivé à Beta
    }

    @Test
    void placesAtOriginWhenNextStopIsFirst() {
        LiveJourney j = new LiveJourney("J1", "0", "Gamma", "STIF:StopPoint:Q:1:", NOW.plusSeconds(30));

        Vehicle v = engine.computeAll(line(), towardGamma(), rtWith(j), NOW, NOW_SOD).getFirst();

        assertThat(v.lng()).isCloseTo(2.300, within(1e-3));
        assertThat(v.nextStop()).isEqualTo("Alpha");
    }

    @Test
    void skipsJourneyWhenNextStopUnknown() {
        LiveJourney j = new LiveJourney("J1", "0", "Gamma", "STIF:StopPoint:Q:999:", NOW.plusSeconds(30));

        assertThat(engine.computeAll(line(), towardGamma(), rtWith(j), NOW, NOW_SOD)).isEmpty();
    }

    @Test
    void selectsDirectionByDestinationWhenStopSharedByBothSenses() {
        // deux sens partagent l'arrêt "Beta" ; destination "Alpha" → sens retour (Gamma→Alpha)
        LineSchedule schedule = new LineSchedule(List.of(
            new DirectionSchedule("Gamma", List.of(
                new StopOnLine("1", "Alpha", 0.000, 8 * 3600),
                new StopOnLine("2", "Beta", 0.010, 8 * 3600 + 600),
                new StopOnLine("3", "Gamma", 0.020, 8 * 3600 + 1200))),
            new DirectionSchedule("Alpha", List.of(
                new StopOnLine("3", "Gamma", 0.020, 8 * 3600),
                new StopOnLine("2", "Beta", 0.010, 8 * 3600 + 600),
                new StopOnLine("1", "Alpha", 0.000, 8 * 3600 + 1200)))));
        LiveJourney j = new LiveJourney("J2", "1", "Alpha", "STIF:StopPoint:Q:2:", NOW.plusSeconds(300));

        Vehicle v = engine.computeAll(line(), schedule, rtWith(j), NOW, NOW_SOD).getFirst();

        assertThat(v.lng()).isCloseTo(2.315, within(1e-3)); // entre Gamma(2.320) et Beta(2.310)
        assertThat(v.bearing()).isCloseTo(270.0, within(5.0)); // cap vers l'ouest
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=PositionEngineTest`
Expected: FAIL.

- [ ] **Step 3: `PositionEngine`**

```java
package com.mapidf.position;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.mapidf.rt.RtSnapshot;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.springframework.stereotype.Component;

@Component
public class PositionEngine {

    public List<Vehicle> computeAll(LineString line, LineSchedule schedule,
                                    RtSnapshot rt, Instant now, int nowSecOfDay) {
        LengthIndexedLine indexed = new LengthIndexedLine(line);
        List<Vehicle> out = new ArrayList<>();
        for (RtSnapshot.LiveJourney journey : rt.journeys()) {
            Vehicle vehicle = compute(indexed, schedule, journey, now, nowSecOfDay);
            if (vehicle != null) {
                out.add(vehicle);
            }
        }
        return out;
    }

    private Vehicle compute(LengthIndexedLine indexed, LineSchedule schedule,
                            RtSnapshot.LiveJourney journey, Instant now, int nowSecOfDay) {
        String key = stopKey(journey.nextStopRef());
        DirectionSchedule direction = pickDirection(schedule, journey.destination(), key);
        if (direction == null) {
            return null;
        }
        List<StopOnLine> stops = direction.stops();
        int index = indexOfStop(stops, key);
        if (index < 0) {
            return null;
        }
        StopOnLine next = stops.get(index);
        long etaDeltaSec = Duration.between(now, journey.expectedTime()).getSeconds();
        int delaySec = (int) (nowSecOfDay + etaDeltaSec - next.scheduledSec());

        double distance;
        double bearing;
        if (index == 0) {
            distance = next.distanceAlongLine();
            StopOnLine after = stops.size() > 1 ? stops.get(1) : next;
            bearing = bearing(indexed, next.distanceAlongLine(), after.distanceAlongLine());
        } else {
            StopOnLine previous = stops.get(index - 1);
            int segmentSec = next.scheduledSec() - previous.scheduledSec();
            double fraction = segmentSec > 0 ? clamp(1.0 - (double) etaDeltaSec / segmentSec, 0.0, 1.0) : 1.0;
            distance = previous.distanceAlongLine()
                + fraction * (next.distanceAlongLine() - previous.distanceAlongLine());
            bearing = bearing(indexed, previous.distanceAlongLine(), next.distanceAlongLine());
        }

        Coordinate point = indexed.extractPoint(distance);
        return new Vehicle(journey.journeyRef(), point.y, point.x, bearing, delaySec,
            journey.destination(), next.stopName(), Vehicle.Source.INTERPOLATED);
    }

    private DirectionSchedule pickDirection(LineSchedule schedule, String destination, String key) {
        List<DirectionSchedule> candidates = schedule.directions().stream()
            .filter(d -> indexOfStop(d.stops(), key) >= 0)
            .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        return candidates.stream()
            .filter(d -> terminusMatches(d.terminusName(), destination))
            .findFirst()
            .orElse(candidates.getFirst());
    }

    private static boolean terminusMatches(String terminus, String destination) {
        if (terminus == null || destination == null) {
            return false;
        }
        String t = terminus.toLowerCase();
        String d = destination.toLowerCase();
        return t.equals(d) || t.contains(d) || d.contains(t);
    }

    private static int indexOfStop(List<StopOnLine> stops, String key) {
        for (int i = 0; i < stops.size(); i++) {
            if (stops.get(i).stopKey().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private double bearing(LengthIndexedLine indexed, double fromDistance, double toDistance) {
        Coordinate a = indexed.extractPoint(fromDistance);
        Coordinate b = indexed.extractPoint(toDistance);
        double angle = Math.toDegrees(Math.atan2(b.x - a.x, b.y - a.y));
        return (angle + 360) % 360;
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    public static String stopKey(String rawRef) {
        return rawRef == null ? "" : rawRef.replaceAll("\\D", "");
    }
}
```

- [ ] **Step 4: run → PASS ; commit**

Run: `cd backend && ./mvnw test -Dtest=PositionEngineTest` → PASS (les 5 cas)
```bash
git add backend/src/main/java/com/mapidf/position backend/src/test/java/com/mapidf/position
git commit -m "feat(backend): PositionEngine pur (interpolation par ETA SIRI + choix du sens, JTS)"
```

---

### Task 8: Endpoint `GET /api/lines/{id}/vehicles`

Deliverable : le front reçoit les véhicules calculés à l'instant courant.

**Files:**
- Create: `backend/src/main/java/com/mapidf/position/ScheduleProvider.java`
- Create: `backend/src/main/java/com/mapidf/controllers/lines/VehicleResponse.java`
- Create: `backend/src/main/java/com/mapidf/controllers/lines/VehiclesResponse.java`
- Modify: `backend/src/main/java/com/mapidf/controllers/lines/LineController.java`
- Create: `backend/src/test/java/com/mapidf/controllers/lines/LineControllerVehiclesIT.java`

**Interfaces:**
- Consumes: `StopTimeRepository`, `GtfsStaticService.getRouteGeometry()`, `PositionEngine`, `RealtimePoller.current()`, `LineProperties`.
- Produces: `ScheduleProvider.getLineSchedule(LineString line, String gtfsRouteId)` → `LineSchedule` (une `DirectionSchedule` par sens, arrêts projetés sur le tracé via JTS). `VehicleResponse` (`@Value @Builder`, `from(Vehicle)`), `VehiclesResponse(Instant asOf, List<VehicleResponse> vehicles)`.

- [ ] **Step 1: `ScheduleProvider` (schedule par sens, projection JTS, sans SQL natif)**

Pour chaque sens (GTFS `direction_id`), on choisit une course représentative (celle
qui a le plus d'arrêts) et on projette chaque arrêt sur le tracé. Le terminus du sens
= dernier arrêt de cette course (sert au `PositionEngine` pour rattacher une course
live à son sens via la destination).

```java
package com.mapidf.position;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mapidf.data.entity.StopTime;
import com.mapidf.data.repositories.StopTimeRepository;
import lombok.AllArgsConstructor;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@AllArgsConstructor
public class ScheduleProvider {

    private final StopTimeRepository stopTimeRepository;

    @Transactional(readOnly = true)
    public LineSchedule getLineSchedule(LineString line, String gtfsRouteId) {
        LengthIndexedLine indexed = new LengthIndexedLine(line);

        Map<String, List<StopTime>> stopTimesByTrip = new LinkedHashMap<>();
        Map<String, Short> directionByTrip = new HashMap<>();
        for (StopTime stopTime : stopTimeRepository.findScheduleByRouteGtfsId(gtfsRouteId)) {
            String tripId = stopTime.getTrip().getGtfsId();
            stopTimesByTrip.computeIfAbsent(tripId, key -> new ArrayList<>()).add(stopTime);
            directionByTrip.putIfAbsent(tripId, stopTime.getTrip().getDirection());
        }

        // course représentative par sens = celle qui a le plus d'arrêts
        Map<Short, String> representativeByDirection = new HashMap<>();
        stopTimesByTrip.forEach((tripId, stopTimes) -> {
            Short direction = directionByTrip.get(tripId);
            String current = representativeByDirection.get(direction);
            if (current == null || stopTimes.size() > stopTimesByTrip.get(current).size()) {
                representativeByDirection.put(direction, tripId);
            }
        });

        List<DirectionSchedule> directions = new ArrayList<>();
        for (String tripId : representativeByDirection.values()) {
            List<StopOnLine> stops = stopTimesByTrip.get(tripId).stream()
                .map(st -> new StopOnLine(
                    PositionEngine.stopKey(st.getStop().getGtfsId()),
                    st.getStop().getName(),
                    indexed.project(st.getStop().getGeom().getCoordinate()),
                    st.getDepartureSec()))
                .toList();
            String terminus = stops.isEmpty() ? "" : stops.getLast().stopName();
            directions.add(new DirectionSchedule(terminus, stops));
        }
        return new LineSchedule(directions);
    }
}
```

- [ ] **Step 2: DTOs réponses (`@Value @Builder`)**

```java
package com.mapidf.controllers.lines;

import com.mapidf.position.Vehicle;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class VehicleResponse {

    String tripId;
    double lat;
    double lng;
    double bearing;
    int delaySec;
    String headsign;
    String nextStop;
    String source;

    public static VehicleResponse from(Vehicle vehicle) {
        return VehicleResponse.builder()
            .tripId(vehicle.tripId())
            .lat(vehicle.lat())
            .lng(vehicle.lng())
            .bearing(vehicle.bearing())
            .delaySec(vehicle.delaySec())
            .headsign(vehicle.headsign())
            .nextStop(vehicle.nextStop())
            .source(vehicle.source().name())
            .build();
    }
}
```

```java
package com.mapidf.controllers.lines;

import java.time.Instant;
import java.util.List;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class VehiclesResponse {

    Instant asOf;
    List<VehicleResponse> vehicles;
}
```

- [ ] **Step 3: test qui échoue**

```java
package com.mapidf.controllers.lines;

import com.mapidf.MapIdfTest;
import com.mapidf.gtfs.GtfsStaticLoader;
import com.mapidf.gtfs.GtfsStaticService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@MapIdfTest
class LineControllerVehiclesIT {

    @Autowired WebApplicationContext wac;
    @Autowired GtfsStaticLoader loader;
    @Autowired GtfsStaticService staticService;
    MockMvc mockMvc;

    @BeforeEach
    void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        try (var in = getClass().getResourceAsStream("/gtfs-mini.zip")) {
            loader.loadFromZip(in, "TEST9");
        }
        staticService.cacheGeometry();
    }

    @Test
    void returnsVehiclesEnvelope() throws Exception {
        mockMvc.perform(get("/lines/TEST9/vehicles"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.asOf").exists())
            .andExpect(jsonPath("$.vehicles").isArray());
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=LineControllerVehiclesIT`
Expected: FAIL.

- [ ] **Step 4: étendre `LineController` avec `/vehicles`**

```java
package com.mapidf.controllers.lines;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import com.mapidf.gtfs.GtfsStaticService;
import com.mapidf.position.LineSchedule;
import com.mapidf.position.PositionEngine;
import com.mapidf.position.ScheduleProvider;
import com.mapidf.position.Vehicle;
import com.mapidf.rt.RealtimePoller;
import com.mapidf.services.NetworkQueryService;
import lombok.AllArgsConstructor;
import org.locationtech.jts.geom.LineString;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/lines")
@AllArgsConstructor
public class LineController {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private final NetworkQueryService networkQueryService;
    private final ScheduleProvider scheduleProvider;
    private final PositionEngine positionEngine;
    private final GtfsStaticService staticService;
    private final RealtimePoller poller;

    @GetMapping("/{id}/shape")
    public ShapeResponse shape(@PathVariable String id) {
        return networkQueryService.getShape(id);
    }

    @GetMapping("/{id}/vehicles")
    public VehiclesResponse vehicles(@PathVariable String id) {
        LineString line = staticService.getRouteGeometry();
        List<VehicleResponse> vehicles = List.of();
        if (line != null) {
            Instant now = Instant.now();
            int nowSecOfDay = LocalTime.now(PARIS).toSecondOfDay();
            LineSchedule schedule = scheduleProvider.getLineSchedule(line, id);
            List<Vehicle> computed = positionEngine.computeAll(
                line, schedule, poller.current(), now, nowSecOfDay);
            vehicles = computed.stream().map(VehicleResponse::from).toList();
        }
        return VehiclesResponse.builder()
            .asOf(Instant.now())
            .vehicles(vehicles)
            .build();
    }
}
```

- [ ] **Step 5: run → PASS ; commit**

Run: `cd backend && ./mvnw test -Dtest=LineControllerVehiclesIT` → PASS
```bash
git add backend/src/main/java/com/mapidf backend/src/test/java/com/mapidf/controllers/lines/LineControllerVehiclesIT.java
git commit -m "feat(backend): endpoint GET /api/lines/{id}/vehicles + ScheduleProvider"
```

---

### Task 9: Résilience du poller + métriques Actuator

Deliverable : un échec IDFM conserve le dernier snapshot ; métriques d'âge de snapshot et d'échecs exposées.

**Files:**
- Modify: `backend/src/main/java/com/mapidf/rt/RealtimePoller.java`
- Create: `backend/src/test/java/com/mapidf/rt/RealtimePollerResilienceTest.java`

**Interfaces:**
- Consumes: Micrometer `MeterRegistry`.
- Produces: métriques `mapidf.rt.poll.failures` (counter), `mapidf.rt.snapshot.age.seconds` (gauge). `RealtimePoller.attachMetrics(MeterRegistry)`.

- [ ] **Step 1: test qui échoue — un échec de fetch conserve le snapshot + incrémente le compteur**

```java
package com.mapidf.rt;

import java.time.Duration;
import java.time.Instant;

import tools.jackson.databind.ObjectMapper;
import com.mapidf.configurations.properties.LineProperties;
import com.mapidf.configurations.properties.PrimProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RealtimePollerResilienceTest {

    private static PrimProperties prim() {
        return new PrimProperties("", "apikey", "", "http://realtime", Duration.ofSeconds(10));
    }

    private static LineProperties line() {
        return new LineProperties("TEST9", "STIF:Line::C01379:", "#D5C900");
    }

    @Test
    void keepsLastSnapshotOnFetchFailure() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RealtimePoller poller = new RealtimePoller(prim(), line(), new ObjectMapper());
        poller.attachMetrics(registry);

        byte[] siri = RtFixtures.siriLineNineSample();
        poller.pollOnce(url -> siri, Instant.ofEpochSecond(100));
        assertThat(poller.current().journeys()).extracting(RtSnapshot.LiveJourney::journeyRef).contains("J1");

        poller.pollOnce(url -> {
            throw new RuntimeException("IDFM down");
        }, Instant.ofEpochSecond(200));

        assertThat(poller.current().journeys()).extracting(RtSnapshot.LiveJourney::journeyRef).contains("J1");
        assertThat(registry.counter("mapidf.rt.poll.failures").count()).isEqualTo(1.0);
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=RealtimePollerResilienceTest`
Expected: FAIL (`attachMetrics` absent).

- [ ] **Step 2: ajouter les métriques à `RealtimePoller`**

Ajouter le champ, la méthode `attachMetrics` (appelée par Spring via l'injection du `MeterRegistry`), et incrémenter dans le `catch`. Modifs :

```java
    // nouveaux imports
import java.time.Duration;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
```

```java
    // champ
    private Counter pollFailures;
```

```java
    // méthode publique testable + binding Spring
    @Autowired
    public void attachMetrics(MeterRegistry registry) {
        this.pollFailures = registry.counter("mapidf.rt.poll.failures");
        registry.gauge("mapidf.rt.snapshot.age.seconds", snapshot,
            ref -> Duration.between(ref.get().asOf(), Instant.now()).getSeconds());
    }
```

```java
    // dans le catch de pollOnce, avant le log :
        } catch (Exception e) {
            if (pollFailures != null) {
                pollFailures.increment();
            }
            log.warn("[RT] Échec du poll, snapshot conservé: {}", e.getMessage());
        }
```

- [ ] **Step 3: run → PASS**

Run: `cd backend && ./mvnw test -Dtest=RealtimePollerResilienceTest`
Expected: PASS.

- [ ] **Step 4: suite complète + commit**

Run: `cd backend && ./mvnw test`
Expected: PASS (toutes classes).
```bash
git add backend/src
git commit -m "feat(backend): résilience poller + métriques Actuator"
```

---

### Task 10: Scaffold frontend (Vite + React + TS + MapLibre)

Deliverable : carte MapLibre centrée sur Paris, proxy `/api` → backend `:8000`.

**Files:**
- Create: `frontend/package.json`, `frontend/vite.config.ts`, `frontend/tsconfig.json`, `frontend/index.html`
- Create: `frontend/src/main.tsx`, `frontend/src/App.tsx`, `frontend/src/map/MapView.tsx`, `frontend/src/api/config.ts`

**Interfaces:**
- Produces: `useMap(container)` montant une carte MapLibre ; base API `/api`.

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

- [ ] **Step 2: `vite.config.ts` (proxy /api → 8000)**

```ts
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: { proxy: { "/api": "http://localhost:8000" } },
});
```

- [ ] **Step 3: `index.html`, `tsconfig.json`, `main.tsx`, `config.ts`**

`index.html` :
```html
<!doctype html>
<html lang="fr">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width,initial-scale=1" />
    <title>MapIDF — Ligne 9</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

`tsconfig.json` :
```json
{
  "compilerOptions": {
    "target": "ES2020",
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "moduleResolution": "bundler",
    "jsx": "react-jsx",
    "strict": true,
    "noEmit": true,
    "skipLibCheck": true
  },
  "include": ["src"]
}
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

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
```

- [ ] **Step 4: `MapView.tsx` + `App.tsx`**

`src/map/MapView.tsx` :
```tsx
import { useEffect, useState } from "react";
import maplibregl, { Map as MlMap } from "maplibre-gl";

// Renvoie l'instance via state (posée DANS l'effet) : le re-render qui suit livre
// la vraie carte aux hooks consommateurs. Renvoyer une ref ne re-render pas et
// laisserait les hooks avec `null` en permanence.
export function useMap(container: React.RefObject<HTMLDivElement>): MlMap | null {
  const [map, setMap] = useState<MlMap | null>(null);
  useEffect(() => {
    if (!container.current) {
      return;
    }
    const instance = new maplibregl.Map({
      container: container.current,
      style: "https://demotiles.maplibre.org/style.json",
      center: [2.34, 48.86],
      zoom: 11,
    });
    setMap(instance);
    return () => {
      instance.remove();
      setMap(null);
    };
  }, [container]);
  return map;
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

- [ ] **Step 5: vérifier visuellement**

Run: `cd frontend && npm install && npm run dev`
Expected: `http://localhost:5173` affiche une carte centrée sur Paris.

- [ ] **Step 6: commit**

```bash
git add frontend/
git commit -m "feat(frontend): scaffold Vite+React+TS+MapLibre, carte de base"
```

---

### Task 11: Affichage du tracé et des arrêts (`useLineShape`)

Deliverable : la ligne 9 et ses arrêts sont dessinés.

**Files:**
- Create: `frontend/src/api/types.ts`, `frontend/src/api/lines.ts`, `frontend/src/map/useLineShape.ts`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: `GET /api/lines/{id}/shape`.
- Produces: `fetchShape(lineId)`, `fetchVehicles(lineId)` ; `useLineShape(map, lineId)`.

- [ ] **Step 1: `types.ts`**

```ts
export interface ShapeResponse {
  lineId: string;
  color: string;
  shape: [number, number][];
  stops: { id: string; name: string; lat: number; lng: number }[];
}

export interface VehiclesResponse {
  asOf: string;
  vehicles: {
    tripId: string;
    lat: number;
    lng: number;
    bearing: number;
    delaySec: number;
    headsign: string;
    nextStop: string;
    source: "REALTIME" | "INTERPOLATED";
  }[];
}
```

- [ ] **Step 2: `lines.ts`**

```ts
import { API_BASE } from "./config";
import type { ShapeResponse, VehiclesResponse } from "./types";

export async function fetchShape(lineId: string): Promise<ShapeResponse> {
  const response = await fetch(`${API_BASE}/lines/${lineId}/shape`);
  if (!response.ok) {
    throw new Error(`shape ${response.status}`);
  }
  return response.json();
}

export async function fetchVehicles(lineId: string): Promise<VehiclesResponse> {
  const response = await fetch(`${API_BASE}/lines/${lineId}/vehicles`);
  if (!response.ok) {
    throw new Error(`vehicles ${response.status}`);
  }
  return response.json();
}
```

- [ ] **Step 3: `useLineShape.ts`**

```ts
import { useEffect } from "react";
import type { Map as MlMap } from "maplibre-gl";
import { fetchShape } from "../api/lines";

export function useLineShape(map: MlMap | null, lineId: string) {
  useEffect(() => {
    if (!map) {
      return;
    }
    let cancelled = false;
    let drawHandler: (() => void) | null = null;
    fetchShape(lineId).then((shape) => {
      if (cancelled) {
        return;
      }
      const draw = () => {
        if (map.getSource("line-shape")) {
          return;
        }
        map.addSource("line-shape", {
          type: "geojson",
          data: {
            type: "Feature",
            properties: {},
            geometry: { type: "LineString", coordinates: shape.shape },
          },
        });
        map.addLayer({
          id: "line-shape",
          type: "line",
          source: "line-shape",
          paint: { "line-color": shape.color, "line-width": 4 },
        });
        map.addSource("stops", {
          type: "geojson",
          data: {
            type: "FeatureCollection",
            features: shape.stops.map((s) => ({
              type: "Feature",
              properties: { name: s.name },
              geometry: { type: "Point", coordinates: [s.lng, s.lat] },
            })),
          },
        });
        map.addLayer({
          id: "stops",
          type: "circle",
          source: "stops",
          paint: {
            "circle-radius": 5,
            "circle-color": "#fff",
            "circle-stroke-color": shape.color,
            "circle-stroke-width": 2,
          },
        });
      };
      if (map.isStyleLoaded()) {
        draw();
      } else {
        drawHandler = draw;
        map.once("load", draw);
      }
    });
    return () => {
      cancelled = true;
      if (drawHandler) {
        map.off("load", drawHandler);
      }
    };
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
  useLineShape(map, LINE_ID);
  return <div ref={container} style={{ position: "absolute", inset: 0 }} />;
}
```

- [ ] **Step 5: vérifier visuellement** (backend lancé avec `PRIM_API_KEY`, base chargée)

Run: backend `./mvnw spring-boot:run` puis `cd frontend && npm run dev`.
Expected: tracé + arrêts de la ligne 9 affichés.

- [ ] **Step 6: commit**

```bash
git add frontend/src
git commit -m "feat(frontend): affichage tracé + arrêts (useLineShape)"
```

---

### Task 12: Véhicules animés (`useVehicles` + tween RAF)

Deliverable : marqueurs qui glissent le long du tracé, MAJ ~4 s, orientés selon le cap.

**Files:**
- Create: `frontend/src/map/VehicleLayer.ts`, `frontend/src/map/useVehicles.ts`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: `fetchVehicles`.
- Produces: `useVehicles(map, lineId)`. `VehicleLayer.update(vehicles, now)`, `VehicleLayer.destroy()`.

- [ ] **Step 1: `VehicleLayer.ts`**

```ts
import type { Map as MlMap, GeoJSONSource } from "maplibre-gl";
import type { VehiclesResponse } from "../api/types";

type V = VehiclesResponse["vehicles"][number];

interface Anim {
  from: [number, number];
  to: [number, number];
  bearing: number;
  start: number;
  vehicle: V;
}

export class VehicleLayer {
  private anims = new Map<string, Anim>();
  private raf = 0;
  private loadHandler: (() => void) | null = null;

  constructor(
    private map: MlMap,
    private durationMs: number,
  ) {
    this.ensureLayer();
  }

  private ensureLayer() {
    const add = () => {
      if (this.map.getSource("vehicles")) {
        return;
      }
      this.map.addSource("vehicles", { type: "geojson", data: this.featureCollection([]) });
      this.map.addLayer({
        id: "vehicles",
        type: "circle",
        source: "vehicles",
        paint: {
          "circle-radius": 7,
          "circle-color": ["case", ["==", ["get", "source"], "REALTIME"], "#e30613", "#f7a600"],
          "circle-stroke-color": "#fff",
          "circle-stroke-width": 2,
          "circle-opacity": ["case", ["==", ["get", "source"], "INTERPOLATED"], 0.7, 1.0],
        },
      });
    };
    if (this.map.isStyleLoaded()) {
      add();
    } else {
      this.loadHandler = add;
      this.map.once("load", add);
    }
  }

  private featureCollection(features: GeoJSON.Feature[]): GeoJSON.FeatureCollection {
    return { type: "FeatureCollection", features };
  }

  update(vehicles: V[], now: number) {
    const seen = new Set<string>();
    for (const vehicle of vehicles) {
      seen.add(vehicle.tripId);
      const prev = this.anims.get(vehicle.tripId);
      const current = prev ? this.pointAt(prev, now) : ([vehicle.lng, vehicle.lat] as [number, number]);
      this.anims.set(vehicle.tripId, {
        from: current,
        to: [vehicle.lng, vehicle.lat],
        bearing: vehicle.bearing,
        start: now,
        vehicle,
      });
    }
    for (const id of [...this.anims.keys()]) {
      if (!seen.has(id)) {
        this.anims.delete(id);
      }
    }
    this.startLoop();
  }

  private pointAt(anim: Anim, now: number): [number, number] {
    const t = Math.min(1, (now - anim.start) / this.durationMs);
    return [
      anim.from[0] + (anim.to[0] - anim.from[0]) * t,
      anim.from[1] + (anim.to[1] - anim.from[1]) * t,
    ];
  }

  private startLoop() {
    if (this.raf) {
      return;
    }
    const step = (now: number) => {
      const source = this.map.getSource("vehicles") as GeoJSONSource | undefined;
      if (source) {
        const features = [...this.anims.values()].map((anim) => {
          const [lng, lat] = this.pointAt(anim, now);
          return {
            type: "Feature",
            properties: {
              tripId: anim.vehicle.tripId,
              source: anim.vehicle.source,
              bearing: anim.bearing,
              headsign: anim.vehicle.headsign,
              nextStop: anim.vehicle.nextStop,
              delaySec: anim.vehicle.delaySec,
            },
            geometry: { type: "Point", coordinates: [lng, lat] },
          } as GeoJSON.Feature;
        });
        source.setData(this.featureCollection(features));
      }
      this.raf = requestAnimationFrame(step);
    };
    this.raf = requestAnimationFrame(step);
  }

  destroy() {
    if (this.raf) {
      cancelAnimationFrame(this.raf);
    }
    this.raf = 0;
    if (this.loadHandler) {
      this.map.off("load", this.loadHandler);
      this.loadHandler = null;
    }
    this.anims.clear();
  }
}
```

- [ ] **Step 2: `useVehicles.ts`**

```ts
import { useEffect } from "react";
import type { Map as MlMap } from "maplibre-gl";
import { fetchVehicles } from "../api/lines";
import { VEHICLE_POLL_MS } from "../api/config";
import { VehicleLayer } from "./VehicleLayer";

export function useVehicles(map: MlMap | null, lineId: string) {
  useEffect(() => {
    if (!map) {
      return;
    }
    const layer = new VehicleLayer(map, VEHICLE_POLL_MS);
    let cancelled = false;
    let timer: number;
    const tick = async () => {
      try {
        const response = await fetchVehicles(lineId);
        if (cancelled) {
          return;
        }
        layer.update(response.vehicles, performance.now());
      } catch {
        // on conserve l'affichage courant
      }
      if (cancelled) {
        return;
      }
      timer = window.setTimeout(tick, VEHICLE_POLL_MS);
    };
    tick();
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
      layer.destroy();
    };
  }, [map, lineId]);
}
```

- [ ] **Step 3: brancher dans `App.tsx`** — ajouter `useVehicles(map, LINE_ID);` après `useLineShape(...)` (`map` est la valeur renvoyée par `useMap`, pas une ref).

- [ ] **Step 4: vérifier visuellement**

Run: backend + `npm run dev`.
Expected: des points glissent le long de la ligne 9 ; rouge = GPS réel, orange semi-transparent = interpolé.

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
- Produces: `<VehiclePanel vehicle={...} onClose={...} />`.

- [ ] **Step 1: `VehiclePanel.tsx`**

```tsx
interface Props {
  vehicle: { headsign: string; nextStop: string; delaySec: number; source: string } | null;
  onClose: () => void;
}

export function VehiclePanel({ vehicle, onClose }: Props) {
  if (!vehicle) {
    return null;
  }
  const delayMin = Math.round(vehicle.delaySec / 60);
  return (
    <div
      style={{
        position: "absolute",
        top: 12,
        right: 12,
        width: 260,
        padding: 16,
        background: "#fff",
        borderRadius: 8,
        boxShadow: "0 2px 12px rgba(0,0,0,.2)",
        font: "14px sans-serif",
      }}
    >
      <button onClick={onClose} style={{ float: "right", border: "none", background: "none", cursor: "pointer" }}>
        ✕
      </button>
      <h3 style={{ margin: "0 0 8px" }}>→ {vehicle.headsign}</h3>
      <p style={{ margin: "4px 0" }}>
        Prochain arrêt : <b>{vehicle.nextStop}</b>
      </p>
      <p style={{ margin: "4px 0" }}>Retard : {delayMin > 0 ? `+${delayMin} min` : "à l'heure"}</p>
      <p style={{ margin: "4px 0", color: "#666" }}>
        Position : {vehicle.source === "REALTIME" ? "GPS temps réel" : "estimée (horaire)"}
      </p>
    </div>
  );
}
```

- [ ] **Step 2: gérer le clic dans `App.tsx`**

```tsx
import { useEffect, useRef, useState } from "react";
import { useMap } from "./map/MapView";
import { useLineShape } from "./map/useLineShape";
import { useVehicles } from "./map/useVehicles";
import { VehiclePanel } from "./ui/VehiclePanel";
import { LINE_ID } from "./api/config";

type Selected = { headsign: string; nextStop: string; delaySec: number; source: string } | null;

export default function App() {
  const container = useRef<HTMLDivElement>(null);
  const map = useMap(container);
  const [selected, setSelected] = useState<Selected>(null);
  useLineShape(map, LINE_ID);
  useVehicles(map, LINE_ID);

  useEffect(() => {
    if (!map) {
      return;
    }
    const onClick = (e: maplibregl.MapLayerMouseEvent) => {
      const props = e.features?.[0]?.properties;
      setSelected(props ? (props as Selected) : null);
    };
    map.on("click", "vehicles", onClick);
    return () => {
      map.off("click", "vehicles", onClick);
    };
  }, [map]);

  return (
    <>
      <div ref={container} style={{ position: "absolute", inset: 0 }} />
      <VehiclePanel vehicle={selected} onClose={() => setSelected(null)} />
    </>
  );
}
```

> `maplibregl` doit être importé pour le type de l'événement : ajouter `import maplibregl from "maplibre-gl";` en tête de `App.tsx`.

- [ ] **Step 3: vérifier visuellement**

Run: backend + `npm run dev`.
Expected: clic sur un véhicule → panneau destination/prochain arrêt/retard/source.

- [ ] **Step 4: commit**

```bash
git add frontend/src
git commit -m "feat(frontend): panneau de détails véhicule au clic"
```

---

### Task 14: Packaging & déploiement (Docker full-stack, monitoring)

Deliverable : `docker compose up` lance PostGIS + backend + front ; healthcheck OK.

**Files:**
- Create: `backend/Dockerfile`, `frontend/Dockerfile`, `frontend/nginx.conf`
- Create: `docker-compose.yml` (racine), `README.md` (racine)

- [ ] **Step 1: `backend/Dockerfile`**

```dockerfile
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY . .
RUN ./mvnw -q -DskipTests package
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8000 9000
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: `frontend/Dockerfile` + `frontend/nginx.conf`**

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

```nginx
server {
  listen 80;
  location /api/ {
    proxy_pass http://backend:8000;
  }
  location / {
    root /usr/share/nginx/html;
    try_files $uri /index.html;
  }
}
```

> `context-path=/api` côté backend + `location /api/` côté nginx : les requêtes `/api/lines/...` arrivent bien sur le backend qui les sert sous `/api`.

- [ ] **Step 3: `docker-compose.yml` racine**

```yaml
services:
  db:
    image: postgis/postgis:18-3.6
    environment: { POSTGRES_DB: mapidf, POSTGRES_USER: mapidf, POSTGRES_PASSWORD: mapidf }
    volumes: ["dbdata:/var/lib/postgresql"]
  backend:
    build: ./backend
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/mapidf
      SPRING_DATASOURCE_USERNAME: mapidf
      SPRING_DATASOURCE_PASSWORD: mapidf
      PRIM_API_KEY: ${PRIM_API_KEY}
    depends_on: [db]
    ports: ["8000:8000", "9000:9000"]
  frontend:
    build: ./frontend
    depends_on: [backend]
    ports: ["8080:80"]
volumes:
  dbdata:
```

- [ ] **Step 4: `README.md` racine**

```markdown
# MapIDF — suivi temps réel de la ligne 9

## Démarrage
1. `export PRIM_API_KEY=<votre clé PRIM>`
2. `docker compose up --build`
3. Front : http://localhost:8080 — API : http://localhost:8000/api/lines/9/vehicles
4. Santé backend : http://localhost:9000/actuator/health

## Développement
- Backend : `cd backend && ./mvnw spring-boot:run` (API :8000, Actuator :9000)
- Front : `cd frontend && npm run dev` (proxy /api → :8000)
- Tests : `cd backend && ./mvnw test`
```

- [ ] **Step 5: vérifier le stack complet**

Run: `export PRIM_API_KEY=... && docker compose up --build`
Expected: `curl localhost:9000/actuator/health` → `{"status":"UP"}` ; front sur :8080 avec carte animée.

- [ ] **Step 6: commit**

```bash
git add backend/Dockerfile frontend/Dockerfile frontend/nginx.conf docker-compose.yml README.md
git commit -m "chore: packaging Docker full-stack + README"
```

---

## Notes de fin

- **Fond de carte** : `demotiles.maplibre.org` est un fond de démonstration. En prod, prévoir un fond vectoriel dédié (clé MapTiler ou tuiles auto-hébergées) — ne change que le champ `style` de `MapView.tsx`.
- **Fréquences de poll** : front 4 s (`VEHICLE_POLL_MS`), backend `app.prim.poll-interval` (10 s) — à ajuster selon les quotas PRIM relevés en Task 2.
- **`@Scheduled` vs multi-instance** : le poller `@Scheduled` tourne sur chaque instance. Pour un déploiement multi-instances, passer le poll sous **Quartz clusterisé** (déjà dans la boîte à outils Steamulo, cf. starter) pour qu'un seul nœud interroge IDFM. Hors périmètre MVP.
- **Validation géométrie / `ddl-auto: validate`** : si Hibernate rejette la colonne `geometry(...)` au démarrage, vérifier que `hibernate-spatial` est au classpath (il l'est via le pom) ; le `columnDefinition` explicite sur les entités aligne la validation.
- **Évolutions post-MVP** (spec §10) : multi-lignes, bascule SSE, historisation. L'archi PostGIS et le `PositionEngine` pur y sont prêts.
```
