# Apple 原生客户端

`apple/` 是同仓库内的 Swift / SwiftUI 原生实现，面向 iOS、iPadOS 17 及以上和 macOS 14 及以上。它处于开发阶段，**没有随 v0.3.2 提供 Apple 正式安装包**。设计、当前验证结果及真机验收范围见 [Issue #31](https://github.com/GHOST-AKU/wozai/issues/31)。

## 原生界面与轻量原则

iPhone 使用系统标签页和导航栈；聊天列表与详情使用 `NavigationSplitView`，由系统根据窗口可用空间折叠或并排显示。iOS/iPadOS 18 及以上使用 `.sidebarAdaptable` 标签页样式，让 iPad 在系统标签页与侧栏之间切换；17 使用系统标签页。侧栏位于系统规定的 leading 一侧，不做左右手动镜像，也不按未发布设备名称或屏幕分辨率硬编码布局。

Mac 使用原生侧栏、工具栏、菜单、快捷键和独立 Settings 窗口。手机和平板使用原生 Form 设置页。图标来自 SF Symbols，字体、语义颜色、选中态、焦点与按钮反馈交给系统；浅色、深色及系统外观可选。支持系统动态字体、文字选择和英文、简体中文。新系统的材质由系统组件提供，不自行绘制仿制玻璃或导航栏。

聊天停在底部时跟随消息；上翻阅读时保留当前页及阅读锚点，新内容通过「新消息」按钮提示，点击后再加载最新页。翻看更早的分页后也可以直接返回最新消息，发送消息会返回最新并保留输入焦点。消息提供系统复制与分享菜单。导航选择使用 `SceneStorage` 按 scene 保存；Mac 的「查找附近设备」快捷键 `⌘⇧N` 通过 `FocusedSceneValue` 只操作当前窗口，聊天内的跳转按钮也按窗口传递。窗口仍共用一套设备身份、聊天存储和当前连接。

应用没有第三方 Swift 包、跨平台 UI 引擎、内嵌 WebView、服务器账号或遥测。使用 SwiftUI、Network、CryptoKit、Security 和系统 SQLite。聊天页以 200 条为一页，查询历史使用时间戳和本地序号组成的游标；不会每条消息重写全量历史。iOS 当前使用单 app scene，可随系统分屏或窗口调整尺寸；不宣称多个 app 窗口之间的连接生命周期已经验收。

## 连接与安全

1. 两端加入可互访的局域网。Mac 可以使用以太网，不要求 Wi-Fi 接口。
2. 在 Apple 客户端的「附近 / Nearby」开启「发现与接收 / Discover and receive」。Bonjour 使用与现有客户端一致的 `_nearbyim._tcp` 服务。
3. 从发现的设备发起连接，或者输入对方显示的局域网 IP / `.local` 名称和端口。应用不提供公网聊天入口。
4. 未记住的设备需要明确认可；设备名称和 Bonjour TXT 仅是未受信任的发现提示。长期根密钥须通过 NIM4 根签名验证，已经记住的设备变更密钥会拒绝连接。
5. 通过聊天菜单比较两端完整安全码，再手动标记已核验。记住设备、加密连接与人工核验是三个不同状态。

NIM4 使用 `Noise_XX_25519_AESGCM_SHA256`，经 CryptoKit 执行 X25519、AES-GCM、SHA-256/HMAC 及 P-256 签名，与 Java 端使用同一握手 prologue、帧格式和根签名绑定。没有 NIM3 或明文降级。传入文字在 SQLite FULL 提交后才发送 ACK；未收到保存回执的消息不会显示 Delivered，也不会在重启后自动重发。

长期设备身份保存在本机 Keychain，使用 `AfterFirstUnlockThisDeviceOnly`；不可读取或损坏时不会静默重置身份。记录和草稿存于 app 容器的 Application Support / NearbyIM 下。**链路加密不等于聊天数据库加密**；当前数据库没有额外的应用层静态加密。

首次局域网访问由系统请求权限。macOS app sandbox 声明网络客户端和服务端权限，Info.plist 声明 Bonjour 服务及本地网络用途。隐私清单声明无跟踪、无数据收集，并为外观与设备名称的 UserDefaults 使用填写用途。

## 当前功能边界

已编写局域网发现/手动连接、双端设备认可、加密文字、持久送达回执、记录/草稿、安全码与原生设置。Apple 文件、图片、头像、经典蓝牙、Wi-Fi P2P 及自动文件选路尚未实现。收到合法文件 offer 时明确回复 rejected，聊天仍可继续，不产生虚假的文件完成回执。

`includePeerToPeer` 当前关闭；不能把 Apple 的此选项当作 Android Wi-Fi Direct 互通。iOS 进入后台会停止发现、结束连接并保存草稿，不承诺后台长期在线。网络恢复后由用户重新发起连接。

本机、模拟器回环互通和编译结果不能替代真机的局域网权限、Bonjour 跨设备发现、吞吐、待机内存和生命周期验证。Apple Distribution、TestFlight、App Store、Developer ID 签名及公证均未提供。现有 Android / Windows / Linux 正式版附件不受这个开发工程影响。

## 构建与开发验证

用支持当前 SDK 的 Xcode 打开 `apple/NearbyIM.xcodeproj`，选择共享 scheme `NearbyIM`。调试到真机需要选择自己的 Apple 开发团队和有效签名。Mac 和 iOS 共用 Swift 核心，但分别使用平台原生界面；macOS 目标不是 Mac Catalyst。

```sh
swift test --package-path apple
bash tools/test-apple-interop.sh

xcodebuild -project apple/NearbyIM.xcodeproj -scheme NearbyIM \
  -destination 'platform=macOS' -configuration Debug \
  -derivedDataPath apple/DerivedData CODE_SIGNING_ALLOWED=NO build

xcodebuild -project apple/NearbyIM.xcodeproj -scheme NearbyIM \
  -destination 'generic/platform=iOS' -configuration Release \
  -derivedDataPath apple/DeviceBuild CODE_SIGNING_ALLOWED=NO build
```

互通测试需要 JDK 17 及以上，只编译现有 Java 核心和测试对端，不重建 APK 或桌面安装包。测试对端使用生产 `NoiseRecordChannel` / `ProtocolV4`，与 Swift `ChatSession` 分别充当 TCP 发起方和接收方，检查双向文字、保存回执、安全码、文件拒绝和 heartbeat。SwiftPM 的 `WozaiInterop` 可执行产品只用于开发验证，不链接进 app。

原生 UI 测试使用固定回环端口 47831 的独立 Java 对端，通过 app 正常的地址输入、认可按钮和消息输入框收发内容。测试使用 Xcode 的 ad-hoc 签名，以生成模拟器 Keychain 所需的身份权限；它不需要开发者证书，也不能用于真机分发。生产 app 不读取测试环境变量，也不内置演示消息或跳过认可的测试后门：

阅读位置回归由 XCTest 通过另一个仅限回环的测试控制端口 47832 驱动 Java 对端，使用生产 NIM4 通道注入消息，检查上翻后的消息坐标、新消息提示与返回最新。Mac 还检查第二个窗口执行快捷键不会让第一个窗口丢失会话。测试控制入口只存在于独立 Java 测试程序，不链接到 app。

```sh
bash tools/test-apple-interop.sh --compile-java
bash tools/test-apple-ui.sh 'platform=macOS' macos
# 替换为 xcrun simctl list devices available 中的模拟器 UUID。
bash tools/test-apple-ui.sh 'platform=iOS Simulator,id=SIMULATOR_UUID' iphone
```

GitHub Actions 的 `Verify native Apple clients` 保留互通日志和原生 XCTest 结果。实际通过或失败的 run、工具链和仍缺少的证据记录在 [#31](https://github.com/GHOST-AKU/wozai/issues/31)，不将工作清单另存为 TODO 文件。

排查 Mac 界面交互时，可以手动触发 workflow 并选择 `validation=macos-ui`，只编译 Java 测试对端和 Mac 原生 UI 测试，跳过无关目标。默认 `full` 仍验证共享核心、Java 互通、双架构 Mac Release、iOS 设备编译及三个原生 UI 目标；同一分支的重复全量运行会自动取消。

## 官方规范依据

- [Tab bars](https://developer.apple.com/design/human-interface-guidelines/tab-bars) 与 [Sidebars](https://developer.apple.com/design/human-interface-guidelines/sidebars)
- [NavigationSplitView](https://developer.apple.com/documentation/swiftui/navigationsplitview) 与 [sidebarAdaptable](https://developer.apple.com/documentation/swiftui/tabviewstyle/sidebaradaptable)
- [Local network usage description](https://developer.apple.com/documentation/bundleresources/information-property-list/nslocalnetworkusagedescription)
- [Network.framework includePeerToPeer](https://developer.apple.com/documentation/network/nwparameters/includepeertopeer)
