# Apple ↔ Windows Bluetooth private chat

## Scope and user flow

This adapter carries existing NIM4 encrypted private sessions directly over Bluetooth LE GATT.
It supports Apple as central or peripheral and Windows Next as central. On iPhone/iPad/macOS,
open **Nearby → Bluetooth private chat → Discover and receive over Bluetooth**. On Windows
Next, open **Nearby → iPhone / Apple Bluetooth private chat → Search devices**, then **Connect**.
The iPhone receives the connection without a second Connect button. On two Apple devices,
enable discovery/receiving and select the other device on one side. Keep the apps foreground.
Existing first-contact consent, remembered root checks, security-code verification and message
receipts still apply. Discovery names are untrusted; choosing a name never verifies an identity.

No common access point, internet or Android relay is required by this carrier. This statement
describes the implementation; **Apple/Windows Bluetooth hardware validation is pending**.
Existing Android Bluetooth private chat uses RFCOMM and does not implement this new GATT
service. This change does not claim iPhone ↔ Android Bluetooth private interoperability.
It does not implement automatic trusted-contact dialing or retransmission after reconnect.

This branch is based on `main`, independent of public Mesh PR #103. It does not forward private
Noise sessions through Mesh or change NIM4, Noise, device identity, LAN or RFCOMM wire formats.
When combining with #103, retain both the public/private Bluetooth permission explanation and
all localization/privacy-resource checks. Public Mesh advertising coexistence also needs hardware
testing; separate successful builds do not prove two simultaneous peripheral managers work on
every Apple device.

## Platform decision and sources

- iOS ordinary apps cannot implement the existing generic classic RFCOMM socket interface.
  [Apple External Accessory](https://developer.apple.com/documentation/externalaccessory)
  applies to supported accessory protocols; it is not a generic Windows RFCOMM replacement.
- [CoreBluetooth peripheral publication](https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager)
  and [indications](https://developer.apple.com/documentation/corebluetooth/cbcharacteristicproperties/indicate)
  provide the shared API surface. Apple's public Mesh CoC implementation is a different carrier;
  no equivalent general-purpose WinRT LE CoC byte-stream API is assumed here.
- [Microsoft GATT client guide](https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/gatt-client)
  describes advertisement discovery, `BluetoothLEDevice`, characteristic discovery, writes and
  subscription callbacks. Windows Next remains an unpackaged WinUI 3 desktop app; no new GUI
  framework or packaging conversion is introduced. System Bluetooth policy/driver support may
  still refuse access. Packaged builds additionally need the documented Bluetooth capability.
- [Apple updateValue](https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager/updatevalue(_:for:onsubscribedcentrals:))
  returns false when its transmit queue is full; retry only after
  `peripheralManagerIsReady(toUpdateSubscribers:)`. Values larger than a central's maximum
  update length can be truncated, so this implementation fragments before calling the API.
- [Apple central writes](https://developer.apple.com/documentation/corebluetooth/cbperipheral/writevalue(_:for:type:))
  and [Windows GattWriteOption](https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattwriteoption)
  supply write-with-response semantics. No write-without-response or notification-only fallback
  silently weakens ordering/flow control.

Code is implemented in this repository against public API documentation. No third-party source
was copied and no dependencies or license obligations were added. Existing cryptographic
implementations and their notices are reused unchanged.

## Carrier contract v1

| Attribute | UUID | Operation |
| --- | --- | --- |
| Private NIM4 BLE service | `b326d8e0-8a5c-4cc2-a35f-4b1146fe1001` | Advertised service |
| RX, central → peripheral | `b326d8e0-8a5c-4cc2-a35f-4b1146fe1002` | Write with response only |
| TX, peripheral → central | `b326d8e0-8a5c-4cc2-a35f-4b1146fe1003` | Indications only |

One subscription is one ordered NIM4 byte stream. The central is the Noise initiator. Subscribe
before emitting any NIM4 bytes. Attribute values are `00 || 1…511 stream bytes`, or the single
byte `01` for close. Values are limited by the current platform value length, clamped to 20…512
bytes; usable payload is one byte smaller. The 20-byte minimum corresponds to ATT MTU 23.
Prepared/offset writes, empty data frames, unknown tags and oversize values fail closed. A future
incompatible carrier needs another service identifier, not reinterpretation of a live stream.

ATT receipts and CoreBluetooth enqueue success are not message-delivery receipts. NIM4 still
acknowledges only through its existing authenticated/persisted message path. After disconnect,
all transport state is discarded and the next connection has a new NIM4 handshake/nonce space.
The carrier does not cache or replay encrypted records. A close indication is best effort when
the OS queue is full; normal NIM4 liveness deadlines remain the fallback.

## Implementation and resource bounds

- `apple/Sources/WozaiCore/BLEByteStream.swift`: one reader/writer; at most 64 KiB unread bytes,
  48 KiB + 26 bytes per write; no per-fragment tasks or unbounded replay queue.
- `BluetoothPrivateRadio.swift`: serial CoreBluetooth queue, at most 32 discovered candidates,
  one active/setup private stream, 20-second setup deadline and a cancelable 10-second outstanding
  ATT operation timer. It waits for backpressure callbacks instead of spinning. Reconstructed
  central managers fence old callbacks on reconnect. iOS background entry closes the private
  session; returning foreground restores discovery only if the user enabled it.
- `windows-next/WindowsNext/Services/BleValueStream.cs`: fixed 64 KiB receive ring, one reader
  and writer, the same frame limits. `Bluetooth/PrivateBluetooth.cs` performs uncached GATT
  discovery, subscribes to indications, retains a GATT session and releases handlers/services
  on cancellation, error or disconnect. Windows shows only advertisement candidates until
  NIM4 authenticates the actual peer. Scan results are capped at 32; stop/start refreshes them.
- `desktop/…/NativePrivateLink.java`: a single ephemeral `127.0.0.1` listener, random 256-bit
  one-use capability supplied only over the owning child's JSONL control channel, 15-second
  accept and 3-second authentication deadlines. It is never advertised as a LAN endpoint.
  A wrong or incomplete capability fails before NIM4. Only one pending/current private session
  is admitted. Generation and owner-token checks prevent stale cleanup closing another session.
  The local socket has no LAN file-route evidence; the native link is labeled Bluetooth.

The IPC capability limits accidental/unsolicited local attachment; it is not a boundary against
malware already controlling the same user's process. Remote identity trust always comes from
NIM4. The native adapter never receives root private keys. Private BLE names/addresses are not
persisted as trusted identities or automatically replayable reconnect routes.

## Verification and limits

Local Java bridge integration: **284 checks passed**, including real independent JSONL children,
native capability rejection/cancellation, remote consent, bilateral encrypted messages and durable
ACK, stale teardown, and the original file/QR/read-receipt/EOF tests. An initial test incorrectly
targeted the LAN listener over loopback, which the existing policy rejects; the fixture was corrected
to use its actual private interface without changing the LAN policy or weakening assertions.

Swift regression tests use the production BLE stream and real NIM4 channels at minimum ATT
value size, test malformed input, overflow, cancellation, ordered close and bilateral text.
C# tests exercise the production ring across wraparound, 20/185/512-byte values, overflow,
cancellation and closure. `tools/test-ble-private-interop.sh` connects independent Java NIM4 and
Swift ChatSession processes through **both production C# and Swift BLE byte adapters**, using
a length-delimited TCP pipe to substitute ATT delivery only. This verifies framing/crypto
interoperability, not radio discovery, Windows driver behavior or iPhone background availability.

Commands:

```sh
JAVA_HOME=/path/to/jdk17 python3 windows-next/tools/build-bridge.py --test
dotnet test windows-next/Tests/WindowsNext.Transport.Tests.csproj -c Release
# macOS / Xcode:
bash tools/test-apple-lifecycle.sh
bash tools/test-apple-interop.sh
bash tools/test-ble-private-interop.sh
# Windows / installed SDK and Rust:
powershell -File windows-next/tools/build.ps1 -Test
```

CI results and exact revisions are recorded in the PR. Until those complete, this document does
not claim that native builds or the cross-language carrier test passed. No wireless throughput,
latency, power, distance or small-MTU file-transfer performance is inferred from these tests.
Large transfers on small ATT MTUs can hit unchanged NIM4 liveness deadlines and require real
measurement; no security timeout is relaxed to conceal that limitation.

## Hardware acceptance still pending

Use signed iPhone/iPad builds and the Windows artifact built from the same revision. Keep both
apps foreground and turn Wi-Fi off to establish that private traffic is using BLE. Cover:

1. Windows→iPhone discovery/connection, first-contact consent, verified identities, bidirectional
   text (including long Unicode), delivered/read receipts and multiple-message bursts.
2. Apple→Apple initiation; decline consent; a replaced identity must still be rejected.
3. Stop receiving, background/foreground, toggle Bluetooth, leave/re-enter range, disconnect
   during send and reconnect. No stale callback may close the replacement session or show a
   message delivered without its NIM4 ACK.
4. Different Windows Bluetooth adapters; access denied/radio off; MTU 23 and negotiated larger
   MTUs; sustained idle and a small file before larger files. Record actual bytes/time and failures.
5. Repeat with public Mesh enabled after both independent branches are integrated. Verify that
   private traffic and public relay policy remain separate.

No signed iOS distribution IPA or Apple/Windows RF result is produced by compile/test CI.
