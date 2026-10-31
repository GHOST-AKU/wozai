# 移动传输研究与能力边界

调研日期：2026-10-31。**hardware validation pending**。本轮读取了官方 API 文档、实际源码及论文；独立 Android 探针完成 Java/DEX 编译，`adb devices -l` 没有设备。以下“系统支持”不表示 Nearby Mesh 已在该系统实现数据适配或完成无线验收。

## 什么能构成手机无基础设施多跳

一个 Wi-Fi Direct 组内的普通 socket、同一 AP 上的 NSD/广播、单个热点连接数，都不是跨组 Mesh 证明。需要能使 B 同时或先后接触两端，并在应用层转发：

| 方案 | 可形成的拓扑 | 主要限制与后续实验 |
| --- | --- | --- |
| B 同时作为一侧 GO、另一侧普通 Wi-Fi STA | 两个不同的设备自建组，经 B 的应用代理连接 | 依赖 GO/STA 并发、OEM HAL/芯片、频道与子网；必须把 socket 绑定正确 `Network`，不能假定操作系统自动转发 |
| B 轮换加入两个组 | 延迟容忍的接触图 | 不是持续在线链；切换耗时、旧连接中断，必须设计持久消息队列/同步协议。本 PoC 不提供这类可靠 DTN |
| BLE central + peripheral / GATT server/client | B 维持两个逻辑邻接，无共享 AP | 手机支持的角色、连接数、MTU、连接参数及后台存活必须逐机验证；需要分片、重组、取消及背压 |
| Wi-Fi Aware 多条 NDP | 无 AP 的邻居 IP 链接 | 芯片/系统可用资源有限，Aware 存在不代表当前可用；它建立点对点数据路径，不自动路由 |
| 混合 BLE ↔ B ↔ Wi-Fi Direct/Aware/Classic | 不要求 B 有两个相同无线接口 | 优先可行替代方案；增加跨载体帧长、流控、断连恢复和生命周期复杂度 |

设备生成的组可以是“无外部基础设施”，但 A/C 若都连到 B 的同一个热点，仍不能用作本任务的严格链形多跳验收。实验必须证明 A–B、B–C 是独立邻接，以及 A/C 互不可达。

## Meshrabiya（常被拼作 Meshrabiyah）：读源码后才能判断其 Mesh

实际审阅 [UstadMobile/Meshrabiya `24e3c0430d27cc8ab929da77ae0f59e2195eb137`](https://github.com/UstadMobile/Meshrabiya/tree/24e3c0430d27cc8ab929da77ae0f59e2195eb137)。[LICENSE](https://github.com/UstadMobile/Meshrabiya/blob/24e3c0430d27cc8ab929da77ae0f59e2195eb137/LICENSE) 为 LGPL v3 文本；库与 Android 集成/重新链接要求需要按具体分发确认，本轮不复制代码。

- [`WifiDirectManager.startWifiDirectGroup`](https://github.com/UstadMobile/Meshrabiya/blob/24e3c0430d27cc8ab929da77ae0f59e2195eb137/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/WifiDirectManager.kt#L316) 调用 `createGroup`，可用预设 SSID/口令创建 GO，并接收实际 group-info 回调。这不是任意多个同时 P2P group 的公开 API。
- [`MeshrabiyaWifiManagerAndroid`](https://github.com/UstadMobile/Meshrabiya/blob/24e3c0430d27cc8ab929da77ae0f59e2195eb137/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/MeshrabiyaWifiManagerAndroid.kt#L301) 使用 `WifiNetworkSpecifier`/`NetworkRequest` 连接上游设备热点，移除 Internet capability，并为连接绑定 socket（同文件约 L524–580）。它还读取 `isStaApConcurrencySupported`、持有 Wi-Fi lock。这说明拓扑依赖普通 STA + 自建 GO/LocalOnlyHotspot/替代载体，而非“Wi-Fi Direct 自动 mesh”。
- [`VirtualNode.route`](https://github.com/UstadMobile/Meshrabiya/blob/24e3c0430d27cc8ab929da77ae0f59e2195eb137/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt#L427) 有实际的本地递交/下一跳转发和 `maxHops` 检查；[`OriginatingMessageManager`](https://github.com/UstadMobile/Meshrabiya/blob/24e3c0430d27cc8ab929da77ae0f59e2195eb137/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/OriginatingMessageManager.kt#L225) 维护 originator 与 hop 信息。它确有应用虚拟网络路由，不能归为只有热点共享；但上游测试通过不等于我们的设备支持同样并发，也不等于消息已具备本轮来源签名/抗重放策略。

Funai、Tapparello、Heinzelman 的论文 [Supporting Multi-hop Device-to-Device Networks Through WiFi Direct Multi-group Networking](https://arxiv.org/abs/1601.00028)（[正文](https://arxiv.org/html/1601.00028)）区分轮换组的 TCP time-sharing、GO/legacy-client 同时连接及混合 UDP/TCP；§III–IV 描述 stock Android 的接口与路由约束，结论仍将路由协议/更多接口支持列为后续工作。其旧设备的性能不能作为当代 Android 或本项目的测量成绩。论文是相关方案的实验依据，不是“所有 Android 手机能多组并发”的保证。

## Android 官方机制

1. [Wi-Fi Direct overview](https://developer.android.com/develop/connectivity/wifi/wifip2p)：`initialize/discoverPeers/connect/createGroup/requestGroupInfo` 是发现和组连接 API。`ActionListener.onSuccess` 只确认请求，必须进一步等待 group/connection-info 并验证 socket 数据通路。GO 是组中心；权限包括相应 Wi-Fi 状态/更改、Internet socket 权限，以及按目标 SDK 分支的附近设备/位置权限。`INTERNET` 权限不表示实际需要互联网。正式适配不应挪用 NearbyIM 为私聊文件尝试专门授权的 Wi-Fi Direct 自动连接策略。
2. [AOSP STA/AP concurrency](https://source.android.com/docs/core/connect/wifi-sta-ap-concurrency)、[multi-interface concurrency](https://source.android.com/docs/core/connect/wifi-hal)：HAL 的 interface combinations、驱动和独立 MAC 能力决定并发。STA/AP 支持标志不能当作任意 GO/GC 双组保证。同子网冲突可导致路由问题；不同信道的时分共享会显著影响吞吐。要记录机型、系统、role/interface/Network 绑定结果，并实际尝试两个链接同时承载。
3. [Wi-Fi Aware](https://developer.android.com/develop/connectivity/wifi/wifi-aware)：Android 8+ 可发现并建立无 AP 的双向 NDP；系统管理 cluster，应用不能自行控制 cluster。Android 12+ 可读 `getAvailableAwareResources`；发布/订阅和数据路径均有资源上限。部分设备在 P2P/SoftAP/tethering 活跃时不能同时使用 Aware。Android 13 的 instant communication mode 会额外耗电且限时 30 秒。它值得作为现代机型首选候选，但仍须应用层路由。
4. [BLE 后台通信](https://developer.android.com/develop/connectivity/bluetooth/ble/background)：扫描 PendingIntent、CompanionDeviceService、`connectedDevice` foreground service 是不同生命周期工具。进程被杀，GATT 连接仍会关闭；Android 12+ 从后台启动 FGS 受限。已关联 companion 不适合无差别陌生节点免交互“自动信任”。扫描/广播支持、同时 central/peripheral、GATT callback 与 MTU 必须测量，不能以 BLE 名称直接推断标准 Bluetooth Mesh Profile。
5. [Bluetooth Classic RFCOMM](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices)：server/client 获得字节流；连接和 accept 阻塞，应在独立任务中运行并能取消。安全 RFCOMM 可能触发系统配对；不能绕过确认。B 能否维持两路连接、SDP/channel 分配和吞吐要逐机测试。Classic 通常更适合 Android/桌面，不能假设 iOS 普通应用支持任意 SPP。
6. [NSD](https://developer.android.com/develop/connectivity/wifi/use-nsd) 是 DNS-SD 本地网络服务发现，不建立无线媒介，也不提供身份信任。PoC 的可选 `--discover` 使用成熟 `mdns-sd`，独立进程限额与随机广告名；它属于 LAN 候选发现，不是无线 mesh 实现。
7. [FGS 类型](https://developer.android.com/develop/background-work/services/fgs/service-types)：Android 14+ 必须声明适当类型与权限；`connectedDevice` 等类型有运行前提。需要常驻通知、显式中继授权、暂停/停止控制和电量门限；不能承诺永久常驻或用 work scheduling 偷换实时邻居维护。用户强停、权限撤销、省电、Doze、厂商限制仍是验收项。

## Apple：P2P 传输不等于跨平台 mesh，也不等于后台常驻

[MultipeerConnectivity 官方概要](https://developer.apple.com/documentation/multipeerconnectivity) 介绍服务发现、session 消息/stream/resource，并在后台行为部分说明：进入后台停止广告/浏览并断开 session，回前台需重建连接。它没有提供本轮所需的可控公开多跳路由；不能从框架名推出已实现 Mesh。概要仍提到 Bluetooth PAN，但承载细节会随系统变化，不能据此承诺某一 iPhone/iOS 可用 BLE 交换数据或与 Android 直接互通。

[Apple TN3213](https://developer.apple.com/documentation/technotes/tn3213-moving-from-multipeer-connectivity-to-network-framework)（本次读取全文的 2026-07-14 更新）明确 Network.framework 也支持 opt-in Apple peer-to-peer Wi-Fi：既有 API 的 `NWParameters.includePeerToPeer`/新接口的 peer-to-peer modifier 配合 Bonjour；它不是 Android Wi-Fi Direct 协议兼容承诺。技术说明要求不再使用时立即停止 browse/网络操作，并警告 P2P Wi-Fi 可影响本应用和其他应用性能。发现名称/证书未做应用验证前不能当成可信身份。

该技术说明也指出新的 [Wi-Fi Aware framework](https://developer.apple.com/documentation/wifiaware) 提供标准 P2P Wi-Fi，是值得继续研究的 Android/Apple 互通候选；其具体硬件、系统版本、权限/entitlement、配对模型与跨厂商数据通路本轮没有验证。TN3213 对 Aware + QUIC 还有系统版本边界，不能以“系统有 Aware”宣称所有传输组合都工作。CoreBluetooth 有独立的后台模式与系统调度约束；任何后台 radio 回调都不能推导无限计算/转发时间。

选择原生 Swift transport glue，避免跨平台 GUI；Rust 核心通过以后独立评审的 C ABI/UniFFI 边界交付帧和事件。第一轮不引入 Apple 客户端代码，也不扩展 Noise/NIM4 会话缓存与重发。

## 复用决策

复用成熟 Ed25519/SHA-256、DNS-SD、平台 Wi-Fi/Bluetooth API；采用受控泛洪这个成熟策略，保留策略替换接口，不自行发明距离估计或无线驱动。Meshrabiya 的 LGPL 集成、libp2p MIT 路由栈、Briar GPL 同步模型分别保留为后续选项。当前 Reticulum/LXMF 的用途限制许可证详见系统研究，不直接引入。论文、官方文档和商业 SDK 的文字/代码授权不可混用；本轮参考后独立实现，没有复制闭源代码或绕过访问控制。
