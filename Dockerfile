# syntax=docker/dockerfile:1
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src/main src/main
# Cache ~/.m2 giữa các lần build và thử tải lại khi mạng chập, để không phải tải lại toàn bộ dependency.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests -Daether.connector.http.retryHandler.count=5 package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /workspace/target/course-registration-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
