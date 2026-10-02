# 我在 · Windows 局域网预览版

目标是在 Windows 上与现有 Android 0.2.0 完成无需账号、无需远程服务器的一对一文字互聊。桌面端直接编译现有 `core/` 和 `TrustPolicy`，使用同一 NIM2 线格式，不修改 Android 协议或源码。

## 使用

Windows 10/11 x64 是本轮目标。解压 `WoZai-0.2.0-windows-x64.zip` 后，双击 `WoZai/WoZai.exe`。保持整个目录完整：其中包含精简 Java 17 运行时，无需另外安装 Java。预览版没有代码签名，也没有安装器、开机启动或托盘常驻。

GitHub Actions 的 `wozai-windows-x64` artifact 也是 ZIP，下载后直接解压即可得到 `WoZai` 目录。

1. 电脑与手机加入同一 Wi-Fi 或手机热点。双方在「附近」开启局域网接收。
2. 若 Windows 防火墙提示网络访问，请允许应用在你使用的私人网络上接收连接。
3. 选择附近设备并「连接并记住」，或输入对方「我的连接」中的完整 IP 与端口。
4. 首次收到请求时可「同意并记住」「仅本次」或「拒绝」。昵称并不证明真实身份，首次连接请当面确认。
5. 以后选择历史聊天并「连接」。优先使用附近发现的新地址，否则尝试原地址；原地址失效时可输入新地址，但仍校验历史设备 UUID 和已信任公钥。

目前支持 Windows ↔ Android 和 Windows ↔ Windows 局域网文字聊天。Windows 蓝牙、附件、群聊、互联网转发和端到端内容加密尚未实现。局域网文字与 Android 0.2.0 一样为明文；签名用于身份连续性和完整性。

连接与接收分别控制：停止接收会关闭 TCP 监听和 mDNS，但保留已连接聊天；「断开连接」停止当前聊天。关闭窗口会保存草稿并停止连接、监听和发现。调整网络后请停止再开启接收。仅支持一条活动连接，新的来访不会替换当前聊天。

## 数据与信任

数据在 `%LOCALAPPDATA%\WoZai`，没有云端同步或遥测。私钥用 Windows DPAPI 的当前用户保护，UUID 和公钥跨启动保持稳定。不能直接把身份文件复制给另一个 Windows 用户使用。身份文件损坏或解密失败时停止启动，保留原文件，不静默换身份。

聊天、设备信任和草稿分别保存。每条消息用同目录原子替换保存，完成保存才发回执；断线或重新启动后仍未收到回执的发送消息显示「未确认」，不会自动重发。消息 UUID 在会话内去重，回执不能修改别的会话。「清空聊天记录」保留信任和草稿，「取消设备信任」撤销认可并断开该设备的活动连接。已有 UUID 更换密钥时会被拒绝，需要先核实身份。

目录锁避免同一数据目录被两个进程同时打开。界面只显示最近 200 条消息，其他历史保留在磁盘。当前预览版使用文件存储，读取历史需要扫描该会话文件；长历史的索引和分页仍有优化空间。

## 多语言与无障碍

本轮提供简体中文和英语。界面文案在资源文件中，时间按应用语言与系统时区格式化；切换语言不重建连接，并保留当前会话和输入。昵称不会因界面语言切换而改变。

使用系统外观和原生 Swing 控件，不用颜色单独表达连接或送达状态。提供输入、消息、地址和列表的无障碍名称，三个文字大小选项，以及可选择复制的消息文本。主要操作可用 Tab、方向键及 Space/Enter；Ctrl+1/2/3 切换聊天/附近/设置，Enter 发送，Shift+Enter 换行，Ctrl+Q 退出。

包内包含 `jdk.accessibility` 和 Java Access Bridge 工具。Windows 读屏需要启用 Java Access Bridge，可运行 `WoZai\runtime\bin\jabswitch.exe -enable` 后重启应用。控件标签和大字体已经有自动化检查；NVDA/Narrator、Windows 高对比度、125%/150%/200% DPI 及实际键盘焦点顺序仍需人工验收，不能把控件支持等同于读屏验收通过。

## 从源码构建

需要 JDK 17，设置 `JAVA_HOME`。桌面构建不需要 Android SDK 或 Gradle，也不会生成 APK。

```powershell
./desktop/tools/build.ps1 -Run
./desktop/tools/build.ps1 -Package
```

第二个命令先测试，然后用 `jpackage` 生成便携应用目录和 ZIP。它不需要 WiX。Windows 应用必须在 Windows 上打包；Linux 的 `jpackage` 不能跨平台生成 Windows 启动器。

Linux 开发环境需要 JDK 17、Python 3：

```sh
sh desktop/tools/build.sh
sh desktop/tools/build.sh --run
java -cp 'desktop/build/classes:desktop/build/tests:desktop/build/lib/*' dev.ghost.wozai.DiscoveryTests
# 在有桌面显示的环境中运行，或用 Xvfb 提供 DISPLAY：
java -cp 'desktop/build/classes:desktop/build/tests:desktop/build/lib/*' dev.ghost.wozai.GuiTests desktop/build/gui.png
```

依赖仅为 JmDNS 3.6.2、SLF4J API / NOP 2.0.17。`desktop/dependencies.txt` 固定制品及 SHA-256；下载或缓存校验失败会终止构建。运行时没有云端通信依赖。第三方许可见根目录 `THIRD_PARTY_NOTICES.md`。

`.github/workflows/windows.yml` 仅手动触发，执行 Windows 身份与存储测试、共同协议和信任测试、真实 mDNS、桌面交互测试，再上传便携 ZIP 和界面截图。提交不会自动编译。

## 实现边界

| 部分 | 实现 |
| --- | --- |
| 协议与信任规则 | 直接复用 Android 无关的 Java 核心 |
| Windows 私钥 | DPAPI CurrentUser；Linux 开发验证使用 0600 文件 |
| 连接 | 私有局域网 IP TCP，带超时、取消、退出清理 |
| 发现 | 与 Android NSD 一致的 `_nearbyim._tcp.local.`、`id` / `name` TXT 属性 |
| 模型 | 有界串行任务队列；套接字和发现工作在后台 |
| 保存 | 每条消息原子文件，信任独立保存，草稿按会话区分 |
| UI | Java Swing 系统外观；翻译和连接生命周期分离 |

## 验证记录 · 2026-10-02

本地 Linux 环境已执行桌面编译、54 项桌面检查、真实 TCP 的 Android 核心互通、真实 mDNS 注册/解析，以及 Xvfb 下的原生界面操作。桌面检查覆盖身份重启、数据锁、去重、回执隔离、未确认状态、草稿、清空与撤销、首次许可、可信重连、换密钥拒绝、仅本次、拒绝、握手期间撤销信任及保存失败不发回执。

桌面 UI 检查涵盖选择历史会话、草稿恢复、中英文切换、文字缩放、接收/许可按钮、双向通信、回执显示、连接中语言切换，以及关闭窗口保存草稿并释放数据锁。

Windows runner 已通过 52 项桌面检查（额外检查 DPAPI 保存格式）、原有 55 项协议/信任检查、真实 mDNS 及原生 GUI 测试，并生成带运行时的便携 ZIP，见 [首次 Windows 构建记录](https://github.com/GHOST-AKU/wozai/actions/runs/36979391767)。另有打包后 `.exe` 启动、窗口与正常退出的检查脚本 `desktop/tools/test-package.ps1`；最终构建链接会记录其执行结果。

以上 TCP 测试使用 Android 的同一协议核心，不代替实体 Windows 电脑与 Android 手机在真实 Wi-Fi、防火墙和热点环境里的双机验收。
