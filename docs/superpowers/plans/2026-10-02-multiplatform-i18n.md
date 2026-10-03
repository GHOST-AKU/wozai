# Multiplatform i18n Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 完整建立安卓和 Windows 的中英文架构，支持跟随系统与可扩展语言目录。

**Architecture:** 一个 BCP-47 翻译目录生成两个平台资源，共用键、语言解析和结构化提示。两端使用 ICU MessageFormat，各自在显示层翻译状态和错误。

**Tech Stack:** Java 17、Android API 26+ 原生资源/ICU、Swing/ResourceBundle/ICU4J、Python 3 生成器。

**Spec:** `docs/i18n.md`

## Global Constraints

- English brand is NearbyIM; 初期完整支持 zh-Hans 与 en，默认 system。
- 不改变 NIM2、用户聊天内容、长期身份、设备信任或现有最低 SDK。
- 两端分别迁移，公共生成器和类由主代理维护，贡献翻译分开写入临时 contributions，集成后删除并保留唯一文案源。
- 语言切换保留连接、会话、草稿、滚动位置与待授权动作。

## Review Focus

- 旧 zh 偏好、未知系统语言和区域变体的回退。
- 英语撇号、花括号、参数与复数不能损坏用户昵称或路径。
- 后台通知不能保持旧语言，服务连接不能被切换中断。
- 中文持久化消息状态必须迁移，历史正文不改动。
- 缺失资源、残留硬编码和生成产物漂移必须被构建检查阻止。

## Task 1: Shared catalog and runtime contracts

**Files:** `i18n/`, `tools/generate-i18n.py`, `app/src/main/java/dev/ghost/nearbyim/i18n/`, `tests/i18n/`, `desktop/dependencies.txt`.

**Interfaces:** `LanguageRegistry`, `UiText`, `LocalizedIOException`, generated `I18nResources.id(String)` as defined in the spec.

- [x] Add locale resolution, ICU formatting, key/placeholder parity and generated-output checks; establish their failing cases.
- [x] Implement registry, generator, Android XML and desktop properties output, and ICU4J dependency.
- [x] Verify round-trip UTF-8, apostrophes, plural categories and locale fallback.

## Task 2: Android migration

**Files:** Android MainActivity/controller/service/store/transports, AppLanguage adapter, resource/manifest/build integration; 规范文案键合并入 `i18n/messages/`.

**Interfaces:** consumes shared registry, UiText and resource IDs; produces complete localized Android UI and language selection.

- [x] Migrate visible text, notifications, accessibility, errors and dates.
- [x] Use language-independent state and upgrade legacy message status without modifying chat text.
- [x] Preserve activity state and live service while switching app locale.
- [x] Run Android compile/lint and meaningful lifecycle/state tests.

## Task 3: Windows migration

**Files:** desktop Java UI/errors/tests; 规范文案键合并入 `i18n/messages/`.

**Interfaces:** consumes shared registry and UiText; emits ICU-formatted UI, dialogs and typed localized errors.

- [x] Remove binary language branches, add system choice and canonical persisted values.
- [x] Migrate remaining errors and date/number formatting; refresh open dialogs and direction.
- [x] Verify live switching, receipt/session/draft preservation and formatted errors.

## Task 4: Integration and delivery

**Files:** build scripts, workflows, tests, README and architecture documentation.

- [x] Merge contributions into canonical locale files and generate resources.
- [x] Run catalog checks, core/trust regressions, desktop GUI, Android build/lint and Windows package checks.
- [x] Review both platform migrations, update existing PR and document verified limits.

## Completion Evidence · 2026-10-02

- Final production/test source: `afaf6826bba8645f6e840f870013fecab79a5f63`. CI build commit: `00ebdbde3e12cfc1a860d68935fd4499309e9410`.
- [Run 37003827341](https://github.com/GHOST-AKU/wozai/actions/runs/37003827341): Android build/lint, Windows package, API 26 native locale regression and API 34 native locale regression all succeeded.
- 299 matching catalog keys; 8 generator tests, 23 registry checks, 339 source references; Windows 631 formatting checks. API 26 and 34 each passed 120 native checks; Windows EXE, bundled-runtime GUI, real mDNS and layout checks passed.
- Independent review fixes and upgrade/signing constraints are documented in `docs/i18n.md` and `docs/verification.md`. Physical Bluetooth and external system permission callback scenarios remain manual device acceptance.
