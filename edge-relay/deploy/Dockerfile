FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY . .
RUN mvn -q -B -pl edge-relay -am package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/edge-relay/target/edge-relay-1.0.0-SNAPSHOT.jar app.jar
ENV SERVER_PORT=8091
EXPOSE 8091
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "app.jar"]