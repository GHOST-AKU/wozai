#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
if [ -n "${JAVA_HOME:-}" ]; then PATH="$JAVA_HOME/bin:$PATH"; export PATH; fi
command -v jpackage >/dev/null || { echo 'jpackage from a full JDK 17 is required.' >&2; exit 1; }
if [ "$(uname -s)" != Linux ]; then echo 'Build the package on Linux.' >&2; exit 1; fi
version=$(python3 -c 'import json; print(json.load(open("../i18n/config.json"))["appVersion"])')
locales=$(python3 -c 'import json; print(",".join(x["tag"] for x in json.load(open("../i18n/config.json"))["languages"]))')
case $(uname -m) in x86_64) architecture=x64; deb_arch=amd64;; aarch64) architecture=arm64; deb_arch=arm64;; *) echo 'Unsupported Linux architecture.' >&2; exit 1;; esac
mkdir -p build/package-input build/package
rm -rf build/package-input/* build/package/NearbyIM
cp build/lib/*.jar build/lib/libwozai_bluetooth.so build/package-input/
jpackage --type app-image --name NearbyIM --app-version "$version" --vendor GHOST-AKU \
    --input build/package-input --main-jar nearbyim-desktop.jar --main-class dev.ghost.wozai.Main \
    --dest build/package --icon assets/icons/linux/hicolor/256x256/apps/nearbyim.png \
    --java-options '-Dwozai.installDir=$APPDIR/../..' \
    --add-modules java.base,java.desktop,java.logging,jdk.crypto.ec,jdk.accessibility,jdk.localedata \
    --jlink-options "--strip-debug --no-man-pages --no-header-files --include-locales=$locales"
image=build/package/NearbyIM
${CXX:-c++} -std=c++17 -O2 -Wall -Wextra -Wpedantic -Werror native/linux_launcher.cpp -o "$image/bin/NearbyIM"
cp ../THIRD_PARTY_NOTICES.md "$image/"
cp ../docs/linux.md "$image/README.md"
cp -R ../licenses "$image/"
cp ../docs/licenses/material-icons-LICENSE.txt "$image/licenses/"
mkdir -p "$image/share/icons"
cp -R assets/icons/linux/hicolor "$image/share/icons/"
chmod -R a+rX "$image"
tar -czf "build/NearbyIM-$version-linux-$architecture.tar.gz" -C build/package NearbyIM
if command -v dpkg-deb >/dev/null; then
    staging=build/deb-root
    rm -rf "$staging"
    mkdir -p "$staging/DEBIAN" "$staging/opt/nearbyim" "$staging/usr/share/applications" "$staging/usr/share/icons"
    cp -R "$image/." "$staging/opt/nearbyim/"
    cp -R assets/icons/linux/hicolor "$staging/usr/share/icons/"
    cat > "$staging/DEBIAN/control" <<EOF
Package: nearbyim
Version: $version
Architecture: $deb_arch
Maintainer: GHOST-AKU <noreply@github.com>
Section: net
Priority: optional
Depends: libc6 (>= 2.34), libstdc++6 (>= 12), bluez, libglib2.0-0 (>= 2.56) | libglib2.0-0t64 (>= 2.56), libx11-6, libxext6, libxi6, libxrender1, libxtst6, libfreetype6, libfontconfig1, libasound2 | libasound2t64
Recommends: blueman
Description: NearbyIM local text chat over LAN and Bluetooth
 Native desktop client with a bundled Java runtime and five interface languages.
EOF
    cat > "$staging/usr/share/applications/nearbyim.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=NearbyIM
Name[zh_CN]=我在
Name[zh_TW]=我在
Exec=/opt/nearbyim/bin/NearbyIM
Icon=nearbyim
Terminal=false
Categories=Network;InstantMessaging;
StartupNotify=true
StartupWMClass=dev-ghost-wozai-Main
EOF
    chmod -R a+rX "$staging"
    dpkg-deb --root-owner-group --build "$staging" "build/NearbyIM-$version-linux-$architecture.deb"
fi
python3 - <<'PY'
import hashlib, json, platform
from pathlib import Path
version=json.loads(Path('../i18n/config.json').read_text())['appVersion']
architecture={'x86_64':'x64', 'aarch64':'arm64'}[platform.machine()]
files=sorted(Path('build').glob(f'NearbyIM-{version}-linux-{architecture}.*'))
Path('build/linux-checksums.txt').write_text(''.join(f'{hashlib.sha256(file.read_bytes()).hexdigest()}  {file.name}\n' for file in files))
print(Path('build/linux-checksums.txt').read_text(), end='')
PY
