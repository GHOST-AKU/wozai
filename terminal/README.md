# NearbyIM Terminal

Independent native terminal client for **我在 / NearbyIM**, executable **`nearbyim`** (`nearbyim.exe` on Windows). Uses the shipping **NIM4** protocol. No Java runtime, GUI, Android SDK, cloud account, telemetry or relay is needed to run it. [中文](README.zh-CN.md).

New source builds use **0.3.3-preview.2**, shared with the other clients; the already published preview.1 retains its original internal `0.1.0-dev` version. See the [0.3.3 plan](../docs/releases/0.3.3.md). The client was implemented under [PR #41](https://github.com/GHOST-AKU/wozai/pull/41), based on [Issue #38](https://github.com/GHOST-AKU/wozai/issues/38) and the supplied [design v0.2](docs/design-v0.2.md). It is not a completed device/platform acceptance or a stable terminal release. Existing GUI clients and v0.3.2 release assets remain separate.

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

Ethernet computers and Wi-Fi devices can share the same LAN. Multi-address discovery prefers the longest matching ordinary LAN prefix and emits one stable endpoint per result, with private routed fallback. See [LAN troubleshooting](../docs/lan-discovery.md) for the Ethernet/Wi-Fi test procedure; real-router acceptance remains tracked in Issue #5.

## TUI

Quiet Terminal themes: `--theme midnight` (default), `paper`, `sage`, `mono`, `system`; `--no-color` / `NO_COLOR`; `--ascii`. `config set color auto|truecolor|256|16|none` controls fallback. The empty screen explains scanning/address connection without reserving empty columns. At 120+ columns a selected authenticated peer gets a details column; 72+ columns use a list/message split, and 45–71 use a single focused pane. Below 45 columns or 16 rows, enlarge the terminal to edit/review consent safely. Borders emphasize the composer and confirmations; identity and message states remain explicit.

- Tab / Shift+Tab: focus; arrows or j/k outside composer: select.
- Ctrl+K / Ctrl+P: command palette; `?` outside composer: help; `/` or Ctrl+F: search.
- Enter: open/send; Ctrl+J or Alt+Enter: newline; bracketed paste never sends on pasted newlines.
- Left/right/backspace/delete edit Unicode grapheme clusters, not UTF-16 units. New peers negotiate **32768 UTF-8 bytes** per message; older peers keep **8192 bytes**. The composer shows the connection limit and preserves oversized drafts (up to **256 KiB**); an edit beyond the draft budget is rejected as a whole. See [text limits and compatibility](../docs/text-messages.md).
- `a` outside composer: attach by path; confirm recipient/size before sending.
- Esc: close palette, cancel scan, return; Ctrl+C / q outside composer: exit; active transfers require exit confirmation and are checkpointed.
- Outside the composer: `1` chats, `2` bounded nearby scan, `3` transfers. On an empty chat screen, `s` scans and `c` opens `connect HOST:PORT`. PageUp/PageDown scroll help and status pages. `status` in the palette shows the complete last notice/error when the footer is too small; it does not clear drafts or hide the original details.

Palette: `chats`, `scan`, `transfers`, `security`, `settings`, `help`, `status`, `connect HOST:PORT`, `disconnect`, `listen on|off`, `file PATH`, `pause ID`, `resume ID`, `cancel ID`, `code`, `verify FULL_CODE`, `unverify`, `search TEXT`, `set KEY VALUE`, `quit`.

### Language and help

The embedded semantic catalog covers English, Simplified Chinese (`zh-CN`, with `zh-Hans` alias), Traditional Chinese (`zh-Hant`, with TW/HK/MO aliases), Japanese (`ja`) and Korean (`ko`): primary TUI/help, every actual CLI command help, onboarding, human line-client notices/confirmations, and cataloged application diagnostics. `config set language system|en|zh-CN|zh-Hant|ja|ko` persists selection; TUI `set language ...` applies after saving succeeds. Existing explicit choices win. New/missing settings use `system`; the first nonblank `LC_ALL`, `LC_MESSAGES`, `LANG` wins, then Windows native locale if available. An invalid/unsupported highest-priority locale falls back to English; explicit Chinese script wins over region. Returning to `system` preserves automatic behavior across restarts.

Help performs a bounded read-only settings probe, including `--profile PATH` / `--profile=PATH`, without creating a directory, identity, lock or history database. Command/flag/settings names, syntax, JSON fields/values, completion scripts and exit codes are stable. JSON errors remain English. Structured diagnostic/settings records retain their stable field/enum values. Human application errors are localized from complete cataloged templates, never by replacing substrings in user messages/paths; OS/library/remote diagnostic details retain their original text under a localized heading. Unknown diagnostics are preserved. `python3 terminal/tools/check-i18n.py` detects missing languages/parameters and uncataloged application assertions/contexts.

Windows Terminal/IME, SSH/tmux, terminal cell-width conventions and system fonts still require their own environment evidence. No terminal font is bundled; use a suitable modern monospace/CJK font, or `--ascii` when line glyphs do not fit.

IME composition and emoji display still depend on the terminal. Automated cell-width/grapheme/PTY tests are evidence for those cases, not a claim that all Windows/Linux terminal/IME combinations have passed.

## Security and trust

NIM4 uses maintained Snow/RustCrypto libraries: Noise XX / X25519 / AES-256-GCM / SHA-256 and P-256 persistent root proofs. Both claims, role, capabilities and transcript are bound; the ten-byte record envelope is authenticated. There is no NIM3/plaintext fallback. The terminal advertises text, file-v2 and avatar-v1 capabilities. Bluetooth RFCOMM and automatic Wi-Fi Direct are not advertised as supported.

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

The interop fixture compiles the **shipping Java** `NoiseRecordChannel`, `ProtocolV4` and `AttachmentTransferV2`. Real TCP tests cover both Noise roles, root proofs/safety codes, bidirectional text/ACK bidirectional files and bidirectional avatar-v1 during the same encrypted session. Unit tests cover authenticated-header tampering, replay, bounds, corruption, restart/resume, lost receipts, identity changes, cancellation and CJK/ZWJ editing/rendering. These are development-machine automation, not Android/Windows/Linux device acceptance. Pending acceptance/evidence is maintained in Issue #38 / PR #41, not a local TODO file. [Architecture and security boundaries](docs/architecture.md).

Rust is the implemented candidate; there is no fabricated Go comparison or GUI performance win. Package/startup/RSS/CPU measurements must record their environment and distinguish warm-cache samples from cold starts. Bluetooth RFCOMM, native Wi-Fi Direct, multiple simultaneous active peers, IPC and background services are not implemented. This development build must not be presented as a completed lightweight stable release until the manual's blocking matrix passes.

History is stored independently of peer clocks. `chats show DEVICE_UUID --limit 100 --before ROW_CURSOR` pages older records; JSON returns `next_before` and `has_more`. In the TUI, `older` / `latest` load bounded history pages rather than retaining an unbounded message list. Five-language presentation and diagnostic boundaries are described above; physical terminal/IME acceptance remains tracked in #53.

Multiple selections use one persistent, identity-bound queue. Repeat `--file` (up to 256) and the CLI waits for every saved receipt, returning all results in one JSON object. The TUI `files` command accepts one path per line (`Ctrl+J` adds a line) and shows one confirmation before enqueueing. A paused active transfer holds the queue; canceling it allows the next selection to proceed. Sources are rechecked before transmission. Common image MIME hints are detected with bounded header reads; an extension alone does not turn an arbitrary file into an image, and no file is automatically opened.

```sh
nearbyim --json send DEVICE_UUID --file ./first.png --file ./second.zip
```


## Avatars

`nearbyim avatar set IMAGE` selects a PNG, JPEG or WebP, applies its orientation and center-crops it to a square static PNG (at most 256×256 and 128 KiB). Decoding is limited to 32 MiB input, 32 megapixels and a 128 MiB decoder allocation budget. `avatar reset` restores the default profile; `avatar show [DEVICE_UUID]` inspects metadata; `avatar export OUTPUT.png [--peer DEVICE_UUID]` exports verified cached bytes without overwriting a file. These local CLI operations do not start a listener. TUI palette equivalents are `avatar set PATH`, `avatar reset`, `avatar show` and `avatar export PATH` (show/export use the selected peer, or your own identity when none is selected).

Online changes synchronize through the production avatar-v1 protocol only after authenticated capabilities and mutual consent. Separate bounded 4 KiB chunks, a four-chunk window, complete static-PNG validation and SHA-256 protect the avatar task. Invalid images and storage failures leave chat and ordinary files connected. The private SQLite cache holds your own avatar plus at most 63 remote roots (at most 8 MiB of PNG content); names and UUIDs cannot replace a root-key binding. Supported terminal graphics vary: use explicit PNG export for viewing; this build does not claim universal inline image rendering.


## Offline identity QR

`nearbyim identity qr DEVICE_UUID --output verification.png` exports the Android-compatible full `WOZAI-ID1:` safety code as a PNG without overwrite. Omitting `--output` prints a monochrome terminal symbol (use a light background); `--json` returns the exact payload. `identity check-qr DEVICE_UUID IMAGE` compares a bounded PNG/JPEG/WebP image with both known roots and leaves trust unchanged. `identity verify DEVICE_UUID --qr IMAGE` requires a TTY, a matching full code and explicit confirmation before recording verification. It does not remember connection consent.

TUI commands: `qr` displays a black-on-white symbol when the panel fits, `qr OUTPUT.png` exports it, and `verify-qr IMAGE` explicitly compares and records verification for the selected known identity. Narrow panels offer PNG export instead of wrapping/cropping the code. Images are bounded/downsampled before detection; decoded text is never opened as an address, URL or new identity. Camera scanning and cross-device acceptance are not claimed for Terminal.


## User-file access

`nearbyim files export TRANSFER_UUID OUTPUT` streams a completed file to a chosen path, checks its recorded whole-file SHA-256, forces the content and publishes without overwrite. TUI equivalent: `export TRANSFER_UUID PATH`. Changed or truncated saved files fail verification and leave no partial export. `files reveal TRANSFER_UUID` / TUI `reveal TRANSFER_UUID` requests the saved location in the OS file manager; headless systems can use the printed location or export. File contents are never passed to a shell or launched by reveal.

Incoming publication and export use an exclusive native rename on Windows/Linux/macOS, retaining the hard-link fallback only when the OS lacks the rename operation. A pre-publication checkpoint permits verification of the final file after interruption. Automated checks cover no-overwrite, hash mismatch, queue restart and real CLI export; removable filesystems and physical power-loss behavior still need device evidence.
