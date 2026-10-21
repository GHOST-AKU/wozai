#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/apple-lifecycle
core_status=0
swift test --package-path apple > build/apple-lifecycle/core.log 2>&1 || core_status=$?
cat build/apple-lifecycle/core.log
bin_path="$(swift build --package-path apple --show-bin-path)"
lifecycle_sources=()
if [[ -f apple/App/MacAppDelegate.swift ]]; then
  lifecycle_sources=(-D HAS_MAC_LIFECYCLE apple/App/MacAppDelegate.swift)
fi
swiftc -target "$(uname -m)-apple-macosx14.0" -parse-as-library -I "$bin_path/Modules" -o build/apple-lifecycle/termination-probe \
  apple/App/AppModel.swift "${lifecycle_sources[@]}" tests/apple/MacTerminationProbe.swift \
  "$bin_path"/WozaiCore.build/*.swift.o -lsqlite3
probe_status=0
python3 - <<'PY' > build/apple-lifecycle/termination.log 2>&1 || probe_status=$?
import pathlib, sqlite3, subprocess, tempfile
probe = pathlib.Path('build/apple-lifecycle/termination-probe').resolve()
with tempfile.TemporaryDirectory(prefix='wozai-termination-') as root:
    database = pathlib.Path(root) / 'chat.sqlite'
    subprocess.run([str(probe), str(database)], check=True, timeout=20)
    with sqlite3.connect(database) as connection:
        drafts = dict(connection.execute('SELECT peer,body FROM drafts'))
    assert drafts == {'edited': 'latest draft before quit', 'cleared': ''}, drafts
    print('PASS: normal AppKit quit durably saves and clears drafts before the 400ms debounce')
PY
cat build/apple-lifecycle/termination.log
if [[ $core_status != 0 || $probe_status != 0 ]]; then exit 1; fi
