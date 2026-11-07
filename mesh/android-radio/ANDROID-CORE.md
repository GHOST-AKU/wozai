# Android arm64 core build

The radio adapter uses the existing `mesh-lab` executable through device-local
loopback TCP. The Rust source and locked Cargo dependencies are unchanged.

Install Rust 1.88+ with `rustup target add aarch64-linux-android`, and an Android
NDK with a Linux or macOS host toolchain. From the repository root:

```sh
ANDROID_NDK_HOME=/absolute/path/to/android-ndk-r27c \
  mesh/tools/build-android-core.sh
```

`CARGO_HOME` and `RUSTUP_HOME` can select an existing Rust installation. The
script uses the NDK's `aarch64-linux-android29-clang` linker and Cargo's `--locked`
release build. It passes a 16 KiB maximum page-size flag to the final binary's
linker for compatibility with Android devices that use 16 KiB pages. Its default
output is:

```text
mesh/target/aarch64-linux-android/release/mesh-lab
```

`CARGO_TARGET_DIR` can override `mesh/target`; relative values are resolved from
`mesh/`. This is a native arm64 executable for Android API 29+, not an APK.
Launch the core with a device-local loopback listener and omit `--discover`:
the unchanged bounded DNS-SD subprocess is Linux-only. BLE and Wi-Fi discovery
and transfer belong to the native adapter.

Inspect a build before putting it on a device:

```sh
file mesh/target/aarch64-linux-android/release/mesh-lab
readelf -h -l -d -n mesh/target/aarch64-linux-android/release/mesh-lab
```

The ELF machine must be AArch64, the program interpreter must be
`/system/bin/linker64`, and its shared-library requirements must be Android
libraries. Cross-compilation and ELF inspection do not establish successful
execution or radio behavior on a real device.

The development build uses the official r27c package, NDK revision
`27.2.12479018`, from
`https://dl.google.com/android/repository/android-ndk-r27c-linux.zip`. Google's
`https://dl.google.com/android/repository/repository2-3.xml` lists its size as
663,987,688 bytes and SHA-1 as
`090e8083a715fdb1a3e402d0763c388abb03fb4e`. Verify the archive against that
official metadata before extraction; an existing verified NDK can be reused.
