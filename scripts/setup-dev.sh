#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.."; pwd)"
cd "$ROOT"
mvn -q clean package
echo "Plugin built at AsyncWorldEdit-Deploy/target/AsyncWorldEdit.jar"
