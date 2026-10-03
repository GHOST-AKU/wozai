#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
python3 tools/generate-i18n.py --check
python3 tools/check-i18n.py
python3 tests/i18n/generator_tests.py
mkdir -p build/i18n-tests
javac -encoding UTF-8 -d build/i18n-tests \
  app/src/main/java/dev/ghost/nearbyim/i18n/LanguageRegistry.java \
  app/src/main/java/dev/ghost/nearbyim/i18n/UiText.java \
  app/src/main/java/dev/ghost/nearbyim/i18n/LocalizedIOException.java \
  app/src/main/java/dev/ghost/nearbyim/i18n/LocalizedIllegalArgumentException.java \
  tests/i18n/LanguageRegistryTests.java
java -cp build/i18n-tests dev.ghost.nearbyim.i18n.LanguageRegistryTests
