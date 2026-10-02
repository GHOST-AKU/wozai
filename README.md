# 我在 · NearbyIM

一个无需账号、无需远程服务器的聊天项目。原生安卓端和 Windows 预览版通过 **同一局域网** 或 **经典蓝牙** 聊天，共用 NIM2 协议。

**当前开发版本：我在 · NearbyIM 0.3.0。安卓与 Windows 共用完整的简体中文／英文翻译目录，默认跟随系统，涵盖界面、错误、通知、权限说明、日期、帮助与关于。多语言架构和新增语言流程见[架构说明](docs/i18n.md)。原有 NIM2 身份、信任与消息协议保持兼容。0.2.0 双机通信及可信重连已由用户报告通过；后续方向见[产品路线图](docs/roadmap.md)。**

Windows 0.3.0 预览版包含局域网发现、IP 直连、经典蓝牙、文字聊天、可信重连、回执、本地记录和草稿，以及与安卓一致的配色、头像、搜索、消息气泡和原图标。英文品牌统一为 NearbyIM，内置思源黑体，无需安装字体；设置支持窄窗口自动换行、顺畅滚动，并提供「使用说明」「关于我在」。支持中英文、文字大小和浅色／深色主题。数据默认放在软件旁的 `data`，旧版便携和用户目录会复制迁移并保留原件。解压便携包后运行 `NearbyIM/NearbyIM.exe`，无需另装 Java。构建、使用范围和验证记录见 [Windows 说明](docs/windows.md)。Windows 蓝牙需真实适配器和手机验收；暂不支持附件和群聊，局域网内容仍为明文。

## 已实现的功能

| 功能 | 局域网 | 蓝牙 |
| --- | --- | --- |
| 发现设备 | NSD / mDNS 自动发现 | 已配对列表与附近设备搜索 |
| 连接 | TCP；支持输入 IP 与端口 | 安全 RFCOMM；系统配对 |
| 聊天许可 | 首次认可并记住；后续验证同一设备后直接连接 | 共用相同应用信任流程；系统配对仍由 Android 处理 |
| 设备身份 | 本机长期密钥，握手和消息签名 | 共用相同身份 |
| 消息 | 文字、换行、中文、颜文字与 Emoji | 共用相同协议 |
| 送达确认 | 对方保存成功后回执 | 共用相同协议 |

另外包含本地 SQLite 记录、会话摘要与昵称搜索、按会话区分的草稿、跟随系统的深浅主题，以及维持连接的前台服务。主页面为「聊天 / 附近 / 设置」，首页左上「我在」，右上显示真实聊天连接状态，启动图标保持纸杯电话白色底板。服务持续通知可停止所有连接。移出最近任务会停止服务；系统强杀后需要重新连接。

新连接采用 **NIM2 认证封装协议**，不向旧版无认证连接降级。两部设备都需要支持 NIM2（0.2.0 及以后版本）；旧版本机消息仍保留，但旧 UUID 历史不能直接转换成可信设备，升级后首次需要重新绑定身份。

第一版是一对一文字聊天，不包含群聊、文件、语音、互联网转发或自动重发。

## 生成 APK

安装支持 AGP 8.13 的 Android Studio，使用 JDK 17，安装 Android SDK Platform 36 和 Build Tools 35.0.0，以及 Python 3（用于生成资源一致性检查）。

1. 用 Android Studio 打开包含 `settings.gradle.kts` 的项目根目录。
2. 等待 Gradle 同步完成。已附官方 Gradle 8.13 Wrapper，下载会校验 SHA-256。
3. 选择 **Build → Generate App Bundles or APKs → Generate APKs**，或在项目终端执行：

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

生成路径：`app/build/outputs/apk/debug/app-debug.apk`。当前调试安装包命名为 `NearbyIM-0.3.0-debug.apk`，使用 Android 调试签名，供安装测试。把同一个 APK 安装到两部安卓手机。此版本的最低系统配置为 Android 8.0 / API 26，目标 SDK 为 36。

第一次同步需要访问 Google Maven、Maven Central 与 Gradle 分发服务器。SDK 路径由 Android Studio 写入本机 `local.properties`，不要把它提交到仓库。

已附 `.github/workflows/android.yml`，仅支持手动运行。在 GitHub 的 Actions 页面选择 **Build 我在 debug APK → Run workflow**，构建成功后可下载 `wozai-debug` artifact。上传源码和提交修改不会自动编译 APK。本轮使用独立构建分支执行了构建与校验，见 [构建记录](https://github.com/GHOST-AKU/wozai/actions/runs/36876158883)。主分支与常规开发分支仍仅手动触发。

## 两部手机怎么聊

### 局域网

1. 两部手机加入同一 Wi-Fi；也可以一部开启手机热点，另一部加入。
2. 双方打开我在，在「附近」页选择「局域网」并「开启接收」。
3. 一方搜索设备，然后「发起聊天」。只需要一方发起连接。
4. 若搜不到，在发起方选择「通过 IP 地址连接」，输入对方「我的连接」显示的完整地址，例如 `192.168.1.20:54321`。端口由系统分配，每次重启接收可能改变。
5. 接收方首次选择「同意并记住」，会话成功建立后双方保存持续授权。发起方点击连接即表达本次同意，不再重复确认；也可选择「仅本次」。

以后打开历史会话点击「连接」即可尝试重连。局域网通过发现服务解析对方当前地址，再校验之前记住的身份；双方仍需处于可达的网络并开启接收。找不到对方时保留记录和草稿，显示重试入口，不声称已连上。

支持私有 IPv4 和 IPv6 ULA 地址；地址直连不接受域名或互联网地址。热点、访客 Wi-Fi、校园网可能开启客户端隔离；自动发现受阻时可试直连，但客户端隔离也可能阻断直连。换 Wi-Fi 后停止再开启接收，以更新广播与地址。VPN 可能改变路由，需要检查其局域网访问设置。

### 蓝牙

1. 两部手机都安装我在，选择「蓝牙」，授权后「开启接收」。
2. 被连接的一方点击「允许被发现 · 120 秒」，同意系统提示。
3. 另一方「搜索设备」，点击对应设备「发起聊天」。第一次连接按系统提示在两部手机上配对。
4. 接收方首次选择「同意并记住」后即可收发消息。已经配对的设备通常可直接出现在列表里；仅有系统配对记录不会自动获得「我在」的聊天信任。

已聊过且记住的蓝牙设备，从历史会话点击「连接」即可通过保存的设备地址尝试重连，不需要反复开启可发现性和应用内同意。对方仍需在范围内、开启蓝牙并运行接收服务；权限或系统配对失效时仍需按系统提示处理。

蓝牙列表也会包含没有安装我在的其他蓝牙设备，这些设备不能建立聊天。使用经典蓝牙 RFCOMM，Android 模拟器无法替代真实双机蓝牙验收。系统关闭蓝牙或撤销权限会中断连接。

## 消息状态与数据

- **待确认**：已保存到本机，尚未收到对方的保存回执。
- **已送达**：对方已把消息保存到本机；不表示已阅读。
- **未确认**：连接中断或消息未能发送，无法确认对方是否收到。不会自动重发，以免重复。

消息按对方设备 UUID 与消息 UUID 去重；不同会话的回执不会相互影响。数据库错误会停止连接并显示提示，发送文本在本机保存成功前不会清空。每个会话界面显示最近 200 条，数据库保留其他消息；会话菜单中可以清空当前会话。

局域网消息内容仍是**明文**，没有端到端加密。新协议使用本机长期 P-256 密钥，握手证明绑定本次连接，后续帧带方向、序列和签名，用于设备身份连续性与消息完整性；信任记录校验第一次认可的公钥。昵称不是经过认证的真实姓名，首次认识仍需当面确认对方，并使用可信网络。蓝牙采用系统安全 RFCOMM 配对与链路加密，未实现额外的端到端加密。

**清空聊天记录不取消设备信任。** 在设备信息或设置的已信任设备中可单独「取消信任」；之后需要重新认可。仅本次、拒绝、超时或未成功建立的连接不会保存持续授权。已建立的另一位连接不会被新请求静默替换。

记录位于应用私有目录，关闭系统自动备份；应用没有云端同步、注册或遥测接口。卸载应用会删除聊天记录并生成新的本机身份。

## 权限

| 权限 | 用途 |
| --- | --- |
| INTERNET、网络状态 | 局域网 TCP / NSD；不是云端聊天 |
| Wi-Fi 状态、多播锁 | 局域网发现与本机地址显示 |
| 附近设备 | Android 12+ 的蓝牙搜索、连接、可发现性 |
| 精确定位 | 仅 Android 11 及以前系统的蓝牙扫描要求；这些版本通常还需打开系统定位 |
| 前台服务 / connectedDevice | 应用切到后台时保留接收与连接 |
| 通知 | 展示连接状态与聊天请求；Android 13+ 可拒绝，拒绝后通知栏可见性受系统限制 |

targetSdk 36 的局域网访问遵循当前官方规则，使用 INTERNET 权限，不声明 SDK 37 的 ACCESS_LOCAL_NETWORK；未来升级目标 SDK 时需适配对应的局域网运行时权限。

## 开发与验证

需要 JDK 17 和 Python 3，不需要安卓 SDK，也没有 JUnit 等测试依赖：

```sh
sh tools/test-i18n.sh
sh tools/test-core.sh
sh tools/test-trust.sh
sh tools/check-source.sh
```

Windows：

```powershell
powershell -ExecutionPolicy Bypass -File tools/test-core.ps1
```

核心测试使用真实本机 TCP socket，覆盖 UTF-8 分片、消息边界、长度与非法输入、密钥证明、篡改与重放、双方许可、回执、断开、立即发送的许可时序，以及有界消息窗口。收件方最多允许 32 条尚未保存的文字，发件方最多保留 32 条待回执消息；输出队列有界。长时间无心跳响应会释放连接。

信任测试使用 JDK 和 Python 3 自带 SQLite，检查认可规则、撤销、数据库升级和会话摘要。源码检查使用 JDK 解析 Java 与 Python 解析 XML/资源引用，**不等于 Android 编译、AAPT 或真机验收**。这些命令不生成 APK。

完整的安卓构建和设备验收步骤见 [docs/device-test.md](docs/device-test.md)。当前验证记录见 [docs/verification.md](docs/verification.md)。

## 源码结构

| 文件 / 目录 | 职责 |
| --- | --- |
| `i18n/` / `tools/generate-i18n.py` | 多端唯一文案目录、ICU 契约及资源生成 |
| `i18n` Java 包 / `AppLanguage` / `Strings` | 共享语言解析、语义提示及各端显示适配 |
| `MainActivity.java` | 原生 UI、权限与系统弹窗返回、会话草稿 |
| `ChatService.java` | 前台服务与持续通知 |
| `ChatController.java` | 主线程状态与串行数据库任务 |
| `ChatStore.java` / `storage/` | SQLite 记录、会话摘要、独立信任与升级；可测试的信任规则 |
| `AndroidIdentity.java` | Android Keystore 长期设备身份 |
| `transport/` | TCP / NSD、蓝牙 RFCOMM、设备列表 |
| `core/` | 设备密钥、认证通道、有界协议、许可握手、收发和心跳 |
| `tests/` | 不依赖 Android 的真实通信测试 |

应用源码没有额外指定开源许可证。附带的 Gradle Wrapper 与 Google Material 图标保留其 Apache-2.0 许可，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

官方参考：[蓝牙权限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)、[RFCOMM 连接](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices)、[局域网权限](https://developer.android.com/privacy-and-security/local-network-permission)、[AGP 8.13](https://developer.android.com/build/releases/agp-8-13-0-release-notes)、[Gradle 校验值](https://gradle.org/release-checksums/)。
