# syntax=docker/dockerfile:1
FROM gradle:8-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
COPY gradlew ./
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src/ src/
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre-jammy AS layers
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=layers /app/dependencies/ ./
COPY --from=layers /app/spring-boot-loader/ ./
COPY --from=layers /app/snapshot-dependencies/ ./
COPY --from=layers /app/application/ ./
USER 1000
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
