# 我在next Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan in the current session.

**Goal:** Build an independently installable Kotlin / Compose Android experiment using the unchanged communication kernel.

**Architecture:** `app-next` shares Java sources and Android resources with `app`, excludes the legacy MainActivity and supplies a Kotlin ComponentActivity. A lifecycle-aware state bridge observes the existing service/controller. Compose screens consume immutable snapshots.

**Tech Stack:** JDK 17, AGP 8.13.0, Kotlin / Compose compiler 2.2.20, Compose BOM 2025.09.01, Material 3, Android API 26–36.

**Spec:** `docs/superpowers/specs/2026-10-31-android-next-design.md`

## Global Constraints

- Name: 我在next; applicationId: dev.ghost.nearbyim.next.
- Reuse the communication kernel without source modifications.
- Read version and build number from i18n/config.json.
- Keep experimental data and identity independent from the existing app.
- Never offer an APK for installation without persistent certificate verification.

## Review Focus

- Files selected during a disconnect/reconnect must never enter a different session.
- Async send completion must preserve newer typing and another peer's draft.
- Permission refusal and Bluetooth enable cancellation must leave controls usable.
- Lifecycle stops, screen locks and offscreen messages must not emit read receipts.
- Small screens, landscape and large font must preserve navigation and composer access.

## Task 1: Independent build and session boundaries

Create `app-next/build.gradle.kts`, its manifest/theme, and `NextSessionPolicyTest.kt`. Add module/plugin registration. Run the boundary tests before implementing `NextSessionPolicy.kt`; expect missing-symbol failure, then implement and expect passing tests. Check manifest applicationId, launcher and shared source exclusions during build.

## Task 2: Native UI and controller bridge

Create Kotlin `MainActivity.kt`, `NextState.kt`, `NextTheme.kt`, `NextApp.kt`, `ConversationScreen.kt`, `NearbyScreen.kt`, `SettingsScreen.kt`, and `ChatScreen.kt`. Expose `NextState` snapshots and controller actions from the Activity; bind/unbind with lifecycle, persist drafts through AndroidDrafts, capture attachment selection sessions, and gate foreground transport operations with permissions. Reuse existing localization keys and Android helper Activities. Build and lint the module; inspect permission and receipt flows against the existing Activity.

## Task 3: Reviewable evidence

Document build commands, migration coverage, independent identity and remaining device acceptance in `docs/android-next.md`; add README entry. Run `:app-next:testDebugUnitTest :app-next:assembleDebug :app-next:lintDebug`, `git diff --check`, and verify the original app source tree is unchanged. Record actual results and device limitations. Review the complete diff and fix material findings before finishing.
