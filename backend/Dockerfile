FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
# Dépendances résolues AVANT le code : une modification de classe ne refait plus le
# téléchargement complet. `go-offline` ne ramène pas tout (certains plugins se résolvent à
# l'exécution) — la couche n'est pas hermétique, elle est utile.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
# uid fixe et hors de la plage système de la distribution : les droits d'un volume monté un jour
# restent prévisibles.
RUN groupadd --system --gid 10001 mapidf \
 && useradd --system --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin mapidf
COPY --from=build --chown=10001:10001 /app/target/*.jar app.jar
USER 10001
EXPOSE 8100 9100
# L'image n'a ni curl ni wget, mais elle a bash : /dev/tcp interroge le vrai endpoint de santé
# sans ajouter un paquet dans l'image finale. On lit la ligne de statut HTTP, pas le corps : un
# sous-composant "UP" isolé y suffirait même si l'agrégat est DOWN. `start-period` généreux — au
# premier démarrage, le backend charge le GTFS complet (~125 Mo) avant d'être prêt.
HEALTHCHECK --interval=10s --timeout=3s --start-period=90s --retries=3 CMD \
  bash -c 'exec 3<>/dev/tcp/127.0.0.1/9100; printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3; head -1 <&3 | grep -qE "^HTTP/1\.[01] 200"'
ENTRYPOINT ["java", "-jar", "app.jar"]
