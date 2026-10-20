#!/usr/bin/env sh
# Only a disposable emulator: changes wm size/density and creates a loopback chat.
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/adaptive
adb install -r -g app/build/outputs/apk/preview/app-preview.apk
adb install -r app/build/outputs/apk/androidTest/preview/app-preview-androidTest.apk
adb shell am instrument -r -w dev.ghost.nearbyim.test/dev.ghost.nearbyim.AdaptiveInstrumentation > build/adaptive/instrumentation.txt
cat build/adaptive/instrumentation.txt
adb logcat -d -v threadtime > build/adaptive/logcat.txt || true
adb pull /sdcard/Android/data/dev.ghost.nearbyim/files/adaptive build/adaptive/screens || true
python3 - <<'PY'
from pathlib import Path
text=Path('build/adaptive/instrumentation.txt').read_text()
if 'checks passed' not in text or 'INSTRUMENTATION_CODE: -1' not in text:
    raise SystemExit('Adaptive native UI checks failed')
PY
