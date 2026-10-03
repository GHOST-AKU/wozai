#!/usr/bin/env sh
# Use an emulator or a disposable test device: this runner creates local test chats.
set -eu
cd "$(dirname "$0")/.."
mkdir -p build
adb install -r -g "${ANDROID_APP_APK:-app/build/outputs/apk/debug/app-debug.apk}"
adb install -r "${ANDROID_TEST_APK:-app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"
adb shell am instrument -r -w dev.ghost.nearbyim.test/dev.ghost.nearbyim.LocalizationInstrumentation > build/android-i18n-device.txt
cat build/android-i18n-device.txt
adb pull /sdcard/Android/data/dev.ghost.nearbyim/files/i18n build/android-i18n-screenshots || true
python3 - <<'PY'
from pathlib import Path
text = Path('build/android-i18n-device.txt').read_text()
if 'checks passed' not in text or 'INSTRUMENTATION_CODE: -1' not in text:
    raise SystemExit('Native Android localization regression failed')
PY
