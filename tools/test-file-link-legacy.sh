#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
# Pin the last candidate before FILE_LINK existed; never silently use current decoders.
legacy_commit=2b7347e39d0360241055610c7c4e7aeeb049bff7
git cat-file -e "$legacy_commit^{commit}"
mkdir -p build/file-handover/legacy-source build/file-handover/legacy-classes
# Only source code is extracted; no checkout, index or working changes are touched.
git archive "$legacy_commit" app/src/main/java/dev/ghost/nearbyim/core app/src/main/java/dev/ghost/nearbyim/noise app/src/main/java/dev/ghost/nearbyim/i18n/UiText.java app/src/main/java/dev/ghost/nearbyim/i18n/LocalizedIllegalArgumentException.java third_party/noise-java/src | tar -x -C build/file-handover/legacy-source
find build/file-handover/legacy-source -name '*.java' > build/file-handover/legacy-sources.txt
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d build/file-handover/legacy-classes @build/file-handover/legacy-sources.txt tests/legacy/LegacyFilePeer.java
java -Xmx48m -cp build/core-tests dev.ghost.nearbyim.core.LegacyFileLinkInteropTests build/file-handover/legacy-classes
