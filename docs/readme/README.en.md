# Wozai · NearbyIM

[简体中文](../../README.md) | **English** | [日本語](README.ja.md)

**Chat without going through a remote server.**

Wozai (NearbyIM) is an open-source chat application for nearby communication. Stable clients support Android, Windows and Linux over **LAN** and **Classic Bluetooth**. Preview releases add **native macOS, iPhone and iPad clients**, plus a **Terminal (TUI/CLI) for Windows/Linux**, currently focused on LAN interoperability. No account registration or cloud chat server is required.

> Latest stable release: **0.3.2** · Android / Windows / Linux
> [Download stable](https://github.com/GHOST-AKU/wozai/releases/tag/v0.3.2) · [View Issues](https://github.com/GHOST-AKU/wozai/issues)
>
> Latest prerelease: **0.3.3-preview.1** · macOS / iPhone / iPad / Windows and Linux Terminal
> [Download preview](https://github.com/GHOST-AKU/wozai/releases/tag/v0.3.3-preview.1) · [Installation and signing](../releases/native-preview-install.md)

**The current development version is `0.3.3-preview.4` / build15** across Android, Windows/Linux GUI, Apple and Terminal. See the [0.3.3 release plan](../releases/0.3.3.md) and [scope/acceptance #55](https://github.com/GHOST-AKU/wozai/issues/55). This is the development source version; new packages are not published yet. Download links above still refer to existing releases.

**0.3.2 is now a stable release**, with matching protocol and UI updates for Android, Windows and Linux, including Linux AppImage / deb / tar.gz packages. It adds Noise end-to-end encryption, bounded streaming files up to 10 GiB with durable resume, automatic file routing, an Android receiving folder and adaptive tablet/foldable layouts. See the [0.3.2 release notes](../releases/0.3.2.md) for changes and acceptance scope. P2P reliability, device throughput/distance recovery, long-term memory and formal performance remain tracked in [#23](https://github.com/GHOST-AKU/wozai/issues/23) and [#9](https://github.com/GHOST-AKU/wozai/issues/9).

## Changes in this 0.3.3 candidate

- Native text read receipts are enabled by default and can be disabled at any time. Both peers must opt in and text must actually be visible in the foreground. Read indicators use `#66CCFF`, with a subtle glow in dark mode and flat styling in light mode. Terminal does not implement read receipts.
- Improved emoji selection, draft persistence, immediate desktop language switching and five-language support (Simplified/Traditional Chinese, English, Japanese and Korean); removed the persistent composer byte counter.
- Negotiated text supports 32768 UTF-8 bytes; legacy or unnegotiated sessions retain 8192. Existing tablet split defaults and center snapping are preserved.
- Fixed Linux AppImage compatibility with Ubuntu 22.04, improved transfer-stage diagnostics and privacy, and refined Apple foreground visibility and input focus handling.

Packages and actual runtime screenshots for this candidate are being prepared; no Release has been published. Simulator/test-session screenshots are not evidence of physical wireless or performance acceptance. Phone screenshots use portrait orientation. Apple signing and installation limitations are described below.

## 🌱 Why Wozai?

Many chat apps assume by default that you have an internet connection, an account, and a remote server.

Wozai takes a different path — **if two people are already nearby, why can't their devices chat directly?**

For now, it focuses on simple, understandable nearby communication: devices discover each other, users decide for themselves whether to trust them, and messages and attachments travel directly between devices. The two devices are already right next to each other, after all. (｡•̀ᴗ-)✧

## 🏡 Where NearbyIM fits

- **Moving things between your own devices:** send text, photos and files between a phone, computer and tablet without uploading them to the cloud or requiring the same device brand. Start on the same LAN or hotspot.
- **Communication in an office, classroom or workshop:** chat one-to-one and exchange documents or materials with colleagues and classmates on the same network. Computers connected to the router by Ethernet can participate when peer access is allowed.
- **Temporary sites without internet:** use an existing router or a manually created hotspot at an event or during outdoor collaboration. The network does not need internet access; clients supporting Classic Bluetooth can also try Bluetooth text chat.
- **Nearby conversations without registration:** discover devices and exchange information, with both sides deciding whether to connect and remember the device identity. History and received files stay on each endpoint.
- **Terminal and script workflows:** chat and transfer files in a Windows/Linux terminal, or combine CLI JSON output and exit codes with local scripts. Over SSH, discovery and connections use the remote host's network.

NearbyIM currently focuses on **one-to-one communication between directly reachable devices**. It does not provide internet relays, group chat or Mesh/multihop forwarding, and cannot replace an assured emergency communications system. Transports and features differ by platform; Apple and Terminal are currently previews. See platform details below.

## ✨ What it can do today

The following primarily describes the stable Android/Windows/Linux clients; Apple and Terminal preview capabilities are listed under platform status.

The `0.3.3-preview.4` development source adds [text read receipts](../read-receipts.md), enabled by default with an opt-out. Both peers must enable them, and the message must actually be displayed in the foreground conversation. This is implemented in source but not published and is not implied to be present in the downloads above.

- **Direct LAN connections**: automatic discovery via NSD / mDNS, with direct IP + port connections also supported
- **Classic Bluetooth communication**: secure RFCOMM with device discovery, pairing, and reconnection
- **Cross-platform chat**: Android, Windows, and Linux clients share encrypted NIM4 records
- **Text, images, and files**: image bubbles, in-app viewing, bounded streaming transfers up to 10 GiB, batch queues, pause/resume and retry
- **Automatic file routing**: reuse an endpoint hotspot, try temporary P2P, LAN and existing Bluetooth; select a faster LAN and continue from checkpoints after interruption, with actual-path indicators
- **Save locations**: choose a file-manager-accessible receiving folder on Android; open original names or save files elsewhere on desktop
- **Trusted reconnection**: after a device is approved for the first time, its long-term identity is recorded; later connections verify that it is the same device
- **Delivery receipts**: delivery is confirmed after the receiving side successfully saves the message
- **Local history and drafts**: chat history stays on the device and does not depend on cloud sync
- **Five languages**, light / dark themes, and text-size settings
- **No accounts, no telemetry, and no cloud chat endpoint**

## 💻 Platform status

| Platform | LAN | Classic Bluetooth | Text | Images / files | Status |
| --- | --- | --- | --- | --- | --- |
| Android | ✓ | ✓ | ✓ | ✓ | Primary mobile client |
| Windows GUI | ✓ | ✓ | ✓ | ✓ | Stable |
| Linux GUI | ✓ | ✓ | ✓ | ✓ | Stable |
| macOS / iPhone / iPad | ✓ | — | ✓ | ✓ | Native preview; incomplete Android parity |
| Terminal (Windows / Linux x64) | ✓ | — | ✓ | ✓ | TUI/CLI preview |

Both desktop clients are distributed with their own runtime, so users do not need to install Java separately. For detailed usage instructions and validation scope on Windows and Linux, see the [Windows guide](../windows.md) and [Linux guide](../linux.md).

Native **Swift / SwiftUI previews for iOS, iPadOS and macOS are available**, using Apple system components. They support encrypted NIM4 LAN text, files/images, device consent, local history and QR identity verification. The Mac package supports Intel and Apple Silicon; one device IPA serves iPhone and iPad. Apple avatars, Bluetooth/P2P/dedicated file lanes and other parity work remain incomplete. See the [Apple guide](../apple.md) and [#31](https://github.com/GHOST-AKU/wozai/issues/31) for capabilities and hardware/performance acceptance.

**Apple previews have no official distribution signing:** the Mac app has only an ad-hoc signature, without Developer ID signing or notarization. The iPhone/iPad IPA is unsigned and requires your own signing/sideloading; tapping the download cannot install it. Minimum versions are macOS 14 and iOS/iPadOS 17. See [installation guidance](../releases/native-preview-install.md), including free-account renewal.

## ⌨️ NearbyIM Terminal (preview)

**NearbyIM Terminal** is an independent Rust TUI/CLI, executable `nearbyim` (`nearbyim.exe` on Windows), without a JRE, GUI or Android SDK at runtime. [Download Windows/Linux x64 preview packages](https://github.com/GHOST-AKU/wozai/releases/tag/v0.3.3-preview.1), extract, then run `nearbyim` or `nearbyim --help`.

- Supports NIM4 identity consent/safety codes, LAN discovery/direct addresses, text/history/drafts, streaming files, queues, pause/resume and durable receipts, plus avatar sync and QR verification.
- **LAN listening is off by default**; unknown identities and incoming files are not automatically approved. Initial TUI setup guides configuration; CLI commands offer JSON and exit codes. See the [Terminal guide](../../terminal/README.md).
- The published `0.3.3-preview.1` contains Terminal internal version `0.1.0-dev`; new builds use the shared `0.3.3-preview.4` version. Windows binaries have no Authenticode signature; the existing preview contains no macOS Terminal binary.
- Bluetooth/P2P adapters and complete acceptance remain in development. Terminal/IME/SSH/tmux, physical interoperability and performance scope are tracked in [#38](https://github.com/GHOST-AKU/wozai/issues/38). Existing GUI clients and stable v0.3.2 assets remain separate.

## 📡 Start chatting

The following UI steps apply to stable Android/Windows/Linux GUI clients. See [preview guidance](../releases/native-preview-install.md) for Apple installation/connections and the [Terminal guide](../../terminal/README.md) for TUI/CLI operation.

### Local network

1. Connect both devices to the same Wi-Fi network, or have one device create a hotspot.
2. Open Wozai on both devices and go to **Nearby → LAN → Enable receiving**.
3. One side searches for the other device and starts a chat.
4. On the first connection, the receiving side decides whether to trust the device.
5. After that, you can try reconnecting directly from the conversation history.

If automatic discovery fails, you can connect directly using the IP address and port shown under **My Connection** on the other device. Guest Wi-Fi, campus networks, VPNs, or client isolation may prevent devices from reaching each other.

### Bluetooth

1. Turn on Bluetooth on both devices, then choose **Nearby → Bluetooth → Enable receiving** in Wozai.
2. The device being connected to temporarily allows itself to be discoverable.
3. The other device searches for it and starts a chat; on first use, complete pairing when prompted by the system.
4. After the device is approved for the first time, Wozai remembers its identity, and later connections can be re-established from the conversation history.

## 🔐 Security and privacy

0.3.2 protects text and attachments using **Noise XX / X25519 / system AES-256-GCM / SHA-256**. Its existing P-256 root identity signs a proof binding both identities, Noise keys, handshake transcript and capabilities. Device UUIDs and existing trust pins are preserved. Each connection creates a new session; authentication, integrity or version failures close the connection.

**The published 0.3.1 release is not end-to-end encrypted**: NIM3 provides authentication and signatures, and Bluetooth also has system RFCOMM link protection.

Please note:

- **Published 0.3.1 LAN contents remain plaintext**.
- A nickname is not the same as an authenticated real-world identity; when connecting to an unfamiliar device for the first time, you should still verify the other party yourself.
- Initial approval (TOFU) accepts a device key; confirm its identity in person or through a trusted channel.
- History and attachments remain unencrypted on local storage. Transport encryption does not encrypt the disk. The app disables system automatic backup.
- Clearing chat history does not automatically revoke trust in a device. Trust can be revoked separately from device information or settings.

The implementation has standard-vector, cross-implementation and negative tests; it has not completed a formal cryptographic security audit or physical Bluetooth acceptance.

## 🧩 NIM protocol versions

NIM identifies the application-layer protocol that NearbyIM uses to exchange messages and attachments. It defines framing, authentication, receipts, and file-transfer rules. LAN, Bluetooth, and Wi-Fi Direct are transport paths, not separate NIM versions. Protocol versions are also distinct from app version numbers.

| Protocol | Used in | Main capabilities and security boundary |
| --- | --- | --- |
| NIM1 | Early prototypes | Basic message framing, without the device-identity signatures or end-to-end encryption of later versions. |
| NIM2 | Early releases starting with 0.2.0 | P-256 device identities, challenge handshakes, signed records, and replay protection; contents remain plaintext. [Historical wire format](../protocol-v2.md) |
| NIM3 | Stable 0.3.1 | Binary attachment chunks, flow control, SHA-256 verification, and durable save receipts; signatures do not encrypt contents. |
| **NIM4** | **Stable 0.3.2** | **Noise end-to-end encryption**, retained device root identities, safety-code verification, streaming large files, and durable resume. |

Android, Windows, and Linux now share **NIM4** and never automatically fall back to NIM3 or plaintext. 0.3.2 cannot communicate directly with 0.3.1; upgrade both peers. Different NIM4 builds must also pass capability checks. Historical records and device root identities survive the upgrade, which does not imply compatibility with old clients.

Text, images, and files are currently supported; group chats, voice, internet relays, and automatic message retransmission are not yet included.

## 📦 Downloads and release notes

The current stable release is **0.3.2**. Apple/Terminal **0.3.3-preview.1** is a separate experimental prerelease with ongoing feature and device acceptance:

- [Apple / Terminal prerelease downloads](https://github.com/GHOST-AKU/wozai/releases/tag/v0.3.3-preview.1)
- [Preview installation, signing and usage](../releases/native-preview-install.md)
- Preview assets include `SHA256SUMS.txt`, build provenance and license files.

- [GitHub Release v0.3.2](https://github.com/GHOST-AKU/wozai/releases/tag/v0.3.2)
- [0.3.2 release notes](../releases/0.3.2.md)
- [Android signing and upgrades](../android-signing.md)
- [Android real-device testing](../device-test.md)
- [Verification records](../verification.md)
- [Performance baseline](../performance.md)
- [Internationalization architecture](../i18n.md)

Preserving chat data during an Android in-place upgrade requires the old and new APKs to use the same signing certificate. If you already have important local records, read the signing and upgrade guide first; do not uninstall the old version directly.

## 🛠️ Build from source

### Android

Requires JDK 17, Python 3, Android SDK Platform 36, Build Tools 35.0.0, and a version of Android Studio that supports AGP 8.13.

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The minimum supported version is Android 8.0 / API 26; targetSdk is 36.

### Core checks

Some protocol, trust, and resource checks can be run without the Android SDK:

```sh
sh tools/test-i18n.sh
sh tools/test-core.sh
sh tools/test-trust.sh
sh tools/check-source.sh
```

Windows PowerShell:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\test-i18n.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\test-core.ps1
```

These checks are not equivalent to a full Android build or real-device acceptance testing. See the [device testing guide](../device-test.md) for the complete procedure.

## 🗂️ Project structure

| Directory / file | Purpose |
| --- | --- |
| `core/` | Device keys, authenticated channels, protocol, permission handshake, sending / receiving, and heartbeat |
| `transport/` | TCP / NSD and Bluetooth RFCOMM |
| `storage/` / `ChatStore.java` | SQLite, conversations, trust records, and migrations |
| `AndroidIdentity.java` | Long-term device identity in Android Keystore |
| `ChatService.java` | Android foreground communication service |
| `i18n/` | Shared copy and localization resources across platforms |
| `tests/` | Communication and trust tests that do not depend on Android |
| `docs/` | Platform, release, security, testing, and performance documentation |

## 🌱 What's next

The project is still developing rapidly. Current priorities include:

- Group chats and multiple simultaneous private chats
- More complete real-device Bluetooth validation on desktop
- In-place upgrade and storage compatibility on more devices
- Independent cryptographic audit and security UX improvements
- Feasibility research for Mesh / multi-hop networking
- More languages, accessibility, and better support for different device sizes

For current progress, see [Issues](https://github.com/GHOST-AKU/wozai/issues) and the repository commit history.

## 📄 Third-party components and licensing

Unless otherwise noted, this project's source code is released under the **GNU Affero General Public License v3.0 or later (AGPL-3.0-or-later)**. See [LICENSE](../../LICENSE) for the full license text. Third-party components including the Gradle Wrapper, Google Material icons, desktop dependencies, ICU4J, and Noto fonts retain their respective licenses; see [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md) for details.

---

**I'm here. You're here. So let's chat directly.**  
`( *ˊᵕˋ)✩︎‧₊`

The composer emoji button now uses a shared Unicode 16 / CLDR 47 catalog: five-language categories/search, 30 local recents and skin variants. Hold an emoji on Android for variants; select Variants on desktop. Desktop Enter/double-click/Insert supports repeated insertion at the caret/selection without sending. No emoji fonts/images are bundled. [Catalog, license and updates](../../i18n/emoji/README.md). Desktop language changes apply after successful persistence; Linux Follow system honors LC_ALL → LC_MESSAGES → LANG, while a manual selection always wins.
