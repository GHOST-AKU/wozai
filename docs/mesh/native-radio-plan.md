# Android BLE / Wi-Fi Aware Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement task-by-task.

**Goal:** Deliver separate executable Android native BLE and Aware adapters connected to the existing independent Mesh Core.
**Architecture:** One radio mode per bounded instrumentation run. Each physical neighbor carries unchanged Rust wire bytes through a device-local loopback bridge; no cross-radio forwarding.
**Tech Stack:** Android29+/Java8 APIs, SDK36/JDK17/DEX35, Rust1.88 + official Android NDK arm64 API29, existing locked Cargo graph.
**Spec:** [native-radio-design.md](native-radio-design.md)

## Global Constraints

No mixed mode, no NIM4/Noise/production client change, no temporary APK signing, no fabricated hardware evidence. Preserve #85 head and use a dependent research branch. Existing AGENTS.md rules apply; do not repeat builds without source changes or diagnostic reason.

## Review Focus

- Native callbacks after close must not reopen listeners/GATT/network requests.
- BLE ingress uses arbitrary byte chunks, not message boundaries; default MTU and CCCD sequencing must preserve bytes.
- Aware unavailable/IPv6 metadata missing must fail, never use default LAN sockets.
- Queue overflow or slow radio must bound memory and fail/close rather than accumulate tasks.
- Local loopback IPC is neither wireless evidence nor a private NIM4 trust grant.

### Task1: Shared runner/bridge

Files: android-radio/src/.../RadioConfig.java, RadioAdapter.java, RadioEvents.java, CoreBridge.java, RadioLab.java; android-radio/build.sh, AndroidManifest.xml; tests/RadioConfigTest.java, CoreBridgeTest.java.
Interfaces: parse(Map<String,String>)->RadioConfig; CoreBridge(port,Sink,Runnable), start(), receive(byte[])->boolean, close().
- [x] RED: compile host fixtures against absent classes; assert mutually exclusive mode/labels/ranges, byte preservation, overflow, repeated close/closed-before-start.
- [x] GREEN: implement bounded loopback bridge and headless explicit native runner.
- [ ] Verify pure host tests and one SDK36 Java+DEX build after adapter integration.

### Task2: BLE native adapter

Files: BleAdapter.java, BleChunks.java, tests/BleChunksTest.java.
Interfaces: BleAdapter(Context,Handler,RadioConfig,RadioEvents) implements RadioAdapter.
- [x] RED/GREEN: defaultMTU23/negotiatedMTU fragmentation and bounds byte preservation tests.
- [x] Implement scan+advertise/GATT, deterministic dial role, write response/indication acknowledgements, bounded retries/links and cleanup.
- [x] Compile against shared APIs; record Android source/docs references and unexecuted device gates.

### Task3: Aware native adapter

Files: AwareAdapter.java, AwareOffer.java, tests/AwareOfferTest.java.
Interfaces: same RadioAdapter constructor; per-neighbor NDP Network binding.
- [x] RED/GREEN: bounded discovery label/version parsing with malformed inputs.
- [x] Implement publish/subscribe, responder port+PSK, requested NDP, scoped IPv6/port retrieval, Network-bound sockets and lifecycle cleanup.
- [x] Compile with Android36 and document unsupported/concurrent-limit failures, never WLAN fallback.

### Task4: Android core build and device reproducibility

Files: tools/build-android-core.sh, android-radio/README.md, tools/radio-lab.py and its controller tests.
- [x] Use existing reusable toolchain or one checksum-verified official NDK; build existing locked core once for aarch64-linux-android API29. Inspect ELF ABI/interpreter, hash actual artifact.
- [x] Provide USB control-only deployment/run commands, A/B/C and relay-off/expiry experiment, separate BLE/Aware modes; never install/route data over ADB automatically.
- [ ] Run boundary tests, final source comparison, fresh review; record exact commands/results in dependent Draft PR and #86/#89/#91. Keep all actual wireless gates pending if hardware absent.

Execution ledger/evidence: [native-radio-verification.md](native-radio-verification.md). Final full compile and CI links belong to the dependent Draft PR; device acceptance remains pending, not an implementation checkbox.
