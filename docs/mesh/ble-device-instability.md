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

## Preview.3 paired trace and explicit server ownership experiment

Both endpoints now show successful ATT acknowledgements and bidirectional one-hop
message receipt. Peripheral disconnect is server_connection_0_0 at stage ready;
central sees gatt_connection_19_0. Cooldown recovery is observed working. Neither
trace shows an app-level ACK timeout as the recorded first failure.

AOSP distinguishes connection callbacks from application link ownership:
- gatt_main.cc `gatt_send_conn_cback` notifies all registered apps but adds holds
  only for apps in the connection-manager set; `gatt_update_app_use_link_flag`
  disables idle expiry while holds exist and arms expiry when no apps hold it.
- gatt_api.cc `GATT_Connect` establishes the calling application's hold.
- Public Android BluetoothGattServer.connect(device,false) is used only after our
  existing peer/role/cooldown/capacity admission; duplicate callbacks cannot reacquire.
  Existing cancelConnection/close cleanup releases it; no background autoConnect.

Sources (read full implementations, no copied code):
https://android.googlesource.com/platform/system/bt/+/915b24bc5ce75c419d41dfec59cb40bc1b568db2/stack/gatt/gatt_main.cc
https://android.googlesource.com/platform/system/bt/+/915b24bc5ce75c419d41dfec59cb40bc1b568db2/stack/gatt/gatt_api.cc
https://developer.android.com/reference/android/bluetooth/BluetoothGattServer#connect(android.bluetooth.BluetoothDevice,boolean)
AOSP is Apache-2.0. Its referenced no-app idle constant is one second, not five;
this supports an ownership mechanism, not proof of the vendor's exact timing or
that this alone explains this report. The device fix remains an experiment pending
paired-phone retest. No idle-timeout constants or authentication deadlines are enlarged.

The production-adapter host regression fails before the hold is added and passes
with it. It verifies unknown peers are not held, one hold per admitted link,
recursive/duplicate callback safety and immediate slot release on hold rejection.
These are API-usage/ownership checks, not simulation of vendor native idle timers.

## Cold-start failure remains separate

User clarified that preview.4 initially fails to connect and requires stopping then
starting Mesh. Its later paired reports show one neighbor each, five matching
received message IDs, and only normal heartbeats in retained ~93-second windows.
Earlier events were evicted: session elapsed time near400s cannot establish a
400-second uninterrupted connection or successful cold start. No cold-start fix
is claimed from this evidence.

Reports now retain the first32 events alongside the latest64. Admission refusals
separate peer_not_discovered, peer_already_linked, retry_cooldown, connection_limit,
previous_indication_pending and role_order. Numeric scan/advertise/service-registration
failure codes are retained. The recent-history drop counter still describes the
latest64 ring; retained startup events are an additional prefix, not disjoint totals.
Regression verifies that1000 later traffic events cannot overwrite startup and
that a new radio session clears both histories. TXT export should be used before
stopping a failed first attempt, and again after a successful restart. Neither an
arbitrary startup delay nor an automatic restart loop is introduced without cause.
