# syntax=docker/dockerfile:1
# --- Derleme aşaması ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
# Maven deposu BuildKit önbelleğinde tutulur; yeniden derlemelerde bağımlılıklar tekrar indirilmez.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q -DskipTests package

# --- Çalışma aşaması ---
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /src/target/oyunport-backend.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
