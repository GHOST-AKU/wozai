#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/../.."
mkdir -p terminal/target/java-interop
find third_party/noise-java/src app/src/main/java/dev/ghost/nearbyim/noise -name '*.java' > terminal/target/java-interop/sources.txt
find app/src/main/java/dev/ghost/nearbyim/core -name '*.java' >> terminal/target/java-interop/sources.txt
printf '%s\n' app/src/main/java/dev/ghost/nearbyim/i18n/UiText.java app/src/main/java/dev/ghost/nearbyim/i18n/LocalizedIllegalArgumentException.java terminal/tests/TerminalInteropPeer.java terminal/tests/TerminalLongTextPeer.java >> terminal/target/java-interop/sources.txt
javac -encoding UTF-8 -d terminal/target/java-interop @terminal/target/java-interop/sources.txt
classes="$(pwd)/terminal/target/java-interop"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) classes="$(cygpath -w "$classes")" ;; esac
NEARBYIM_JAVA_CLASSES="$classes" cargo test --manifest-path terminal/Cargo.toml --locked -- --include-ignored --skip large_file_stress --skip ui::render_tests::export_preview
