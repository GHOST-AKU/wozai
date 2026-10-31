# Nearby Mesh 前期研究：五类现有系统的源码证据与独立实现边界

调研日期：2026-10-31。范围：Bitchat、Briar、Meshtastic、Reticulum/LXMF、rust-libp2p。本文是 GHOST-AKU/wozai Nearby Mesh 预研的外部系统研究材料；不构成 NIM4/NIM5 的实现或验收，也不表示已有手机无线多跳、后台常驻、移动相遇或断网实测证据。所有结论分为已读取的上游实现、上游文档/讨论、以及对本项目的设计建议。没有将外部代码复制进产品仓库。

方法：使用 GitHub/Exa 获取公开文档、Issue 与评论；为交叉核查协议、实现、配置、LICENSE 和测试，只读克隆上游源码。以下源码链接固定到实际审阅的提交；官网和 issue 是可变补充证据。研究参考没有作为代码搬入本模块。Android/Apple 及 Wi-Fi Direct 研究见 [移动传输研究](mobile-transports.md)。本报告不是无线设备实测。

## 1. 先区分“邻居连接”“消息多跳”和“携带后交付”

| 系统 | 实际转发对象与多跳性质 | 断连后交付能力 | 对 Nearby Mesh 的适配判断 |
| --- | --- | --- | --- |
| Bitchat | 手机应用在 BLE 连接上做应用层受控洪泛；普通消息、announce、部分定向密文确有中继路径；还包含源路由与失败后的洪泛 | 有持久 outbox、courier spray-and-wait、公开历史同步；Nostr 邮箱另需互联网 | 最接近“手机附近公开广播”的用户体验与受控洪泛，但不能继承其安全性、后台能力或设备实测结论 |
| Briar | BSP 在可见、共享的组中同步不可变消息；论坛等可经共同订阅者逐步传播；私信按联系人单独组，只交付给该联系人 | 组数据库可存储并在再次接触时同步，属于适合断续连接的复制；不是任意陌生人代转私信 | 借鉴 inventory/offer/request/ack、组可见性和状态管理；不能把私信笼统说成 public mesh |
| Meshtastic | 独立 LoRa 无线电节点进行受控洪泛及定向路由；手机 BLE 通常只是客户端到一台无线电的入口 | 有独立 Store & Forward 模块，但受配置、节点角色和硬件内存制约 | 借鉴去重、调度、拥塞与重复抑制的取舍；其 LoRa 覆盖/电耗不能外推为手机 BLE 指标 |
| Reticulum + LXMF | Reticulum 的 SINGLE/Link 通过路径表做真正多跳；LXMF 是上层签名消息与 propagation node 存储转发；当前 PLAIN/GROUP 不等于可多跳频道广播 | LXMF propagation node 持有接收者密文，节点间同步，接收者稍后拉取 | 借鉴路由层/消息层/持久存储层分离；不是直接可用的 Nearby BLE SDK；当前许可证有用途限制 |
| libp2p | transport 建立邻居连接；Gossipsub 在这些连接之上形成多跳主题覆盖网；Circuit Relay 是受限电路转发，不能与 Gossipsub 混为一谈 | Gossipsub 的秒级消息缓存和去重缓存不是持久 DTN 邮箱 | Rust 接口与协议构件有价值；首个最小洪泛 PoC 不必引入整个栈，后续采用需要传输、发现和生命周期适配 |

## 2. 固定版本与许可核查

| 项目 | 本次实际源码提交 | 实际读取的许可及复用选择 |
| --- | --- | --- |
| Bitchat | `04995f68e470a18d1fc99f86fa326c2a87bab4f2`，2026-10-25 | [LICENSE：Unlicense][BL]。可研究；若复用具体文件，仍需检查其第三方来源及依赖许可。本文建议仅借鉴架构并独立实现。 |
| Briar | `fbfebc4242f09e996a867de7988683628d426534`，2026-10-30 | [LICENSE.txt：GPL-3.0-or-later][BRL]。若复制、链接并分发派生程序，需要处理 GPL 条件；不能因“只是一个小模块”直接忽略。 |
| Briar 规范 | `623b8b29b491cca8e7eb109c4738b68d6bd0b28d`，2025-02-01 | [规范 LICENSE：CC BY-SA 4.0][BSL]。文档许可与应用代码许可不同；研究引用与搬用规范原文需分开处理。 |
| Meshtastic firmware | `f5a8aef7c943e7b8f699c15f959ca0652773d190`，2026-10-31 | [LICENSE：GPL v3 文本][ML]。不要无依据写成 MIT 或推断所有子仓库都相同；此处决定是参考行为、独立实现。 |
| Reticulum | `e40191b3d193b46b7f2d8a44424a594cd758839b`，2026-09-30 | [LICENSE：自定义 Reticulum License][RL]；含禁止用于具有蓄意伤害人类功能的系统、禁止直接或间接创建 AI/ML/LM 训练数据集的用途限制及保留通知条件。当前版本不是 MIT，不可凭旧印象标为标准宽松开源许可。 |
| LXMF | `c3ff2d6dc2f256daab896dadc044dd5a913ecbb7`，2026-09-30 | [LICENSE：同名自定义 Reticulum License][LL]，同类用途限制。当前只研究架构；引入代码前需单独确认兼容性和使用条件。 |
| rust-libp2p | `c449090f49b5ae2338e8f0727be98e0a739ba135`，2026-10-27 | [LICENSE：MIT][PL]；`libp2p/Cargo.toml`、`protocols/gossipsub/Cargo.toml` 也声明 MIT。可以评估按许可使用库，须保留通知并审查实际选中依赖。 |
| libp2p 规范 | `c750236de43cd8b246a824b377dfdb26bae686db`，2026-10-09 | 本次根目录文件清单未发现独立 LICENSE；不从 rust-libp2p 的 MIT 推断规范仓库的许可。这里只做来源链接与概括，不搬用规范正文。 |

许可结论用于工程选型，不代替具体分发形态的法律判断。独立实现是“参考公开协议和行为后重新设计自己的格式、状态机和代码”，不是删除版权头后改写变量名。

## 3. Bitchat：确有 BLE 多跳，但 TTL 和 courier 预算不是恶意节点的承诺

**源码所证实的洪泛。** `RelayController.decide` 将输入 TTL 先与 `messageTTLDefault` 取最小值；TTL ≤ 1、本机发送、本机收件时不再转发；定向密文/握手递减 TTL，普通广播根据邻居度数裁剪，在密集图 cap 为 5，并加入随机 jitter。`BLEReceivePipeline.relayDecision` 实际把 courier、ping/pong 等定向包接入该策略，且 `REQUEST_SYNC` 被明确限制为 link-local，避免一个同步请求引发全网响应。[受控洪泛策略][B1]、[接收管线][B2]、[邻居选择器 BLEFanoutSelector][B3] 可作为首个公开消息 PoC 的行为参考。

**去重不是无限期抗重放。** `MessageDeduplicator` 有年龄和数量淘汰；配置的网络去重默认是 300 秒、1000 条。`BLEIngressLinkRegistry.messageID` 拼接发送者、时间戳、类型及 payload SHA-256 的前 4 字节。前缀只含 32 bit，不应在自己的协议中当作完整的密码学消息身份；数量淘汰后旧消息也可能重新进入，需要签名 expiry、持久去重策略与限速共同约束。见 [去重实现][B4]、[配置][B5]、[ID 构造和入口绑定][B6]。这也提示：先验证有界帧、签名及时间范围，再把消息纳入可信去重/转发表，避免无效消息污染缓存。

**DTN 分层存在，但能力要逐项描述。** 当前 `MessageRouter` 有每联系人持久待发送队列、24 小时有效期、重试上限和 courier 副本数限制；`CourierStore` 保存不透明 envelope，执行分层配额、到期清理、持久化以及将可用 copies 对半分配的 spray-and-wait。见 [MessageRouter][B7]、[CourierStore][B8]。公开历史 gossip、私信 courier、Nostr relay 邮箱是不同路径；不能把需要互联网的 Nostr 交付写成“完全断网也可远程交付”，也不能把本地重试队列等同于消息已经被中间节点接管。

**加密必须按当前调用路径判断。** 当前 `BLEService.sendCourierMessage` 优先使用已验证、未过期且有可用 prekey 的 bundle，走 `sealPrekeyPayload`；否则退回 `sealCourierPayload` 的静态 Noise X。消费后的私有 prekey 仍有 48 小时重投宽限，删除之后才缩小未来静态身份泄露的影响。不能宣称全部 courier 路径均有即时前向保密。见 [courier 发送分支][B9]、[NoiseEncryptionService 中两种封装][B10]。同一提交的 [WHITEPAPER §5.2/§9][BW] 仍将 prekey 记作未来工作，落后于实现；本文不照搬那条过时表述。

**移动端证据边界。** [BLERadioController][B11] 明确处理前后台差异、扫描 duty cycle、背景 pending connect 和前台 stale reclaim；这说明无线发现/连接不是一个 Rust 函数即可保证的后台服务。没有对本项目的 iOS/Android 机型、电量、锁屏、厂商省电策略作实测。

## 4. Briar：私信直达与共享组复制必须分开

[Messaging Client 规范][BR1] 明确每联系人使用独立 BSP group；当前 `MessagingManagerImpl.addingContact` 实际为联系人建组、设置可见性、绑定 ContactId，[源码][BR2] 与这个边界吻合。因而 A 给 C 的私信并不会因附近有 B 就自动交给 B 携带。相反，[Forum Client][BR3] 的帖子有作者签名、存储和共享策略，结合 [BSP][BR4] 的组共享规则，A–B 和 B–C 共享同一组时，组消息可先落盘再经 B 同步给 C。这是基于社会关系与组可见性的应用消息复制，不是匿名公众洪泛。

BSP 同步的是不可变消息图，消息 ID 包括组、时间和消息体哈希；每条消息/每个 peer 记录 seen、ack、request、发送次数与下次可发送时间；使用 OFFER/REQUEST/MESSAGE/ACK 并要求指数退避。它对断续连接的启发是“对方缺什么才发什么、明确何时持有/已确认”，不是“只要有一个 TTL 就完成 DTN”。[BSP §2–4][BR4] 同时解释依赖图和验证/交付顺序，适合后续做历史同步时参考。

[Briar 官方 Public Mesh Research 报告][BR5] 将原系统称为 one-hop social mesh，并明确私信只直接送收件人。这个报告讨论的是后续公开 mesh 的研究，不能当作当前应用已提供 public mesh 的证据。规范仓库比应用提交旧，具体功能细节应继续以当前代码为准。

加密也不要沿用错误直觉：Android 当前 `AndroidBluetoothPlugin` 使用 RFCOMM `listenUsingInsecureRfcommWithServiceRecord` / `createInsecureRfcommSocketToServiceRecord`，[源文件][BR6] 中的 “insecure” 指底层配对方式；[BTP 规范][BR7] 在其上提供保密、完整性、认证及明确模式下的前向保密。不能由底层 API 名字推导应用消息明文传输。另一方面，BTP 有 rotation/handshake 模式及密钥删除要求，不能把使用同一术语当作无条件前向保密。Android 调度还依赖 wakeful task 等生命周期设施，见 [AndroidTaskScheduler][BR8]。这些机制不证明 Nearby PoC 已满足手机后台交付。

## 5. Meshtastic：管理洪泛有覆盖率/占空比取舍，手机不是 LoRa 中继

当前 `FloodingRouter::shouldFilterReceived`、`perhapsHandleUpgradedPacket`、`perhapsCancelDupe`、`roleAllowsCancelingDupe` 证明：它有 packet history 去重、等待队列里“更高剩余 hop”版本替换、听到他人重播后取消自己的排队以及 router 角色例外；[FloodingRouter.cpp][M1]、[PacketHistory.cpp][M2] 是实际路径。当前还存在明确的原发端可靠重发例外；不能用“同 ID 只转发一次”的一句话完整描述它。`Router::shouldDecrementHopLimit` 还含 favorite router 策略，[Router.cpp][M3] 说明其 hop 预算带有网络策略语义，并非单纯每无线链路严格减一。

重复抑制不是免费优化。上游 [#8274][M4] 给出 A 同时被 B/C 收到、B 先播导致 C 取消、只有 C 能覆盖的 D 漏收的拓扑；[维护者回复][M5] 确认并建议 ROUTER_LATE 等角色选择。该讨论是机制/故障场景证据，不是本项目真实设备复现。首个 PoC 应先保证小型链/环/菱形和桥接拓扑的确定性转发边界，再把 jitter/fanout/抑制做成可评估策略。

DTN 需另看 [StoreForwardModule][M6]：有历史收集、按窗口请求、返回数量和信道使用率限制；服务器初始化检查 PSRAM 及其可用量。不能写成“所有节点默认持久保存所有消息”或“任何手机都天然是离线邮箱”。专用 LoRa 节点的 airtime、法规占空比、无线范围、CPU/内存与手机 BLE 的约束不同。

当前加密实现也较旧文章复杂：[CryptoEngine.cpp::buildSigningBuffer][M7] 的注释明确 channel crypto 为 AES-CTR without MAC，同时存在覆盖完整 Data envelope、收件人等字段的 XEdDSA 签名；[passesRoutingAuthGate][M3] 在已解码/可解码/不透明转发路径实施不同规则。既不能把“频道加密”当成来源认证，也不能说“当前所有消息都完全无认证”。其源码明确排除会被中继改写的 hop 字段，因此签名仍不证明真实已经历多少跳。

## 6. Reticulum/LXMF：密码学目的地址、路由与 DTN 是三个层次

Reticulum 的 announce 传播为目的地址建立下一跳，Transport 节点根据路径表搬运流量；节点不需要持有整个端到端源路由。[理解 Reticulum 文档][R1]、[Transport.py][R2] 展示实际路径、announce 及去重机制：`PATHFINDER_M=128`，两代 packet hash set 去重；announces 有独立随机调度、重试和接口带宽政策。当前默认 hashlist 大小百万量级，不适合不经测量搬进手机小缓存。

尤其要纠正常见概括：当前 `Transport.packet_filter` 会丢弃已跨过一跳的 PLAIN/GROUP 普通包；SINGLE/Link 才是应讨论的可路由多跳路径。[packet_filter 实际实现][R2] 比“Reticulum 所有广播天然无限多跳”的说法可靠。同一文档 `understanding.rst` 前面写 GROUP 当前不多跳，后面简述却写支持多跳，存在内部矛盾；因此本结论优先使用当前过滤代码。

[AutoInterface 文档][R3] 使用局域 Ethernet/Wi-Fi 介质上的 IPv6 link-local discovery 与 UDP，不要求 DHCP/互联网，但仍要存在可交换数据的物理媒介，AP 隔离或防火墙可阻断。它不是自动实现 Android/iOS Wi-Fi Direct 或 BLE 后台连接的证据；Reticulum 多载体支持不等于已经提供适合本项目的手机 radio/lifecycle glue。

LXMF 在更上层签名消息。`LXMessage.pack` 用目的、来源及 msgpack payload 计算 ID 和签名；`PROPAGATED` 分支保留目的哈希、将其余内容加密给接收者，并对密文求 `transient_id`。[LXMessage.py][L1]；`LXMRouter.enable_propagation`、`offer_request`、`message_get_request`、`clean_message_store` 提供节点间 offer 同步、鉴别后取回、到期（`MESSAGE_EXPIRY=30天`）与可配置空间清理，[LXMRouter.py][L2]。这才是 DTN 层，不能由 Reticulum 的 packet cache/ACK 独自推导离线交付。

密码学边界：`Identity.validate_announce` 的 signed_data 是 destination hash、public key、name hash、random hash、ratchet、app_data，没有 hop count；`Packet.get_hashable_part` 从 raw[2:] 等位置构造，排除可变 hop 字节。[Identity.py][R4]、[Packet.py][R5]。因此“目的身份/密文可验证”不推出“中继路径长度不可欺骗”。当前 LICENSE 的用途限制还要求在考虑实际集成前单独审查，不能标作 MIT 后直接 vendoring。

## 7. libp2p：Gossipsub 真多跳，但不是 BLE 发现或离线投递方案

rust-libp2p 的 `gossipsub::Behaviour::handle_received_message` 处理缓存和验证，`forward_msg` 将消息发往主题 mesh peers，排除传播来源和原作者；[behaviour.rs][P1] 证明确有覆盖网多跳。`ConfigBuilder` 当前默认 history 5 个窗口、gossip 3 个窗口、heartbeat 1 秒、duplicate cache 60 秒；[config.rs][P2]。这里 `fanout_ttl` 是 fanout 状态寿命，不是签名消息的网络 hop-budget。默认 `validate_messages=false` 表示没有启用异步应用层逐条放行；它不等于关闭 `ValidationMode::Strict` 的签名校验。这两个控制不能混为一谈。

[Gossipsub v1.0 规范][P3] 解释 bounded degree、seen cache、IHAVE/IWANT 与 mcache；[v1.1][P4] 加入评分和更严格的 IHAVE/IWANT 开销控制。它限制的是覆盖网连接度/转发与控制开销，不提供绝对的签名 hop 限额，也不把几秒缓存变成持久 DTN。作者身份签名不等于主题内容保密，公开消息仍会向订阅者及转发节点暴露。

[Gossipsub Rust 模块文档][P5] 明确它自身不做 peer discovery；应用还要选择 transport、发现、身份、资源上限和手机生命周期。现成的 TCP/QUIC/WebSocket/WebRTC 等 transport 不能被描述成“有 BLE mesh”；本项目若需要 BLE 仍需平台适配或自有 Transport。另见 [Circuit Relay v2][P6] 的 reservation、时长与数据 cap：这是一台 relay 为两端转发在线字节流的电路协议，不是遇到谁就存给谁的 public message flood，更不是掉线后邮差。

MIT 的 rust-libp2p 有合法复用可行性；但首个独立 Rust bounded-flood PoC 只要验证应用层模型，不必为“显得去中心化”引入发现、NAT 穿透、评分等整套状态。后续是否使用库由传输目标和资源测量决定。

## 8. 对独立 Rust 公开消息 PoC 的明确建议

1. **范围与消息格式。** 只验证公开短消息：域分离、版本、作者公钥、随机 nonce/序号、创建与截止时间、原始 `max_hops`、payload 的规范编码全部入作者签名；消息 ID 使用完整密码学摘要。签名证明来源/完整性，不提供保密、可信昵称、位置隐私或 Sybil 防御。不要把该 PoC 标作端到端加密私信，也不冒充 NIM4/NIM5 完成。
2. **诚实转发与本地资源有界。** 每节点限定邻居、payload、frame、待发队列、缓存条数及总字节，有限 fanout；入口验证后按 message ID 去重，排除 ingress，不转发已到达 budget 的消息。时钟偏差、expiry、缓存淘汰和限流应明确定义。TTL、expiry、seen-cache 三者各自解决深度、时间和重复流量，不能互相替代。
3. **预算不增发是独立安全问题。** 仅签名正文而把 `remaining_hops` 排除，中继可将它重置到允许的全局上限；即使签名了原始 `max_hops`，也只能防止改大原始上限，不能证明每次转发都消耗一跳。Bitchat 的 `toBinaryDataForSigning` 明确将 TTL 设为 0，[BitchatPacket][BH] 是直接证据；Meshtastic、Reticulum 也有上述可变 hop 字段边界。
4. **若 PoC 要验证抗“改单字节增额”，用签名 hop proof。** 作者签名绑定原始 max；每个转发 receipt 签名覆盖 message ID、前一个 proof 的摘要、严格递减的剩余额度、转发者和预期下一接收身份；验证完整链、长度与原始 max 一致，并把链尾签名身份与已认证的入口邻居绑定。必须有负例：把剩余额度改大、删中间 hop、换 payload/作者/expiry/max、换链尾身份、超长链均拒绝。这是本项目独立设计建议，不声称上述上游都使用此协议。
5. **仍需诚实写明无法证明物理跳数。** 可验证链防止修改既有证明，但无全局状态/硬件邻接证明时，恶意节点可能重播自己曾见的旧前缀，串谋者可能隧道包、重新从作者包开始分支、持有多身份或不记录私下搬运；签名链会额外暴露路径身份并放大帧长。若下一跳身份不入签名、链尾不绑定入口邻居，攻击者尤其容易把旧前缀冒充更短路径。PoC 的承诺应限于规定模型中的不可篡改逻辑预算，不宣称可认证真实无线跳数或完全阻止恶意网络放大。
6. **持久化和手机验收分开。** 可用显式 store-and-forward 队列展示“节点 B 存储后，在新相遇的 B–C 链接交付”；需要 disk quota、到期、确认语义、失败状态和重启恢复。若只做到在线 chain flood，就准确记录为在线应用层多跳 PoC。后续真实设备另测 A–B–C 无 A–C 直连、锁屏/后台/进程终止、飞行模式但启用 radio、跨平台、移动断连重连、时延/丢包/功耗；逻辑模拟测试不能替代这些证据。

密码学可变字段的研究依据还包括 [SAODV Internet-Draft §11.1–11.2][S1] 与 [Zapata/Asokan 的 WiSe 2002 论文][S2]：将不可变字段签名，同时用额外机制保护 hop count。SAODV 是路由研究，不是本项目协议；该 Internet-Draft 已过期、不是 IETF 标准。不能因提到 hash chain 就声称解决串谋/wormhole/真实物理邻接证明，也不应复制其历史算法选择到新实现。

[BL]: https://github.com/permissionlesstech/bitchat/blob/04995f68e470a18d1fc99f86fa326c2a87bab4f2/LICENSE
[BRL]: https://code.briarproject.org/briar/briar/-/blob/fbfebc4242f09e996a867de7988683628d426534/LICENSE.txt
[BSL]: https://code.briarproject.org/briar/briar-spec/-/blob/623b8b29b491cca8e7eb109c4738b68d6bd0b28d/LICENSE
[ML]: https://github.com/meshtastic/firmware/blob/f5a8aef7c943e7b8f699c15f959ca0652773d190/LICENSE
[RL]: https://github.com/markqvist/Reticulum/blob/e40191b3d193b46b7f2d8a44424a594cd758839b/LICENSE
[LL]: https://github.com/markqvist/LXMF/blob/c3ff2d6dc2f256daab896dadc044dd5a913ecbb7/LICENSE
[PL]: https://github.com/libp2p/rust-libp2p/blob/c449090f49b5ae2338e8f0727be98e0a739ba135/LICENSE
[B1]: https://github.com/permissionlesstech/bitchat/blob/04995f68e470a18d1fc99f86fa326c2a87bab4f2/bitchat/Services/RelayController.swift#L11
[B2]: https://github.com/permissionlesstech/bitchat/blob/04995f68e470a18d1fc99f86fa326c2a87bab4f2/bitchat/Services/BLE/BLEReceivePipeline.swift#L39
[B3]: https://github.com/permissionlesstech/bitchat/blob/04995f68e470a18d1fc99f86fa326c2a87bab4f2/bitchat/Services/BLE/BLEFanoutSelector.swift#L212
[B4]: https://github.com/permissionlesstech/bitchat/blob/04995f68e470a18d1fc99f86fa326c2a87bab4f2/bitchat/Utils/MessageDeduplicator.swift
[B5]: https://github.com/permissionlesstech/bitchat/blob/04995f68e470a18d1fc99f86fa326c2a87bab4f2/bitchat/Services/TransportConfig.swift#L241
[B6]: https://github.com/permissionlesstech/bitchat/blob/04995f68e470a18d1fc99f86fa326c2a87bab4f2/bitchat/Services/BLE/BLEIngressLinkRegistry.swift#L109
[B7]: https://github.com/permissionlesstech/bitchat/blob/04995f68e470a18d1fc99f86fa326c2a87bab4f2/bitchat/Services/MessageRouter.swift#L130
[B8]: https://github.com/permissionlesstech/bitchat/blob/04995f68e470a18d1fc99f86fa326c2a87bab4f2/bitchat/Services/Courier/CourierStore.swift#L381
[B9]: https://github.com/permissionlesstech/bitchat/blob/04995f68e470a18d1fc99f86fa326c2a87bab4f2/bitchat/Services/BLE/BLEService.swift#L4431
[B10]: https://github.com/permissionlesstech/bitchat/blob/04995f68e470a18d1fc99f86fa326c2a87bab4f2/bitchat/Services/NoiseEncryptionService.swift#L436
[B11]: https://github.com/permissionlesstech/bitchat/blob/04995f68e470a18d1fc99f86fa326c2a87bab4f2/bitchat/Services/BLE/BLERadioController.swift#L79
[BW]: https://github.com/permissionlesstech/bitchat/blob/04995f68e470a18d1fc99f86fa326c2a87bab4f2/WHITEPAPER.md
[BH]: https://github.com/permissionlesstech/bitchat/blob/04995f68e470a18d1fc99f86fa326c2a87bab4f2/localPackages/BitFoundation/Sources/BitFoundation/BitchatPacket.swift
[BR1]: https://code.briarproject.org/briar/briar-spec/-/blob/623b8b29b491cca8e7eb109c4738b68d6bd0b28d/clients/Messaging-Client.md
[BR2]: https://code.briarproject.org/briar/briar/-/blob/fbfebc4242f09e996a867de7988683628d426534/briar-core/src/main/java/org/briarproject/briar/messaging/MessagingManagerImpl.java#L145
[BR3]: https://code.briarproject.org/briar/briar-spec/-/blob/623b8b29b491cca8e7eb109c4738b68d6bd0b28d/clients/Forum-Client.md
[BR4]: https://code.briarproject.org/briar/briar-spec/-/blob/623b8b29b491cca8e7eb109c4738b68d6bd0b28d/protocols/BSP.md
[BR5]: https://code.briarproject.org/briar/public-mesh-research/-/wikis/Public-Mesh-Research-Report/diff?version_id=de7631940d6491cf7aa50dae0f5beb7352dfafb2
[BR6]: https://code.briarproject.org/briar/briar/-/blob/fbfebc4242f09e996a867de7988683628d426534/bramble-android/src/main/java/org/briarproject/bramble/plugin/bluetooth/AndroidBluetoothPlugin.java#L154
[BR7]: https://code.briarproject.org/briar/briar-spec/-/blob/623b8b29b491cca8e7eb109c4738b68d6bd0b28d/protocols/BTP.md
[BR8]: https://code.briarproject.org/briar/briar/-/blob/fbfebc4242f09e996a867de7988683628d426534/bramble-android/src/main/java/org/briarproject/bramble/system/AndroidTaskScheduler.java#L101
[M1]: https://github.com/meshtastic/firmware/blob/f5a8aef7c943e7b8f699c15f959ca0652773d190/src/mesh/FloodingRouter.cpp#L28
[M2]: https://github.com/meshtastic/firmware/blob/f5a8aef7c943e7b8f699c15f959ca0652773d190/src/mesh/PacketHistory.cpp#L66
[M3]: https://github.com/meshtastic/firmware/blob/f5a8aef7c943e7b8f699c15f959ca0652773d190/src/mesh/Router.cpp
[M4]: https://github.com/meshtastic/firmware/issues/8274
[M5]: https://github.com/meshtastic/firmware/issues/8274#issuecomment-3382053385
[M6]: https://github.com/meshtastic/firmware/blob/f5a8aef7c943e7b8f699c15f959ca0652773d190/src/modules/StoreForwardModule.cpp
[M7]: https://github.com/meshtastic/firmware/blob/f5a8aef7c943e7b8f699c15f959ca0652773d190/src/mesh/CryptoEngine.cpp#L110
[R1]: https://github.com/markqvist/Reticulum/blob/e40191b3d193b46b7f2d8a44424a594cd758839b/docs/source/understanding.rst
[R2]: https://github.com/markqvist/Reticulum/blob/e40191b3d193b46b7f2d8a44424a594cd758839b/RNS/Transport.py#L1624
[R3]: https://github.com/markqvist/Reticulum/blob/e40191b3d193b46b7f2d8a44424a594cd758839b/docs/source/interfaces.rst#L37
[R4]: https://github.com/markqvist/Reticulum/blob/e40191b3d193b46b7f2d8a44424a594cd758839b/RNS/Identity.py#L511
[R5]: https://github.com/markqvist/Reticulum/blob/e40191b3d193b46b7f2d8a44424a594cd758839b/RNS/Packet.py#L363
[L1]: https://github.com/markqvist/LXMF/blob/c3ff2d6dc2f256daab896dadc044dd5a913ecbb7/LXMF/LXMessage.py#L360
[L2]: https://github.com/markqvist/LXMF/blob/c3ff2d6dc2f256daab896dadc044dd5a913ecbb7/LXMF/LXMRouter.py
[P1]: https://github.com/libp2p/rust-libp2p/blob/c449090f49b5ae2338e8f0727be98e0a739ba135/protocols/gossipsub/src/behaviour.rs#L2948
[P2]: https://github.com/libp2p/rust-libp2p/blob/c449090f49b5ae2338e8f0727be98e0a739ba135/protocols/gossipsub/src/config.rs#L510
[P3]: https://github.com/libp2p/specs/blob/c750236de43cd8b246a824b377dfdb26bae686db/pubsub/gossipsub/gossipsub-v1.0.md
[P4]: https://github.com/libp2p/specs/blob/c750236de43cd8b246a824b377dfdb26bae686db/pubsub/gossipsub/gossipsub-v1.1.md#L627
[P5]: https://github.com/libp2p/rust-libp2p/blob/c449090f49b5ae2338e8f0727be98e0a739ba135/protocols/gossipsub/src/lib.rs
[P6]: https://github.com/libp2p/specs/blob/c750236de43cd8b246a824b377dfdb26bae686db/relay/circuit-v2.md
[S1]: https://datatracker.ietf.org/doc/html/draft-guerrero-manet-saodv-06#section-11.2
[S2]: https://dl.acm.org/doi/10.1145/570681.570682
