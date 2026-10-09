# Wozai · NearbyIM

[简体中文](../../README.md) | **English**

**Chat without going through a remote server.**

Wozai (NearbyIM) is an open-source chat application for nearby communication. It lets Android, Windows, and Linux devices connect directly over **the same local network** or **Classic Bluetooth**, with no account registration and no dependency on a cloud chat server.

> Latest stable release: **0.3.1** · Android / Windows / Linux
> [Download the latest release](https://github.com/GHOST-AKU/wozai/releases/tag/v0.3.1) · [View Issues](https://github.com/GHOST-AKU/wozai/issues)

This branch contains **unreleased 0.3.2 candidate source**: NIM4 Noise sessions, streaming files up to 10 GiB and persistent resume. Evidence and blockers are tracked in [PR #18](https://github.com/GHOST-AKU/wozai/pull/18), [files #11](https://github.com/GHOST-AKU/wozai/issues/11), [encryption #15](https://github.com/GHOST-AKU/wozai/issues/15) and the [performance report](../performance.md). Phone-to-phone speed, physical Bluetooth and thermal endurance remain unverified.

## 🌱 Why Wozai?

Many chat apps assume by default that you have an internet connection, an account, and a remote server.

Wozai takes a different path — **if two people are already nearby, why can't their devices chat directly?**

For now, it focuses on simple, understandable nearby communication: devices discover each other, users decide for themselves whether to trust them, and messages and attachments travel directly between devices. The two devices are already right next to each other, after all. (｡•̀ᴗ-)✧

## ✨ What it can do today

- **Direct LAN connections**: automatic discovery via NSD / mDNS, with direct IP + port connections also supported
- **Classic Bluetooth communication**: secure RFCOMM with device discovery, pairing, and reconnection
- **Cross-platform chat**: candidate Android, Windows, and Linux clients share NIM4
- **Text, images, and files**: image bubbles, in-app viewing, and candidate single-file transfers up to 10 GiB with pause/resume; published 0.3.1 supports 1 GiB
- **Trusted reconnection**: after a device is approved for the first time, its long-term identity is recorded; later connections verify that it is the same device
- **Delivery receipts**: delivery is confirmed after the receiving side successfully saves the message
- **Local history and drafts**: chat history stays on the device and does not depend on cloud sync
- **Five languages**, light / dark themes, and text-size settings
- **No accounts, no telemetry, and no cloud chat endpoint**

## 💻 Platform status

| Platform | LAN | Classic Bluetooth | Text | Images / files | Status |
| --- | --- | --- | --- | --- | --- |
| Android | ✓ | ✓ | ✓ | ✓ | Primary mobile client |
| Windows | ✓ | ✓ | ✓ | ✓ | Available |
| Linux | ✓ | ✓ | ✓ | ✓ | Available |

Both desktop clients are distributed with their own runtime, so users do not need to install Java separately. For detailed usage instructions and validation scope on Windows and Linux, see the [Windows guide](../windows.md) and [Linux guide](../linux.md).

## 📡 Start chatting

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

The candidate source protects text and attachments using **Noise XX / X25519 / system AES-256-GCM / SHA-256**. Its existing P-256 root identity signs a proof binding both identities, Noise keys, handshake transcript and capabilities. Device UUIDs and existing trust pins are preserved. Each connection creates a new session; authentication, integrity or version failures close the connection.

**The published 0.3.1 release is not end-to-end encrypted**: NIM3 provides authentication and signatures, and Bluetooth also has system RFCOMM link protection.

Please note:

- **Published 0.3.1 LAN contents remain plaintext**.
- A nickname is not the same as an authenticated real-world identity; when connecting to an unfamiliar device for the first time, you should still verify the other party yourself.
- Initial approval (TOFU) accepts a device key; confirm its identity in person or through a trusted channel.
- History and attachments remain unencrypted on local storage. Transport encryption does not encrypt the disk. The app disables system automatic backup.
- Clearing chat history does not automatically revoke trust in a device. Trust can be revoked separately from device information or settings.

The candidate has standard-vector, cross-implementation and negative tests; it has not completed a formal cryptographic security audit or physical Bluetooth acceptance.

## 🧩 NIM4

The candidate clients share **NIM4 encrypted records** and never fall back to NIM3 or plaintext. Both endpoints must upgrade to compatible NIM4 versions. Historical records remain readable and root identities are unchanged.

Text, images, and files are currently supported; group chats, voice, internet relays, and automatic message retransmission are not yet included.

## 📦 Downloads and release notes

The current stable release is **0.3.1**:

- [GitHub Release v0.3.1](https://github.com/GHOST-AKU/wozai/releases/tag/v0.3.1)
- [0.3.1 release notes](../releases/0.3.1.md)
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
- A stable Android signing and upgrade path
- End-to-end encryption
- Feasibility research for Mesh / multi-hop networking
- More languages, accessibility, and better support for different device sizes

For current progress, see [Issues](https://github.com/GHOST-AKU/wozai/issues) and the repository commit history.

## 📄 Third-party components and licensing

Unless otherwise noted, this project's source code is released under the **GNU Affero General Public License v3.0 or later (AGPL-3.0-or-later)**. See [LICENSE](../../LICENSE) for the full license text. Third-party components including the Gradle Wrapper, Google Material icons, desktop dependencies, ICU4J, and Noto fonts retain their respective licenses; see [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md) for details.

---

**I'm here. You're here. So let's chat directly.**  
`( *ˊᵕˋ)✩︎‧₊`
