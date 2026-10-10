#!/usr/bin/env sh
# Disposable emulators only: seeds real persistent state in the published APK.
set -eu
cd "$(dirname "$0")/.."
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1 || { echo 'Upgrade acceptance requires a disposable emulator'; exit 1; }
mkdir -p build/upgrade-evidence
diagnose() {
    result=$?
    if [ "$result" -ne 0 ]; then
        timeout 20 adb logcat -d -t 1200 > build/upgrade-evidence/failure-logcat.txt 2>&1 || true
        timeout 20 adb shell dumpsys activity activities > build/upgrade-evidence/failure-activities.txt 2>&1 || true
        python3 - <<'PY'
from pathlib import Path
import re
pattern=re.compile(r'AndroidRuntime|FATAL|ANR|ActivityManager|ActivityTaskManager|keystore|SigningUpgrade',re.I)
lines=Path('build/upgrade-evidence/failure-logcat.txt').read_text(errors='replace').splitlines()
print('\n'.join(line for line in lines if pattern.search(line))[-12000:])
PY
    fi
    exit "$result"
}
trap diagnose EXIT
candidate=build/signing-upgrade/candidate.apk
baseline=build/signing-upgrade/baseline.apk
test_apk=build/signing-upgrade/test.apk
wrong=build/signing-upgrade/wrong.apk
code=$(python3 -c 'import re; from pathlib import Path; print(re.search(r"versionCode\s*=\s*(\d+)",Path("app/build.gradle.kts").read_text()).group(1))')
baseline_code=$(python3 -c 'import json; print(json.load(open("tools/android-signing.json"))["baseline_version_code"])')
case "${UPGRADE_BASELINE_CODE:-7}" in
    7) ;;
    8) baseline=build/signing-upgrade/maintenance.apk; baseline_code=8 ;;
    *) echo 'Unsupported upgrade baseline'; exit 1 ;;
esac
launch() {
    echo "Cold launch: $1"
    # Every acceptance phase gets a fresh process, including after instrumentation.
    timeout 120 adb shell am start -S -W -n dev.ghost.nearbyim/.MainActivity > "build/upgrade-evidence/launch-$1.txt"
    python3 - "$1" <<'PY'
from pathlib import Path
import sys
text=Path('build/upgrade-evidence/launch-'+sys.argv[1]+'.txt').read_text()
assert 'Status: ok' in text and 'Error:' not in text, text
PY
    sleep 2
    adb shell am force-stop dev.ghost.nearbyim
}
instrument() {
    echo "Upgrade check: $3 ($1, code $2)"
    timeout 180 adb shell am instrument -r -w -e phase "$1" -e version_code "$2" dev.ghost.nearbyim.test/dev.ghost.nearbyim.SigningUpgradeInstrumentation > "build/upgrade-evidence/$3.txt"
    cat "build/upgrade-evidence/$3.txt"
    python3 - "$3" <<'PY'
from pathlib import Path
import sys
text=Path('build/upgrade-evidence/'+sys.argv[1]+'.txt').read_text()
assert 'checks passed' in text and 'INSTRUMENTATION_CODE: -1' in text, text
PY
}
adb install -r -g "$baseline"
adb install -r "$test_apk"
launch baseline
instrument seed "$baseline_code" seed
if adb install -r "$wrong" > build/upgrade-evidence/wrong-signer.txt 2>&1; then
    echo 'Android unexpectedly accepted a different certificate'; exit 1
fi
python3 - <<'PY'
from pathlib import Path
text=Path('build/upgrade-evidence/wrong-signer.txt').read_text()
assert 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' in text, text
print('Different certificate correctly rejected: INSTALL_FAILED_UPDATE_INCOMPATIBLE')
PY
launch rejected-update
instrument verify "$baseline_code" after-rejection
adb install -r -g "$candidate"
launch upgraded
instrument verify "$code" after-upgrade
instrument noise "$code" noise-created
launch restarted
instrument noise "$code" noise-preserved
echo 'Same-certificate upgrade preserved persistent data; wrong certificate rejected without data loss'
