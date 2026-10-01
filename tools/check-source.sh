#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
java tools/CheckJavaSyntax.java app/src/main/java
python3 tools/check-resources.py
