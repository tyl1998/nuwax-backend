# syntax=docker/dockerfile:1.7
FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 cp app-platform-bootstrap/app-platform-web-bootstrap/src/main/resources/application-prod.sample.yml app-platform-bootstrap/app-platform-web-bootstrap/src/main/resources/application-prod.yml && mvn -pl app-platform-bootstrap/app-platform-web-bootstrap -am package -DskipTests -Pprod

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends bash curl netcat-openbsd ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=builder /workspace/app-platform-bootstrap/app-platform-web-bootstrap/target/app-platform-web-bootstrap-*.jar /app/app.jar
COPY docker/entrypoint.sh /app/entrypoint.sh
COPY docker/application-external.yml /app/config/application-external.yml
COPY sql /app/sql
RUN chmod +x /app/entrypoint.sh && mkdir -p /app/upload /app/logs
EXPOSE 8081 6443
ENTRYPOINT ["/app/entrypoint.sh"]
