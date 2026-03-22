FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app

# Copy maven executable and configuration
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Build dependencies separately to improve cache
RUN chmod +x mvnw
RUN ./mvnw dependency:go-offline -B

# Copy source code and package application
COPY src src
RUN ./mvnw package -DskipTests

# Run stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
