# Wozai · NearbyIM

[简体中文](README.md) | **English**

**Chat without going through a remote server.**

Wozai (NearbyIM) is an open-source chat application for nearby communication. It lets Android, Windows, and Linux devices connect directly over **the same local network** or **Classic Bluetooth**, with no account registration and no dependency on a cloud chat server.

> Current version: **0.3.1** · Android / Windows / Linux  
> [Download the latest release](https://github.com/GHOST-AKU/wozai/releases/tag/v0.3.1) · [View Issues](https://github.com/GHOST-AKU/wozai/issues)

## 🌱 Why Wozai?

Many chat apps assume by default that you have an internet connection, an account, and a remote server.

Wozai takes a different path — **if two people are already nearby, why can't their devices chat directly?**

For now, it focuses on simple, understandable nearby communication: devices discover each other, users decide for themselves whether to trust them, and messages and attachments travel directly between devices. The two devices are already right next to each other, after all. (｡•̀ᴗ-)✧

## ✨ What it can do today

- **Direct LAN connections**: automatic discovery via NSD / mDNS, with direct IP + port connections also supported
- **Classic Bluetooth communication**: secure RFCOMM with device discovery, pairing, and reconnection
- **Cross-platform chat**: Android, Windows, and Linux share the NIM3 protocol
- **Text, images, and files**: image bubbles, in-app viewing, and single-file transfers up to 1 GiB
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

Both desktop clients are distributed with their own runtime, so users do not need to install Java separately. For detailed usage instructions and validation scope on Windows and Linux, see the [Windows guide](docs/windows.md) and [Linux guide](docs/linux.md).

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

Wozai aims to keep the communication path as direct and understandable as possible, but **0.3.1 is not yet an end-to-end encrypted chat tool**.

NIM3 currently uses a local long-term P-256 device identity, an authenticated handshake, message signatures, and direction and sequence information to verify continuity of device identity and message integrity. A public key accepted for the first time is stored in the local trust record.

Please note:

- **LAN message contents are currently still plaintext**. Do not treat them as E2EE communication suitable for untrusted networks.
- Bluetooth uses the system's secure RFCOMM pairing and link encryption, but application-layer end-to-end encryption has not yet been added.
- A nickname is not the same as an authenticated real-world identity; when connecting to an unfamiliar device for the first time, you should still verify the other party yourself.
- Chat history, device identity, and trust records are stored locally; the app disables the system's automatic backup.
- Clearing chat history does not automatically revoke trust in a device. Trust can be revoked separately from device information or settings.

End-to-end encryption and more advanced networking capabilities are still in the design / pre-research stage for future development.

## 🧩 NIM3

The Android, Windows, and Linux clients share the **NIM3 authenticated encapsulation protocol**. NIM3 does not downgrade to NIM2 or to unauthenticated connections, so both sides need to use a version that supports NIM3.

Text, images, and files are currently supported; group chats, voice, internet relays, and automatic message retransmission are not yet included.

## 📦 Downloads and release notes

The current stable release is **0.3.1**:

- [GitHub Release v0.3.1](https://github.com/GHOST-AKU/wozai/releases/tag/v0.3.1)
- [0.3.1 release notes](docs/releases/0.3.1.md)
- [Android signing and upgrades](docs/android-signing.md)
- [Android real-device testing](docs/device-test.md)
- [Verification records](docs/verification.md)
- [Performance baseline](docs/performance.md)
- [Internationalization architecture](docs/i18n.md)

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
.\tools\test-i18n.ps1
.\tools\test-core.ps1
```

These checks are not equivalent to a full Android build or real-device acceptance testing. See the [device testing guide](docs/device-test.md) for the complete procedure.

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

Unless otherwise noted, this project's source code is released under the **GNU Affero General Public License v3.0 or later (AGPL-3.0-or-later)**. See [LICENSE](LICENSE) for the full license text. Third-party components including the Gradle Wrapper, Google Material icons, desktop dependencies, ICU4J, and Noto fonts retain their respective licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for details.

---

**I'm here. You're here. So let's chat directly.**  
`( *ˊᵕˋ)✩︎‧₊`
