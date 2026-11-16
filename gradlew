#!/bin/sh
set -eu
app_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
java_bin=java
if [ -n "${JAVA_HOME:-}" ]; then
    java_bin="$JAVA_HOME/bin/java"
fi
exec "$java_bin" -jar "$app_dir/gradle/wrapper/gradle-wrapper.jar" "$@"

