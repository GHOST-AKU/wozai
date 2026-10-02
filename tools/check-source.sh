#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
python3 tools/generate-i18n.py --check
python3 tools/check-i18n.py
java tools/CheckJavaSyntax.java app/src/main/java
python3 tools/check-resources.py
