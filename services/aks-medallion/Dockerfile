FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN apt-get update \
    && apt-get install -y --no-install-recommends maven \
    && mvn -B -DskipTests=false clean package \
    && rm -rf /var/lib/apt/lists/*

FROM eclipse-temurin:21-jre
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=build /build/target/aks-medallion-*.jar app.jar
USER 10001:10001
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
