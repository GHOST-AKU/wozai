# Android Next IME regression (#94)

## Inset ownership

`MainActivity` enables edge to edge; the manifest uses `adjustResize`.
`NextApp` owns safe drawing insets (including IME) at its Scaffold. Its content
must apply **and consume** `innerPadding`, so nested Material top bars and
navigation rails see the remaining insets. Chat must not apply another IME
padding. The message viewport is weighted between the header and the bottom
composer. Window classification remains outside the inset-reduced Scaffold.

The original content applied padding without consuming it and Chat applied
`imePadding` again. The same IME therefore reduced the available content twice;
unconsumed status/navigation insets could also reach nested Material components.
No protocol, storage, service, or shared `app/` source changes are required.

## Automated evidence and limits

`NextUiTest` renders the real NextApp and ChatScreen with synthetic bottom insets
at 0 / 300 / 0 / 300 dp (180 dp in the short-window fixture), dispatching matching Android IME insets to descendants.
Geometry assertions cover header/list/composer order, positive list height,
composer bottom against the root's safe/IME edge, preserved draft, and stable pane position.
The tablet fixture deliberately crosses below 480 dp of available height with IME
visible, catching accidental pane reclassification based on keyboard height.
Fixtures cover 412×915 phone, 1280×700 tablet, and 640×480 short window with
multiline draft. These are Robolectric tests, not physical IME or animation tests.
The test inset injection defaults to native `WindowInsets.safeDrawing` in the app.

No emulator or Android device is attached in the execution environment. No
physical device, keyboard animation, keyboard switching, focus retention,
large-font device, send/attachment, or navigation-mode acceptance is claimed.
All device rows below are **pending** until executed and evidence is attached
to #94. Do not close #94 based on the automated results alone.

## Executable manual matrix — all pending

Install a persistent-certificate-verified Next development APK without clearing
app data. Record APK commit/version, device model, Android version, keyboard
name/version, navigation mode, theme, font scale, and window size per run.
Use a peer conversation containing enough long messages to scroll.

| Run | Device/window | Theme/navigation/font | Status |
| --- | --- | --- | --- |
| P1 | Phone portrait | Light, gesture, default | pending |
| P2 | Phone portrait | Dark, three-button, default | pending |
| P3 | Phone landscape | Light, gesture, largest available | pending |
| T1 | Tablet full width | Dark, gesture, default | pending |
| T2 | Tablet full width | Light, three-button, largest available | pending |
| T3 | Tablet split screen; drag divider narrower/wider | Both themes, configured navigation, default | pending |

For **each row**:

1. Open a conversation. Capture closed-keyboard screenshot. Verify top navigation,
   scrollable messages, composer, bottom safe area order.
2. Focus the input; type a five-line draft. Record keyboard opening/closing video.
   Verify the composer meets the IME upper edge throughout animation, with no
   blank strip, overlap, inversion, or clipped controls. Scroll the message list.
3. Hide IME with system Back, reopen it three times, then switch keyboard using
   the system picker. Verify draft and focus remain usable and layout is anchored.
4. Send text; open attachment entry and cancel; switch conversations and return.
   Verify send/attachment controls remain reachable and each conversation's draft
   follows existing behavior. Do not mistake a disconnected draft fixture for send testing.
5. Rotate (or resize the tablet split window) with IME visible. Verify stable
   pane selection, ordered layout, no overlap, and retained draft. Repeat after
   closing IME. Capture final screenshot and record any focus changes.

Attach before/after screenshots and transition recording, plus observed result
for every step and the device metadata. Failed or unexecuted steps remain open.

## Local verification (2026-11-01)

Toolchain: Android SDK 36, Temurin JDK 17.0.16+8, Gradle 8.13,
Robolectric Android API 34. Run with the existing cached toolchain:

```sh
JAVA_HOME=/workspace/toolchains/jdk-17.0.16+8 \
ANDROID_HOME=/workspace/toolchains/android-sdk \
ANDROID_USER_HOME=/workspace/toolchains/android-user \
GRADLE_USER_HOME=/workspace/toolchains/gradle \
./gradlew :app-next:testDebugUnitTest --offline --console=plain
```

The pre-fix geometry run failed all three IME fixtures; phone composer bottom
was 315 px instead of 615 px and tablet 200 px instead of 500 px, reproducing
an extra 300 dp offset. Local evidence: `/workspace/issue94-red.log` and
`/workspace/issue94-red-results.xml`. Final suite: **10 tests passed (6 UI + 4 session-policy), zero failures**. Evidence:
`/workspace/issue94-green.log` and `app-next/build/test-results/testDebugUnitTest/`.
Online Gradle metadata resolution stalled in this environment; the offline run
uses existing dependencies. The build's i18n, icons, and QR prerequisites also run.
No APK was offered for installation and no physical-device evidence was produced.
