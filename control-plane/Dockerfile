# --- Build stage --------------------------------------------------------------------------
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build

# Cache dependencies separately from source so `docker build` only re-downloads them when the
# POM actually changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true

COPY src src
RUN mvn -B -q -DskipTests clean package

# --- Runtime stage -------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy

# curl for the HEALTHCHECK below; not present in the base JRE image.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Run as a non-root user - never as root in the container.
RUN groupadd --system agentplane && useradd --system --gid agentplane --home /app agentplane
WORKDIR /app
COPY --from=build /build/target/agentplane-control-plane.jar app.jar
RUN chown -R agentplane:agentplane /app
USER agentplane

# JAVA_TOOL_OPTIONS notes (see also the "kept getting OOM-killed by Kubernetes" postmortem this
# is fixed from):
#   -XX:MaxRAMPercentage=75   The JVM only auto-detects the CONTAINER's memory limit (cgroup)
#                             since JDK 10+/UseContainerSupport (on by default) - the bug this
#                             fixes was heap sizing based on the *host's* memory when the process
#                             couldn't see its own cgroup limit correctly, so the heap grew far
#                             past the container's actual limit and Kubernetes OOM-killed the pod
#                             long before the JVM itself thought it was under memory pressure.
#                             Capping the heap at a percentage of the (correctly detected)
#                             container limit - leaving headroom for thread stacks, metaspace,
#                             direct buffers and native memory - keeps the process inside
#                             whatever `resources.limits.memory` the pod is actually given.
#   -XX:+UseG1GC              G1 is the right default for a request-serving Spring Boot service:
#                             low, predictable pause times at this heap size, without the manual
#                             region tuning ZGC/Shenandoah would need to pay off here.
#   -XX:+ExitOnOutOfMemoryError  Fail fast and let Kubernetes restart a genuinely broken pod,
#                             rather than limping on in a half-alive state after an OOM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -sf http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
