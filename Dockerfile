# Build stage
#
# The Alpine variants of these images are published for amd64 only, so the previous version
# could not be built on an Apple Silicon Mac at all. The Ubuntu-based tags are multi-arch.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src src
RUN mvn package -DskipTests

# Run stage
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Run as an unprivileged user rather than root.
RUN groupadd --system app && useradd --system --gid app --home /app app
COPY --from=build --chown=app:app /app/target/*.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
