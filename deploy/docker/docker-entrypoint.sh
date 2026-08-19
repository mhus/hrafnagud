#!/bin/sh
#
# hrafnagud entrypoint.
#
# Exists for one reason: JAVA_OPTS has to undergo word splitting so that
# `-XX:+UseG1GC -XX:MaxRAMPercentage=75.0` arrives as two arguments. An
# exec-form ENTRYPOINT in the Dockerfile cannot do that, and a shell-form
# one would leave a /bin/sh as PID 1 that does not forward SIGTERM — which
# turns every rollout into a 30-second kill wait.
#
# The image ships the JAR exploded into Spring Boot layers, so the launcher
# is invoked as a class against /app rather than with -jar.

set -e

exec java $JAVA_OPTS \
    -cp /app \
    org.springframework.boot.loader.launch.JarLauncher "$@"
