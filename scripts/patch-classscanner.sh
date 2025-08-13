#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.."; pwd)"
JAR="$ROOT/AsyncWorldEdit-Deploy/target/AsyncWorldEdit.jar"
CLASS_SRC="$ROOT/AsyncWorldEdit/src/main/java/org/primesoft/asyncworldedit/injector/scanner/ClassScanner.java"
CLASS_OUT="$ROOT/AsyncWorldEdit/target/classes"

if [ ! -f "$JAR" ]; then
  echo "Plugin jar not found at $JAR. Build the project first." >&2
  exit 1
fi

mkdir -p "$CLASS_OUT"
javac -cp "$JAR:$ROOT/libs/*" -d "$CLASS_OUT" "$CLASS_SRC"
jar uf "$JAR" -C "$CLASS_OUT" org/primesoft/asyncworldedit/injector/scanner/ClassScanner.class
echo "Patched ClassScanner into $JAR"
