# Windows Mesh lab (experimental, no GUI)

This package contains a native Windows Rust Core and a separate Python BLE LE CoC
client adapter. It does not add Mesh to the WinUI client. **Hardware validation
pending**: the Windows native Bluetooth stack is not used for CoC. A dedicated USB
Bluetooth controller supported by Bumble, already assigned to WinUSB by its owner,
is required. This tool never installs/replaces drivers or enables a system radio.
Do not change the driver of an in-use/internal Bluetooth adapter to try this lab.

Research and alternatives: [Windows port](https://github.com/GHOST-AKU/wozai/blob/research/windows-mesh-port/docs/mesh/windows-port.md).
Windows 10/11 x64 and Python 3.12 are the initial lab target. Python/runtime and USB
driver are not bundled. The executable is not Authenticode signed. Source revision,
file hashes and hardware status are in `build-info.json`.

## Run with Android preview.6

Use a dedicated controller already prepared per the [Bumble Windows guide](https://google.github.io/bumble/platforms/windows.html).
In PowerShell, inside this directory:

```powershell
py -3.12 -m venv .venv
.venv/Scripts/python -m pip install -r requirements.txt
.venv/Scripts/bumble-usb-probe
# Terminal 1: independent private state, relay is an explicit choice.
./run-core.ps1 -Relay
# Terminal 2: choose the actual dedicated controller index shown by usb-probe.
.venv/Scripts/python nearby_coc.py --transport usb:0 --seconds 300
```

The launcher protects new state at `%LOCALAPPDATA%/NearbyMeshLab/state` with an
owner-only inherited file ACL, and rejects existing unprotected directories. Run
from an owner-controlled parent. Directly launching `mesh-lab.exe` does not apply
Windows private ACLs automatically. Software seeds are not DPAPI-protected and
administrators can still take ownership. Never point this lab at NIM4 state.

Enable BLE Mesh on Android preview.6. The Windows adapter scans its service data,
connects as central, verifies the NMLC/version/label/READY transport preface and
bridges the byte stream to `127.0.0.1:39770`. Core signatures authenticate peers;
labels/PSM and `channel_ready` do not. There is no network radio fallback. The local
TCP connection is only IPC on this computer. Public messages are not E2EE.

In terminal 1, enter JSON commands, one per line:

```json
{"cmd":"neighbors"}
{"cmd":"send","text":"Windows Mesh lab","ttl":30,"hops":3}
{"cmd":"diagnostics"}
{"cmd":"quit"}
```

A 300s session closes automatically; Ctrl+C stops it early. Stop the adapter before
quitting Core to avoid intentionally triggering radio retry. Raw Core stdout
contains public message bodies and public identities: do not share it as a safe
diagnostic. Adapter JSON excludes addresses, labels, PSM, bodies and exceptions.
Counters are session bytes at this adapter, not radio airtime or end-to-end delivery.

## Bounds and limits

- Client/central only, with a random `-xxxxxx` hint sorting below Android's hex hints;
  no advertisement/peripheral implementation. Two Windows clients cannot discover
  each other with this adapter. Up to four Android connections can attach to one
  Core and be relayed only when explicitly enabled.
- Sixteen candidates/four sessions, single concurrent HCI dial, no reconnect without
  a fresh observation; backoff1/2/4s then60s cooldown. Advertising hints are untrusted
  and can exhaust these bounded slots; no Sybil immunity is claimed.
- Connect/channel/preface15s after dial admission; connect10s, writes5s, local
  Core connect3s. Queued dials need an observation within30s. CoC MTU/MPS2048 and
  eight initial credits. One4096byte egress block before drain, ingress32KiB with
  fail-closed disconnect on overflow; CoreBridge is not copied.
- Bumble0.0.219 `pause_reading` is a no-op. The adapter limits its own queues and
  closes on overflow rather than claiming upstream credit suspension. Upstream
  scanner caches and controller/USB buffers are not a total-process memory bound.
- RF, firmware support, Windows USB behavior, simultaneous hardware neighbors,
  power use and Android interoperability are pending. Virtual tests are not RF.
- Python package pin is explicit; transitive versions are recorded by CI in
  `windows-python-resolved.txt`, not claimed fully reproducible across all platforms.

## Build/test

```powershell
cargo build --manifest-path mesh/Cargo.toml --release --locked
cargo test --manifest-path mesh/Cargo.toml --locked
$env:MESH_BINARY = "$pwd/mesh/target/release/mesh-lab.exe"
py -3.12 -m unittest discover -s mesh/tests -p test_lab.py -v
py -3.12 -m unittest discover -s mesh/windows-radio/tests -v
```

The radio tests require Bumble. They use real LE credit channels over virtual HCI
controllers and three real Rust Core processes. They check fragmented prefaces,
duplex payloads, signed A-B-C/C-B-A, relay-off with expiry, input bounds, write
stall, EOF, malformed prefaces, cancellation/late owners and admission limits. The existing Core process suite checks restart,
replay, loops, malicious input and duplicate connections on Windows loopback. It
does not isolate A/C at the OS/radio level. Physical A/C exclusion and relay-off
hardware experiment remain separate acceptance gates.
