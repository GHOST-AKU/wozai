# Android Mesh diagnostic report

The Next public Mesh page exposes a snapshot through Diagnostics → Export TXT. The system document picker lets the user choose a
folder/provider and filename; the report is written as UTF-8 text containing JSON.
Canceling the picker writes nothing. Provider/permission failures show an error;
saving runs on an IO dispatcher, with no broad storage permission. This UI change
is for the next APK; the already delivered preview.4 still uses text sharing.
Nothing is uploaded automatically. The JSON schema is `nearby-mesh-diagnostics-v1`.

Included: application version/package; manufacturer/model/hardware identifier,
Android release/API/security patch/ABIs/core count; advertised BLE, Aware, Direct
and Wi-Fi capabilities; permission grant/not-applicable states; battery level/scale
and temperature, thermal status, power saving/idle/interactive/optimization exemption;
available system memory, Java heap occupancy and cumulative app process CPU time.
Features indicate OS-advertised capability, not successful topology or chip model.
Java heap and app CPU exclude the separate Rust process; neither is whole-app RSS
or a battery-consumption measurement.

Mesh data includes selected mode, start/run/relay/error state, direct-neighbor count,
and up to 200 message IDs with local states, timestamps and hop counts. Bodies,
origin keys and neighbor keys are omitted. IDs can correlate reports and should be
shared deliberately. Creation timestamps are not delivery timestamps or latency.

Each radio session retains at most 64 metadata events with monotonic elapsed times,
internal event/reason codes and adapter-reported byte-counter snapshots. BLE MTU
callbacks now emit the negotiated value as `mtu_N`; numeric GATT status/state codes
are preserved. Free-form error text, peer labels, MAC/IP/SSID, serial numbers and
keys are omitted. Old-session callbacks cannot append to a new session's record.
Dropped-history count is explicit. Counters are NOT additive totals or throughput,
and are not attributed to exported peer identities. The last session is retained
through stop for diagnosis, with its own mode/session/start time in the report.

No background sampler, extra wireless probe, recurring disk write or new permission
request is added. System properties are read on demand when opening Diagnostics.
Exporting saves that same frozen snapshot; reopen the dialog for a fresh report.
RSSI, distance, PHY selection, complete hop paths, end-to-end latency, radio power
and per-peer throughput are not measured by this version and must not be inferred.

For a test, save reports from all devices before and after a uniquely identified
message series; note phone placement, radio mode, relay settings, screen state and
whether Internet/shared AP were absent. Pair them with screenshots and the existing
physical-isolation protocol. A diagnostic JSON alone does not satisfy #91.
