# 真实无线验收与设备探针

**状态：hardware validation pending。** 本环境 `adb devices -l` 为空，没有三台 Android/Apple 设备、无线电或电池测试环境。已交付独立进程 TCP 实验和原生 Android 能力探针源码；探针不是 Mesh 数据路径适配器。未提供可安装 APK、未绕过签名或系统权限，也没有为编译源代码而重建正式客户端。

## 当前证据能说明什么

| 层级 | 本轮交付/证据 | 不能推出的结论 |
| --- | --- | --- |
| 协议/路由 | 单元及资源测试：签名、期限、去重、链预算、队列/速率、持久状态 | 手机无线吞吐、可用并发接口、耗电 |
| 隔离进程 TCP | network namespaces + 两条 veth，A/C 无路由、B 内核 IP forwarding=0，实际进程双向转发 | 已实现基础设施外无线 Mesh |
| DNS-SD | 可选 worker 使用成熟 LAN 发现机制，有限候选与隔离内存 | 它能创建 Wi-Fi/蓝牙网络，或广告即身份可信 |
| 原生 Android 探针 | API36 Java classes + DEX 编译；有界异步 radio 请求/回调与脱敏 JSONL | 已连接 Rust Core、已传消息、后台常驻、无线多跳验收 |
| 真正无线 Mesh | 无本地硬件证据 | 不得勾选 #60 的三设备 gate 或称 v0.4 Mesh Alpha |

## 原生探针复现

源代码位于 `mesh/android-probe/`，包名 `org.nearbymesh.probe`，与正式 NearbyIM 独立。只使用原生 Android API，无 Activity 和聊天 UI。需要 Android SDK platform 36、build-tools 35.0.0、JDK17；工具路径可用环境变量指定。

```sh
ANDROID_SDK_ROOT=/path/to/android-sdk JAVA_HOME=/path/to/jdk17 bash mesh/android-probe/build.sh
python3 -m unittest discover -s mesh/android-probe/tests -v
```

默认只生成内部 classes/DEX。`--unsigned-internal-apk` 供有签名环境的开发者组装包，不是可安装交付。仓库规则要求任何给 owner 安装的 APK 先通过 `tools/verify-android-signature.py` 与持久证书比对；该脚本当前只认识既有客户端/测试包，此独立新包尚需独立的签名交付支持，本轮没有改脚本或提供临时 debug 签名替代。

在授权的设备实验环境，以满足上述签名要求的独立探针包安装后，通过系统设置/测试设备授权相应 Nearby Wi-Fi、Bluetooth 运行时权限；旧系统按 manifest 分支提供位置权限与启用条件。不能用用户私聊配对授权替代这些权限。采集工具不会修改 radio 开关或自动授予权限。

```sh
python3 mesh/tools/android-capture.py --serial DEVICE --mode inventory --seconds 5 > inventory.jsonl
python3 mesh/tools/android-capture.py --serial DEVICE --mode wifi-discover --seconds 10 > wifi.jsonl
python3 mesh/tools/android-capture.py --serial DEVICE --mode wifi-group --seconds 15 > group.jsonl
python3 mesh/tools/android-capture.py --serial DEVICE --mode aware --seconds 15 > aware.jsonl
python3 mesh/tools/android-capture.py --serial DEVICE --mode ble --seconds 15 > ble.jsonl
```

- `inventory`：读 feature/API 可用性、权限状态、接口与网络计数；不创建组、不扫描陌生 BLE。
- `wifi-discover`：显式请求 P2P peer discovery，记录实际 callback/peer 计数；超时清理本次 discovery。
- `wifi-group`：没有现存组时才请求随机命名实验 GO；请求被接受与 group 真实出现分开记录。清理前再核验它仍是本次创建的 GO 名称，不删除外来组。
- `aware`：attach + publish/subscribe，记录资源与发现回调；没有创建 NDP 或声称 socket 通路成功。
- `ble`：只匹配实验专用 service UUID 的 scan + 广播/GATT service 请求，记录回调；不代表已完成 GATT 连接、MTU/分片或转发。

运行限制 2–30s，退出停止本次 BLE/Aware/发现资源。`operation.error/SecurityException`、unsupported、request failure 需要原样保留，不伪造 success。身份/MAC/SSID/IP 等只有每次运行的 token 或计数，采集脚本过滤原始 adb 输出；不上传完整系统 dumps 或正式客户端数据。异常清理失败也必须报告并人工核查实验组是否残留。

## 三台 Android 的逐级实验

1. **登记与离线条件。** 记录 A/B/C 机型、Android/固件/目标 SDK、电池/省电模式、radio 特性、后台策略和应用权限；关闭蜂窝数据和 Internet 出口，设备不连接共享 AP/路由器。控制电脑仅 USB 日志，禁用 adb reverse/forward 或 USB tethering 的数据捷径。记录拓扑和时间。
2. **证明两个邻接。** 首选 BLE A–B + Wi-Fi Direct/Aware B–C，或在确实支持并发的 B 上采用两个不同自建组。每跳验证两端实际 socket/GATT 数据；P2P request ACK、GO/client flags 和 discovered 列表只作候选证据。若并发失败，保存具体 API reason、接口/Network 变化，再尝试混合载体。若必须轮换组，标记 DTN 实验并先完成可靠存储设计，不能当作本轮持续在线链通过。
3. **实现 native adapter。** Android `Network`/RFCOMM/GATT 原生连接提供 frames 给 Rust；FFI/进程桥必须有取消、生命周期与有界帧/分片，Mesh 身份独立。`TcpStream` 不能自动替代 Android `Network.bindSocket`，本轮 CLI 没有完成该桥。平台 adapter 独立评审，不接入正式 App 的私聊通道。
4. **排除 A/C 直连。** 使用距离/屏蔽、不同 radio 路径或实验连接允许列表，证明 A/C 无可用直接连接；分别尝试所有已启用传输并保留失败证据。协议 hop trace 不能替代物理拓扑证据；若采用允许列表隔离，要把这个条件明确记入结果。
5. **双向与反例。** A→B→C、C→B→A 各至少 20 条，包含 1 byte/2048 byte UTF-8、Emoji；每条按完整 ID 匹配各跳 ingress/egress、transport、验证与展示时间。B relay off 后发送新 ID，等待全部 TTL 到期，确认 A/C 不互达；off 前已经写入内核的字节不可撤回，必须用新消息作为负例。
6. **故障与资源。** B 断电重启、radio off/on、权限撤销、A/C 移动、锁屏/后台 5/30/60 分钟；重复/环路/坏签名/超长帧/慢接收/洪泛。记录 RSS/PSS、CPU、TX/RX、队列/丢弃、唤醒、存储写入和实际失败状态。电池使用外部电量计或可复现系统电量统计，报告采样方法、环境和误差；没有测量就写未测。
7. **发布验收材料。** 正式文档/PR 附机型矩阵、命令、脱敏日志、拓扑图与失败/替代方案。区分 native data path 是否完成、各项结果 pass/fail/pending。只有无基础设施无线 A–B–C 与 relay-off 两个 gate 都通过，才可讨论后续 Mesh Alpha；本分支仍为 Draft 预研，不合并/发行。

## Apple 后续

优先 Swift 原生 Network.framework + Bonjour/P2P 或受支持设备的 Wi-Fi Aware；CoreBluetooth 为另一个独立 adapter。需逐系统版本测 iOS 前后台暂停、重新发现/连接、权限与 entitlements，并单独验证 Android 互通。MPC session 的使用或平台宣称 P2P 不构成移动跨平台 Mesh 证据。
