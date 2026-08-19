# syntax=docker/dockerfile:1.7
#
# hrafnagud runtime image.
#
# Build context is the REPOSITORY ROOT, not this directory — the JAR path
# below has to resolve. Use deploy/docker/build-image.sh, which runs Maven
# first (unless SKIP_MAVEN=1) and then calls `docker build` with the right
# context.
#
# The JAR is pre-built on the host rather than inside a builder stage on
# purpose: hrafnagud-translate and hrafnagud-centauri depend on
# de.mhus.vance.ode:* artifacts that are installed into the local Maven
# repository from a sibling checkout, not resolvable from Maven Central. An
# in-image `mvn package` would need that repository mounted in, which buys
# nothing here — the host build is the one that already works.

# ── Stage 1: split the uber JAR into layers ─────────────────────────────
#
# The JAR is ~145 MB, and ~138 MB of that is dependencies (Lingua's
# language models dominate). Copying it as one blob would put those 138 MB
# into the same image layer as the 400 KB of application classes, so every
# code change would re-push the whole thing. Extracting Spring Boot's
# layers instead means a routine rebuild pushes only the application and
# snapshot-dependencies layers.
FROM eclipse-temurin:25-jre-noble AS extractor

# The JAR sits outside the destination directory on purpose: `extract`
# refuses a destination that is not empty, so extracting into the directory
# holding the JAR fails.
COPY server/hrafnagud-server/target/hrafnagud.jar /hrafnagud.jar
# --launcher keeps the Spring Boot loader as its own layer, which lets the
# runtime stage start via JarLauncher against the exploded tree instead of
# the JAR. `-Djarmode=tools` is the Boot 3.3+ successor to the older
# `-Djarmode=layertools`.
RUN java -Djarmode=tools -jar /hrafnagud.jar extract --layers --launcher --destination /extract

# ── Stage 2: runtime ───────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-noble

# MaxRAMPercentage rather than a fixed -Xmx so the heap follows the
# container limit. Lingua in low-accuracy mode over all languages is the
# largest single consumer — see deploy/README.md for the memory notes.
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0" \
    SPRING_PROFILES_ACTIVE=prod

# curl is for the HEALTHCHECK below and nothing else. Everything hrafnagud
# does at runtime — feed parsing, HTML extraction, language detection — is
# pure Java, so there is no native tooling to install.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 1500 hrafnagud \
 && useradd  --system --uid 1500 --gid 1500 --home-dir /app --shell /bin/bash hrafnagud \
 && mkdir -p /app/config \
 && chown -R hrafnagud:hrafnagud /app

WORKDIR /app

# Spring Boot auto-loads application*.yaml from ./config/ relative to the
# working directory, so the prod profile lands there. Copied before the
# application layers so editing it does not invalidate them.
COPY --chown=hrafnagud:hrafnagud deploy/docker/application-prod.yaml /app/config/application-prod.yaml
COPY --chown=hrafnagud:hrafnagud deploy/docker/docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

# Layer order is deliberate: least-likely-to-change first, so the big
# dependency layer survives ordinary rebuilds.
COPY --from=extractor --chown=hrafnagud:hrafnagud /extract/dependencies/ ./
COPY --from=extractor --chown=hrafnagud:hrafnagud /extract/spring-boot-loader/ ./
COPY --from=extractor --chown=hrafnagud:hrafnagud /extract/snapshot-dependencies/ ./
COPY --from=extractor --chown=hrafnagud:hrafnagud /extract/application/ ./

USER hrafnagud

EXPOSE 9800

# start-period covers the cold start (Spring context + Mongo index
# creation). In Kubernetes the startupProbe owns that budget instead; this
# HEALTHCHECK is for plain `docker run` / compose.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
  CMD curl --fail --silent http://localhost:9800/actuator/health || exit 1

ENTRYPOINT ["/app/docker-entrypoint.sh"]
