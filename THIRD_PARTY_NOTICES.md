# Third-party notices

`gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` come from the official Gradle repository, tag `v8.13.0`:

https://github.com/gradle/gradle/tree/v8.13.0

Copyright 2015 the original author or authors. Licensed under Apache License, Version 2.0; see `licenses/Gradle-Apache-2.0.txt`. The original script headers are retained. The wrapper JAR SHA-256 is `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`, verified against https://gradle.org/release-checksums/.

No third-party Android UI or networking library is bundled with application source.

## Desktop interface and networking

The Windows and Linux desktop builds download these pinned artifacts, with SHA-256 verification in `desktop/dependencies.txt`:

- FlatLaf 3.6.2 (`com.formdev:flatlaf`), https://github.com/JFormDesigner/FlatLaf. Copyright FormDev Software GmbH. Apache License 2.0 at `licenses/FlatLaf-Apache-2.0.txt`, retained from the upstream JAR. Desktop visual tokens and bubble layout match the Android application.
- JmDNS 3.6.2 (`org.jmdns:jmdns`), https://github.com/jmdns/jmdns. Originally developed by Arthur van Hoff, moved to SourceForge by Rick Blair and to GitHub by Kai Kreuzer; maintained by the JmDNS contributors. Upstream v3.6.2 license and notice are retained at `licenses/JmDNS-Apache-2.0.txt` and `licenses/JmDNS-NOTICE.txt`.
- SLF4J API and NOP 2.0.17 (`org.slf4j:slf4j-api`, `org.slf4j:slf4j-nop`), https://www.slf4j.org/. Copyright 2004–2022 QOS.ch Sarl (Switzerland). MIT license at `licenses/SLF4J-MIT.txt` (retained from the upstream API JAR).
- Noto Sans CJK SC Regular and Bold (思源黑体), https://github.com/notofonts/noto-cjk. Copyright Adobe and Google, SIL Open Font License 1.1 at `licenses/NotoSansCJK-OFL.txt`. The unmodified upstream fonts are downloaded and SHA-256 verified using `desktop/font-dependencies.txt`, embedded in the desktop JAR, and registered only within the running application. No system font installation is required.
- ICU4J 77.1 (`com.ibm.icu:icu4j`), https://github.com/unicode-org/icu. Copyright Unicode, Inc. The Unicode License V3 and upstream third-party notices are retained at `licenses/ICU4J-LICENSE.txt`. Windows uses ICU MessageFormat for the same catalog syntax as Android's system ICU; Android adds no ICU library dependency.

Windows and Linux packaging include a linked Eclipse Temurin OpenJDK 17 runtime. Its legal notices remain in the bundled runtime's `legal/` directory; OpenJDK components use GPL-2.0 with the Classpath Exception and their included third-party licenses. The source build does not redistribute a JDK. These desktop dependencies do not change Android runtime dependencies.

The Linux Bluetooth bridge dynamically links the distribution's GLib/GIO libraries (LGPL-2.1-or-later, https://gitlab.gnome.org/GNOME/glib) and uses the system BlueZ D-Bus service (https://www.bluez.org/). These system libraries and bluetoothd are not included in the application package. The Linux native launcher and BlueZ bridge are application source; no BlueZ implementation source is copied into them.

## Google Material Icons

The `app/src/main/res/drawable/outline_*_24.xml` vector icons are Material Icons Outlined assets from Google's official repository, retrieved on 2026-10-01 and 2026-10-04:

https://github.com/google/material-design-icons

Licensed under Apache License, Version 2.0. The upstream license is retained at `docs/licenses/material-icons-LICENSE.txt`. Each vector is adapted to reference the framework `?android:attr/colorControlNormal` instead of the library-specific `?attr/colorControlNormal`; paths and dimensions are unchanged. Runtime tint and drawable bounds are set by the application.

| Asset | Upstream directory beneath `android/` |
| --- | --- |
| `outline_chat_bubble_24.xml` | `communication/chat_bubble/materialiconsoutlined/black/res/drawable` |
| `outline_wifi_tethering_24.xml` | `device/wifi_tethering/materialiconsoutlined/black/res/drawable` |
| `outline_settings_24.xml` | `action/settings/materialiconsoutlined/black/res/drawable` |
| `outline_search_24.xml` | `action/search/materialiconsoutlined/black/res/drawable` |
| `outline_close_24.xml` | `navigation/close/materialiconsoutlined/black/res/drawable` |
| `outline_add_24.xml` | `content/add/materialiconsoutlined/black/res/drawable` |
| `outline_arrow_back_24.xml` | `navigation/arrow_back/materialiconsoutlined/black/res/drawable` |
| `outline_more_vert_24.xml` | `navigation/more_vert/materialiconsoutlined/black/res/drawable` |
| `outline_bluetooth_24.xml` | `device/bluetooth/materialiconsoutlined/black/res/drawable` |
| `outline_wifi_24.xml` | `notification/wifi/materialiconsoutlined/black/res/drawable` |
| `outline_attach_file_24.xml` | `editor/attach_file/materialiconsoutlined/black/res/drawable` |
| `outline_send_24.xml` | `content/send/materialiconsoutlined/black/res/drawable` |
| `outline_emoji_emotions_24.xml` | `social/emoji_emotions/materialiconsoutlined/black/res/drawable` |
| `outline_photo_24.xml` | `image/photo/materialiconsoutlined/black/res/drawable` |
| `outline_description_24.xml` | `action/description/materialiconsoutlined/black/res/drawable` |
| `outline_file_download_24.xml` | `file/file_download/materialiconsoutlined/black/res/drawable` |
| `outline_zoom_in_24.xml` | `action/zoom_in/materialiconsoutlined/black/res/drawable` |
| `outline_zoom_out_24.xml` | `action/zoom_out/materialiconsoutlined/black/res/drawable` |
| `outline_pause_24.xml` | `av/pause/materialiconsoutlined/black/res/drawable` |
| `outline_play_arrow_24.xml` | `av/play_arrow/materialiconsoutlined/black/res/drawable` |

The two playback controls were retrieved on 2026-10-09 from pinned upstream commit `49d4db35df873165d6bd6ba09b063c7dafbac2f4` (SHA-256 of upstream pause XML: `464af7751d0f67a1c900ce4809a308c41fbe4c585543b31f3227b21f94893b2f`; play-arrow XML: `7f9fb25ab5d4328b3128d424c44ccf2541c74a2d1bac7fda6670cd0e40252196`).

The Windows and Linux resources under `desktop/src/main/resources/dev/ghost/wozai/icons/` rasterize eighteen of these same Android vector paths for runtime tinting. The original Material Icons license is also included in the Windows and Linux packages. The shared app artwork is maintained at `desktop/assets/icons/master/icon-master-1024.png`; `desktop/assets/icons/windows/nearbyim.ico` contains the Windows launcher sizes.
