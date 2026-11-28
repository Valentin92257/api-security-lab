# syntax=docker/dockerfile:1

# --- БД: init-скрипты внутри образа ---
FROM postgres:17-alpine AS lab-db
COPY db/control/role.sql        /docker-entrypoint-initdb.d/10-control.sql
COPY db/detector/bootstrap.sql  /docker-entrypoint-initdb.d/15-detector.sql
COPY db/init/bootstrap.sql      /docker-entrypoint-initdb.d/20-databases.sql
COPY db/shop/fixture-roles.sql  /docker-entrypoint-initdb.d/25-shop-fixture-roles.sql

# --- Сборка всех модулей один раз ---
FROM --platform=$BUILDPLATFORM maven:3.10.2-eclipse-temurin-25 AS build
WORKDIR /workspace
ARG SKIP_TESTS=false
COPY pom.xml ./
COPY contracts/ contracts/
COPY db/ db/
COPY libs/ libs/
COPY apps/ apps/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipUTs=${SKIP_TESTS} package \
 && for app in shop-api lab-gateway lab-detector lab-control; do \
      java -Djarmode=tools -jar "apps/$app/target/app.jar" extract --layers --destination "/extracted/$app"; \
      mkdir -p "/extracted/$app/dependencies/lib" "/extracted/$app/snapshot-dependencies"; \
    done \
 && mkdir -p /extracted/common/lib /extracted/logs-dir/logs \
 && for jar in /extracted/shop-api/dependencies/lib/*.jar; do \
      [ -f "$jar" ] || continue; \
      name=$(basename "$jar"); \
      if cmp -s "$jar" "/extracted/lab-gateway/dependencies/lib/$name" \
      && cmp -s "$jar" "/extracted/lab-detector/dependencies/lib/$name" \
      && cmp -s "$jar" "/extracted/lab-control/dependencies/lib/$name"; then \
        mv "$jar" "/extracted/common/lib/$name"; \
        rm "/extracted/lab-gateway/dependencies/lib/$name" \
           "/extracted/lab-detector/dependencies/lib/$name" \
           "/extracted/lab-control/dependencies/lib/$name"; \
      fi; \
    done \
 && find /extracted -exec touch -d '2026-01-01T00:00:00Z' {} +

# --- Приложение: общая JRE + общие JAR ---
FROM eclipse-temurin:25-jre-alpine AS java-app
WORKDIR /app
COPY --from=build --chown=10001:10001 /extracted/logs-dir/ ./
COPY --from=build /extracted/common/ ./

ARG APP
COPY --from=build /extracted/${APP}/dependencies/          ./
COPY --from=build /extracted/${APP}/snapshot-dependencies/ ./
COPY --from=build /extracted/${APP}/application/           ./

USER 10001:10001
EXPOSE 8080
ENV SERVER_PORT=8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=5 \
  CMD wget -q -O /dev/null -T 2 "http://127.0.0.1:${SERVER_PORT}/health" \
   && { [ -z "${GATEWAY_ADDITIONAL_PORT:-}" ] || wget -q -O /dev/null -T 2 "http://127.0.0.1:${GATEWAY_ADDITIONAL_PORT}/health"; }
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:+UseCompactObjectHeaders", "-Xmx512m", "-jar", "app.jar"]
