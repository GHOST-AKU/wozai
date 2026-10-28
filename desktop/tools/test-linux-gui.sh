#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/../.."
if [ -n "${JAVA_HOME:-}" ]; then PATH="$JAVA_HOME/bin:$PATH"; export PATH; fi
if [ -z "${DISPLAY:-}" ]; then echo 'Use an X11 desktop or xvfb-run.' >&2; exit 1; fi
library="-Dwozai.bluetooth.library=$(pwd)/desktop/build/lib/libwozai_bluetooth.so"
classes='desktop/build/classes:desktop/build/tests:desktop/build/lib/*'
java "$library" -cp "$classes" dev.ghost.wozai.EmojiPickerTests desktop/build/gui-linux-emoji
java "$library" -cp "$classes" dev.ghost.wozai.TransferPathGuiTests
java "$library" -cp "$classes" dev.ghost.wozai.TransferPathWindowTests
java "$library" -cp "$classes" dev.ghost.wozai.AvatarGuiTests
java "$library" -cp "$classes" dev.ghost.wozai.AttachmentSelectionGuiTests
java "$library" -cp "$classes" dev.ghost.wozai.DesktopRetryTests --gui
java "$library" -cp "$classes" dev.ghost.wozai.ThumbnailScrollTests
java "$library" -cp "$classes" dev.ghost.wozai.DiscoveryTests
java "$library" -cp "$classes" dev.ghost.wozai.LanDiscoveryTopologyTests
java "$library" -cp "$classes" dev.ghost.wozai.GuiTests desktop/build/gui-linux.png
java "$library" -cp "$classes" dev.ghost.wozai.VerificationGuiTests desktop/build/gui-linux-verification
runtime=desktop/build/package/NearbyIM/lib/runtime/bin/java
bundled='desktop/build/package/NearbyIM/lib/app/*:desktop/build/tests'
"$runtime" -cp "$bundled" dev.ghost.wozai.GuiTests desktop/build/gui-linux-bundled.png
"$runtime" -cp "$bundled" dev.ghost.wozai.TransferPathGuiTests
"$runtime" -cp "$bundled" dev.ghost.wozai.TransferPathWindowTests
"$runtime" -cp "$bundled" dev.ghost.wozai.AvatarGuiTests
"$runtime" -cp "$bundled" dev.ghost.wozai.DesktopRetryTests --gui
"$runtime" -cp "$bundled" dev.ghost.wozai.ThumbnailScrollTests
"$runtime" -cp "$bundled" dev.ghost.wozai.VerificationGuiTests desktop/build/gui-linux-verification-bundled
for scale in 1 1.25 1.5 2; do
    "$runtime" "-Dsun.java2d.uiScale=$scale" -cp "$bundled" dev.ghost.wozai.LayoutTests "desktop/build/gui-linux-settings-$scale.png"
done
python3 desktop/tools/test-linux-package.py
