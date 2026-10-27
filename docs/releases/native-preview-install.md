# Native preview / 原生客户端预览版

This is an experimental preview, not full Android feature parity. Stable Android/Windows/Linux GUI v0.3.2 remains unchanged. NIM4 interoperability is tested, but physical Apple wireless, camera, storage-provider and performance acceptance is incomplete. Back up important data before testing; uninstalling an app can delete its local history/files.

这是实验性预览，尚未完整对齐安卓版。稳定版 Android/Windows/Linux GUI v0.3.2 不变。已验证 NIM4 互通，但 Apple 真机无线、相机、存储提供者和性能验收尚未完成。测试前备份重要数据，卸载可能删除本地聊天和文件。

## macOS 14 or later / macOS 14 及以上

Download the universal `macos-universal-adhoc.zip`, extract NearbyIM.app and move it to Applications. Supports Apple Silicon and Intel. It has only an **ad-hoc signature**, not an Apple Developer ID signature, and is **not notarized**. It does not establish a verified publisher identity. After checking the download hashes and source, try opening it, then use System Settings → Privacy & Security → Open Anyway if macOS blocks it. Follow the system prompts; do not disable Gatekeeper globally. Managed Macs may prohibit this installation.

下载通用 Mac ZIP，解压后把 NearbyIM.app 移至“应用程序”。支持 Apple Silicon 与 Intel。包只有 **ad-hoc 临时签名，没有 Developer ID 开发者签名，也未公证**，不能据此验证发布者身份。核对哈希和来源后尝试打开；如被拦截，在“系统设置 → 隐私与安全性”中选择“仍要打开”，按系统提示操作。不要全局关闭 Gatekeeper；受管理的 Mac 可能禁止安装。

## iPhone / iPad: iOS or iPadOS 17 or later

The shared `ios-ipados-unsigned.ipa` contains a device build for both iPhone and iPad, **without an installation signature or provisioning profile**. Downloading, AirDropping or tapping the IPA cannot install it. It is not a simulator build, TestFlight invitation or App Store package.

Use a compatible local signing/sideloading tool (for example Sideloadly or AltStore on a supported Windows/Mac setup), your own Apple ID, and the connected device to sign and install it. Consult the tool's current official instructions, enable Developer Mode when requested, and trust the developer profile under Settings → General → VPN & Device Management if prompted. Free Personal Team installations typically expire after seven days and require refreshing/re-signing. Device, app-count and entitlement restrictions apply; compatibility with every signing tool has not been verified. Keep the same bundle identifier when updating and avoid uninstalling if retaining data. Alternatively, build and install this source with Xcode and your Personal Team. Paid membership is required for TestFlight; this release is not distributed through TestFlight.

共用 IPA 同时支持 iPhone/iPad，**没有可安装的分发签名和描述文件**。直接下载、隔空投送或点击不能安装；它不是模拟器包、TestFlight 邀请或 App Store 安装包。

可在支持的 Windows/Mac 环境使用本地签名侧载工具（例如 Sideloadly、AltStore），配合自己的 Apple ID 和连接的设备签名安装。请按工具当前官方说明操作，按提示开启“开发者模式”，必要时在“设置 → 通用 → VPN 与设备管理”中信任开发者。免费 Personal Team 通常每七天需要续签，受设备、应用数量和权限限制；尚未逐一验证所有侧载工具的兼容性。更新时尽量保持相同 Bundle Identifier，不要为更新随意卸载。也可以用 Xcode 与 Personal Team 从源码安装。TestFlight 需要付费会员，本版不是 TestFlight 分发。

## Terminal / 终端客户端

The Windows/Linux x64 archives contain the independent Rust TUI/CLI, documentation, completions and dependency licenses; no Java runtime is required. Published preview.1 embeds Terminal version `0.1.0-dev`; new source builds use the shared `0.3.3-preview.2` identity. See the [0.3.3 plan](0.3.3.md); updating source versions does not replace published downloads. These binaries have **no publisher code-signing certificate** (Windows: no Authenticode signature). No macOS Terminal binary is included in this preview.

Windows: extract the ZIP, then run `.\nearbyim.exe --help` in PowerShell, or `.\nearbyim.exe` for TUI. Linux: extract the tar.gz, then run `./nearbyim --help` or `./nearbyim`. Use the bundled README for consent, listening/discovery, profiles and file commands. Linux builds target x86_64 glibc systems compatible with Ubuntu 22.04 (glibc 2.35+), not Alpine/musl or ARM.

Windows/Linux x64 压缩包包含独立 Rust TUI/CLI、说明、补全和依赖许可，无需安装 Java。已发布 preview.1 的 Terminal 内部版本为 `0.1.0-dev`；新源码构建统一使用 `0.3.3-preview.2`。参见 [0.3.3 更新规划](0.3.3.md)，源码版本更新不表示已替换下载附件。二进制**没有发布者代码签名证书**，Windows 没有 Authenticode 签名。本次不包含 macOS Terminal 二进制。

Windows 解压后在 PowerShell 运行 `.\nearbyim.exe --help`，或直接 `.\nearbyim.exe` 进入 TUI。Linux 解压后运行 `./nearbyim --help` 或 `./nearbyim`。监听/发现、接收授权、Profile 和文件命令见包内 README。Linux 包面向与 Ubuntu 22.04 兼容的 x86_64 glibc 系统（glibc 2.35+），不适用于 Alpine/musl 或 ARM。

## Verify downloads / 核验下载

Compare SHA-256 with the release's SHA256SUMS.txt: `shasum -a 256 FILE` on macOS, `sha256sum FILE` on Linux, or `Get-FileHash FILE -Algorithm SHA256` in PowerShell. Hashes check consistency with this release; they do not replace publisher signing/notarization.

使用上述命令计算附件 SHA-256，与发布页 SHA256SUMS.txt 对照。哈希用于核对本次附件完整性，不能代替开发者签名或公证。
