#!/bin/sh
# Gradle wrapper script for MebahelZombieHorde.
# Note: gradle/wrapper/gradle-wrapper.jar is omitted from this text-only distribution.
# Run `gradle wrapper --gradle-version 9.1.0` once to generate gradle-wrapper.jar.
set -e
APP_HOME=$(cd "$(dirname "$0")" && pwd -P)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$CLASSPATH" ]; then
    echo "ERROR: Missing $CLASSPATH" >&2
    echo "Bootstrap once with: gradle wrapper --gradle-version 9.1.0" >&2
    exit 1
fi
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
