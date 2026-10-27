---
name: nearbyim
description: Operate the NearbyIM (WoZai / 我在) terminal client via its local nearbyim CLI. Use when a user asks an agent to discover NearbyIM devices, inspect local conversations or transfers, send an approved message or file, diagnose connectivity, or manage verified device trust. No MCP server required.
license: AGPL-3.0-only
compatibility: Requires the NearbyIM Terminal executable on the agent's execution machine, shell access, and the appropriate local profile/network. First-contact consent and manual identity verification require user interaction.
---

# NearbyIM Agent Skill

Operate **NearbyIM Terminal** using its existing `nearbyim` executable, not a fabricated API or a GUI automation script. NearbyIM's Chinese brand is 我在 / WoZai; the English brand is NearbyIM. The current independent Rust terminal client uses **NIM4** and an identity/profile separate from Android and desktop GUI clients.

## Before doing anything

1. Determine **where commands execute**. A cloud agent's shell is not the user's Windows/Linux terminal. Do not imply remote or cross-city access merely because the user has NearbyIM installed elsewhere.
2. Check whether `nearbyim` is installed and executable (`nearbyim --version`, `nearbyim --help`). If absent, explain that this skill cannot install or reach a client on another machine by itself; consult [the terminal manual](../../../terminal/README.md) in this repository or ask the user to provide a suitable execution environment.
3. Prefer the executable's current `--help` output to any examples in this Skill. This repository's CLI is a **development version**; don't claim features or compatibility not exposed by the installed executable.
4. Before running a stateful query, tell the user that the Terminal profile is independent from the GUI profile. CLI queries can initialize a profile; they do **not** import existing GUI trust, conversations or keys.
5. For scripted operations use `--json` and bounded `--timeout` where supported. Capture the exit status and inspect the final JSON object; don't interpret CLI progress or a socket write as successful remote delivery.

See [CLI command reference](references/cli.md) for grounded examples. Read [identity and authorization](references/security.md) before any send, trust change, file operation or listening action. See [troubleshooting](references/troubleshooting.md) for error handling.

## Decision workflow

- **Inspect only:** With the user's authorization, use `nearbyim --json status`, `nearbyim --json doctor`, `nearbyim --json trust list`, `nearbyim --json chats list` as appropriate. Device identifiers, conversations and diagnostic output are private information; minimize disclosure.
- **Find a peer:** Request permission before an active `peers scan`. Discovery entries and their names/addresses are *untrusted hints*. Never equate a nickname or advertised UUID with authenticated identity. Resolve the intended device uniquely; otherwise ask.
- **Send text / files:** Obtain the user's approval for the exact recipient and content or file(s); check existing remembered/verified peer state and whether the CLI can reach the peer. Invoke `send` only after these preconditions. For files, don't expand globs to surprising files. Await and interpret the result.
- **Read history:** Read only the conversation requested by the user, using `chats show` with a bounded `--limit`; do not crawl all chats or disclose bodies unnecessarily. Further paging requires a user-relevant reason.
- **First-contact approval / identity change:** Stop automation and hand control to a human interactive terminal. A remembered peer uses TOFU and is **not** automatically manually verified. The full safety code must be compared through another trusted channel before verification.
- **Listen / config / trust / identity / cancellation:** These can change exposure, security posture or stored data. Require explicit user intent and review the specific operation. Never silently enable `lan_listen`, `auto_receive_trusted`, or an indefinite listener. Do not silently revoke or forget device identity, delete data, or cancel transfers.

## Operating constraints

- Use **no direct database edits**, profile-key extraction, trust bypass, CLI input automation to approve unknown devices, protocol downgrade, cloud relay or unsolicited network access.
- `nearbyim listen --lan` is a **foreground receiver**. There is no background daemon or IPC; `status` reports `not_running` even when it describes a valid local profile. An existing TUI/CLI instance may hold the exclusive profile lock (`PROFILE_BUSY`); do not kill it or bypass the lock.
- A successful `send` indicates **remote durable saved receipt**, **not** a read receipt. On an unconfirmed timeout, **do not automatically retransmit text**; confirm with the user after checking status/history.
- LAN discovery/direct addresses work only within actually reachable networks. The Terminal client does not currently implement native Wi-Fi Direct, Bluetooth RFCOMM, multiple simultaneous active sessions or background connectivity.
- Text has an **8192 UTF-8 byte** limit. Files are bounded to **10 GiB** and up to **256 selections**; the recipient may have to approve reception. A file saved locally is not automatically safe to open or execute.
- Private identity material is sensitive. Terminal message history and received files are **not encrypted at rest**; never treat the OS-protected identity key as disk encryption for user content.
- Do not claim independent validation on a real device merely because the CLI, Skill or automated tests exist.

## Reporting

Tell the user what was **actually** executed, which local machine/profile it applied to, which peer was targeted when relevant, the concrete outcome/error, and whether receipt is confirmed or uncertain. Redact private keys, full safety codes, sensitive message bodies and private filesystem paths unless the user explicitly needs them.

This Skill is documentation for shell-capable agents. It **does not grant shell/network access**, deploy a service, or provide an MCP implementation. If the current CLI lacks a requested capability, report the limitation instead of inventing a command.
