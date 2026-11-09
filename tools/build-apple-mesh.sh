#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ $(uname -s) == Darwin ]] || { echo 'Apple Mesh requires the Apple SDK and linker' >&2; exit 1; }
mesh_build_root="$(pwd)/mesh/target/apple-native"
mesh_headers="$mesh_build_root/headers"
mkdir -p "$mesh_headers" "$mesh_build_root/macos" "$mesh_build_root/simulator"
cp mesh/apple-bridge/MeshRust.h "$mesh_headers/"
printf 'module MeshRust { header "MeshRust.h" export * }\n' > "$mesh_headers/module.modulemap"
mesh_targets=(aarch64-apple-darwin x86_64-apple-darwin aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios)
rustup target add "${mesh_targets[@]}"
for mesh_target in "${mesh_targets[@]}"; do
  CARGO_TARGET_DIR="$mesh_build_root" cargo build --manifest-path mesh/apple-bridge/Cargo.toml --locked --release --target "$mesh_target"
done
lipo -create "$mesh_build_root/"{aarch64,x86_64}-apple-darwin/release/libnearby_mesh_apple.a -output "$mesh_build_root/macos/libnearby_mesh_apple.a"
lipo -create "$mesh_build_root/aarch64-apple-ios-sim/release/libnearby_mesh_apple.a" "$mesh_build_root/x86_64-apple-ios/release/libnearby_mesh_apple.a" -output "$mesh_build_root/simulator/libnearby_mesh_apple.a"
# This directory contains only this script's generated XCFramework, never a user artifact.
rm -rf apple/MeshCore.xcframework
xcodebuild -create-xcframework \
  -library "$mesh_build_root/macos/libnearby_mesh_apple.a" -headers "$mesh_headers" \
  -library "$mesh_build_root/aarch64-apple-ios/release/libnearby_mesh_apple.a" -headers "$mesh_headers" \
  -library "$mesh_build_root/simulator/libnearby_mesh_apple.a" -headers "$mesh_headers" \
  -output apple/MeshCore.xcframework
python3 - <<'PY'
import hashlib, json, pathlib, subprocess
p=pathlib.Path('apple/MeshCore.xcframework')
data={'source':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
      'hardware_validation':'pending','libraries':{str(f):{'bytes':f.stat().st_size,'sha256':hashlib.sha256(f.read_bytes()).hexdigest()} for f in p.rglob('*.a')}}
pathlib.Path('mesh/target/apple-native/build-info.json').write_text(json.dumps(data,indent=2)+'\n')
print(json.dumps(data,indent=2))
PY
