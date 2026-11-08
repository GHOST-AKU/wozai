# Windows Next public Mesh preview.7

## Product use

Unzip the Windows Next portable package and open `WoZaiNext/WindowsNext.exe`.
The public Mesh page starts the bundled native Core and system Bluetooth discovery;
no Python, terminal, external USB adapter, WinUSB change, address entry or separate
Mesh process startup is part of this path. A working Windows Bluetooth Classic
adapter and normal OS permissions are required. Start/stop and system Bluetooth
settings remain accessible when Bluetooth is disabled or denied.

Install the matching persistent-certificate Android preview.7 over the existing
Next installation. In Nearby, enable Bluetooth Mesh and allow the system Bluetooth
visibility prompt for initial PC discovery. If visibility expires before first
connection, use Make visible to computers without stopping existing Mesh links.
Previously discovered peers remain candidates for reconnection. Relay starts off
and must be enabled explicitly on a device intended to forward messages.

The Windows GUI retains private chats/settings, with its Java private-chat process
started only when a private feature is opened. Public Mesh uses the existing WinUI
framework and the small native Rust Core; no extra GUI/Python/JVM runtime is added
for Mesh. The portable app still bundles its existing private-chat runtimes so
those features work offline. Exact package/Core sizes are measured by CI, not
estimated. WinAppSDK now references the self-contained WinUI component package,
not the complete Runtime or umbrella package which also includes AI/ML. This follows Microsoft's guidance in
https://github.com/microsoft/WindowsAppSDK/issues/6464 and
https://github.com/microsoft/WindowsAppSDK/issues/6015 . No DLL is deleted after
publish; the build rejects unexpected ONNX/DirectML payloads and real UI capture
rechecks the resulting runtime. Jlink uses its supported compression level2,
retaining all existing Java modules/locales. PE dependency inspection found the
Rust MSVC child imports VCRUNTIME140.dll; the packager places the already bundled
JDK's redistributable beside that child, retaining its notices, so a separate VC
runtime installation is not assumed. This is a portable preview, not an installer or signed Windows release.

## Transport and boundaries

Windows uses public WinRT RFCOMM service provider, unpaired DeviceWatcher and
GetRfcommServicesForIdAsync(Uncached), with a dedicated UUID
`27a34e22-69d1-4d97-93a4-12f231df9ab8`. Microsoft documents unpaired RFCOMM support:
https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/bluetooth-dev-faq
https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.bluetoothdevice.getdeviceselectorfrompairingstate
https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.bluetoothdevice.getrfcommservicesforidasync

Android Bluetooth mode keeps the successful LE CoC path and adds passive classic
RFCOMM ingress, with two compatibility slots and separately bounded process-wide
workers. No NIM4 service UUID, Noise session, identity or private trust is reused.
Both stream adapters use the same versioned NMLC/READY admission preface, followed
by the unchanged Rust signature/authentication/message protocol. Hint labels and
radio connections do not populate authenticated neighbors; Core events do.

Bluetooth mode can relay between its BLE and Classic neighbors; Wi-Fi Aware is
still a separate mode and no Bluetooth/Wi-Fi mixed routing is introduced. Android
preview.6 does not provide the new RFCOMM service and is not a Windows native-radio
peer for this build. The prior Bumble/USB research adapter remains separate and is
not packaged in the GUI product. Do not use its instructions for Windows Next.

Public messages are readable by neighbors/relays and are not E2EE. Automatic
connection is not automatic NIM4 trust. The Windows listener requests discoverable
advertising only while Mesh is active; Android visibility is granted through the
OS dialog. No pairing UI, radio enablement, driver or firewall policy is bypassed.

## Engineering controls

- At most four Windows pending/active sessions, process-wide capacity retained by
  old jobs until they actually finish. Thirty-two queued radio callbacks,
  sixteen discovery candidates and sixty-second candidate retries.
- Setup/handshake deadlines, one4096-byte block per pump and five-second writes;
  device-local loopback bridges never substitute for a wireless connection.
- Windows child stdin/stdout are private, UTF-8, bounded at128 queued events and
  16Ki characters per event; at most four pending sends and200 display messages.
- Draft clears only after the Core acknowledges local queueing. Handed status is
  explicitly neighbor handoff, not network-wide delivery. Expiry/stop are visible.
- Separate owner-only Mesh state ACL; reparse points/shared state are rejected.
  Seeds are software plaintext, not TPM/DPAPI protected. Parent paths must be
  trusted. Job Object kill-on-close owns the Core even on UI process termination.
- Stop closes listeners/advertisements/sockets and cancels pending operations.
  Incomplete cleanup is reported. The app does not claim cancellation can always
  force unresponsive vendor firmware to return.

## Verification and hardware gate

Native Windows tests exercise the real production stream handshake/pumps, including
three independent Rust Core processes, Chinese/Emoji bidirectional messages,
relay-off across the full TTL, malformed prefaces and exact rejection reasons,
and real Job Object child termination. Existing NIM4 bridge/transport/native UI
checks remain in the Windows build. The actual Mesh page is captured in WinUI.
Android runs its existing suite, radio API/DEX compilation and signature verification.
Final source/run/artifact evidence is recorded in PR99.

Virtual/loopback and GUI tests do not prove RF interoperability. The owner has
since reported successful Mesh and relay operation after the Windows/Android
preview.7 delivery. Device roles, isolation, relay-off and per-hop evidence are
not yet captured; formal hardware acceptance remains pending. See the
[field report and PR evidence index](field-validation-2026-11-08.md).
