# Multi-stage: сборка Maven'ом, рантайм — только JRE (образ в разы меньше).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Сначала только pom — слой с зависимостями кэшируется, пока pom не менялся
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
