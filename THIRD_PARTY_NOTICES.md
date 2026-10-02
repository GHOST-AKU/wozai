# Third-party notices

`gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` come from the official Gradle repository, tag `v8.13.0`:

https://github.com/gradle/gradle/tree/v8.13.0

Copyright 2015 the original author or authors. Licensed under Apache License, Version 2.0; see `licenses/Gradle-Apache-2.0.txt`. The original script headers are retained. The wrapper JAR SHA-256 is `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`, verified against https://gradle.org/release-checksums/.

No third-party Android UI or networking library is bundled with application source.

## Desktop networking

The Windows desktop build downloads these pinned artifacts, with SHA-256 verification in `desktop/dependencies.txt`:

- JmDNS 3.6.2 (`org.jmdns:jmdns`), https://github.com/jmdns/jmdns. Copyright 2003–2005 Arthur van Hoff, Rick Blair; maintained by the JmDNS contributors. Apache-2.0 license at `licenses/JmDNS-Apache-2.0.txt`.
- SLF4J API and NOP 2.0.17 (`org.slf4j:slf4j-api`, `org.slf4j:slf4j-nop`), https://www.slf4j.org/. Copyright 2004–2022 QOS.ch Sarl (Switzerland). MIT license at `licenses/SLF4J-MIT.txt` (retained from the upstream API JAR).

Windows packaging includes a linked Eclipse Temurin OpenJDK 17 runtime. Its legal notices remain in the bundled runtime's `legal/` directory; OpenJDK components use GPL-2.0 with the Classpath Exception and their included third-party licenses. The source build does not redistribute a JDK. These desktop dependencies do not change Android runtime dependencies.

## Google Material Icons

The ten `app/src/main/res/drawable/outline_*_24.xml` vector icons are Material Icons Outlined assets from Google's official repository, retrieved on 2026-10-01:

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
