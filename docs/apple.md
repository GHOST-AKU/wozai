# Apple 原生客户端

`apple/` 是同仓库内的 Swift / SwiftUI 原生实现，面向 iOS、iPadOS 17 及以上和 macOS 14 及以上。它处于开发阶段，**没有随 v0.3.2 提供 Apple 正式安装包**。设计、当前验证结果及真机验收范围见 [Issue #31](https://github.com/GHOST-AKU/wozai/issues/31)。

## 原生界面与轻量原则

iPhone 使用系统标签页和导航栈；聊天列表与详情使用 `NavigationSplitView`，由系统根据窗口可用空间折叠或并排显示。本开发分支为 iPad/Mac 加入可拖动的分栏边界：默认保持原宽度，拖动时吸附到默认位置或内容区域中线；点击分隔线等宽，长按/右键可恢复默认。比例以 `SceneStorage` 按窗口保存，窗口缩小仅临时钳制，不覆盖宽窗口偏好。窄窗口仍由系统折叠导航；保留设备认可、选中会话、草稿及正在传输的同一会话。分隔线支持 VoiceOver 调整及等宽/恢复默认动作，触摸命中区为44pt，使用系统语义颜色和Mac调整宽度光标。iOS/iPadOS 18 及以上使用 `.sidebarAdaptable` 标签页样式，让 iPad 在系统标签页与侧栏之间切换；17 使用系统标签页。侧栏位于系统规定的 leading 一侧，不做左右手动镜像，也不按未发布设备名称或屏幕分辨率硬编码布局。

Mac 使用原生侧栏、工具栏、菜单、快捷键和独立 Settings 窗口。手机和平板使用原生 Form 设置页。图标来自 SF Symbols，字体、语义颜色、选中态、焦点与按钮反馈交给系统；浅色、深色及系统外观可选。支持系统动态字体和文字选择；应用文案与权限说明提供英文、简体中文、繁体中文、日文、韩文资源，语言选择遵循 Apple 的系统与每 App 语言设置。新系统的材质由系统组件提供，不自行绘制仿制玻璃或导航栏。

聊天停在底部时跟随消息；上翻阅读时保留当前页及阅读锚点，新内容通过「新消息」按钮提示，点击后再加载最新页。翻看更早的分页后也可以直接返回最新消息，发送消息会返回最新并保留输入焦点。消息提供系统复制与分享菜单。导航选择使用 `SceneStorage` 按 scene 保存；Mac 的「查找附近设备」快捷键 `⌘⇧N` 通过 `FocusedSceneValue` 只操作当前窗口，聊天内的跳转按钮也按窗口传递。窗口仍共用一套设备身份、聊天存储和当前连接。

文字页使用最多 200 条消息的确定布局，避免快速插入时懒布局高度估算造成定位漂移；更早的记录通过分页读取。这个上限限制了单页布局范围，但不能替代最大字体、长文本和媒体附件的真机内存/响应时间验收。

应用没有第三方 Swift 包、跨平台 UI 引擎、内嵌 WebView、服务器账号或遥测。使用 SwiftUI、Network、CryptoKit、Security、PhotosUI、ImageIO、Quick Look、Core Image、Vision/VisionKit 和系统 SQLite。聊天页以 200 条为一页，查询历史使用本地插入序号游标；不会每条消息重写全量历史。iOS 当前使用单 app scene，可随系统分屏或窗口调整尺寸；不宣称多个 app 窗口之间的连接生命周期已经验收。

## 连接与安全

1. 与 Android/Windows/Linux 通信时，两端加入可互访的局域网，Mac 可以使用以太网。Apple 设备之间也可保持 Wi-Fi/蓝牙开启，并在两端启用发现，通过 Bonjour 服务尝试系统点对点 Wi-Fi；无需由应用自动开普通热点。此项是开发分支的新能力，已发布 preview.1 不包含它。
2. 在 Apple 客户端的「附近 / Nearby」开启「发现与接收 / Discover and receive」。Bonjour 使用与现有客户端一致的 `_nearbyim._tcp` 服务，并请求附带的 TXT 记录以显示对端昵称、读取设备 ID 提示及排除自身服务。缺少 TXT 时保留服务名称作为发现提示，不把它当作已认可或已核验的身份。
3. 从发现的设备发起连接，或者输入对方显示的局域网 IP / `.local` 名称和端口。应用不提供公网聊天入口。
4. 未记住的设备需要明确认可；设备名称和 Bonjour TXT 仅是未受信任的发现提示。长期根密钥须通过 NIM4 根签名验证，已经记住的设备变更密钥会拒绝连接。
5. 通过聊天菜单比较两端完整安全码，再手动标记已核验。记住设备、加密连接与人工核验是三个不同状态。

NIM4 使用 `Noise_XX_25519_AESGCM_SHA256`，经 CryptoKit 执行 X25519、AES-GCM、SHA-256/HMAC 及 P-256 签名，与 Java 端使用同一握手 prologue、帧格式和根签名绑定。没有 NIM3 或明文降级。传入文字在 SQLite FULL 提交后才发送 ACK；未收到保存回执的消息不会显示 Delivered，也不会在重启后自动重发。

历史页按本机 SQLite 插入序号排序和分页，消息自带的时间仅用于显示；对端时钟超前、倒退或重新投递旧消息都不会把新收到的文字挤出最新页。已有数据库沿用原插入序号，不改写消息时间。查询最多读取 201 条、显示最多 200 条，以额外一行判断是否存在更早历史，恰好满页时不会进入空页。冻结阅读页保留该页自己的分页状态。

消息 UUID 接受生产 Java 合法的大小写并原样保存、回 ACK；身份与传输 ID 继续要求小写。文字与对端昵称按 Java 的空内容规则校验，保留合法 Unicode 空白。连接握手、认可与心跳期限使用 ContinuousClock 单调时间，系统日期调整不参与超时判定；消息仍携带原来的显示时间。

长期设备身份保存在本机 Keychain，使用 `AfterFirstUnlockThisDeviceOnly`；不可读取或损坏时不会静默重置身份。记录和草稿存于 app 容器的 Application Support / NearbyIM 下。**链路加密不等于聊天数据库加密**；当前数据库没有额外的应用层静态加密。打开数据库前会为整个 NearbyIM 存储目录设置系统备份排除标记，覆盖数据库、WAL/SHM 和草稿；已有目录在每次打开时同样处理，标记设置失败则停止初始化。该标记用于后续系统备份，不能清理已经生成的旧备份。

首次局域网访问由系统请求权限。macOS app sandbox 声明网络客户端和服务端权限，Info.plist 声明 Bonjour 服务及本地网络用途。隐私清单声明无跟踪、无数据收集，并为外观与设备名称的 UserDefaults 使用填写用途。

## 当前功能边界

当前开发分支已实现局域网发现/手动连接、双端设备认可、加密文字、持久送达回执、记录/草稿、安全码、原生设置，以及附件 v2 流式文件/图片、多选队列、接收文件夹、系统预览/分享和二维码核验。文件记录当前显示最近 50 条；统一的文字/文件历史分页仍需完善。本开发分支已在发现、监听和服务连接三处启用系统 P2P；文字与文件复用同一条经过身份核验和授权的 NIM4 加密连接。头像、经典蓝牙、Android Wi-Fi Direct 组协商、独立文件链路及自动质量选路尚未完成，不宣称与 Android 全部功能对齐。

附件最大协议上限 10 GiB，以 32 KiB 记录、1 MiB 块哈希、整文件 SHA-256、接收信用窗口及强制检查点传输。每份新文件需要明确接收；选择文件与系统照片/视频入口均可多选（最多 256 项），排队后逐项发送，支持暂停/取消及身份、源文件代际绑定的恢复。接收目录由系统文件夹选择器授权，保存书签；默认在 Documents / Received files，iOS 的 Files 可访问文稿。系统 Quick Look、分享面板及 Mac Finder 提供后续访问。发布使用排他原生重命名，发布中断或重复回执前会重新核验最终字节；不覆盖已存在的目标。移动存储、File Provider、书签恢复及真机断电保证仍待验证。

身份核验页用 Core Image 生成与 Android 相同的 `WOZAI-ID1:` 完整安全码 QR，可通过系统文件导出器保存，或用 Vision 读取有界图片；支持的 iPhone/iPad 通过系统 VisionKit 扫描并按需请求相机权限。图片处理在本机完成；扫码结果不能指定地址或替换身份。标记核验前重新检查当前对端根密钥和连接代际，连接变更会禁用旧页面操作。相机可用性和跨设备扫码仍需真机证据。

仅供开发验证的文字模式仍明确拒绝附件，继续覆盖合法 PAUSE/CANCEL/STATUS 后的聊天与心跳。非法元数据或控制字段按协议错误处理。

`includePeerToPeer` 在 Bonjour 浏览、监听和服务连接中一致启用；连接保留 Bonjour 服务端点，解除发现接口的固定绑定，让系统在可用局域网/Apple点对点接口中选择。手动 IP/`.local` 地址连接仍使用不含 P2P 的 LAN 参数，维持现有跨平台能力。系统点对点 Wi-Fi 不等于 Android Wi-Fi Direct；不使用私有 AWDL API，不强制5GHz，不自动开热点，也不承诺当前路由最快或已实现中断后自动换路。

路径通过 `NWConnection.pathUpdateHandler` 获取。聊天与文件气泡显示真实可证明的 Wi-Fi/以太网/回环/系统本地路径；设置页可分享不含私钥、临时组凭据或聊天内容的连接诊断。`apple_peer_to_peer_enabled=true` 表示接口已允许，不是建链成功证据。`availableInterfaces` 仅记为可用接口列表，不能当作实际选中的接口；仅凭 Wi-Fi 不能标记为“Wi-Fi直连”。历史文件未持久记录路径时显示“未记录路径”，不会用当前连接替代历史路径。

验证无路由器 Apple P2P 时，两端打开本分支同源版本，保留 Wi-Fi/蓝牙开关、断开普通路由器/热点，在“附近”打开接收并从发现结果连接，完成双端认可、文字保存回执、文件确认和字节核验；再记录断网条件、系统版本、连接诊断及实际速率。模拟器/回环只能证明参数与协议互通，不证明系统射频建网或性能。Wi-Fi Aware 是另一套有系统/硬件/配对与权限要求的API，本分支不增加该依赖或降低现有最低系统版本。iOS 进入后台会停止发现、结束连接并保存草稿，不承诺后台长期在线。网络恢复后由用户重新发起连接。Mac 正常退出通过 NSApplicationDelegate 的 terminateLater 等待草稿落盘：同步截取当前草稿，取消旧的延迟写入，在后台等待旧写入结束后保存快照，再通过主运行循环（含 AppKit 模态模式）回复允许退出，避免退出循环阻塞 MainActor 造成死锁；失败则取消退出并显示系统警告，内存草稿保留。iOS 后台保存复用同一写入排序。强制终止或崩溃不属于正常退出回调的保证范围。

本机、模拟器回环互通和编译结果不能替代真机的局域网权限、Bonjour 跨设备发现、吞吐、待机内存和生命周期验证。Apple Distribution、TestFlight、App Store、Developer ID 签名及公证均未提供。现有 Android / Windows / Linux 正式版附件不受这个开发工程影响。

## 构建与开发验证

用支持当前 SDK 的 Xcode 打开 `apple/NearbyIM.xcodeproj`，选择共享 scheme `NearbyIM`。调试到真机需要选择自己的 Apple 开发团队和有效签名。Mac 和 iOS 共用 Swift 核心，但分别使用平台原生界面；macOS 目标不是 Mac Catalyst。

```sh
python3 tools/validate-apple-localizations.py
swift test --package-path apple
# 在 macOS 上使用真实 Bonjour 广播检查昵称、设备 ID 提示和自身服务过滤。
swift test --package-path apple --filter LANDiscoveryTests
# 同时验证独立 AppKit 进程在立即正常退出前保存草稿。
bash tools/test-apple-lifecycle.sh
bash tools/test-apple-interop.sh

xcodebuild -project apple/NearbyIM.xcodeproj -scheme NearbyIM \
  -destination 'platform=macOS' -configuration Debug \
  -derivedDataPath apple/DerivedData CODE_SIGNING_ALLOWED=NO build

xcodebuild -project apple/NearbyIM.xcodeproj -scheme NearbyIM \
  -destination 'generic/platform=iOS' -configuration Release \
  -derivedDataPath apple/DeviceBuild CODE_SIGNING_ALLOWED=NO build
```

会话回归使用可控写入完成时序验证断连后的“未确认”状态，并实际等待47秒后认可连接，确认认可时间不计入已连接后的心跳窗口。

互通测试需要 JDK 17 及以上，只编译现有 Java 核心和测试对端，不重建 APK 或桌面安装包。测试对端使用生产 `NoiseRecordChannel` / `ProtocolV4`，与 Swift `ChatSession` 分别充当 TCP 发起方和接收方，检查双向文字、保存回执、安全码、Unicode 空白昵称/文字、大写消息 ID，以及文字模式文件拒绝后在途 PAUSE/STATUS 不切断 heartbeat。额外使用生产 `AttachmentTransferV2` 在双 Noise 角色下交换多块文件、核验实际字节、块/整文件哈希与持久保存回执。SwiftPM 的 `WozaiInterop` 可执行产品只用于开发验证，不链接进 app。

原生 UI 测试使用固定回环端口 47831 的独立 Java 对端，通过 app 正常的地址输入、认可按钮和消息输入框收发内容，并通过实际接收授权和保存回执验证附件。测试使用 Xcode 的 ad-hoc 签名，以生成模拟器 Keychain 所需的身份权限；它不需要开发者证书，也不能用于真机分发。生产 app 不读取测试环境变量，也不内置演示消息或跳过认可的测试后门：

阅读位置回归由 XCTest 通过另一个仅限回环的测试控制端口 47832 驱动 Java 对端，使用生产 NIM4 通道连续三轮注入共 240 条未来时间消息，跨过单页上限，再在每轮上翻后注入正常时间的新消息，检查可见性、消息坐标、新消息提示与返回最新。Mac 还检查第二个窗口执行快捷键不会让第一个窗口丢失会话，以及两个窗口打开同一会话时同步草稿。每会话草稿由 AppModel 的共享可观察状态管理，持久写入去抖；Mac 界面回归还会正常退出并重启，检查草稿恢复。发送期间其它窗口继续编辑时，保留新内容。测试控制入口只存在于独立 Java 测试程序，不链接到 app。

```sh
bash tools/test-apple-interop.sh --compile-java
bash tools/test-apple-ui.sh 'platform=macOS' macos
# 替换为 xcrun simctl list devices available 中的模拟器 UUID。
bash tools/test-apple-ui.sh 'platform=iOS Simulator,id=SIMULATOR_UUID' iphone
```

独立 AppKit 进程探针在同一个主线程任务内编辑和清空草稿后立即调用正常退出，父进程在子进程结束后直接检查 SQLite；不依靠 UI 自动化的等待时间复现 400 毫秒窗口。此探针只链接测试工具，生产 app 没有测试开关。

真实 SQLite 写入屏障还覆盖后台 flush 与前台编辑、清空、重叠 flush 的顺序，后续写入不能绕过仍在落盘的快照。核心测试覆盖单调期限与认可后心跳重置的边界，不把这些测试称为实际系统调时的真机复现；Mac UI 另覆盖恰好 200/400 条历史的分页入口。

GitHub Actions 的 `Verify native Apple clients` 保留互通日志和原生 XCTest 结果。实际通过或失败的 run、工具链和仍缺少的证据记录在 [#31](https://github.com/GHOST-AKU/wozai/issues/31)，不将工作清单另存为 TODO 文件。

排查 Mac 界面交互时，可以手动触发 workflow 并选择 `validation=macos-ui`，只编译 Java 测试对端和 Mac 原生 UI 测试，跳过无关目标。若只调整网络回调、核心或编译兼容性，可选择 `validation=core-builds` 验证核心、生命周期、生产Java互通、Mac通用Release和iOS设备构建，跳过已验证且相关UI源码未变的模拟器回归；必须注明复用的精确提交与行为差异，不能把跳过的UI检查写成新跑通过。默认 `full` 仍验证共享核心、Java 互通、双架构 Mac Release、iOS 设备编译及三个原生 UI 目标；同一分支的重复全量运行会自动取消。

## 官方规范依据

- [Tab bars](https://developer.apple.com/design/human-interface-guidelines/tab-bars) 与 [Sidebars](https://developer.apple.com/design/human-interface-guidelines/sidebars)
- [NavigationSplitView](https://developer.apple.com/documentation/swiftui/navigationsplitview) 与 [sidebarAdaptable](https://developer.apple.com/documentation/swiftui/tabviewstyle/sidebaradaptable)
- [ScrollViewReader](https://developer.apple.com/documentation/swiftui/scrollviewreader) 与 [onGeometryChange](https://developer.apple.com/documentation/swiftui/view/ongeometrychange(for:of:action:))，在当前页完成布局后定位，按实际可见性保留阅读位置；[onScrollPhaseChange](https://developer.apple.com/documentation/swiftui/view/onscrollphasechange(_:)) 在 iOS/iPadOS 18、macOS 15 及以上区分用户滚动，较早系统使用手势及可见性反馈
- [Local network usage description](https://developer.apple.com/documentation/bundleresources/information-property-list/nslocalnetworkusagedescription)
- [Bonjour discovery with associated TXT records](https://developer.apple.com/documentation/network/nwbrowser/descriptor-swift.enum/bonjourwithtxtrecord(type:domain:))
- [Network.framework includePeerToPeer](https://developer.apple.com/documentation/network/nwparameters/includepeertopeer)

## 当前开发变更的 English summary

验证可拆分为 `validation=macos-ui` 与 `validation=core-ios`：前者只检查Mac原生UI，后者运行核心、生命周期、生产Java互通、Mac/iOS设备构建和iPhone/iPad原生UI，避免再次运行相同源的Mac UI。复用前必须比较实际源文件、Xcode配置和测试脚本；默认 `full` 仍保留全部检查。

The 0.3.3-preview.2 development branch retains native NavigationSplitView navigation and compact collapse while adding draggable column widths, default/midpoint snapping, equal/reset actions and per-scene proportions for iPad and Mac. It uses Apple components, semantic styling and accessible actions; changing geometry does not recreate the model or connection.

Bonjour browsing, listeners and service connections opt into system peer-to-peer Wi-Fi together. Discovered services are re-resolved without pinning the discovery interface; manual LAN addresses remain available to existing Java clients. The existing authenticated/consent-gated NIM4 stream carries both text and files. This is Apple system P2P support, not Android Wi-Fi Direct group interoperability, forced 5GHz, independent file lanes or measured automatic handover. It does not require a new connection-mode switch.

Path updates identify only publicly evidenced interface types. Wi-Fi and available interfaces do not prove AWDL selection; diagnostics explicitly distinguish enabled capability from confirmed Direct. Completed records without stored path evidence remain unknown. Verify actual offline Apple-to-Apple discovery, consent, files, lifecycle and throughput on physical devices before claiming acceptance. See [TN3151](https://developer.apple.com/documentation/technotes/tn3151-choosing-the-right-networking-api), [TN3111](https://developer.apple.com/documentation/technotes/tn3111-ios-wifi-api-overview), [includePeerToPeer](https://developer.apple.com/documentation/network/nwparameters/includepeertopeer) and [Split views HIG](https://developer.apple.com/design/human-interface-guidelines/split-views).

## 开发中的文字已读 / Text read receipts in development

preview.3/build14原生开发分支新增真实文字已读回执：默认开启，可在隐私设置关闭；双方开启且协商成功才交换。只有活动窗口中实际可见并稳定显示的文字才计入，切到附近页不会误报；离线和重连使用持久化的具体UUID补发。Apple保留原生状态标签，以强调色显示“已读”，图标来源另行考虑；文件保存与打开不等于已读。此改动尚未合并或发布。[协议、隐私和准确验证范围](read-receipts.md)。

The preview.3/build14 native development branch adds real text read receipts, default on with an opt-out under Privacy and reciprocal negotiation. Only stably visible text in the active conversation qualifies; Nearby/background views do not. Durable exact-ID observations survive offline/reconnect. Apple retains its native status label and highlights Read; attachment completion/opening remain separate. This change is not merged or published. See [protocol, privacy and exact verification scope](read-receipts.md).
