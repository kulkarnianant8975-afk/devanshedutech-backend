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

# The media directory is created here, owned by app, and that ownership matters more than it
# looks. Docker seeds a new named volume from whatever is at the mount point in the image — so
# without this the volume arrives owned by root, the unprivileged process cannot write to it, and
# every upload fails with a permission error that reads like a corrupted file.
RUN mkdir -p /var/lib/devansh/media && chown -R app:app /var/lib/devansh
COPY --from=build --chown=app:app /app/target/*.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
