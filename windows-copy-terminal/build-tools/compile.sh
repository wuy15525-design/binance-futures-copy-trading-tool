#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
dep=${DEPS:-build/deps}
json=${JSON_JAR:-$dep/json.jar}
mkdir -p build/classes
java -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -encoding UTF-8 -cp "$json:$dep/sqlite.jar" -d build/classes $(find src -name '*.java')
