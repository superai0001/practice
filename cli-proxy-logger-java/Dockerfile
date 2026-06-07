# cli-proxy-logger (Java / Spring Boot) — multi-stage: build fat jar, then run on a JRE.
FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /src
# Cache dependencies first (re-resolved only when pom.xml changes).
COPY pom.xml .
RUN mvn -q -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:8-jre
WORKDIR /app
COPY --from=build /src/target/cli-proxy-logger-*.jar app.jar

# Spring Boot binds all interfaces by default; proxy + UI share one port (8788).
ENV LOG_DIR=/app/logs
EXPOSE 8788

ENTRYPOINT ["java", "-jar", "app.jar"]
