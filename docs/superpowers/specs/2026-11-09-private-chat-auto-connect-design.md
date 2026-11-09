# 私聊直接发送：研究与完整设计

日期：2026-11-09。状态：**研究方案，未批准实施，未修改产品代码**。

基线：main `f59853eac6d08285090a2be033fe973fbffbdc30`，0.4.0-preview.7/build22。
本文由主 agent 独立研究、自我评审；未使用 subagent，未运行构建或设备实验。

## 1. 产品决策

用户希望私聊像公共频道一样，选好对象、输入、发送，不再由双方操作连接按钮。
推荐以**应用层持久待发队列 + 按联系人管理的自动会话**实现。复用 NIM4、Noise、TEXT/ACK、现有 LAN/经典蓝牙；不重写密码学，不升级 NIM5，不把私聊放进公共 Mesh 消息。

最终交互：

1. 首次使用时说明并开启“接收附近私聊”，系统权限按需申请；不要求每个聊天分别启动接收。
2. 选择联系人或附近设备即可进入会话、写字、发送。未连接时先可靠保存，再自动找人、连接、发送。
3. 已记住且公钥匹配的联系人自动接入；未知来访者仍有一次明确的接受/拒绝。记住身份和人工核验安全码是两件事。
4. 对方暂不可达时显示“等待对方可连接”；不能断言一定关机或离线。重连不需要双方按按钮。
5. 打开其他聊天不关闭现有聊天或文件传输；资源满时排队，不能静默踢掉正在传文件的会话。
6. 接收关闭、系统权限、蓝牙配对/可发现性、身份变化等需要用户操作的情况可提示；这些不伪装成程序可自动解决的问题。

这些是本方案的推荐决策，不代表用户已批准具体参数或实现。

### 不包含

- 私聊经 Mesh 的多跳可靠通道、服务器信箱、公网 NAT 穿透、陌生人无条件自动信任。
- 承诺强制停止后继续接收、睡眠电脑仍即时接收、零耗电或任意后台永久在线。
- 自动发送文件、自动下载附件、改变已读隐私设置、自动标记安全码已核验。
- Apple 无线适配或 Wi-Fi Direct Mesh 研发；后续适配同一应用契约即可。

## 2. 当前代码：哪些已存在，哪些缺失

以下路径均以基线提交为准。可在 [固定源码树](https://github.com/GHOST-AKU/wozai/tree/f59853eac6d08285090a2be033fe973fbffbdc30) 查证，不把本次阅读称作新测试。

| 位置 / 符号 | 已确认行为 | 对设计的影响 |
| --- | --- | --- |
| `app/.../ChatController.java`: `start`, `reconnect`, `beginConnect`, `onConnection` | 接收需启动；LAN 重连按设备 ID 重新发现；单个 `active`；忙时拒绝连接 | 需要应用级接收与会话表，不能只改按钮 |
| `app/.../core/ConnectionRole.java`: `decide` | 普通传输本端正拨号时，拒绝入站，即使模式相同 | 双端同时发送可能相互拒绝；不能把拒绝简单改成接纳任意来访者 |
| `app/.../storage/TrustPolicy.java`: `begin` | 已有 pin 匹配或用户主动发起可批准；pin 变化拒绝；未知入站 ASK | 复用策略；连接发起方向与用户同意必须分离 |
| `app-next/.../next/ChatScreen.kt`、`MainActivity.kt`: `sendText` | `canSend` 为 false 禁止提交，需另点重连 | 接受发送意图与当前网络能否发送必须解耦 |
| `app/.../ChatController.java`: `send`, `onText`, `onAck` | 已连接才能发送；先保存再写；接收保存后 ACK | 保留先落盘后回执；新增离线 outbox |
| `app/.../ChatStore.java`, `storage/StoreSchema.java` | 消息主键 `(peer_id,id,outgoing)`；启动把 pending 改 unknown；重复正文检查但未比较 timestamp | 旧 pending 不是自动补发队列；新状态不能复用旧恢复 SQL；跨端重复语义需补齐 |
| `app/.../core/FramedSession.java` | TEXT 需双方 READY；32 个待回执、32 个未落盘；onTextWritten 在完整写入后；ACK 仅认当前登记 ID | 不要把排队当送达；在应用层调度、不扩大 wire 窗口 |
| `app/.../noise/NoiseRecordChannel.java` | 认证后导出双方一致的 connectionGeneration（身份及握手绑定） | 可用于候选连接排序，不修改 Noise 字节或身份 |
| `app/.../ChatService.java` | connectedDevice FGS，START_NOT_STICKY；任务移除会 stopAll | 单纯保存“自动接收”开关不等于后台服务会恢复 |
| `desktop/.../DesktopClient.java` | 单个 `current` 会话；连接中拒绝入站；send 需要 ready | Windows 实际修改点在 Java 内核，不仅是 WinUI |
| `desktop/.../DesktopStore.java`: `receive` | 原子文件持久化；相同 ID 需正文和原 senderTime 一致；保留首次本地收到时间 | 可复用文件后端；不能假设有 SQLite 事务 |
| `windows-next/bridge/.../BridgeMain.java`: `send` | requireSelected + requireReady；保存成功后精确清理对应草稿 | IPC 要支持“接受入队”，同时保留防串人/草稿保护 |
| `windows-next/PROTOCOL.md` | 当前启动不接收、不发现，UI 与单会话内核契约对应 | 启动策略属于明确产品契约变化，须更新文档与测试 |
| `mesh/`、`MeshClient`、`ClassicMeshAdapter` | 公共 Mesh Core 与私聊分开；Android CoC / Windows Mesh RFCOMM 独立服务 | 公共邻居不是 NIM4 联系人，也不是自动可复用的私聊 stream |

私聊经典蓝牙服务 UUID 是 `90c649e1-c095-4b22-8bc3-35e4c9c7b372`；Windows 公共 Mesh RFCOMM UUID 是 `27a34e22-69d1-4d97-93a4-12f231df9ab8`。不能因 Mesh 已连上就认定私聊服务可达。Android Mesh 使用的 CoC/广播标签也不能当作 NIM4 根身份。

## 3. 外部研究与复用结论

通过 Exa 三个检索方向得到 11 个候选结果；排除镜像综述、DeepWiki 和旧 Tox 分支作为结论依据，继续阅读官方协议、官方源码和系统文档。检索数量不是验证通过的实现数量。

| 来源与关键位置 | 阅读到的机制 | 采用 / 不采用 |
| --- | --- | --- |
| Briar `ConnectionRegistryImpl.setPriority/compareConnections` [固定源码](https://code.briarproject.org/briar/briar/-/blob/fbfebc4242f09e996a867de7988683628d426534/bramble-core/src/main/java/org/briarproject/bramble/connection/ConnectionRegistryImpl.java) | 按联系人登记连接，传输偏好和 nonce 排序处理重复连接 | 借鉴认证后的确定性竞争处理，不复制其协商记录 |
| Briar `PollerImpl` [固定源码](https://code.briarproject.org/briar/briar/-/blob/fbfebc4242f09e996a867de7988683628d426534/bramble-core/src/main/java/org/briarproject/bramble/plugin/PollerImpl.java) | 连接事件触发调度、合并较晚任务、随机化后续轮询 | 借鉴事件驱动、任务合并与抖动；不照搬其传输、唤醒策略 |
| [Bramble BSP 官方规范](https://code.briarproject.org/briar/briar-spec/-/blob/master/protocols/BSP.md) §4 | 持久的 seen/ack/send count/send time，offers/requests/messages/acks 分离 | 借鉴持久状态和稳定消息身份；不把整套 BSP 装进 NIM4 |
| Tox [固定 tox.h](https://github.com/TokTok/c-toxcore/blob/189d1074e12e2daa6b0f067570c6cc8acd1fefb3/toxcore/tox.h) `tox_friend_send_message` / `friend_read_receipt` | 发送可能返回 FRIEND_NOT_CONNECTED；所谓 read_receipt 注释说明是接收，不代表人阅读 | 自动连接/离线队列不是换个加密库就得到；保留我们的 delivered/read 区分 |
| Android [蓝牙后台通信](https://developer.android.com/develop/connectivity/bluetooth/ble/background) | 进程死亡会断连接；不推荐周期扫描；FGS/Companion 方案均有前提 | 事件驱动、用户启用接收；不把 GATT autoConnect 当通用 Socket/CoC 重连开关 |
| Android [FGS 启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)、[FGS 类型](https://developer.android.com/develop/background-work/services/fgs/service-types) | Android12+ 后台启动受限，Android14+ 检查类型和权限 | 从前台用户动作启动；继续逐版本核对 connectedDevice 的实际适用条件 |
| Android [WorkRequest](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work) | 周期任务最短15分钟；重试 backoff 最短10秒，执行时间不精确 | 不用 WorkManager 驱动秒级聊天；它至多承担非实时恢复/维护 |
| Windows [Socket broker API](https://learn.microsoft.com/en-us/uwp/api/windows.networking.sockets.streamsocketlistener.enabletransferownership?view=winrt-26100) | 需要注册后台任务与所有权转移，不是任意 WinUI 进程天然被唤醒 | 当前便携桌面应用以运行中接收为承诺；不自动引入 UWP broker/Windows 服务 |

许可证与研究溯源：Briar 固定仓库 `LICENSE.txt` 为 GPL-3.0-or-later；Tox 固定仓库 `LICENSE` 为 GPLv3，具体源文件另受其文件头约束。本方案只复用机制、引用资料，没有复制外部源码或引入依赖，因而不声称已完成将来代码移植的许可审核。真要移植须再审兼容性、文件头和署名。

Briar 源码读取快照 SHA256：ConnectionRegistryImpl `f35f093ddc0eb79b927649fe808a21ebe496cb236903e4588e7a68b57f31690f`；PollerImpl `ede4e25db9dd6df318c6f462be38e72f4a6f7eb44a0b77784c3a6b8e05a92780`。官方文档访问于本日；动态 URL 不代表已冻结的未来平台行为。

## 4. 路线比较与明确选型

| 路线 | 优点 | 无法满足的点 / 成本 | 结论 |
| --- | --- | --- | --- |
| A. 打开聊天自动点一次 reconnect | 小改、直接复用单会话 | 双向竞争、切换对象、离线发送、崩溃恢复仍失败 | 仅可做临时体验，不作为完整交付 |
| B. 持久发送意图 + 自动连接管理 + 有界多会话 | 复用 NIM4，双方不用手动连，状态可解释 | 需要存储迁移、会话所有权重构与针对性回归 | **推荐** |
| C. Mesh 私聊或 Briar/libp2p 全栈替换 | 可进一步研究多跳/同步 | 新信任/路由/协议边界，跨端成本高；当前不是现成 NIM4 传输 | 独立后续 RFC，不阻塞 B |

语言：Android 接收/权限/界面继续 Kotlin，已有共享 JVM 通信/策略继续 Java；Windows 沿用 WinUI C# + Java bridge。策略尽量是无 Android/UI 依赖的 Java 状态机，让两端复用；平台存储和系统监听原生实现。无需加 Rust FFI、新 GUI 框架、Briar/Tox/libp2p 运行时依赖。Apple 后续原生 Swift 实现同一契约，不因共用规范而强行嵌入 JVM。

## 5. 应用架构与职责

```text
Android Compose / Windows WinUI
          │ submitText(target, text, submissionId, draftRevision)
          ▼
PrivateMessagingCoordinator（单线程串行处理事件）
  ├─ OutboxRepository：可靠入队、配额、恢复、回执、取消
  ├─ ContactDirectory：持久根身份、信任版本、阻止/撤销
  ├─ ConnectionSupervisor：候选发现、重试、竞争、会话容量
  │    └─ PeerSession[localRoot, peerId, remoteRoot]
  │         ├─ FramedSession / Noise（保持现有协议）
  │         └─ 该会话独享的文件路由、头像同步、读回执
  └─ PlatformReception：权限、监听、网络事件、进程生命周期
```

UI 选择、连接生命周期、身份授权是三个独立状态；入站消息更新对应聊天及通知，不抢走用户正在看的聊天。所有异步操作携带 localRoot/profile、peer/root、attemptId/sessionGeneration、trustRevision；UI 另带 selection/draftRevision。迟到回调只能影响它所属的任务。

### 身份与授权不变量

- 地址、广告 UUID、MAC、Mesh 标签都是寻址线索。身份只能由现有 NIM4 握手证明，并与 pin 比较。
- 新联系人可先存“等待身份确认”的本地发送意图；得到证明且本地授权后，原子绑定 remoteRoot，才允许取正文发出。未绑定的内容不得自动迁移到另一个同名/同 UUID 设备。
- 主动点击陌生设备发消息只授权该次被选目标，不等于批准任意同时间到来的入站连接。未知入站仍 ASK；已绑定的同一目标可合并请求，不能靠广播 ID 跳过确认。
- pin 变化、撤销、拉黑立即使未发送任务停止；旧正文不自动转交新公钥。恢复须明确重新授权和新 submission，不静默改原 outbox 的收件人。
- 信任撤销是同步屏障：不再开始新的写入，关闭会话并取消队列。已交给内核/对端的数据无法撤回，应显示结果未确认，而不是声称撤销成功删除远端正文。
- 不以“记住设备”显示“安全码已核验”。保留现有人工/二维码核验条件。

## 6. 接收、发现和自动连接

### 接收生命周期

首次前台进入私聊，说明附近设备可发现性和后台接收通知，用户启用一次。后续启动在前台恢复已授权接收，不重复弹开关。拒绝权限不循环申请；UI 给出一次可操作状态。

Android：复用 ChatService 的 connectedDevice 前台服务，从合法的前台动作启动；监听持有权由服务而非聊天页面持有。任务划掉是否保留接收要改为明确策略：**启用后台接收时不再仅因 onTaskRemoved 就 stopAll**，通知始终提供停止；这项行为变化必须单独测试。强制停止、厂商杀进程和重启不承诺自动可达，恢复 APP 后继续队列。收到可用事件也不得绕过后台启动限制。无需常持唤醒锁，不自动加入忽略电池优化名单。

Windows：程序运行时按已保存的接收偏好启动。关闭窗口默认退出；如以后提供托盘“继续接收”，必须显式选择并有真正退出入口。电脑睡眠、程序退出期间只保留待发状态。当前不引入守护进程或开机自启动。

### 寻址与路由

1. 已授权、已认证的现有直连会话优先复用。
2. LAN 用 DNS-SD/NSD 查新地址，不把旧 IP 当身份；蓝牙可尝试该根身份曾用且系统允许的经典蓝牙地址，最终仍校验根公钥。
3. 无可用地址时，在有发送需求/用户打开附近列表的短窗口内发现；LAN 可用性看本地接口，不要求互联网 VALIDATED；不受 WorkManager CONNECTED 网络约束误伤离线 LAN/蓝牙。
4. 两种直连候选都可用时先试近期成功路径，默认同 LAN 优先；另一传输延后尝试，共享全局拨号预算。数据只走认证成功、双方 READY 的会话。
5. Mesh 已发现节点最多作为能力/寻址提示，不暴露或新增未审查的稳定身份广播；未实现安全身份映射时公共节点详情明确显示“私聊暂不可用”，不能虚假承诺每个公共发言者都能私聊。
6. 旧版蓝牙的首次可发现性、系统配对、用户许可可能必须人工操作；这与每次双方点应用“连接”不同。不得强改系统蓝牙或伪造可达状态。

### 连接状态

```text
Stopped → WaitingPermission / Listening
Listening + demand → Resolving → Dialing → Authenticating
 → AwaitingConsent → Ready → Backoff / WaitingReachability
IdentityChanged / Blocked：停止自动重试，等待明确处理
用户停止：取消尝试与定时器，队列保留但不再发送
```

打开已记住聊天可预热一次；真正发送提高任务优先级。入站可独立发生，不要求接收端进入该会话。

## 7. 双向竞争：不能让两边互相拒绝

不修改 Noise 发起者/响应者规则，仍按实际 stream 方向握手；先证明身份，再解决会话竞争。

1. 为正在拨号的目标保留有限入站候选，不能延用“connecting 时所有入站 DISCARD”；陌生候选不能占满已知联系人保留资源。
2. 认证前不取消原拨号、不转移用户授权、不发送正文。候选和主动请求的 expected peer/root/trustRevision 必须匹配。
3. 同一根身份的并发候选在认证后用一致规则排序：优先发起方根公钥较小的连接，再按双方一致的 connectionGeneration 字节排序；公钥按已验证的规范编码字节比较，不比较昵称、地址或不同格式的文本。相同代次视为同一候选。排序只管重复连接，不授权身份，不增加线协议字段。
4. 拨号租约绑定 attemptId，失败释放；规范排序不能变成“较大公钥永远不许主动拨号”。只有一端发送时它必须能主动成功。
5. 允许两端短暂不同步；在有界候选窗口内保留竞争候选，按确定性顺序收敛。若后到的更优候选替换已有会话，必须先完成授权/READY，再原子切换所有权；失败不能先拆掉旧连接。未确认消息保留原 ID 等待安全重试。
6. 文件路由/头像/安全码带原代次；切换时按原有安全流程暂停或恢复，绝不把旧 Noise 状态塞到新 stream。不能以延迟回调关闭新会话。
7. 旧端仍可能拒绝竞争连接。新版采用有上限的随机错峰重试，保留至少一条可完成旧端握手的连接，不要求旧端理解上述排序。旧旧、新旧、新新均须覆盖。

排序逻辑和收敛性目前是**待验证的设计**。实施前必须用独立端点、可控交错/丢事件证明；若既有 API 无法在 READY 前后安全容纳候选，应调整应用会话层，而不是偷偷加 Noise 字段或绕过同意。零时延竞争收敛不作承诺。

## 8. 待发队列、回执与崩溃恢复

### 记录与提交契约

`OutboxEntry` 至少包含：profile/localRoot、peerId、expectedRemoteRoot 或未绑定状态、trustRevision、submissionId、messageId、原文、原创建时间、每联系人 sequence、状态、尝试计数、下次尝试、自动重试截止、最近 attempt/sessionGeneration、失败原因。正文不 trim、不重新生成消息 ID；严格验证 UTF-8 和现有 TEXT 上限。

`submitText` 返回 `Accepted(messageId)` 只表示**本机可靠保存**；返回 `Rejected(reason)` 保留输入。submissionId 是同一次提交的幂等键，重复 IPC/旋转恢复返回同一结果；同键不同正文或目标必须拒绝。用户再次明确点击发送相同文字是新的提交。

Android 在 SQLite 同一事务中写消息和 outbox，配额计数同步提交。Desktop 仍用现有 AtomicFiles：一个私有原子 outbox record 作为权威提交日志，消息列表是可恢复投影；不能把两个独立文件 rename 当作跨文件事务。启动先恢复日志，再发布 UI；写入/同步失败不能确认 Accepted。容量索引可重建但必须有界，不能信任坏文件大小或无限扫描。

数据与现有聊天一样放在系统私有 profile，不声称因此获得磁盘端到端加密。outbox 不进入公开诊断或公共 Mesh。新 schema 的迁移版本在实施时分配；升级事务失败保持旧库可诊断，不擦除重建。旧版程序不支持新 schema 时明确阻止降级打开，不能误读待发日志并发送。备份/恢复到另一 localRoot 时任务暂停，必须重新确认身份和目标。

草稿与队列跨存储，不能假设同事务。持久入队后只清理相同 profile/peer/draftRevision/原文的草稿；提交期间新输入保留。若在清草稿前崩溃，用 submission 关联恢复可重试的草稿清理，不能再次自动提交或丢掉后续文字。Windows bridge 的响应明确区分 `acceptedToOutbox` 与 `delivered`。

### 消息状态

| 内部状态 | 给用户的含义 | 恢复行为 |
| --- | --- | --- |
| Queued / Resolving / Connecting | 已存本机，正在寻找/连接 | 自动调度，不显示已发送 |
| AwaitingConsent / IdentityBlocked | 等待对方接受 / 身份需要处理 | 不发正文；身份失败不盲目重试 |
| AttemptStarted | 已记录一次可能写入的尝试 | 崩溃后视为结果未知，用原 ID 安全重试 |
| WrittenAwaitingAck | 已写出，等待对方确认 | 不能显示已送达；ACK 丢失时仍可能已到达 |
| Delivered / Read | 对方落盘 ACK / 既有已读协议确认 | 单调终态，迟到 written 不能降级 |
| WaitingReachability | 当前没有可用链路 | 等待有效事件，保存待发 |
| Failed / RetryExpired / Canceled | 已停止尝试，正文保留在本机历史 | 手动重试/删除；不静默清空 |

### 正确发送和重试

- 每次发送前重新检查根公钥、授权版本、用户停止/取消、会话 READY 与实际协商文字上限；持久写 AttemptStarted 在调用 session.send **之前**，即使因此把未真正写出的任务保守记为未知，也不把已写出的任务误认成从未发送。
- 重连后重新做 Noise 握手，将同一个 TEXT 的 ID、正文、原 timestamp 在新会话重新加密。**绝不重放旧 Noise 密文、计数器或缓存会话状态。**
- 接收端只在成功持久化后 ACK；重复消息同 ID、同正文、同 timestamp 时不重复展示/通知，仍可回 ACK。相同 ID 不同内容拒绝。Android/桌面/Apple 需针对实际存储语义核对，不把现有实现假定为完全相同。
- ACK 必须属于已认证目标，且该消息在该 session/attempt 有登记。迟到 ACK 可把非终态或已取消但已发出的记录标为已送达；不能复活删除记录、切换收件人、重启发送。当前 FramedSession 的已登记 ID 检查保留。
- 提供“至少一次尝试 + 保留历史期间去重”，不宣称网络 exactly-once。接收方删除历史/去重记录后，重试可能再次出现；协议无跨设备删除同步。清空聊天同时取消本机未发任务；不要把历史删除当成自动补发旧 unknown 的理由。
- 旧版历史 pending/unknown 保留原语义，**不迁移成自动发送**，避免升级后把用户已放弃的消息突然发出。
- 离线提交默认按旧端安全的 8KiB TEXT 上限；在线完成既有协商才提供32KiB。超限保留草稿并解释，不偷偷拆消息。已排队消息仍以当前会话上限校验。
- 文件仍按既有手动授权、分片与恢复路径，不把 URI/文件选取过程混入新文字 outbox。

取消：Queued 可确认停止；AttemptStarted/Written 显示“已停止重试，可能已送达”。没有新远端撤回协议。重试保持原 ID；修改正文属于新的消息提交。

## 9. 多会话、调度与资源边界

最终方案应支持多个私聊会话；只有自动重连一个 `active/current` 不是完成产品目标。实现可分步，但对外不能把过渡实现宣称为完整方案。

初始预算是设计提议，未经真机性能验证，不是测得最优值：

| 资源 | 初始上限 / 策略 |
| --- | --- |
| 已认证私聊会话 | 全局4，每根身份1条活动；平台更低能力时降低，不保证4条无线并发 |
| 未认证握手 / 并行主动拨号 | 各全局2；同目标正常1次，竞争例外最多2候选；分别有超时与配额 |
| 未知身份确认 | 全局最多2，合并同根请求；超限拒绝，不弹窗洪泛 |
| Outbox | 每联系人64、总256，未发正文 UTF-8 总计8MiB，序列化记录/磁盘另计并设独立上限16MiB；两种限制同时满足 |
| 内存发送窗口 | 每联系人4、全局16；尊重 FramedSession 原32上限，不能按四会话无限相乘 |
| 寻址候选 | 全局64、每目标最多4，过期和逐出受控；完整地址仅必要的私有存储 |
| 重试 | 首次立即；之后1/2/4/8/16秒基础间隔，加独立抖动；单个60秒活动窗口最多6次；窗口后等有效可达事件 |
| 重试闸门 | 同根至少60秒才允许新一轮自动窗口；连续发送、新广告和同一系统事件不能重置该闸门；显式重试仍服从全局限速 |
| 无可达事件的保底 | 服务合法运行且有待发、平台无可用 presence 回调时，每5分钟左右（±20%抖动）最多做一次寻址/拨号探测，全局每小时最多12次；不是每轮再拨6次；已订阅的有效 NSD/系统事件优先 |
| 自动重试期限 | 默认24小时，届时 Failed/RetryExpired，保留正文；不是 wire TTL，也不自动删除消息 |
| 空闲连接 | 前台当前聊天短期保温；无待发/文件/核验任务的后台连接可在60秒空闲后回收；监听与已建立连接分开管理 |

单线程 actor 只处理小状态变更；磁盘和阻塞 IO 各用现有有界执行器。状态改变再写磁盘，不按每次 tick/广告落盘；相同事件合并，定时器按截止时间调度，禁止10ms轮询。一次成功写入不把整个应用 CPU 时间当作链路开销。

保底探测只为避免“重试耗尽后等不到新广告，必须手动开关”的死锁，不是对所有 BLE 设备周期扫描。优先尝试已记住的合法地址/事件驱动发现，只有需要寻址且平台允许时才进行有界扫描。没有待发任务、用户关闭接收或进程不在合法运行状态时不保底、不唤醒；长期退出由系统边界决定。一次真实失败和一次广告风暴都不能把预算清零。

拨号窗口、扫描窗口、平台握手超时分开计时；保留现有 Noise/同意期限，不为“更快”缩短安全握手。使用单调时间安排运行中重试；持久截止使用墙钟并记录剩余预算，时钟跳变时夹限并重新计算，不产生负延时空转或无限续期。

公平性：用户提交优先于无需求预热；多个有待发联系人轮转，单联系人每轮最多4条；未知入站不得挤掉已授权资源。正在传文件的会话不做普通 idle 逐出；多个文件任务还受全局磁盘/无线预算，不能只把所有单会话配额乘4。

## 10. 用户界面与可诊断性

- 聊天列表和附近设备均能“发消息”；附近条目先创建临时目标会话，身份绑定成功后合并到正确联系人，不能按昵称合并。
- 输入框发送条件改为“内容合法、目标明确、能可靠入队、配额允许”，不要求当前连接 READY；草稿加载失败仍禁止覆盖。
- 正常操作隐藏手动连接；详情保留重新尝试、停止自动连接、忘记/阻止。现有测试/维护入口可以保留诊断连接功能。
- 不在公共频道自动公布“正在和谁私聊”、稳定私聊根身份或待发数量。公共 Mesh relay 开关与私聊接收是独立授权。
- 界面使用各平台原生组件；新增状态文案走现有 i18n 生成链，覆盖既有语言和屏幕阅读器，避免高频连断造成朗读洪泛。

诊断导出继续提供 TXT/JSON 文件，不要求用户复制：schema/version、硬件与系统能力、权限/接收模式、按 session 的阶段/相对时间、发现到连接/认证/READY/提交到写出/ACK 的本机时差、失败码、重试原因、连接/队列/字节水位、去重、写盘次数、清理未完成。相对时差不能推导未经时钟校准的跨设备单程延迟。

默认不导出正文、密钥、MAC/IP/SSID、完整联系人身份及可跨报告关联的稳定标识；使用每次导出随机盐的代号。需要双端关联的实验 ID 必须显式启用，仅限该次实验。环形事件记录与聚合统计分开，截断需标明，不把有界历史假装成全量。

## 11. 安全与隐私失效模式

| 情况 | 必须行为 |
| --- | --- |
| 同名设备/广告 ID 冒充，IP/MAC 重用 | 校验根身份，不发送队列正文；发现不等于信任 |
| 来访者利用本端拨号骗取 outgoing 权限 | 授权按目标证明绑定，不能由入站方向或 connecting 标志推导 |
| 身份更换、撤销与异步 IO 竞争 | 版本屏障、停止开始新写入、关闭旧会话；已写内容明确不可撤回 |
| 未知设备握手/确认洪泛、Sybil | 全局未认证配额与速率限制；不以“每公钥限额”单独抵御 Sybil |
| 存储满、损坏、权限失效 | 拒绝 Accepted，保留草稿；不先 ACK 再保存；恢复失败可解释且有界 |
| 恶意/重复 ACK、旧会话回调 | 已认证 session + 已登记消息 + 目标根版本校验；状态不回退 |
| APP 停止/账户资料切换 | 取消 timer/discovery/候选/writer，释放 stream；旧 profile 队列不在新身份下恢复发送 |
| 公共 Mesh 正文误复用 | 禁止将私聊 TEXT 交公共广播；未来多跳另做端到端安全 RFC |

## 12. 实施分期与发布门槛

本轮只研究；以下是可据此制定实施计划的工作包，**并非已完成任务**。

1. **应用契约与可测模型**：定义 OutboxEntry/提交/状态/授权代次，拆开 UI selection 和 session；用无平台 actor、虚拟时钟验证调度及竞争。兼容测试先行，不改 wire。
2. **持久化和单目标闭环**：Android SQLite 与 Desktop AtomicFiles 的提交/恢复；断线仍能提交；先保存后清草稿。旧历史不补发。单目标只是中间里程碑。
3. **自动接收及连接竞争**：接收偏好/服务生命周期、原生发现、事件触发重试、双向竞争、旧端兼容和主动停止；保持系统权限与配对提示。
4. **有界多会话**：active/current 拆成会话表；文件/头像/读回执/安全码全部按会话隔离，Android 与 Windows 两端完成；不靠频繁自动切断模拟并行。
5. **界面与 IPC**：直接发送、状态文案、草稿 CAS、通知、诊断文件，更新 windows-next/PROTOCOL.md 及 Android 文档。功能开关可回退手动模式，但禁用时队列不能偷偷继续发送。
6. **代表性完整验证后出 Preview**：针对性回归一次，最终跑相关完整套件；只有版本/产物需要才构建。Android 交付必须原持久证书验签，Windows 原生构建与真实 stream/生命周期验证；不在文档阶段消耗构建资源。

### 验收矩阵（独立于实现的可观察结果）

| 场景 | 核验结果 |
| --- | --- |
| 已记住 A 向 B 发文字；B 停留其他页面 | 双方不点连接；B 自动收到，A 仅收到落盘 ACK 后显示送达 |
| 陌生 A 发给 B | A 只发送一次；B 一次明确接受；拒绝时 B 不收到正文，不自动记住 |
| A/B 同时发送，强制调度各握手阶段交错 | 候选有界、最终同一活动连接、两条消息分别一次展示，无无限连断 |
| A/B/C 三人交错私聊，A 与 B 传文件时给 C 发送 | 不串人、不抢 UI、不切断 B 文件；容量不足明确排队并公平恢复 |
| ACK 在接收落盘后被丢弃，然后断线/重启 | 原 ID/正文/timestamp 重试；接收方不重复展示/通知，再 ACK；发送方最终正确终态 |
| 崩溃点：入队事务前后、草稿清理前后、attempt 标记前后、写出前后、ACK 落盘前后 | 可恢复，不丢草稿或已接受消息；不伪造送达；遗留 unknown 不自行重发 |
| 拒绝权限、蓝牙关闭、LAN 无互联网、IP 变更、配对取消 | 正确停止/等待/恢复，无重复系统弹窗，不错误要求互联网 |
| 假冒广告、公钥变化、撤销发生于 DB/握手/写前 | 私密正文不交给不匹配身份；撤销阻止后续写入，已交出部分如实表示 |
| 单 peer 与 Sybil 洪泛、存储配额、事件风暴、时钟跳变 | 全局上限可测，无无界内存/磁盘/线程增长、空转和重试风暴 |
| 手动停止/任务移除/强制停止/重启/退出/睡眠 | 区分用户停止与系统限制；释放资源；不许靠重启 UI 清空失败状态 |
| 新旧 Android/Windows 双向组合 | 现有 NIM4 握手、TEXT/ACK、文件、安全码不退化；旧端无新字段要求 |

逻辑与存储以故障注入和真实 SQLite/文件系统验证，不能只测与实现共享假设的 mock。独立进程集成让网络转发器丢 ACK/断 stream，保留原始日志和确切版本。无线验收沿用已有成功设备作为起点，再只补新自动私聊行为的必要实验；不要要求用户把已经成功的 Mesh 全套从头反复重测。

性能基线必须在相同设备、正文大小、系统/无线状态下实测：首次接收准备时间、发现/连接/READY 分段、暖/冷发送到 ACK 的 p50/p95 与失败样本、恢复时间、空闲/突发 CPU/RSS/线程/写盘、队列高水位、重复次数。以实际样本数和失败率报告，不能只报成功样本或把参数上限叫性能成绩。本研究没有产生任何新性能测量。

## 13. 自我评审与剩余验证

- 已修正“双方都点连接”的错误建模：现有被动接收存在；缺的是接收生命周期、发送意图及竞争管理。
- 已区分当前 pending/unknown 与新可靠 outbox，避免升级补发旧私聊。
- 已补充 Desktop 非事务文件后端、草稿跨存储、取消/迟到 ACK/原文保留边界。
- 已分开自动连接、用户同意、根身份 pin、安全码核验；主动请求不批准任意入站。
- 已分开经典蓝牙私聊与 Mesh CoC/RFCOMM，不承诺公共节点直接变联系人。
- 已加入无 presence 事件时的有界保底探测，避免“冷却后永久等新观察”再次要求用户手动重开。
- 已将多会话、文件隔离纳入完整交付；单目标重连不是最终完成。
- 仍须在实施验证：候选排序在新旧端及迟到 READY 下的收敛、厂商蓝牙私聊与公共 Mesh 共存容量、四会话资源实测、Android 各版本接收生命周期、Desktop journal 恢复与配额一致性。它们有具体测试门槛，不作为现在继续无边界研究的理由。

**研究结论：无需改变 NIM4，即可实现已记住联系人直接发送、接收方自动接入；完整可靠体验需要应用层持久队列、自动接收和有界多会话，而非删除连接按钮。** 本文完成后停留在设计阶段，产品代码实施需另获批准。
