# 私聊直接发送：逐平台设计与跨端验收

日期：2026-11-09；状态：**完整设计提案，未实施**。与[通用契约](2026-11-09-private-chat-auto-connect-design.md)共同构成方案，基线同为 main `f59853eac6d08285090a2be033fe973fbffbdc30`。

本次只读源码、官方资料并修改文档。没有编译、安装、设备测试、运行时改动或新增性能数据；没有使用 subagent。文中的类名和接口名，除明确列出的既有符号外，均为建议设计，不是已存在代码。

## 1. 全平台的含义与架构选择

覆盖产品中实际存在的六类客户端：Android、Windows、Linux 桌面、iOS/iPadOS、macOS，以及可在多个桌面 OS 运行的 Rust 终端。iPadOS 与 iOS 同一原生实现，但单独验证多窗口、锁屏和系统能力。

选择“**统一应用契约 + 三套原生/既有语言实现 + 平台能力适配**”：

| 实现家族 | 执行层 | 平台接入 | 原因 |
| --- | --- | --- | --- |
| JVM | 可共享的纯 Java 连接/队列策略 | Android Kotlin/Java；Windows WinUI bridge；Linux Desktop | 沿用已验证 NIM4，不重复写三套策略 |
| Swift | WozaiCore actor/SQLite | iOS/iPadOS、macOS 原生 App | 保持 Apple 原生生命周期和现有 NIMChannel |
| Rust | terminal 的有界事件循环/rusqlite | TUI/CLI 在其已支持的桌面 OS | 终端已是独立 NIM4 实现，不强塞 JVM 或 Mesh Core |

不要求跨语言共享数据库文件、身份文件或源代码。共用的是收件人绑定、状态、回执、重试、安全不变量、NIM4 兼容性和一致性测试数据。把整个聊天引擎再写成一个 Rust/FFI 库会扩大迁移和安全审计范围，本轮不选。

**通用产品承诺**：在双方存在兼容链路且接收进程处于系统允许的运行状态时，用户提交文字后由程序建立连接，对方无需点击连接。已授权联系人无需逐次确认；首次陌生联系仍需接受。允许本地排队不等于保证对端此刻可被唤醒。

## 2. 每种传输能连接哪些端

标记：`已有路径`指基线有对应实现，不代表本轮新增自动发送已实现或每对设备已实测；`待适配`指不得作为已交付能力展示。

| 传输 | Android | Windows Desktop/Next | Linux Desktop | iOS/iPadOS | macOS 原生 | Rust 终端 |
| --- | --- | --- | --- | --- | --- | --- |
| 可路由的 LAN TCP + NIM4 | 已有路径 | 已有路径 | 已有路径 | 已有路径 | 已有路径 | 已有路径 |
| Bonjour/mDNS 发现 | Android NSD | Desktop LanDiscovery | Desktop LanDiscovery | NWBrowser | NWBrowser | mdns-sd |
| 通用经典蓝牙 RFCOMM 私聊 | 已有路径 | 既有 native JNI | 既有 BlueZ D-Bus JNI | 无现成对应通用服务 | 本原生端未实现；不推断系统 API 完全不支持 | 未实现 |
| Network.framework Apple P2P Wi-Fi | 不直接兼容 | 不直接兼容 | 不直接兼容 | 已启用 includePeerToPeer | 已启用 includePeerToPeer | 未实现 Apple 专用接入 |
| BLE 私聊字节流 | 待适配；公共 CoC 不等于私聊服务 | 待适配 | 待适配 | CoreBluetooth 适配设计，待验证 | CoreBluetooth 适配设计，待验证 | 未实现 |
| Apple Wi-Fi Aware / 跨端 NAN | 按设备/API另验 | 未实现 | 未实现 | 新 API，按 OS/设备/权限另验 | 不假设有相同 API/能力 | 未实现 |

**共同基线是 LAN，不是共同路由器强制要求**：只要两个端点之间已有可达的本地 IP 网络并允许互通，NIM4 即可使用；同一 AP 的客户端隔离也可能阻止它。LAN 自动私聊不被称为无线 Mesh。手动填本地地址是发现失效时的降级，不绕过身份验证。

跨端矩阵（链路能力设计，不是新增实测成绩）：

| 两端组合 | 共同基本路径 | 可增加路径 / 限制 |
| --- | --- | --- |
| Android ↔ Windows ↔ Linux Desktop（各两两） | LAN | 经典 RFCOMM，须控制器、配对和服务均可用 |
| iOS/iPadOS ↔ macOS 原生（含同类两端） | LAN | Apple P2P Wi-Fi 候选；系统 flag 不保证每次选择 P2P |
| 任意 Apple ↔ Android/Windows/Linux | LAN | 不把 Apple P2P 当 Wi-Fi Direct；BLE/NAN 是后续独立无线验收 |
| 终端 ↔ 任意原生/桌面端 | LAN | 终端现无蓝牙，不因运行在 Windows/macOS 上自动获得它 |

接收界面按 `capabilities` 展示可用方式、失败原因和平台运行条件，不按“都是蓝牙/都是 Wi-Fi”猜测互通。公共 Mesh 的身份、邻居和传输不能自动当作 NIM4 私聊链路。

## 3. 跨语言应用契约

### 3.1 边界接口与数据

每个实现都提供逻辑上等价的操作，接口可以符合本地语言风格：

| 操作 | 输入 | 成功的精确定义 |
| --- | --- | --- |
| `submitText` | profile/localRoot、target、submissionId、正文、draftRevision | 本机提交日志可靠落盘，返回稳定 messageId，不代表发送/送达 |
| `setReception` | scope（本地网络/可用蓝牙）、enabled | 更新明确的用户策略；实际生效单独用状态报告 |
| `ensureReachable` | 已绑定联系人、触发原因、预算 | 注册/合并连接需求，不在接口内无限等待对端上线 |
| `cancelMessage/retryMessage` | 目标根身份、messageId、授权版本 | 取消未来尝试 / 对同一不可变消息重新调度；不表示远端撤回 |
| `approve/reject` | requestId、已证明的 remoteRoot、授权代次、remember | 只处理该请求；不批准任意同名设备或迟到请求 |
| `stop/close` | profile、是否退出 | 停止任务和接收并释放资源；保留已落盘队列 |

`Target` 区分 `KnownContact(peerId, root)` 与 `UnboundSelection(selectionId, candidateHint)`；后者不能凭昵称/广播 ID 直接转换成根身份。第一次 TOFU 的局限及带外核验沿用通用文档，不声称公开广播可证明真人身份。

共同 Outbox 字段沿用通用文档。规范化根身份用现有 NIM4 校验后的 P-256 DER 公钥字节；新消息 ID 使用各端已有的 UUID 兼容格式。不要为了本地排序而修改 HELLO、Noise、TEXT、ACK 或已存历史 ID。跨语言排序采用无符号字节序，不能让 Java signed byte、Swift Data 或 locale 字符串排序产生不同结果。

入站持久化的去重键在语义上为 `(profile, remoteRoot, peerId, direction, messageId)`。现有数据库可能只按 peerId 或 `(peer,id)`：迁移需把身份版本关系补齐，或通过不可变的联系人 epoch 提供等效隔离；不能假设各端 schema 已一致。旧历史无需伪造缺失的根身份，也不能自动升级为可重发队列。

### 3.2 统一状态与能力报告

共用消息状态、认证/READY门槛和至少一次尝试语义，以通用文档为准。状态报告额外带平台的 `executionEligibility`：

`Foreground`、`AllowedBackground`、`SuspendedByOS`、`UserStopped`、`PermissionRequired/Denied`、`RadioUnavailable`、`NetworkUnavailable`、`UnsupportedTransport`。这是状态模型，不是“后台必可用”的枚举承诺。系统有时不通知进程确切暂停原因，允许 `Unknown`，不得编造诊断。

`TransportCapabilities` 记录 listen/dial/discovery、传输族、有效会话上限、可否后台维持、权限/配对要求及来源；静态 OS 可用性与当前硬件运行结果分开。预算取共同上限与平台有效上限的较小值；后台暂停时有效拨号上限为0，但 outbox 不丢失。

### 3.3 一致性而非共享运行时

设计一份只用于测试的版本化 JSON 事件样例集：给定用户动作、候选/认证结果、时钟推进、落盘结果、ACK和重启点，输出消息状态、发送目标、预算计数和允许的连接集合。Java、Swift、Rust 分别执行相同样例；测试格式不进入网络协议，不增加产品运行时依赖。

至少包含：存储失败不 Accepted；双向连接用相同根字节及 connectionGeneration 收敛；相同 submission 不重复入队；同ID不同正文/时间拒绝；ACK丢失重试不重复通知；身份撤销与迟到回调；旧 pending不补发；拒绝/暂停后无计时空转；资源满时公平排队；profile更换和备份恢复不串身份。样例给的是外部预期，不能由实现自行生成正确答案。

所有端还须真实跨进程/NIM4互通及真实持久化测试，不能用同一套 mock 的绿色结果代替。旧端混用时，对方没有新自动接收策略就仍需事先开启接收，但不能要求它理解新 wire 字段。

旧版本在未知/重复ACK处理上存在差异，例如终端会拒绝未登记ACK。统一的是“不得据此制造送达或串人”，不是偷偷放宽所有端的协议检查；重试与重复连接实现必须在此约束下运行，兼容样例需要包含这些旧端分支。

### 3.4 本地边界与诊断

原客户端/Next/终端的profile和身份继续独立，不为统一体验自动拷贝密钥、合并信任或共用数据库。多个进程/版本争用同一profile时明确失败；多个APP争用同一蓝牙服务/监听资源时报告能力冲突，不能关掉另一APP或把它当作目标联系人。

诊断共用schema字段与隐私规则，各端映射到原生文件导出：Android系统创建文档/分享、WinUI文件选择器、Linux桌面保存对话框、Apple FileExporter/分享、CLI显式输出路径。系统目录可写性和沙箱授权由本机处理，不要求全平台写同一固定绝对路径。报告包括“功能已启用但当前系统不允许运行”的区别，不让用户靠复制屏幕正文反馈。

## 4. Android 独立设计

**覆盖**原客户端 `app/` 与 Compose `app-next/`，不只改 Next 按钮。

- **落点**：`ChatController` 拆为联系人/会话协调与 `PeerSession` 所有权；`FramedSession` 保留 wire 行为。纯 Java 策略与 Desktop 复用；`ChatService` 拥有监听，Activity 只申请权限和提交用户意图。
- **持久化**：`ChatStore` / `StoreSchema` 添加 outbox 事务与索引；旧 `RECOVER_PENDING` 只处理旧状态。队列提交与消息投影同事务，草稿仍按 revision 清理。schema升级失败保留原库，不重置身份。
- **传输**：LAN沿用 NSD/TCP；经典蓝牙沿用现有私聊 UUID 和系统安全 RFCOMM。Mesh的CoC及兼容RFCOMM是独立服务，不换UUID冒充私聊。WIFI Direct现有文件升级路线继续按所属会话授权，不自动扩为普通私聊发现网络。
- **启动**：首次前台告知并启用接收；以后在前台启动时恢复偏好，系统权限按需请求；未知好友仍只弹一份确认。主动发消息自动启动其所需接收/发现，不让用户先去附近页点启动。
- **后台**：按目标版本满足 connectedDevice FGS 的类型、权限和合法启动条件，保留通知停止入口。不把 WorkManager 当秒级聊天循环；不使用常驻唤醒锁/伪音频。划掉任务后是否继续由明确后台接收偏好决定；强制停止、系统杀进程和重启不保证唤醒。
- **恢复/释放**：网络/蓝牙可用事件驱动，重复事件合并；拒绝权限停止重试而非连环弹框。停止按 session 关闭 stream、file routes、avatar sync、reader/writer、timer；代次隔离，旧回调不清空新会话。
- **UI**：原界面和 Next 均以是否可入队决定发送按钮；联系人列表/通知不因入站连接抢选中对象。文件仍逐次选取授权；多会话共享全局限额。
- **验收**：真实 SQLite 事务、Activity重建/草稿并发、服务启停、权限撤销、任务移除/强制停止、与公共Mesh并开、A/B同时发送、A/B文件期间A/C文字；相关 JVM策略/存储/Compose回归及持久证书APK门槛。

## 5. Windows 独立设计

**覆盖**既有 Desktop Java 与 WinUI 3 Next；不把 C# 公共 MeshClient 当作 NIM4 私聊内核。

- **落点**：`desktop/.../DesktopClient.java` 的单 `current`/全局phase拆成有界会话表，共享 Java策略；`DesktopWindow` 与 `windows-next/bridge/.../BridgeMain.java` 调同一 submit/会话操作。C#负责展示、系统选择器和程序生命周期，不另存一份私聊 outbox。
- **存储**：`DesktopStore` 的 AtomicFiles 私有profile复用，采用权威 outbox journal + 可重建消息投影；Windows rename替换、文件句柄关闭、ACL/profile锁和损坏恢复都在该后端测试。不是假设SQLite事务。
- **传输**：LAN/LanDiscovery和既有 `desktop/native/windows_bluetooth.cpp` 私聊RFCOMM保持。已配对缓存可作为hint，仍校验NIM4根身份；不把WinRT公共Mesh服务或蓝牙配对当作应用信任。
- **IPC**：本地 bridge协议增加带 `peerId/rootEpoch/submissionId/draftRevision` 的入队/状态操作和版本能力声明。保留旧 send 的“requireReady/原返回语义”以兼容旧UI，新UI只在检测到队列能力后启用直接入队。IPC版本不是NIM版本，不以向旧桥发送未知命令来猜功能。
- **生命周期**：接收偏好在程序启动、用户会话已解锁时应用。默认关窗口仍退出；如有继续接收选项，必须提供可见托盘状态和真正退出。主窗失焦/最小化不等于停止接收；锁屏可继续收发但不自动已读。睡眠/休眠期间不保证接收；唤醒后重发现、重新握手，不复用失效 socket。
- **进程**：应用关闭取消bridge请求并正常停止子进程，超时终止；父进程崩溃的现有子进程保护继续有效。启动失败、IPC断流须显示待发状态，C#不能把超时请求再造一个submission导致双发。
- **系统**：仓库当前WinUI项目最低Windows10 build19041、unpackaged。防火墙、网卡/蓝牙禁用、配对/UI和用户账户权限均需给出可操作原因，不要求管理员权限或自动改防火墙。不开机自启Windows服务，不借用UWP socket broker承诺后台唤醒。
- **验收**：真实Windows文件/ACL与原生字节流测试、bridge端到端、WinUI草稿与不同联系人的迟到回复、父进程退出/崩溃、锁屏不已读、睡眠恢复；沿用现有打包和许可证来源。无源码变化时不重复重建。

## 6. Linux 桌面独立设计

仓库没有独立 `linux/` 客户端目录；实际是 `desktop/` Java桌面与原生launcher/JNI。必须在这里落地，不另起一套 GTK/Qt UI。

- **共用部分**：与Windows共用DesktopClient/Store/Outbox/策略，Swing UI改为提交而非requireReady；`DesktopWifiGateway` 等升级/文件路线按会话归属保留，不声称已有普通Wi-Fi Direct Mesh。
- **LAN**：沿用LanDiscovery和ServerSocket；接口变化重注册/重解析，mDNS不可用时保留手动本地地址。目标网络不必有互联网；跨子网/访客隔离不保证发现或直达。
- **蓝牙**：现有 `desktop/native/linux_bluetooth.cpp` 已通过 GIO/D-Bus 使用 BlueZ `ProfileManager1/Profile1`，无需重写为raw HCI。复用其私聊UUID、NewConnection fd所有权、蓝牙服务owner校验。ConnectProfile/注册失败要有结构化错误，不把系统权限错误当成“好友离线”。
- **发现**：按BlueZ共享discovery session处理，自身Stop只释放自身请求；不得全局关掉其他程序的扫描，不自动设置全局Powered/Pairable/Discoverable。服务注册仅一份，多个PeerSession共享该监听入口。
- **恢复**：订阅D-Bus NameOwnerChanged、Adapter属性及设备移除；bluetoothd重启退役旧owner和fd，重新注册前检查接收仍启用。绝不接受已退役owner的NewConnection回调，不沿用旧对象路径当新身份。
- **权限/部署**：以普通登录用户运行，系统D-Bus/BlueZ/polkit按发行版决定权限；返回权限/配对请求，不建议sudo运行聊天客户端、修改系统策略或替换蓝牙驱动。应用会话锁继续防两个进程写同profile。
- **后台**：桌面进程运行即可监听；默认关窗口退出，可选托盘保持需明确开启。无强制systemd守护程序。支持有logind的系统时通过PrepareForSleep事件协调恢复；不可用则以网络/蓝牙状态与重连失败检测降级。不安装system-sleep脚本，不阻止整机睡眠来维持聊天。
- **存储**：与Windows一样使用AtomicFiles，但测试Linux权限、链接拒绝、fsync/rename、profile锁；不能用Windows的ACL测试替代Unix权限测试。
- **验收**：现有 `linux_bluez_contract` 与native/Java测试扩展，D-Bus owner更换、配对取消、多fd清理、两个发现客户端互不破坏；真实发行版/BlueZ版本、LAN和蓝牙设备互通分别记录。mock总线不替代真实无线验收。

## 7. iOS / iPadOS 独立设计

### 7.1 已有基础与落点

`apple/Package.swift` 当前最低iOS17；`AppModel` 单session，`RootView`在iOS scene进入background时调 `suspend`，它停发现、断会话、刷草稿。`ChatSession.sendText`仅ready后可发且内部生成消息ID。`ChatStore`已有SQLite与落盘后ACK；`LANNetwork`用NWBrowser/NWListener/NWConnection并启用Apple P2P。

实现拆分：`WozaiCore/PrivateMessagingCoordinator` actor拥有联系人会话表/调度；`ChatStore` actor拥有outbox事务；`ChatSession`仍只负责单条已认证会话，增加发送既有不可变消息的应用入口，不能每次重试生成新ID。`AppModel`回到MainActor呈现状态；不能直接把Android的controller/foreground service概念照搬。

Swift actor在await处可重入，不能把“在同actor”当成跨await原子事务。事务内不await网络；每个await后验证profile/root/session/consent代次。接收先commit后ACK，重复消息不再次触发通知；停用不允许迟到Task把接收重开。

### 7.2 前台直接发送和持久化

- 使用现有Swift SQLiteDatabase，新增outbox/message原子事务与迁移；恢复旧pending→unconfirmed语义不变。SQLite FULL提交成功才Accepted。不能把Swift UI Task启动成功当落盘成功。
- 新增队列状态独立于现有MessageStatus，投影到现有消息列表；提交与草稿清理按同一peer和revision绑定，iPad多窗口共享同一profile协调器，不因某个scene消失而停整个应用的接收。
- 本地网络权限未决定时在前台、用户启用接收/发送时触发；Info.plist保留NSLocalNetworkUsageDescription和`_nearbyim._tcp`服务声明。NWConnection等待/路径原因用于解释权限拒绝，不能用一次连接失败猜测拒绝。
- 接收模式与附近列表开关拆开：关附近页不停止后台所有权；前台接收允许其他页面自动收到。AppModel不能继续用全局busy拒绝所有其他联系人。
- 以LAN为跨平台基线；Apple↔Apple可用includePeerToPeer候选。不得把选择该flag直接显示成“无路由器链路已验证”，应根据实际path和实验条件判断。

### 7.3 后台、锁屏、退出

区分三种状态：短暂inactive不立即拆链；真正background按执行许可停止新调度；返回active恢复偏好/队列和发现，自动重新握手。多scene只有全部进入background时才触发应用级后台策略。

允许用 `beginBackgroundTask` 在已有用户发送期间请求有限收尾时间；到expiration必须停止网络任务、保留未确认状态并endBackgroundTask。不能固定承诺系统给30秒、不能循环申请延寿，也不能用音频/VoIP/location假后台模式保活。BGTaskScheduler不是附近即时消息触发器，URLSession后台HTTP也不接管NIM4 socket。

当前LAN模式下，挂后台被系统暂停后**不承诺收到新私聊**；UI显示“后台接收受系统限制”，发送端保留等待，不假装送达。force-quit后不自动唤醒；再次打开APP恢复队列。锁屏的数据保护策略可能让SQLite不可读：不能发ACK后再发现落不了盘；不要擅自降低现有保护级别以换取后台能力。

这仍满足前台/系统允许运行时的直接发送目标，但不能宣传成“iPhone关掉APP仍离线随时收信”。如果最终产品必须达到后者，需要另外评估服务器推送/信箱等，已经超出本次纯离线、无服务器范围。

### 7.4 Apple 蓝牙与 Wi-Fi Aware 接入设计

为避免把平台支持写成空话，预留明确适配边界，但不把未实现能力计入共同交付：

1. BLE先实现CoreBluetooth central/peripheral → 有界ByteStream，再交现有NIMChannel，选型优先验证公开LE L2CAP CoC接口。PSM由系统动态分配，须有应用服务来发布/发现，PSM与广播都是不可信hint。
2. Android要有独立且明确版本的私聊BLE服务；不能复用公共Mesh CoC监听后直接灌NIM4。需要小型transport RFC确定服务UUID、PSM发现、角色/竞争、最大队列、取消和链路加密/配对策略；不改NIM4 payload。CoreBluetooth的Stream适配须处理partial read/write、runloop事件、取消和积压，不能把Stream当同步无界buffer。
3. 若CoC在目标OS/硬件不可靠，备选GATT有界分片流另行对比；应先复用可维护的成熟实现或系统组件并审许可，不能为“统一”直接换掉已稳定的公共Mesh。
4. Windows/Linux如需加入该BLE私聊服务，也需自己的BLE字节流adapter；终端继续报告Unsupported直到真的实现。不能以Apple↔Android成功宣布六端蓝牙互通。
5. CoreBluetooth后台central/peripheral模式、扫描/广播变化和state restoration必须逐OS验证。最新TN3115在iOS/iPadOS26的特定恢复情形增加AccessorySetupKit条件；不能沿用旧QA1962泛化承诺，也不能把普通手机聊天伪装成硬件配件来绕过条件。
6. Apple已提供独立WiFiAware框架，具备配对、服务声明、entitlement与设备能力条件；TN3151将其与Apple专有P2P Wi-Fi分开。可作为未来NIM4 ByteStream候选，但需确认SDK availability/目标设备、WACapabilities和跨Android配对/服务兼容。设备支持列表不是所有Apple手机，更不是macOS自动具备同接口。

### 7.5 验收

Swift既有Session/Store/Noise/LocalNetwork/LANDiscovery测试扩展；真实SQLite、actor重入与取消、iPad多scene、UIKit有限后台时间、被拒绝的本地网络权限和首次提示均单测/集成。原生设备需验证前台异页接收、inactive/锁屏/background/强退/再启动、iOS17与实际支持的新版本（包括26规则），不以模拟器代替隐私权限或无线结果。

## 8. macOS 原生独立设计

不是把iOS的后台限制套在Mac上，也不是让Mac用户改用旧Java桌面。基线最低macOS14，复用WozaiCore策略和SQLite，但使用AppKit进程生命周期。

- **落点**：与iOS共用Swift协调器和PeerSession/Outbox；`AppModel`不再全局单session。macOS路径不在窗口失焦/最小化时调用iOS suspend策略；各窗口仅控制选中聊天，不持有网络服务。
- **网络**：Network.framework LAN/Bonjour和Apple P2P；保留sandbox网络client/server entitlement。macOS15+有本地网络隐私规则，与macOS14分开测试；不要根据iOS API假造通用“检查权限”开关，尊重实际NW状态和系统提示。
- **生命周期**：默认关闭最后窗口结束接收并退出应用；若用户显式选“关闭后继续接收”，用原生菜单栏状态项保持同一进程，提供停止和退出，不安装launchd helper。菜单栏选项必须真落地后才能显示可用。程序在后台/其他窗口前台仍可运行，但App Nap和睡眠不允许声称实时性有硬保证。
- **睡眠恢复**：AppKit/NSWorkspace系统睡眠/唤醒事件暂停新拨号、保存未知状态；唤醒后重新验证路径和session代次。不能要求禁止睡眠或持续进程断言来维持聊天。
- **退出**：既有MacAppDelegate的terminateLater/草稿刷盘流程扩展为协调器停止、outbox提交与草稿barrier；失败时不默默丢Accepted消息。阻塞网络不得跑MainActor或卡住退出modal loop；超时仅关闭网络，已经提交的数据保留。
- **蓝牙**：本原生产品没有现成RFCOMM adapter。macOS系统API与iOS不同，不能据iOS限制断言Mac绝对不支持；本方案优先LAN/Apple P2P，BLE可共用CoreBluetooth协议规范但runtime、隐私声明与事件循环另验。没有adapter前不显示经典蓝牙已支持。
- **存储/通知**：同SwiftSQLite迁移，保留macOS文件权限与sandbox。仅真正前台可见正文产生已读，窗口不活跃和屏幕锁定时不标已读。通知授权与接收/信任分开，不以拒绝通知禁止前台聊天。
- **验收**：Mac原生UI多窗口、菜单栏模式、最后窗口关闭、Cmd-Q时有DB写、磁盘满退出、macOS14/15+权限差异、睡眠恢复、Mac↔iOS与Mac↔非Apple LAN。签名sandbox构建行为与裸Swift测试分别记录。

## 9. Rust 终端独立设计

终端是独立客户端，支持TUI/CLI，不是公共mesh-lab。基线 `terminal/src/network.rs` 有单活动会话lease，profile是rusqlite，状态输出明确蓝牙未实现。Rust的消息状态词汇也与Java/Swift不同，不能直接替换Java controller就声称终端完成。

- **模型**：新增无UI依赖的协调器事件循环和联系人会话map；保留有界mpsc/线程停止机制，不为本功能强引入Tokio或新的运行时。`network::Runtime` 的全局single lease改成每根身份租约和全局配额；UI selected与各session命令sender分开。
- **数据**：复用profile SQLite、profile文件锁、已有OS密钥保护；事务写outbox/消息/顺序。基线主键 `(peer,id)` 没有direction，须独立migration处理方向碰撞及联系人root epoch，旧数据保留。现有pending→unconfirmed只影响旧消息；不能改成启动全量重发。
- **IO所有权**：现有Profile使用Mutex，事务期间不得持锁等待网络/用户同意；IO回调只提交有界事件，持久化结果后再ACK。generation从既有认证握手导出，不能重用Noise密码状态。读写线程、avatar/file worker按session隔离。
- **TUI**：选择联系人后Enter提交入队，未连接也可保存；状态明确等待/未确认/已送达。开机设置中的LAN接收偏好在TUI启动时生效；陌生人请求用有界可审阅列表，不堵住其他联系人事件处理。
- **CLI**：保留现有 `send` 等待回执及退出码语义；另设计显式 `send --queue` 返回 `accepted_to_outbox`、稳定ID与 `delivery_confirmed=false`。它成功退出仅表示本机保存，不能改旧脚本的0退出码为“已送达”。`status/outbox list` 可在无网络runtime时读待发；`outbox run`为显式前台处理直到截止/取消，不在提交后偷偷fork守护进程。
- **进程/信任**：同profile已有进程锁生效。另一个CLI发现TUI占用时先明确返回profile busy；本轮不绕锁双开SQLite/身份。若以后需要CLI控制运行中的TUI，需单独的本地认证IPC设计，不能为便利开放无鉴权TCP端口。非交互未知身份不能自动同意，返回/记录NeedsConsent；保存队列不等于承认对方身份。
- **平台**：在终端已支持的Linux/Windows/macOS上都以LAN为共同路径，遵循各OS本地网络权限/防火墙。Rust进程不会因为在Mac运行就自动拥有NWBrowser的Apple专有P2P，也不会因为Windows有蓝牙就获得JNI后端。休眠/进程结束只留队列，不承诺系统唤醒。
- **验收**：cargo profile/network/crypto测试、SQLite崩溃点、锁冲突、共享行为样例、TTY与非TTY、SIGINT/EOF及其他OS退出取消、`--queue` vs 旧`send`机器输出/退出码。绝不将mesh-lab的TCP多跳测试冒充terminal私聊队列测试。

## 10. 实施顺序、跨端测试与完成定义

这是完整设计的依赖顺序，不是允许缺端发布为“全平台完成”：

1. **统一规格与外部预期样例**：冻结内部状态含义、身份epoch、排序、submission幂等、存储/取消语义与CLI/IPC版本策略，核对新旧端互通。当前阶段到此为研究交付，不生成正式代码。
2. **三套模型/存储闭环**：Java、Swift、Rust各自完成入队与崩溃恢复；Android SQLite、Desktop AtomicFiles、Swift SQLite、Rust SQLite四个后端分别证明可靠提交。不要先把前两端UI做完再倒推另外两套语义。
3. **六端接收和LAN自动会话**：各平台生命周期/权限与有界多会话落实；Android原界面/Next与WindowsDesktop/Next都接通。有效路径存在时不要求接收方点连接；旧端依然可以手动开启接收后互通。
4. **附加原生路径**：Android/Windows/Linux已有RFCOMM接入新会话管理；Apple专有P2P按实际path验证。新BLE/NAN适配各有transport RFC、可运行spike和设备验收，再转产品实施；不能提前列入“支持”。
5. **跨端和失效验证后分别出Preview**：每次只运行受改动影响的针对性测试，收尾一次代表性完整验证；正式产物按各平台签名/权限/许可证流程，不用文档承诺代替验证。

### 必须覆盖的矩阵

| 层级 | 覆盖方式 | 通过标准 |
| --- | --- | --- |
| 共享行为 | Java/Swift/Rust执行同一外部预期样例 | 收件人、终态、重试边界、确定性竞争结果一致，允许平台明确暂停 |
| LAN全平台两两 | 六类客户端15个无序组合，正反向都发送 | 接收已启用且链路可达时不点连接；稳定ID、落盘ACK、无重复展示；同类双设备另测 |
| 各UI覆盖 | Android两界面、Windows两界面、Linux桌面、Apple各端、TUI/CLI | 无requireReady UI遗留、草稿/选中人不串、系统确认不被绕过 |
| 故障跨语言 | JVM↔Swift、JVM↔Rust、Swift↔Rust | 双向竞争、落盘后丢ACK、重连/崩溃、相同ID内容冲突与迟到回调，真实端点/DB |
| 容量与隐私 | 每套调度+每个平台资源回收 | 到上限排队、不踢文件、无无界队列/写盘/线程，锁屏不误报已读 |
| 存量兼容 | 新/旧JVM、Swift、Rust的双向组合 | Noise和NIM4语义不变；旧历史不自动补发；不要求新握手字段 |
| 无线附加 | A/W/L各RFCOMM组合；iOS/macOS AppleP2P；新BLE/NAN单独 | 明确传输、角色、硬件/系统、无基础设施条件；LAN结果不代替无线结果 |

15组合中的同OS不同实现也计入（例如macOS原生↔macOS终端），但不会据此免除两台实体设备的权限/无线/生命周期验证。进程级功能矩阵可自动化；系统权限、睡眠、前后台、真实无线必须用对应原生环境。每个结果记录端点版本与实际链路，不将“计划覆盖”写成“已通过”。

**全平台方案完整**的判断：本文每端有落点、状态所有权、持久提交、实际链路、系统边界、UI/CLI接入、恢复与验收，不留“以后再适配同契约”的空白。**全平台功能完成**则必须上述共同LAN门槛真实通过；Apple BLE等未完成能力继续显示Unsupported/Pending，不挡已定义LAN体验，但不能宣传全平台无路由器无线互通。

## 11. 官方资料、证据与剩余不确定性

本次查阅上述固定源码树和以下官方原文；没有复用外部实现代码，无新增许可证或依赖。额外Exa检索14个候选结果后阅读原文，旧QA/综述不代替新规则。Microsoft一个launch-resume候选链接抓取失败，不作为结论依据。

- [Apple TN3179 本地网络隐私](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy)：iOS14+/macOS15+；Bonjour权限、未决定状态的后台行为、NW路径拒绝原因；监听TCP与Bonjour并非相同权限行为。
- [Apple TN3151 网络API选择](https://developer.apple.com/documentation/technotes/tn3151-choosing-the-right-networking-api)：Network.framework、Bonjour；明确Apple专有P2P只在Apple间、另有Wi-Fi Aware。
- [Apple includePeerToPeer](https://developer.apple.com/documentation/network/nwparameters/includepeertopeer)：选择启用P2P link technologies，不是Wi-Fi Direct的跨厂商保证。
- [Apple 有限后台执行时间](https://developer.apple.com/documentation/uikit/extending-your-app-s-background-execution-time)：有限任务/expiration/endBackgroundTask义务；不是永久socket后台模式。
- [Apple TN3115 蓝牙恢复](https://developer.apple.com/documentation/technotes/tn3115-bluetooth-state-restoration-app-relaunch-rules)：2025-09-15更新iOS/iPadOS26规则。原始Apple文档JSON引用AccessorySetupKit，正文抓取可能丢链接名称，已核对，不能只用旧QA1962表格。
- [Apple CoreBluetooth后台指南](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html)：central/peripheral模式、扫描/广播和恢复边界；遇到与TN3115版本差异以后者为准。
- [Apple publishL2CAPChannel](https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager/publishl2capchannel(withencryption:))：PSM动态分配且需自行发现；link encryption不等于NIM4应用信任。
- [Apple WiFiAware](https://developer.apple.com/documentation/wifiaware)：设备支持、配对、entitlement、service declaration、运行时能力；后台可连接以APP实际获得执行时间为前提。
- [Apple WWDC2019 CoreBluetooth](https://developer.apple.com/videos/play/wwdc2019/901/) 与 [ExternalAccessory](https://developer.apple.com/documentation/externalaccessory)：BR/EDR上的GATT支持不等于通用RFCOMM socket；MFi accessory接口不能直接套普通手机聊天。
- [BlueZ Adapter API](https://bluez.readthedocs.io/en/latest/adapter-api/)、[Profile API](https://bluez.readthedocs.io/en/latest/profile-api/)、[Device API](https://bluez.readthedocs.io/en/latest/device-api/)：共享发现、注册/注销、连接fd、蓝牙层配对和授权。
- [systemd睡眠生命周期](https://www.freedesktop.org/software/systemd/man/latest/systemd-suspend.service.html)：应用不应通过安装system-sleep脚本处理恢复；采用受支持的会话事件，缺少logind时降级。
- Android/Windows及Briar/Tox研究沿用通用文档第3节的来源和许可边界。

仍待实施前/实施中验证：Apple新OS权限/恢复实际表现；BLE私聊PSM/角色协议及厂商互通；BlueZ发行版授权和多连接；Swift/Rust对共同竞争排序与取消语义的实现；四种存储后端在断电/损坏下的恢复。每项已有归属、降级行为与验收门槛，不以“全平台同代码”掩盖差异。

自我评审已检查：六端均有独立实施落点；没有将Linux误认成新目录或raw HCI实现；没有将Apple后台规则泛化到macOS；没有把CoreBluetooth的BR/EDR GATT当RFCOMM；没有把WiFiAware与Apple专有P2P混淆；没有让CLI排队退出码冒充ACK；没有让旧pending升级后自动补发。仍为设计审查结论，不是运行验证结果。
