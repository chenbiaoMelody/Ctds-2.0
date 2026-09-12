# C-TDS backend container (WBS 2.5.1, spec: ADR-013).
# Packaging recipe only: the fat jar is built OUTSIDE the image (local
# `mvn -B -ntp package -DskipTests` or nightly N3, WBS 2.2.6) and must exist
# in target/ before `docker build`.
FROM eclipse-temurin:17-jre

WORKDIR /app

# uid 1001: uid 1000 is already taken by the base image's default user
# (useradd exit 4 on eclipse-temurin:17-jre, measured 2026-09-12).
RUN useradd --system --uid 1001 appuser \
    && mkdir -p /app/logs \
    && chown -R appuser:appuser /app

COPY target/example-service*.jar /app/app.jar

USER appuser

EXPOSE 8080

# MaxRAMPercentage: JVM heap follows the container memory limit (K8s K1, 1Gi cap)
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
