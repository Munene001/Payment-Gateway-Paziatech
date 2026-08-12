# Stage 1: Build using latest Gradle with JDK 21
FROM gradle:jdk21-alpine AS builder
WORKDIR /app

# Copy all project files into the container
COPY --chown=gradle:gradle . .

# Build the Spring Boot JAR directly using pre-installed Gradle
RUN gradle bootJar --no-daemon -x test

# Stage 2: Minimal Java 21 Runtime image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy the built JAR from the builder stage
COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar", "--spring.config.additional-location=optional:file:/app/config/application-prod.properties"]