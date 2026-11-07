# Windows Mesh port: research and first executable lab

The current GUI product path is documented in [Windows client](windows-client.md).
The USB/Bumble route below is retained as research history and is not the GUI runtime.

Decision date: 2026-11-07. Independent experimental module; no WinUI integration,
NIM4 change, Android transport change, release or merge. Android preview.6 is the
compatibility baseline. The owner's later report of stable multi-device Android
relaying is user-reported field feedback, not Windows interoperability evidence.

## Public API evidence

1. [Microsoft Bluetooth socket documentation](https://learn.microsoft.com/en-us/windows/win32/bluetooth/bluetooth-and-socket)
   specifies AF_BTH/SOCK_STREAM and **BTHPROTO_RFCOMM** as supported protocol.
   This is not an LE credit-channel socket API. Existing Windows Classic private
   chat code therefore cannot simply connect to Android's dynamic CoC PSM.
2. [Windows Bluetooth namespace](https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth?view=winrt-26100)
   exposes adapter/device APIs, GATT and RFCOMM namespaces. Its documented surface
   does not supply a matching Android listen/createInsecureL2capChannel API.
   [Watcher](https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.advertisement.bluetoothleadvertisementwatcher?view=winrt-26100)
   can observe advertisements; discovery alone does not transport Mesh messages.
3. [Windows11 profile support](https://learn.microsoft.com/en-us/windows-hardware/drivers/bluetooth/general-bluetooth-support-in-windows)
   lists L2CAP, but this is OS protocol support, not proof of a public application
   CoC interface. [Profile driver DDIs](https://learn.microsoft.com/en-us/windows-hardware/drivers/bluetooth/using-the-bluetooth-driver-stack)
   use internal BRBs/IRPs. They are not user-mode sockets; these pages also do not
   prove that a custom driver would support this particular LE credit mode.

Conclusion is deliberately narrow: direct native user-mode CoC support has not
been established with the reviewed public SDK. Do not claim Windows lacks BLE or
all L2CAP, or that a vendor-specific driver can never offer another path.

## Selected experimental alternative

[Bumble Windows documentation](https://google.github.io/bumble/platforms/windows.html)
explicitly supports a dedicated USB Bluetooth controller assigned to WinUSB.
[USB transport](https://google.github.io/bumble/transports/usb.html) runs a separate
host stack. Bumble is Apache-2.0; selected version0.0.219 has public LE CoC
[implementation](https://github.com/google/bumble/blob/v0.0.219/bumble/l2cap.py)
and virtual controller/link facilities. Existing LGPL/GPL/native driver boundaries
are not bypassed or copied. No driver replacement is performed by this project.

Implement `mesh/windows-radio/nearby_coc.py` as a central-only adapter:
Android service-data UUID/PSM -> LE connect -> credit channel -> exact existing
NMLC/READY preface -> local Core IPC. Random labels sort before Android hex labels;
Android already accepts an admitted incoming CoC without a reciprocal scan.
Core signatures and explicit relay consent remain authoritative. Adapter hints
and a ready channel grant no NIM4 trust. The protocol is public plaintext.

This is a usable experimental route for Windows with prepared dedicated hardware;
it does not promise operation using the internal Windows adapter. It also works
on other Bumble USB hosts, so its portability is tested separately from Windows OS.

Other alternatives: a separately designed native GATT adapter on both ends could
use standard WinRT, but cannot interoperate with the current CoC-only preview by
itself; RFCOMM likewise needs an Android Mesh adapter. Both would require explicit
new transport testing. Wi-Fi Direct is another separate adapter, not a CoC fix.
A custom kernel driver has distribution/signing/support costs and is not selected
for the first desktop prototype. None is silently substituted for BLE CoC.

## Core port and state

Use the same Rust1.88 Core and lockfile. Move Linux DNS-SD worker implementation
behind target cfg so Windows static checks fail neither on unreachable code nor
Linux-only imports; `--discover` remains explicitly unsupported outside Linux.
Keep explicit/native candidates and loopback transport available.

Reject Windows reparse points (including junctions) at checked state entries;
close snapshot temporary handles before replacement. Native regression verifies
repeated snapshot replacement and a real Windows junction rejection. Existing
Unix permission/symlink coverage is retained. State paths must be in trusted,
owner-controlled parents: this is not a general race-proof sandbox against a
concurrent local attacker. Parent traversal/hardlink hardening and platform key
vaults remain outside this prototype.

Windows launcher creates owner-only ACLs before Core creates seed files and
rejects existing shared state. Direct CLI calls do not automatically apply this
ACL. Seed contents remain software plaintext; no DPAPI/TPM claim. Lab directories
must remain separate from NIM4 and production application data.

## Verification and remaining gates

Local Linux: Rust fmt/Clippy passed;47 Rust tests passed. Eight radio tests passed,
including cancellation/late-owner and malformed-preface checks, real Bumble credit channels and three independent Rust processes for
signed bidirectional relay plus relay-off/expiry. These are virtual controllers,
not USB or RF. Dedicated Windows workflow builds/tests the native executable,
runs the existing independent-process suite, runs the same virtual CoC tests,
checks the private-state launcher and packages source-bound lab inputs. Actual
workflow run/source/artifact links belong in the PR; no historical run is passed
off as this port's CI.

Hardware pending: prepared Windows USB controller to one Android preview.6 peer;
then two simultaneously connected Android peers; then A/C physical exclusion with
Windows B relay, relay-off full TTL negative and reverse messages. Preserve failure
reports and controller/vendor/OS/driver versions. No throughput/energy/range numbers
have been measured, and no three-phone or Windows wireless gate is closed here.
