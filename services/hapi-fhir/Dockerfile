FROM --platform=$BUILDPLATFORM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace/services/hapi-fhir
COPY services/hapi-fhir/pom.xml ./
COPY services/hapi-fhir/src ./src
COPY samples/epna /workspace/samples/epna
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode --no-transfer-progress verify

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/services/hapi-fhir/target/gold-fhir.jar /app/gold-fhir.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/gold-fhir.jar"]
