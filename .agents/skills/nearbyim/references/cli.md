# NearbyIM Terminal CLI — agent reference

**Authority:** `terminal/src/main.rs` and `terminal/README.md` on the checked-out version. Verify installed syntax with `nearbyim --help` and subcommand help. Executable: `nearbyim` (`nearbyim.exe` on Windows). The following examples represent the repository's existing Rust CLI, not prospective commands.

## Safe initial checks

```sh
nearbyim --version
nearbyim --help
nearbyim --json status
nearbyim --json doctor
```

The first two are non-mutating program queries. Commands opening a Terminal profile can initialize its independent local identity. `--profile PATH` switches the **Terminal** profile; never aim it at a GUI profile. `--timeout` is a global option, defaults to 60 seconds, accepts seconds / `s` / `m` and is capped at 24 hours; zero permits an operation to continue until interrupted. Prefer bounded deadlines for agent automation.

## Devices and identity

```sh
nearbyim --json peers scan --timeout 8s
nearbyim --json peers show DEVICE_UUID
nearbyim --json identity show
nearbyim --json trust list
nearbyim identity verify DEVICE_UUID
nearbyim identity qr DEVICE_UUID --output code.png
nearbyim identity check-qr DEVICE_UUID code.png
```

Scan is an explicit local-network discovery action. Identity verification requires a human-mediated trusted comparison; `check-qr` only compares and **does not mark trusted/verified**. Do not use automated inputs or JSON mode to approve an unknown device. `identity forget DEVICE_UUID` and `trust revoke DEVICE_UUID` are sensitive state changes.

## Conversation and sending

```sh
nearbyim --json chats list
nearbyim --json chats show DEVICE_UUID --limit 20
nearbyim --json chats show DEVICE_UUID --limit 20 --before ROW_CURSOR
nearbyim --json --timeout 2m send DEVICE_UUID --text 'Hello'
printf 'hello\nworld' | nearbyim --json --timeout 2m send DEVICE_UUID --stdin
nearbyim --json --timeout 5m send DEVICE_UUID --file ./demo.zip
nearbyim --json --timeout 5m send DEVICE_UUID --file ./a.png --file ./b.zip
```

The `--before` cursor must come from a previous `chats show` result's `next_before`; check `has_more`. The local chat history must not be confused with remote read status. A send has a remote saved receipt only when the CLI reports completion. Send requires a valid remembered consent/trust flow and the peer reachable in the actual execution environment; `--address HOST:PORT` may be supplied for a known direct route without bypassing identity checks. Shell quoting and path rules differ between POSIX shells and PowerShell; do not copy `printf` into PowerShell unmodified.

## Transfers, foreground sessions and configuration

```sh
nearbyim --json files list
nearbyim --json files resume TRANSFER_UUID
nearbyim --json files cancel TRANSFER_UUID
nearbyim --json files export TRANSFER_UUID ./output.zip
nearbyim --json files reveal TRANSFER_UUID
nearbyim config list
nearbyim config get lan_listen
nearbyim listen --lan --timeout 5m
```

`files resume` and `files cancel` affect saved task state; require authorization. `files export` refuses to overwrite an existing path. `files reveal` may launch a file manager; do not use in headless sessions. `listen` occupies the foreground and the Terminal profile; do not start it as an implicit daemon.

`nearbyim chat DEVICE_UUID` opens the interactive TUI; `nearbyim connect --address HOST:PORT` opens a foreground line client. Avoid these for unattended structured automation.

## JSON and exit handling

When `--json` is supported, stdout contains **one final JSON object**; inspect `ok`, `status`, `operation`, identifiers, and `error`, together with the **process exit code**. Do not assume every output field exists on every operation. Some diagnostics go to stderr. No terminal ANSI/TUI is opened for non-interactive commands.

| Exit | Meaning |
| --- | --- |
| 0 | completed |
| 2 | invalid input |
| 3 | not found |
| 4 | profile or resource busy |
| 5 | network unreachable |
| 6 | interaction required |
| 7 | identity mismatch |
| 8 | protocol / handshake error |
| 9 | local I/O error |
| 10 | unconfirmed timeout |
| 11 | user cancellation |

If an operation returns 10 or loses its receipt, delivery is **unconfirmed**; never silently re-send text. Preserve the actual error code in the report.
