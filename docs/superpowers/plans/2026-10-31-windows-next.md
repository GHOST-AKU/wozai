# Windows Next Implementation Plan

> **For agentic workers:** Use focused parallel implementation across the independent bridge, client transport, UI and Windows acceptance tasks; preserve the protocol contract.

**Goal:** A usable independently packaged WinUI 3 Windows client with existing desktop feature coverage and real runtime screenshots.

**Architecture:** Native C# UI → redirected private JSONL pipes → unchanged DesktopClient / transport / store sources. Bundle .NET, Windows App SDK and a Java runtime. Independent profile, no default networking.

**Tech Stack:** .NET 8, WinUI 3 Windows App SDK, Java 17, Windows x64. Windows 10 19041+.

**Spec:** `docs/superpowers/specs/2026-10-31-windows-next-design.md`; exact interfaces in `windows-next/PROTOCOL.md`.

## Global Constraints

- Preserve existing desktop and Android communication sources byte-for-byte.
- Name 我在next; separate local data and portable package.
- Screenshots must come from actual Windows WinUI runtime; identify acceptance fixtures.
- Shared version authority remains `i18n/config.json`.

## Review Focus

- Child EOF/crash while requests are pending must fail them and stop networking.
- Selection/reconnect must not target another peer or stale generation.
- Async saved sends must preserve subsequent typing and another peer's draft.
- Read receipts must stop on background, modal overlay and offscreen text.
- Theme/language/font changes must retain state; dialogs and settings scroll in short windows.

## Tasks

1. Implement private Java adapter and real-kernel integration tests, with failing boundary tests before implementation. Own `windows-next/bridge`, `windows-next/tools/build-bridge.py` and bridge acceptance fixtures.
2. Implement C# models and robust request-correlated BridgeClient, subprocess lifetime and JSONL limits; test framing, EOF and pending-request behavior. Own `windows-next/WindowsNext/Services`, Models and its test project.
3. Implement native WinUI shell, complete operations and system pickers with parity matrix; own remaining `windows-next/WindowsNext` sources.
4. Build/portable-pack Windows x64 in a dedicated workflow, actual-window screenshot and UI smoke acceptance. Reuse unchanged-kernel checks; record source comparison, runtime evidence and device limitations in docs and PR. Review all changes before delivery.
