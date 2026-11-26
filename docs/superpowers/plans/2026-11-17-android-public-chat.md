# Android public chat implementation plan

Execute independently in the primary agent, as required by AGENTS.md and the owner.

**Goal:** Simplify Android Next public chat and deliver an upgrade-compatible, pinned-certificate APK.
**Architecture:** Keep Compose navigation, MeshService, bounded process-local history and the existing Rust/radio paths. Add a hardware capability model, simplify MeshScreen and move controls to an options sheet.
**Spec:** [Design](../specs/2026-11-17-android-public-chat-design.md).

## Tasks

1. Add capability tests for unsupported API/ABI, BLE-only/Aware-only hardware and default mode selection; implement MeshCapabilities and use the same support check in the service guard and UI.
2. Update Compose regressions for hidden controls, first-use notice/cancel, persistent acknowledgment, private navigation, diagnostics and actual connection/message status. Implement the compact timeline, scrollable consent and options, preserving draft acknowledgment and lifecycle ownership.
3. Test/document existing 200-message process-local history across runtime recreation and stop. Show retention rules in the UI; leave persisted private data and public identity profile untouched.
4. Generate preview.8/build23, inspect diff/localization and run relevant Android unit tests/lint plus the signed installation build once via existing CI. Record source revision, workflow and public signature evidence in the review PR; retrieve and locally verify the delivered APK. Do not merge or publish.

## Review focus

- Denied permissions, disabled hardware and canceled discoverability must not produce a false connected state.
- Rotation during permission/notice flows must not lose the pending request or silently enable relay.
- Message handoff must never claim delivery/read or trusted identity.
- Process restart must not imply durable public history; stopping alone must retain it.
- Long translations/large font must keep consent and options scrollable and actions reachable.
