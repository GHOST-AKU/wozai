# Native radio size and host-cost audit

Measured on 2026-11-01 for PR #93 source `e98efa8274b1953ce43fb5709b090c620cca9e30`. This is a source, ELF, Java/DEX and host-helper audit. Android hardware execution, simultaneous radio neighbors, wireless throughput and phone power remain **pending**.

## Measurements and provenance

The Rust core was inspected without rebuilding it. All ten Cargo/core source SHA-256 values in [native-radio-android-core.json](../../mesh/evidence/native-radio-android-core.json) match the audited revision and the existing binary matches its recorded digest. That record references earlier source commit `cdb028f68aaa9c82e0cc9c897314fe2e7becc03f`; matching source hashes permit reuse for this unchanged core.

The pre-existing local Java JAR/DEX were stale: the JAR lacked `CallbackBudget.class`, which the audited adapters reference. Their 85,052-byte DEX is excluded from the current baseline.

One isolated Java compilation used `git archive` of the audited commit, JDK Temurin 17.0.16+8, Android platform 36 revision 2 and build-tools 35.0.0, D8 8.6.2-dev build `abaab469b5ebd4dd2bb91ba0ed6f45277faae4ca`. D8 default and release consumed the **same** resulting JAR. No Rust rebuild, APK build or device run was performed.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Existing Android arm64 release core | 940,272 | `cfdbe28813c0957a5726af4705b7e5838069f044ad81e6cf73113dc35d9241dc` |
| Pinned Java classes JAR | 61,942 | `b30db948f92ee8b05b9895fbdfc8b436413e5dd08097d42a5427ad6a069f8f37` |
| Pinned default D8 DEX | 87,500 | `0cab78b4fd03c0f9b76ebb2cb0c1a6ff76a4fdb2922e1608ad4a4d8e740de680` |
| Same JAR, D8 `--release` | 86,600 | `ef6ec0dd8c0d9d1de7ba6c44562cb4514355ebaf616482e283ad0a3916129284` |

The JAR is a build intermediate; do not add its size to DEX when estimating a future package. These are raw file sizes, not an installable APK, download size, installed footprint or RSS measurement. Subsequent radio source fixes require their own CI output binding; these numbers remain the pinned baseline.

## ELF contents and dependencies

NDK r27c LLVM inspection confirms ELF64 AArch64 PIE, Android API 29 note, interpreter `/system/bin/linker64`, four `PT_LOAD` segments with 16,384-byte alignment, immediate binding, GNU RELRO and a non-executable stack. Dynamic dependencies are only Android system `libdl.so` and `libc.so`; these are not extra bundled libraries.

| Selected ELF section | Bytes | Interpretation |
| --- | ---: | --- |
| `.text` | 651,416 | Executable code |
| `.rodata` | 98,700 | Constants and read-only data |
| `.eh_frame` | 78,888 | Runtime unwind metadata |
| `.gcc_except_table` | 23,508 | Exception/unwind tables |
| `.eh_frame_hdr` | 11,668 | Unwind index |
| `.rela.dyn` + `.rela.plt` | 39,048 | Dynamic relocations |
| `.data.rel.ro` | 25,656 | Relocation-time writable, then read-only data |
| `.data` | 2,560 | Initialized writable data |
| `.bss` | 2,496 | Zero-filled memory; no corresponding payload bytes |

ELF section totals include `NOBITS` memory and exclude some container overhead; they must not be presented as the file size. There are no `.debug_*`, `.symtab` or ordinary `.strtab` sections. Cargo already uses `strip = true`, thin LTO and one codegen unit.

A scratch copy processed with `llvm-strip --strip-debug` became 940,264 bytes: **eight bytes saved**, from rewriting the section-name string table/alignment. Runtime sections remained unchanged. Its SHA-256 is `df7927d4eca2ecc65bc41adf73599bde0d7325ade992360997d3089c32963186`. Another stripping step is not a worthwhile optimization. The 114,064 bytes of unwind/exception metadata are runtime data, not debug symbols; deleting those sections would change runtime behavior.

Offline `cargo tree --locked --target aarch64-linux-android -e normal` found the expected Ed25519/SHA-256, random-source, JSON, file-lock and discovery dependencies. The graph includes `mdns-sd`/`if-addrs` even though Linux-only discovery returns an error on Android and release dead-code elimination can discard its unreachable implementation. A future target-specific dependency/cfg cleanup could reduce compilation work. No binary-byte saving is established by dependency presence alone.

Do not remove signature verification, secure randomness, durable replay state, file locking, limits, tests or notices to make a size number smaller. Changing to `panic=abort` or another optimization level requires separate crash/cleanup policy review and measurement; neither is a proven free saving here.

## DEX release comparison

D8 `--release` saved **900 bytes (1.03%)** from the pinned DEX while retaining every class descriptor. This is a modest, legitimate production compilation setting.

| DEX property | Default | Release |
| --- | ---: | ---: |
| Unique class definitions | 97 | 97 |
| Code items | 422 | 422 |
| Field IDs | 374 | 374 |
| Method IDs, including external references | 728 | 729 |
| String IDs | 1,109 | 1,110 |
| Classes retaining source names | 97 | 97 |
| Debug-info items | 233 | 230 |
| Debug-info map span, including section padding | 2,920 | 2,528 |
| Positive source-line positions in `dexdump -d` | 1,308 | 1,015 |

The additional release method ID is the platform constructor `StringBuilder(String)`, used by D8's normal code transformation. No default-only method IDs disappeared. Identical class membership and code-item counts are useful sanity checks; they do not constitute Android execution or a formal proof of semantic equivalence.

The JAR contains 29 application class files, 34 ZIP entries including directories/manifest, 124,843 uncompressed bytes and no duplicate entry names. D8 produces 97 unique class descriptors, including desugared synthetic classes; these are not duplicate bundled libraries. Java dependencies come from Android/JDK APIs; this script bundles no Kotlin runtime, AndroidX, protobuf or third-party Java JAR.

Default javac preserves source/line information; no `LocalVariableTable` generation was requested. Release D8 retains source filenames and substantial line information, but changes line positions and debugging shape. Keep the default diagnostic mode available if interactive debugging needs it. Do not use `javac -g:none` simply to remove useful failure locations for an unmeasured small saving.

Decision for this internal diagnostic lab: keep default D8 mode. Saving 900 bytes does not justify losing some source-position detail during device investigation. Explicit `--release` remains a measured option for a future distribution build. No R8 shrinker, obfuscation, dependency deletion or security change is needed for that option. Validate the final edited source once in the existing host/API/DEX CI; no APK rebuild is implied.

## Bounded host helper costs

A separate host fixture compiled only `BleChunks`, `CoreBridge` and `CallbackBudget` plus a scratch measurement helper. Their source bytes matched the pinned archive. The fixture ran on Linux x86_64, AMD EPYC 9V45, JDK17, `-Xms128m -Xmx128m -XX:+UseSerialGC`, with a two-CPU cgroup quota and 8 GiB memory limit.

For each MTU it warmed 10,000 splits, then measured seven batches of 20,000 splits of a 4,096-byte buffer. It consumed every fragment length and first byte through a volatile result; the first input byte varied per iteration. Allocation came from `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`. The timings are host Java helper timings, not Android ART or any transport capacity.

| Negotiated MTU | Payload bytes/fragment | Fragments/4 KiB | Median ns/split | Seven-trial range, ns | Measured allocated bytes/split |
| --- | ---: | ---: | ---: | ---: | ---: |
| 23 | 20 | 205 | 1,079.2 | 1,037.5–1,617.5 | 9,032–9,088 |
| 247 | 244 | 17 | 175.6 | 165.8–178.8 | 4,520 |
| 517 | 512 | 8 | 141.3 | 137.2–151.4 | 4,272 |

The first MTU23 trial had higher allocation/time than the later trials, consistent with a JVM still optimizing; the entire observed range is retained. There was one JVM run, no confidence interval and no device measurement. Dividing these nanoseconds into a throughput number would omit GATT acknowledgement, scheduling, radio airtime, interference and OS overhead.

At default MTU, eager splitting creates 205 owned arrays for one 4 KiB read. This remains bounded, but streaming fragments one at a time could reduce temporary allocations if device profiling later identifies GC pressure. Ownership must remain stable until the corresponding GATT acknowledgement/cancellation finishes. Reusing mutable callback or transmission buffers early would break correctness.

`AwareAdapter` currently copies a counted socket buffer before `CoreBridge.receive` clones it again. A future counted-range ingress API could remove one copy while retaining the bridge's ownership boundary, byte bounds and close-on-overload behavior. Removing the ownership clone without an API change is unsafe. Neither host timings nor the existence of a copy establish a phone-power or wireless-throughput benefit.

The no-socket fixture also observed exactly eight accepted `CoreBridge.receive` calls followed by rejection/one close callback on the ninth, and exactly 128 callback-budget admissions followed by a permanent overflow latch. These confirm helper bounds only, not device backpressure behavior. The core ingress queue contains at most 32 KiB of accepted payload per bridge, plus any writer-owned in-flight chunk and object overhead; the rejected ninth call can allocate a temporary clone before queue admission fails.

These helper measurements describe the pinned baseline. The review's subsequent byte-pipe/coalescing and lazy-fragment changes require their own final source/output binding; do not reuse these timings, allocation numbers or count-based ingress behavior as measurements of the revised implementation. A fixed 32 KiB ingress allocation trades lower per-chunk allocation for up to 128 KiB reserved across four radio neighbors, before reader buffers, object overhead and native/OS resources. No fresh performance run is claimed for that tradeoff.

Source review found one sender semaphore per BLE link and a wait for each ATT acknowledgement. Server indications share one outstanding operation. `pumpServer` scans map order rather than maintaining an explicit fairness cursor; this host microbenchmark does not establish starvation, multipeer fairness or radio concurrency. Those require the existing device acceptance scenarios.

## Build reproducibility and evidence

The build scripts already clean their temporary class directories and use a locked Cargo graph. CI checks out the exact PR head and hashes sources/outputs; this is a useful provenance record. It does not establish bit-for-bit rebuild reproducibility:

- The Java JAR uses default ZIP timestamps/manifest creator information.
- Rust 1.88 is a minimum, not a repository-pinned compiler toolchain.
- The native helper accepts an installed NDK; its actual revision must accompany the evidence.
- Android platform 36 can have package revisions; this baseline used revision 2, with `android.jar` SHA-256 `d9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a`.
- CI action major tags and SDK download contents are separate moving inputs unless pinned and recorded.

Keep a build manifest containing exact source revision/hashes, tool versions, input archive/JAR digests, compiler flags and output digests. Reuse verified artifacts while those sources/configurations are unchanged. A fresh build is justified after a concrete code/configuration fix, not solely to produce a new timestamp.

## Commands and local records

Read-only core commands used the existing NDK binaries:

```sh
llvm=/workspace/toolchains/android-ndk-r27c/toolchains/llvm/prebuilt/linux-x86_64/bin
"$llvm/llvm-readelf" -h -l -S -d -n mesh/target/aarch64-linux-android/release/mesh-lab
"$llvm/llvm-size" -A mesh/target/aarch64-linux-android/release/mesh-lab
CARGO_HOME=/workspace/toolchains/mesh-cargo RUSTUP_HOME=/workspace/toolchains/mesh-rustup \
  /workspace/toolchains/mesh-cargo/bin/cargo tree --manifest-path mesh/Cargo.toml \
  --locked --offline --target aarch64-linux-android -e normal --prefix none
```

The one Java baseline build and D8 comparison:

```sh
review_dir=/workspace/scratch/radio-size-review
mkdir -p "$review_dir/baseline-e98efa8" "$review_dir/baseline-release"
git archive e98efa8274b1953ce43fb5709b090c620cca9e30 mesh/android-radio \
  | tar -x -C "$review_dir/baseline-e98efa8"
RADIO_BUILD_DIR="$review_dir/baseline-default" \
  JAVA_HOME=/workspace/toolchains/jdk-17.0.16+8 \
  ANDROID_SDK_ROOT=/workspace/toolchains/android-sdk \
  bash "$review_dir/baseline-e98efa8/mesh/android-radio/build.sh"
JAVA_HOME=/workspace/toolchains/jdk-17.0.16+8 \
  /workspace/toolchains/android-sdk/build-tools/35.0.0/d8 --release --min-api 29 \
  --lib /workspace/toolchains/android-sdk/platforms/android-36/android.jar \
  --output "$review_dir/baseline-release" "$review_dir/baseline-default/radio-classes.jar"
```

Archived measurement inputs, per-source hashes, raw seven-trial output and exact helper source are retained in [radio-size-review.json](../../mesh/evidence/radio-size-review.json). The paths below identify their original scratch locations, not required external downloads:

- `/workspace/scratch/radio-size-review/baseline-size.json`: per-source hashes, artifact digests, DEX maps/class descriptors.
- `baseline-default/dexdump.txt` and `baseline-release/dexdump.txt`: decoded code/source positions.
- `HostFragmentMeasure.java`: measurement helper, SHA-256 `22113dcabb9940bcf9841334dd537d633526eed94532e83ac82a923e8ab2f306`.
- `host-fragment.txt`: seven raw trials/MTU and helper bounds, SHA-256 `315561c94c4a75dbbd14b19a2ada6876b66375c071c649ac4ebfe36eb5cce55f`.
- `host-manifest.json`: host, source and scratch-record digests.

The host helper invocation was:

```sh
review_dir=/workspace/scratch/radio-size-review
java_dir=/workspace/toolchains/jdk-17.0.16+8
python3 - <<'PYCODE'
import json
from pathlib import Path
record=json.loads(Path('mesh/evidence/radio-size-review.json').read_text())
Path('/workspace/scratch/radio-size-review/HostFragmentMeasure.java').write_text(record['host_helper_java'])
PYCODE
"$java_dir/bin/javac" -source 8 -target 8 -Xlint:-options \
  -d "$review_dir/host-classes" \
  "$review_dir/baseline-e98efa8/mesh/android-radio/src/org/nearbymesh/radio/BleChunks.java" \
  "$review_dir/baseline-e98efa8/mesh/android-radio/src/org/nearbymesh/radio/CoreBridge.java" \
  "$review_dir/baseline-e98efa8/mesh/android-radio/src/org/nearbymesh/radio/CallbackBudget.java" \
  "$review_dir/HostFragmentMeasure.java"
"$java_dir/bin/java" -Xms128m -Xmx128m -XX:+UseSerialGC \
  -cp "$review_dir/host-classes" org.nearbymesh.radio.HostFragmentMeasure
```
