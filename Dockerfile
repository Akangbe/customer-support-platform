# ---- Build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Dependencies first so they're cached across builds when only src/ changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

COPY src/ src/
RUN ./mvnw -B clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /build/target/*.jar app.jar
RUN chown app:app app.jar
USER app

# Render (and most PaaS hosts) inject PORT at runtime; application.yml
# already binds to ${PORT:8080}, so no extra wiring is needed here.
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
