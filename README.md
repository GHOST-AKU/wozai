# 我在 · NearbyIM

**简体中文** | [English](docs/readme/README.en.md)

**不经过远程服务器，也能好好聊天。**

我在（NearbyIM）是一款面向近距离通信的开源聊天应用。正式版支持 Android、Windows 和 Linux，提供 **局域网** 与 **经典蓝牙** 连接；预览版新增 **macOS、iPhone、iPad 原生客户端**，以及 **Windows/Linux 的 Terminal（TUI/CLI）客户端**，目前主要通过局域网互通。无需注册账号，也不依赖云端聊天服务器。

> 最新正式版：**0.3.2** · Android / Windows / Linux
> [下载正式版](https://github.com/GHOST-AKU/wozai/releases/tag/v0.3.2) · [查看 Issues](https://github.com/GHOST-AKU/wozai/issues)
>
> 最新预发布：**0.3.3-preview.1** · macOS / iPhone / iPad / Windows、Linux Terminal
> [下载预览版](https://github.com/GHOST-AKU/wozai/releases/tag/v0.3.3-preview.1) · [安装与签名说明](docs/releases/native-preview-install.md)

**0.3.2 正式版已发布**，Android、Windows、Linux 同步更新协议与界面，Linux 提供 AppImage / deb / tar.gz。新增 Noise 端到端加密、最大 10 GiB 的流式附件与持久续传、自动文件选路、Android 接收文件夹及平板/折叠基础适配。完整变化与验收范围见 [0.3.2 发布说明](docs/releases/0.3.2.md)；P2P 稳定性、真实速度/距离恢复、长期内存及正式性能继续在 [#23](https://github.com/GHOST-AKU/wozai/issues/23)、[#9](https://github.com/GHOST-AKU/wozai/issues/9) 跟进。

## 🌱 为什么做「我在」

很多聊天软件默认假设：你有互联网、账号和一台远程服务器。

「我在」尝试另一条路——**如果两个人就在附近，设备为什么不能直接聊天？**

它目前专注于简单、可理解的近距离通信：设备自己发现彼此、用户自己决定是否信任、消息和附件直接在设备之间传输。两台设备明明就在彼此身边嘛。 (｡•̀ᴗ-)✧

## 🏡 Apple 与 Terminal 可以用在哪里

- **iPhone / iPad 与 Mac、Android、PC 之间**：在同一局域网或热点下交换文字、照片和文件，不必先上传到云端，也不需要双方使用同一家设备生态。
- **Mac 上的日常局域网协作**：使用原生窗口与系统文件组件，和同一办公室、教室或工作室里的设备一对一沟通、交换资料。路由器不必接入互联网，但必须允许设备互访。
- **只想使用终端的电脑**：Windows/Linux 的 Terminal 提供全屏 TUI，可在终端里聊天、查看历史、收发文件，无需启动 GUI 或安装 Java。
- **命令行与远程主机工作流**：通过 CLI 的 JSON 输出、退出码和文件命令配合本地脚本；也可经 SSH 操作远端的 Terminal。此时发现和连接发生在远端主机的网络，SSH 不会把它变成跨互联网聊天中继。

Apple 与 Terminal 当前都是**预览版**，尚未完整对齐安卓版；本版不提供它们的蓝牙/P2P 自动文件链路、群聊或 Mesh/多跳转发。安装包和具体范围见下方平台说明。

## ✨ 现在能做什么

以下主要描述 Android / Windows / Linux 正式版；Apple 与 Terminal 的预览范围见平台状态。

- **局域网直连**：NSD / mDNS 自动发现，也支持 IP + 端口直连
- **经典蓝牙通信**：使用安全 RFCOMM，可搜索、配对和重连
- **跨平台聊天**：Android、Windows、Linux 共用 NIM4 加密协议
- **文字、图片和文件**：支持图片气泡、应用内查看、最大 10 GiB 的有界流式传输、多选队列、暂停/续传和失败重发
- **自动文件选路**：复用端点热点、尝试临时 P2P、LAN、已有蓝牙；按质量选择 LAN，断路后按检查点继续，气泡显示实际路径
- **保存位置**：Android 可选择文件管理器能访问的接收文件夹；桌面支持原名打开与另存为
- **可信重连**：首次认可设备后记录其长期身份，后续连接验证同一设备
- **送达回执**：对方成功保存消息后确认送达
- **本地记录与草稿**：聊天记录留在设备本地，不依赖云同步
- **五种语言**、浅色 / 深色主题与文字大小设置
- **无账号、无遥测、无云端聊天接口**

## 💻 平台状态

| 平台 | 局域网 | 经典蓝牙 | 文字 | 图片 / 文件 | 状态 |
| --- | --- | --- | --- | --- | --- |
| Android | ✓ | ✓ | ✓ | ✓ | 主要移动端 |
| Windows GUI | ✓ | ✓ | ✓ | ✓ | 正式版 |
| Linux GUI | ✓ | ✓ | ✓ | ✓ | 正式版 |
| macOS / iPhone / iPad | ✓ | — | ✓ | ✓ | 原生预览版，尚未完整对齐 Android |
| Terminal（Windows / Linux x64） | ✓ | — | ✓ | ✓ | TUI/CLI 预览版 |

桌面端均提供自带运行环境的发行包，不要求用户额外安装 Java。Windows 与 Linux 的具体使用和验证范围见 [Windows 说明](docs/windows.md) 与 [Linux 说明](docs/linux.md)。

iOS、iPadOS、macOS 提供 **Swift / SwiftUI 原生预览版**，使用 Apple 系统组件，支持 NIM4 局域网加密文字、文件/图片、设备认可、本地记录和二维码身份核验。Mac 包支持 Intel 与 Apple Silicon；iPhone/iPad 共用一个设备 IPA。Apple 头像、蓝牙/P2P/独立文件链路等仍未完整实现，真机与性能验收继续见 [Apple 说明](docs/apple.md) 和 [#31](https://github.com/GHOST-AKU/wozai/issues/31)。

**Apple 预览版没有正式分发签名：**Mac 包只有 ad-hoc 临时签名，没有 Developer ID 签名或公证；iPhone/iPad IPA 未签名，不能直接点击安装，需要自行签名侧载。最低系统为 macOS 14、iOS/iPadOS 17。具体安装与免费账号续签步骤见[安装说明](docs/releases/native-preview-install.md)。

## ⌨️ NearbyIM Terminal（预览版）

**NearbyIM Terminal** 是独立 Rust 终端客户端，主命令为 `nearbyim`（Windows：`nearbyim.exe`），同时提供全屏 TUI 和 CLI，运行不依赖 JRE、GUI 或 Android SDK。[下载 Windows/Linux x64 预览包](https://github.com/GHOST-AKU/wozai/releases/tag/v0.3.3-preview.1)，解压后运行 `nearbyim`，或 `nearbyim --help` 查看命令。

- 支持 NIM4 身份认可/安全码、LAN 发现/地址直连、文字、历史/草稿、流式文件、队列、暂停/续传及保存回执，也支持头像同步和二维码核验。
- **默认关闭 LAN 监听**，不自动批准未知身份或接收文件。首次 TUI 启动会引导设置；CLI 接口提供 JSON 和退出码，详见 [Terminal 使用说明](terminal/README.zh-CN.md)。
- 当前内部版本为 `0.1.0-dev`，与合集的 `0.3.3-preview.1` 分开；Windows 包无 Authenticode 签名，本次不包含 macOS Terminal 二进制。
- 蓝牙/P2P 等适配与完整验收仍在开发；终端、IME、SSH/tmux、真机互通和性能范围见 [#38](https://github.com/GHOST-AKU/wozai/issues/38)。不替代现有 GUI，0.3.2 正式版附件保持不变。

## 📡 开始聊天

以下界面步骤面向 Android / Windows / Linux GUI 正式版；Apple 安装和连接见[预览版说明](docs/releases/native-preview-install.md)，Terminal 操作见[终端使用说明](terminal/README.zh-CN.md)。

### 局域网

1. 让两台设备连接同一个 Wi-Fi，或让一台设备开启热点。
2. 双方打开「我在」，进入 **附近 → 局域网 → 开启接收**。
3. 一方搜索另一台设备并发起聊天。
4. 首次连接时，接收方确认是否信任该设备。
5. 之后可从历史会话直接尝试重连。

如果自动发现失败，可以使用对方「我的连接」中显示的 IP 地址和端口直连。访客 Wi-Fi、校园网、VPN 或客户端隔离可能阻止设备互访。

### 蓝牙

1. 双方打开蓝牙，并在「我在」中选择 **附近 → 蓝牙 → 开启接收**。
2. 被连接方临时允许设备被发现。
3. 另一方搜索设备并发起聊天；首次使用时按系统提示完成配对。
4. 首次认可后，「我在」会记住该设备身份，后续可从历史会话重连。

## 🔐 安全与隐私

0.3.2 使用 **Noise XX / X25519 / 系统 AES-256-GCM / SHA-256** 保护端点间的文字与附件。原有 P-256 设备根身份证明绑定双方身份、Noise 公钥、握手转录与能力，保留既有设备 UUID 和信任记录。新连接建立全新会话，认证、完整性校验或版本失败时关闭连接。

需要注意：

- **已发布 0.3.1 的局域网内容仍是明文**；该版本只有 NIM3 认证与签名，蓝牙另有系统 RFCOMM 链路保护。
- 昵称不等于经过认证的真实身份；首次认可（TOFU）表示接受设备公钥，仍需当面或通过可信渠道核对对方身份。
- 聊天记录与附件仍以未加密内容保存在本机；传输加密不等于磁盘加密。应用关闭系统自动备份。
- 清空聊天记录不会自动取消设备信任，可在设备信息或设置中单独取消信任。

实现有标准向量、跨实现互操作与负向测试；尚未完成正式密码安全审计或蓝牙真机验收。

## 🧩 NIM 协议与版本

NIM 是「我在 / NearbyIM」在设备之间交换消息与附件的应用层协议标识。它规定消息格式、身份验证、回执和文件传输规则；LAN、蓝牙和 Wi-Fi Direct 是承载路径，不是不同的 NIM 版本。协议版本也不等于应用版本号。

| 协议 | 使用阶段 | 主要能力与安全边界 |
| --- | --- | --- |
| NIM1 | 早期原型 | 基础消息分帧；没有后续版本的设备身份签名与端到端加密。 |
| NIM2 | 0.2.0 起的早期版本 | P-256 设备身份、挑战握手、逐帧签名和防重放；内容仍为明文。[历史格式说明](docs/protocol-v2.md) |
| NIM3 | 0.3.1 正式版 | 扩展二进制附件分块、流控、SHA-256 校验与保存回执；身份签名不等于内容加密。 |
| **NIM4** | **0.3.2 正式版** | **Noise 端到端加密**，保留长期设备根身份，支持安全码核验、流式大文件与持久续传。 |

当前 Android、Windows 与 Linux 客户端共用 **NIM4**，不会自动回退到 NIM3 或明文。0.3.2 与 0.3.1 不能直接互通，双方均应更新；同为 NIM4 的不同构建也须满足能力校验。协议升级保留旧聊天记录和原有设备根身份，不代表旧客户端仍可连接。

当前支持文字、图片和文件；暂不包含群聊、语音、互联网中继或消息自动重发。

## 📦 下载与版本说明

当前正式版为 **0.3.2**。Apple / Terminal 的 **0.3.3-preview.1** 是独立的实验性预发布，功能与设备验收仍在推进：

- [Apple / Terminal 预发布下载](https://github.com/GHOST-AKU/wozai/releases/tag/v0.3.3-preview.1)
- [预览版安装、签名与使用说明](docs/releases/native-preview-install.md)
- 预发布附件附有 `SHA256SUMS.txt`、构建来源与许可文件。

- [GitHub Release v0.3.2](https://github.com/GHOST-AKU/wozai/releases/tag/v0.3.2)
- [0.3.2 发布说明](docs/releases/0.3.2.md)
- [Android 签名与升级](docs/android-signing.md)
- [Android 真机测试](docs/device-test.md)
- [验证记录](docs/verification.md)
- [性能基线](docs/performance.md)
- [多语言架构](docs/i18n.md)

Android 覆盖安装并保留聊天数据要求新旧 APK 使用相同签名证书。已有重要本地记录时，请先阅读签名与升级说明，不要直接卸载旧版本。

## 🛠️ 从源码构建

### Android

需要 JDK 17、Python 3、Android SDK Platform 36、Build Tools 35.0.0，以及支持 AGP 8.13 的 Android Studio。

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

调试 APK 输出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```

最低支持 Android 8.0 / API 26，targetSdk 为 36。

### 核心验证

无需 Android SDK 即可运行一部分协议、信任和资源检查：

```sh
sh tools/test-i18n.sh
sh tools/test-core.sh
sh tools/test-trust.sh
sh tools/check-source.sh
```

Windows PowerShell：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\test-i18n.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\test-core.ps1
```

这些检查不等同于 Android 完整编译或真实设备验收。完整步骤见 [设备测试说明](docs/device-test.md)。

## 🗂️ 项目结构

| 目录 / 文件 | 作用 |
| --- | --- |
| `core/` | 设备密钥、认证通道、协议、许可握手、收发与心跳 |
| `transport/` | TCP / NSD 与蓝牙 RFCOMM |
| `storage/` / `ChatStore.java` | SQLite、会话、信任记录与迁移 |
| `AndroidIdentity.java` | Android Keystore 长期设备身份 |
| `ChatService.java` | Android 前台通信服务 |
| `i18n/` | 多端共享文案与本地化资源 |
| `tests/` | 不依赖 Android 的通信与信任测试 |
| `docs/` | 平台、发布、安全、测试和性能文档 |

## 🌱 接下来

项目仍在快速开发。当前较重要的方向包括：

- 群聊与多人同时私聊
- 更完整的桌面端蓝牙真机验证
- 更多设备上的覆盖升级与存储兼容验证
- 独立密码学审计与安全体验完善
- Mesh / 多跳组网的可行性研究
- 更多语言、无障碍与不同尺寸设备体验

具体进度以 [Issues](https://github.com/GHOST-AKU/wozai/issues) 和仓库提交为准。

## 📄 第三方组件与许可

除另有说明外，本项目源码以 **GNU Affero General Public License v3.0 or later（AGPL-3.0-or-later）** 发布，完整条款见 [LICENSE](LICENSE)。Gradle Wrapper、Google Material 图标、桌面依赖、ICU4J 与 Noto 字体等第三方组件保留各自许可，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

---

**我在。你也在。那就直接聊吧。**  
`( *ˊᵕˋ)✩︎‧₊`
