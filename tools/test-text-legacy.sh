#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
legacy_commit=c3e4a808f08a75ef40acf7bbcc9d2bc9a6ddc2c5
if ! git cat-file -e "$legacy_commit^{commit}"; then git fetch --no-tags --depth=1 origin "$legacy_commit"; fi
mkdir -p build/text-legacy/source build/text-legacy/classes
git archive "$legacy_commit" app/src/main/java/dev/ghost/nearbyim/core app/src/main/java/dev/ghost/nearbyim/noise app/src/main/java/dev/ghost/nearbyim/i18n/UiText.java app/src/main/java/dev/ghost/nearbyim/i18n/LocalizedIllegalArgumentException.java third_party/noise-java/src | tar -x -C build/text-legacy/source
find build/text-legacy/source -name '*.java' > build/text-legacy/sources.txt
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d build/text-legacy/classes @build/text-legacy/sources.txt tests/legacy/LegacyTextPeer.java
java -Xmx48m -cp build/core-tests dev.ghost.nearbyim.core.LegacyTextInteropTests build/text-legacy/classes
