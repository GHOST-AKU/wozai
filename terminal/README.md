# NearbyIM Terminal

Independent native terminal client for **我在 / NearbyIM**, executable **`nearbyim`** (`nearbyim.exe` on Windows). Uses the shipping **NIM4** protocol. No Java runtime, GUI, Android SDK, cloud account, telemetry or relay is needed to run it. [中文](README.zh-CN.md).

This is development version **0.1.0-dev** under [PR #41](https://github.com/GHOST-AKU/wozai/pull/41), based on [Issue #38](https://github.com/GHOST-AKU/wozai/issues/38) and the supplied [design v0.2](docs/design-v0.2.md). It is not a completed device/platform acceptance or a stable terminal release. Existing GUI clients and v0.3.2 release assets remain separate.

## Build and install

Requires Rust 1.88+ and a C compiler for bundled SQLite. Run from the repository root:

```sh
cargo build --manifest-path terminal/Cargo.toml --locked --release
cargo install --path terminal --locked
nearbyim --version
```

The executable is `terminal/target/release/nearbyim[.exe]`. CI builds independent Linux x64 and Windows x64 archives containing the executable, manuals, shell completions, dependency licenses and SHA-256 digest. Download only artifacts from a trusted workflow/commit. Extract into your user applications directory and add that directory to PATH; no `wozai` alias is installed or overwritten. Linux binaries built on Ubuntu 22.04 require glibc 2.35+; they are not advertised as universally static binaries. Tag events `terminal-v*` build archives without replacing historical Android/Desktop release assets.

## Start safely

```sh
nearbyim
nearbyim config list
nearbyim config set receive_dir /home/me/Downloads/NearbyIM
nearbyim config set lan_listen true
nearbyim config set auto_receive_trusted false
```

On first TUI launch, setup explains its independent identity, asks for a nickname, receive directory and listening policy. **LAN listening and automatic trusted-file acceptance default to off.** A CLI query may create the profile without opening a network listener. TUI scans are explicit and bounded; use the `scan` command. `listen` is an explicit foreground receiver; it does not start a daemon. By default it stops at the operation deadline; use `--timeout 0` to keep running until interrupted.

```sh
nearbyim peers scan --timeout 8s
nearbyim peers show DEVICE_UUID
nearbyim chat DEVICE_UUID
nearbyim chat --address 192.168.1.20:47832
nearbyim listen --lan --timeout 0
nearbyim connect --address 192.168.1.20:47832
```

`chat` opens the TUI. `connect` is a foreground line client (use `/quit`, `/file PATH`, `/pause ID`, `/resume ID`, `/cancel ID`); exiting closes its connection. There is no invisible persistent connection. One active authenticated session is allowed; close it explicitly before switching peers. Multiple local histories are retained. Searches and connections under SSH occur on the machine executing the program.

Discovery uses `_nearbyim._tcp.local.` on LAN interfaces, including Ethernet. Addresses/UUIDs/nicknames in mDNS are untrusted hints; only the authenticated root key can establish identity. Direct addresses work when multicast is blocked. IPv4 discovery and explicit IPv4/IPv6 LAN addresses are supported; link-local IPv6 addresses need their interface scope. VPN, firewall and AP client isolation can prevent LAN communication.

## TUI

Quiet Terminal themes: `--theme midnight` (default), `paper`, `sage`, `mono`, `system`; `--no-color` / `NO_COLOR`; `--ascii`. `config set color auto|truecolor|256|16|none` controls fallback. At 110+ columns, chats/messages/details use three columns; 72–109 use two; 45–71 use one. Below 45 columns or 16 rows, enlarge the terminal to edit safely.

- Tab / Shift+Tab: focus; arrows or j/k outside composer: select.
- Ctrl+K / Ctrl+P: command palette; `?` outside composer: help; `/` or Ctrl+F: search.
- Enter: open/send; Ctrl+J or Alt+Enter: newline; bracketed paste never sends on pasted newlines.
- Left/right/backspace/delete edit Unicode grapheme clusters, not UTF-16 units. Text is limited to **8192 UTF-8 bytes**.
- `a` outside composer: attach by path; confirm recipient/size before sending.
- Esc: close palette, cancel scan, return; Ctrl+C / q outside composer: exit; active transfers require exit confirmation and are checkpointed.

Palette: `chats`, `scan`, `transfers`, `security`, `settings`, `help`, `connect HOST:PORT`, `disconnect`, `listen on|off`, `file PATH`, `pause ID`, `resume ID`, `cancel ID`, `code`, `verify FULL_CODE`, `unverify`, `search TEXT`, `set KEY VALUE`, `quit`.

IME composition and emoji display still depend on the terminal. Automated cell-width/grapheme/PTY tests are evidence for those cases, not a claim that all Windows/Linux terminal/IME combinations have passed.

## Security and trust

NIM4 uses maintained Snow/RustCrypto libraries: Noise XX / X25519 / AES-256-GCM / SHA-256 and P-256 persistent root proofs. Both claims, role, capabilities and transcript are bound; the ten-byte record envelope is authenticated. There is no NIM3/plaintext fallback. The terminal advertises base text/file-v2 capabilities only; Bluetooth RFCOMM, automatic Wi-Fi Direct and avatars are not advertised as supported.

First connections require **once / remember / reject** consent. Remembered consent is TOFU, separate from manual verification. Compare the complete safety code using a different trusted channel before marking it verified. Unknown/unremembered peers cannot be approved by piped input or JSON mode. Changed pins fail closed. Revoking trust does not silently accept a replacement key; explicitly forget the old pin only after investigating the change.

```sh
nearbyim identity show
nearbyim identity verify DEVICE_UUID
nearbyim trust list
nearbyim trust revoke DEVICE_UUID
nearbyim identity forget DEVICE_UUID
```

The default profile is the OS local-data directory's `nearbyim-terminal` (Linux XDG / Windows LocalAppData). `--profile` selects another independent terminal identity, **never a GUI identity import**. Unix uses a 0700 profile and 0600 identity; Windows encrypts private key material with current-user DPAPI. **Message history and received files are not encrypted at rest.** Protect the OS account and backups. Corrupt/missing established keys stop startup instead of rotating identity. A profile lock prevents concurrent TUI/CLI writes (`PROFILE_BUSY`); no IPC is implemented.

Back up only while the client is closed: copy the whole profile and keep received files if you need transfer recovery. Windows DPAPI backups depend on the original user's Windows protection material; copying `identity.json` to a different account is not a supported migration. Diagnostics do not print private keys, message bodies or safety codes. Explicit chat history and identity verification operations reveal the data they request. Untrusted ANSI/OSC/Bidi controls are made safe before terminal output; received files are never auto-executed.

## Text, files and scripts

```sh
nearbyim chats list
nearbyim chats show DEVICE_UUID
nearbyim send DEVICE_UUID --text '你好'
printf 'hello\nworld' | nearbyim --json send DEVICE_UUID --stdin
nearbyim --json --timeout 5m send DEVICE_UUID --file ./demo.zip
nearbyim files list
nearbyim files resume TRANSFER_UUID
nearbyim files cancel TRANSFER_UUID
nearbyim status --json
nearbyim doctor --json
nearbyim completion bash
nearbyim completion zsh
nearbyim completion powershell
```

Establish remembered consent interactively before scripted sending. `--address HOST:PORT` can route a specific peer without weakening consent or identity checks. JSON mode emits **one** object to stdout with `schema`, `ok`, `status`, `operation`, UTC ISO timestamp, identifiers and an `error` object on failure. Human progress goes to stdout; diagnostics/errors go to stderr. Non-TTY never opens the TUI or emits ANSI animation. `status` reports `not_running` because it does not query a daemon/another process.

`send` succeeds only after the remote durable saved receipt. Socket write success is insufficient; disconnect/timeout remains unconfirmed, and text is never automatically retransmitted. Files stream in bounded 32 KiB records with 1 MiB block SHA-256, whole-file SHA-256, receiver credit and forced checkpoints; max size **10 GiB**. Receive confirmation is required unless `auto_receive_trusted=true` and the peer is remembered. User paths are configurable; safe UUID-prefixed names and atomic no-replace publication prevent overwrite/path traversal. A `.nearbyim-*.part` file is retained for recovery, bound to the peer root and source generation. Resume checks the existing prefix; changed identities, changed sources, corrupted prefixes and cancellation tombstones fail closed. There is no claim of untested 10 GiB device recovery or guaranteed power-loss behavior on every filesystem.

Exit codes follow design v0.2: 0 completed, 2 invalid input, 3 not found, 4 profile/resource busy, 5 network unreachable, 6 interaction required, 7 identity mismatch, 8 protocol/handshake error, 9 local I/O, 10 unconfirmed timeout, 11 user cancellation. JSON error codes provide more detail.

## Verification and limits

```sh
cargo test --manifest-path terminal/Cargo.toml --locked
terminal/tools/test-interop.sh  # Java 17 required only for this test fixture
python3 terminal/tools/test-pty.py terminal/target/release/nearbyim  # Linux
python3 terminal/tools/package.py
```

The interop fixture compiles the **shipping Java** `NoiseRecordChannel`, `ProtocolV4` and `AttachmentTransferV2`. Real TCP tests cover both Noise roles, root proofs/safety codes, bidirectional text/ACK and bidirectional files. Unit tests cover authenticated-header tampering, replay, bounds, corruption, restart/resume, lost receipts, identity changes, cancellation and CJK/ZWJ editing/rendering. These are development-machine automation, not Android/Windows/Linux device acceptance. Pending acceptance/evidence is maintained in Issue #38 / PR #41, not a local TODO file. [Architecture and security boundaries](docs/architecture.md).

Rust is the implemented candidate; there is no fabricated Go comparison or GUI performance win. Package/startup/RSS/CPU measurements must record their environment and distinguish warm-cache samples from cold starts. Bluetooth RFCOMM, native Wi-Fi Direct, multiple simultaneous active peers, IPC and background services are not implemented. This development build must not be presented as a completed lightweight stable release until the manual's blocking matrix passes.

History is stored independently of peer clocks. `chats show DEVICE_UUID --limit 100 --before ROW_CURSOR` pages older records; JSON returns `next_before` and `has_more`. In the TUI, `older` / `latest` load bounded history pages rather than retaining an unbounded message list. Interface labels have an initial `zh-CN` translation; protocol diagnostics and some help text remain English.
