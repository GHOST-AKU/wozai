# BLE stability audit and release gate

Status: LE CoC replacement implemented; final verification and hardware acceptance pending. The previous preview.6 candidate is withheld;
no further phone test is requested until reviewed fixes and CI converge. User goal:
first-start automatic connection and automatic fault recovery, without repeated
manual stop/start. NIM4/private trust/wire/keys remain outside this change.

## Evidence and corrections

The owner supplied two complete preview.5 TXT reports (session1, no recent-event
loss). Sanitized facts and SHA256 input hashes are in
`mesh/evidence/ble-preview5-paired-summary.json`. Four message IDs match received
messages across the pair, two in each direction. Initial traffic really occurred,
but two ready links subsequently dropped; later attempts failed in client CCCD
with invalid_ingress_or_mtu and both peers ended in cooldown. This is not successful
cold-start/recovery acceptance. Per-device durations are reported; unsynchronized
wall clocks cannot produce trustworthy cross-device latency.

Already reproduced and corrected in candidate3dd3eb4: client indications can precede
descriptor acknowledgement, so CCCD-phase data must enter the bounded early buffer,
not be disconnected. Global server callbacks do not prove Mesh ownership: no
cancelConnection on an unowned callback. These fixes alone are insufficient release
criteria; independent reviews found additional lifecycle defects below.

## Architecture review findings

1. AUTO is not LE ownership. Public SDK36 BluetoothManager exposes only the
   two-argument openGattServer, whose transport is AUTO. Android13/14 native
   btif_gatts_open_impl selects BR_EDR for DUMO under AUTO. Therefore the prior
   server.connect hold can be on the wrong transport. It is not proof that the
   owner's device type cache was DUMO, but is a concrete unsupported assumption.
   Supported resolution: dedicated explicit TRANSPORT_LE client anchor per admitted
   peripheral Link, separate from the data client, with no MTU/discovery/data calls,
   its own callback identity/deadline and cleanup. No reflection/hidden API.
2. An indication completion is not guaranteed after disconnect. Native GattService
   can remove connId before onNotificationSent; the app then sees no completion.
   Waiting five seconds and permanently closing the entire adapter violates automatic
   recovery. Clearing the owner and reusing the same address is also unsafe: a late
   completion could credit new work. Resolution: bounded server-registration rebuild
   with fresh callback epoch checks at dispatch, explicit affected-link failures,
   preserved data-client links/scanner where possible, and bounded recovery attempts.
3. Core auth3s starts at loopback acceptance, but descriptor setup allows15s. The
   peripheral must not start Core while central is still awaiting CCCD success.
   Resolution: retain subscription state/buffer, await valid central first bytes,
   then start Core once. Keep a bounded first-byte/setup deadline; do not enlarge
   Rust authentication deadlines or accept unauthenticated messages.
4. Runtime reader's5s event watchdog races platform startRadio's permitted5s wait
   plus setup. Resolution: Ready processing uses remaining existing30s startup
   budget, steady events retain5s, cancellation still invalidates generations and
   closes late owners. Do not globally widen all watchdogs.

All resolutions require deterministic callback, absent-callback and timer tests;
these models verify ownership rules, not vendor native-stack behavior. Independent
source and whole-change reviews plus final-head signed CI precede delivery.

## Bitchat comparison and reuse boundary

Research reference: permissionlesstech/bitchat-android commit
998f1684878f1e8aaf2c4d5ad682795acd3cedad, GPL-3.0 (distinct from the earlier iOS
Unlicense reference). Read client/server/connection managers and mesh service;
no implementation is copied into this change. Root project is AGPL-3.0; this
review makes no claim that the Android source is Unlicense or drop-in reusable.

Its client receives packets without our erroneous CCCD-ready rejection. Its scan
manager distinguishes desired scanning from actual scanning, backs off transient
errors and includes liveness recovery; server advertisement recovery is separate.
These are useful engineering strategies. Its source also contains timed readiness
delays and pending-connection TODOs, so do not treat its behavior as a universal
hardware guarantee or copy arbitrary sleeps as a fix. Our transport must preserve
bounded ACK ownership, parser limits, independent Core identity and explicit relay
consent. Existing Core/routing libraries do not replace Android GATT lifecycle work.

Reference sources:
- https://github.com/permissionlesstech/bitchat-android/tree/998f1684878f1e8aaf2c4d5ad682795acd3cedad/app/src/main/java/com/bitchat/android/mesh
- https://github.com/permissionlesstech/bitchat-android/blob/998f1684878f1e8aaf2c4d5ad682795acd3cedad/LICENSE.md
- https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android14-release/system/btif/src/btif_gatt_server.cc
- https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android14-release/framework/java/android/bluetooth/BluetoothManager.java
- https://developer.android.com/reference/android/bluetooth/BluetoothDevice#connectGatt(android.content.Context,boolean,android.bluetooth.BluetoothGattCallback,int)

## Final device gate

After software verification converges, deliver one final signed APK. One paired
experiment should cover cold start, bidirectional messages, idle holding, one peer
stop/restart, partition/recovery and radio/lifecycle changes, exporting TXT at each
failure before manually restarting. This is still single-hop testing. Three-device
physical isolation/no shared AP/relay-off acceptance remains #91 pending.

## Final transport decision: public LE CoC (preview.6)

The GATT mitigations above are research findings, not the final product transport.
`BleCocAdapter` replaces the production GATT factory with API29+ public insecure
LE L2CAP credit-based sockets. Old GATT code and its regression fixtures remain in
`mesh/android-radio/research/gatt`; it is excluded from production compilation.
No hidden API, Bluetooth Classic fallback or NIM4 change is introduced.

A fresh service namespace advertises the assigned PSM only after listener binding.
Both devices must upgrade. Discovery labels are untrusted admission hints; existing
Core signatures authenticate messages. Public messages are not end-to-end encrypted.
A bounded preface and READY exchange precedes Core startup. Four slots cover
pending and active streams; per-stream ingress is 32 KiB. Setup is limited to15s,
individual writes to5s; closing the owned socket cancels blocked IO. Socket writes
are not end-to-end delivery acknowledgements. Listener repair preserves established
streams; callbacks and publication use epoch fencing. Global pools bound workers
across adapter restart. A vendor Binder call stuck inside socket creation cannot be
forcibly cancelled via public APIs: its single worker remains bounded, a timeout is
reported, and a late-returned socket is closed.

Host stream tests cover framing, deadlines, late ownership, capacity, duplex bytes
and no false link-up after Core startup failure. The integration test uses three
independent Rust Core processes and production stream lifecycle, covering signed
bidirectional relay, relay-off, reconnect and deduplication through pipe doubles.
These tests do not validate Android controller firmware, RF, simultaneous hardware
neighbors, background power consumption or infrastructure-free three-phone Mesh.
Both first-start and multi-device wireless acceptance remain pending.

## Local verification, 2026-11-03

Final working-tree checks: App `testDebugUnitTest` 50 tests, zero failures/errors/
skips; `lintDebug` zero errors; API36 Java/DEX compile passed; archived GATT20
regressions passed; controller Python23 tests passed; host transport programs and
CoC five groups passed; three-process Core integration passed. Commands are in
android-radio/README and android-next workflow. These are local results; only a
workflow run at the committed source revision counts as CI evidence.
