# syntax=docker/dockerfile:1
# Build from this folder: docker build -t blink-backend .
FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends git python3 python3-yaml ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/target/blink-backend-0.1.0.jar app.jar
ENV BLINK_AUTOMATION_SDLC_PATH=/app/automation_sdlc
EXPOSE 8090
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS:-} -Dserver.port=${PORT:-8090} -jar /app/app.jar"]
