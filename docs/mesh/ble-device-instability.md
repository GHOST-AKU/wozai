# BLE device report triage — preview.2

User report: Xiaomi 23043RP34C, Android14/API34, BLE supported, Aware unsupported,
Direct advertised but not implemented by this preview. Bluetooth permissions granted;
foreground, no power saving, thermal status0, battery31.2°C. These observations do
not establish that background restrictions or precise-location denial caused drops.

The supplied central-side trace shows MTU517 and real ATT traffic. Repeated link-up
to disconnect intervals are 5015ms,5007ms,4978ms; status19/state0 is compatible with
remote-user termination (0x13), not a diagnosis of why the remote app/stack closed.
Later attempts stop after MTU with no link-up, and their numeric failures are
redacted. A second peer_seen event may be another candidate: exported v1 lacks peer
correlation, so it cannot establish that the same peer bypassed retry cooldown.

Confirmed observability defect: safe diagnostic filtering hid cccd_write_N,
att_ack_N, server_connection_N_N and attempt_N. Regression reproduces this loss.
These bounded structured codes are now allowed; raw exception text/IP/keys remain
excluded. The adapter additionally emits ble_failed_stage before link-down so
connecting/MTU/services/CCCD/Core/data-stage failures can be distinguished.

This correction does not claim to fix the physical disconnect. Collect the other
endpoint's same-time report and confirm both APK versions before changing timeout,
retry or security budgets. In particular, a periodic five-second disconnect must
not be treated as proof that longer deadlines solve the cause. On this device,
Wi-Fi Aware is unavailable; FEATURE_WIFI_DIRECT alone supplies no data adapter.
The existing preview.2 APK does not include this later diagnostic correction.

The second endpoint (Xiaomi M2102J2SC/API33, also preview.2) confirms the same
message ID received at one hop. Its peripheral trace repeatedly drops near five
seconds and then rejects known central reconnections for minutes. The rejection
is reproducible in the actual adapter host fixture: after cooldown, server admission
still requires a new scan callback to reset attempts and rejects stale discovery.
Known incoming connections now count as fresh presence after cooldown, under the
existing role, neighbor-count, ACK-tombstone and authenticated-Core constraints.
Unknown addresses remain rejected; an incoming request cannot bypass cooldown.
Six production BLE scheduler/admission fixtures and seven host programs pass.
Numeric-status diagnostics regression also passes. The five-second initial drop
cause is still unresolved; preserving its status and failed phase is necessary.
