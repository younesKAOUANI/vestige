# Multi-stage build. The `build` stage needs Maven Central reachable - unlike the sandbox this
# project was originally developed in (see README "A note on how this was built"), a normal
# `docker build` has outbound network access and this stage is untested here for exactly that
# reason. The runtime stage ships no build tooling, no source, and runs as a non-root user.

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S vestige && adduser -S vestige -G vestige
WORKDIR /app
COPY --from=build /workspace/target/vestige-*.jar app.jar
USER vestige
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
