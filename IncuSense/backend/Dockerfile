# ── Stage 1: Build ────────────────────────────────────────────
# Usa l'immagine ufficiale Maven che include già Java 21.
# Non usa --mount=type=cache (richiede BuildKit, problematico su Windows).
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app

# Copia prima solo il pom.xml per sfruttare la cache layer di Docker:
# se il pom non cambia, le dipendenze non vengono ri-scaricate.
COPY pom.xml .
RUN mvn dependency:go-offline -B --no-transfer-progress

# Copia i sorgenti e builda il JAR
COPY src ./src
RUN mvn package -DskipTests -B --no-transfer-progress

# ── Stage 2: Runtime ───────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Utente non-root per sicurezza
RUN addgroup -S incusense && adduser -S incusense -G incusense
USER incusense

# Copia solo il JAR dal builder stage
COPY --from=builder /app/target/incusense-backend-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
