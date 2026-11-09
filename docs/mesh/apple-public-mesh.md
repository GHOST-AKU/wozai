# Apple 公共蓝牙 Mesh 原生接入

本模块接入既有公共 Mesh，**不是 NIM4 蓝牙私聊，也不是 Bluetooth SIG Mesh 标准**。公共正文对中继可读；Mesh签名公钥不代表已信任的私聊身份。Apple真实无线验收为 **hardware validation pending**，现有Android/Windows实机成功反馈不自动推广到新端。

## 架构与互通

```text
SwiftUI PublicMeshView → PublicMeshModel
                              │ bounded C ABI
                       Rust embedded::Engine
                       shared runtime / Core
                              │ private loopback byte stream
                      Native BLE CoC bridge
                         ↙             ↘
                   Android Mesh     Apple Mesh
                         │
                  Windows RFCOMM Mesh
```

- iOS不能按Windows方式启动Core子进程。`mesh/src/runtime.rs`从原CLI提取节点运行循环，CLI仍使用同一逻辑；`embedded.rs`提供有界命令/事件队列和停止信号，`mesh/apple-bridge`导出opaque ID C ABI。没有重写路由或密码学，也没有修改NIM4。
- Core只监听设备内部127.0.0.1临时端口，关闭DNS-SD worker/子进程发现。CoreBluetooth流经短NMLC前置握手后桥接该端口，保留原Hello/Auth、消息签名、去重、期限、路径及中继策略。
- Android既有BLE CoC服务UUID `eed3c891-5c83-4f9c-a88d-94bc822c71e0`、广告offer和NMLC/READY前置握手保持兼容。Apple扫描其服务、读取Service Data中的动态PSM；无需修改已稳定的Android APK。
- Apple peripheral广播只能使用系统支持的字段，不能照搬Android Service Data。因此广播相同服务UUID，并增加只读GATT offer characteristic `e22630c6-9f5f-4984-a8d3-65924f1648bc`。它返回与原offer相同的 `version=1 | labelLength | ASCII label | PSM big-endian`，最长12字节。Apple central在广告无offer时读取此特征再打开CoC。
- Android原适配器会忽略缺Service Data的Apple广播，Apple主动连接Android；Apple↔Apple通过GATT读取offer，并按既有label排序确定发起方向。标签仍是不可信发现线索，真正公共身份由Rust邻居认证证明。
- **Windows现有RFCOMM不是iOS CoC**。Apple可经Android节点与Windows交换公共消息；没有宣称Apple直接连接Windows蓝牙。新版Apple↔Apple与Apple↔Android无线行为均需真实设备验证。

## 用户操作与生命周期

在iPhone/iPad的“公共 Mesh”标签页、macOS侧栏同名入口点击启动，按系统提示授权蓝牙。默认不自动启动、不自动中继，开启“中继公共消息”后才转发。邻居计数来自Core认证事件，扫描/物理建链成功不等于有效邻居。

公共文字上限2048 UTF-8 bytes；发送后保留草稿直至Core接受，状态明确区分等待邻居、已交邻居但未确认、过期/停止。消息列表最多200条，仅内存保存，与私聊数据库分开。中继开关只在Core确认后更新。

iOS当前采用明确的前台实验模式：进入background时停蓝牙和Core；回前台仅当此前运行意图仍有效才自动重新启动，**中继仍从关闭开始，需重新明确开启**。用户手动停止后不自动重开。不滥用后台模式，不声称强退/锁屏后长期中继。macOS进程运行中可维持，睡眠/退出不保证可用。

诊断通过系统文件导出为TXT（内容为JSON），包含系统、CPU核数/总内存、当前运行与中继状态、认证邻居/队列数量、最近64条阶段事件。默认没有正文、密钥、发现标签、MAC/IP/SSID和稳定设备标识，不把当前报告视为RF距离、RSSI或吞吐测量。

## 资源与安全边界

- C ABI最多4个实例；每实例16个待处理命令、128个事件，每个控制/事件至多16384字节。队列满失败，不无限积压；停止幂等，失效ID拒绝。启动/轮询/关闭放工作线程，不能在MainActor等待磁盘。
- 原Core的连接/消息/速率/持久去重预算不放宽。FFI没有导出私钥；路径使用系统私有Application Support子目录，Mesh身份与NIM4分离。
- BLE最多32个候选、2个主动建连/设置尝试、4个流会话；握手10秒、原生设置20秒，退避有上限；取消无法完成时重建对应manager以隔离旧回调。
- 每条Foundation stream由一个睡眠run loop线程拥有；应用接收缓冲64KiB、写入总预算64KiB/最多8个操作、待调度操作最多16个。部分读写、关闭、取消和回压显式处理；这些是应用预算，**不是整个连接RSS或系统缓冲峰值**。
- Core内部仍有原5ms运行循环、3秒心跳、10秒checkpoint；本轮不虚构零轮询或功耗改善。嵌入入口保存未完成队列计数，重启不静默恢复正文队列。
- PSM/GATT读取和NMLC标签均不授予身份信任。`withEncryption:false`只指BLE链路策略，不声称公共消息E2EE；来源完整性与路径校验仍由既有Rust签名完成。

## 构建与验证

需要macOS、项目支持的Xcode、Rust1.88与对应Apple标准库。使用项目现有最低iOS17/macOS14。先生成真实Core XCFramework，再编译原生客户端：

```sh
bash tools/build-apple-mesh.sh
swift test --package-path apple
xcodebuild -project apple/NearbyIM.xcodeproj -scheme NearbyIM \
  -destination 'platform=macOS' -configuration Release CODE_SIGNING_ALLOWED=NO build
xcodebuild -project apple/NearbyIM.xcodeproj -scheme NearbyIM \
  -destination 'generic/platform=iOS' -configuration Release CODE_SIGNING_ALLOWED=NO build
```

脚本构建macOS arm64/x86_64、iOS arm64、模拟器arm64/x86_64切片，并记录确切源码与archive哈希。生成产物不提交到Git。应用bundle保留Mesh依赖原许可证与通知，没有复制第三方Mesh源码或引入新GUI框架。

仅源码checkout可以继续构建原私聊客户端；若未生成XCFramework，C桥明确返回Mesh不可用，页面不能伪造启动。Mesh测试要求真实Core存在，不跳过此断言；正式Mesh构建必须运行上面脚本。无分发签名的iOS编译结果不能当作可安装IPA；实际设备调试仍需合法开发签名。

本次测试的不同层级：

1. Rust既有协议/资源测试加嵌入实例的状态锁、控制边界、溢出停止、双向三实例中继与relay-off。
2. C ABI实际调用、短缓冲事件保留、非法ID/空指针长度、幂等停止。
3. Swift独立Android offer/NMLC固定向量、Foundation小缓冲真实部分写入/取消、CoC前置握手后字节完整性。
4. Swift生产流桥接两个真实嵌入Core，交换签名公共消息；这是本机stream集成，不是蓝牙无线实测。
5. 既有CLI独立进程回归，验证运行循环提取后没有改变路由/重连/去重/恶意输入行为。

CI和精确测试结果记入对应PR；不以早期失败提交、`[skip ci]`或本地结果冒充最新Apple HEAD的通过记录。

## 真机实验（尚未声称通过）

先Apple↔Android双向公共文字、自动发现/断线恢复，再Apple↔Apple。三设备分别验证Apple为端点和中继：A/C不能直接连接、无互联网/共同AP，B开中继双向到达，关中继新ID在全TTL内不能越过B，再恢复中继发送新ID。记录真实角色、Core路径、系统/蓝牙权限、设备型号、失败/重试与导出诊断。

Windows组合使用 `Apple ↔ Android ↔ Windows`，明确中间Android在同一蓝牙Mesh族内桥接CoC/RFCOMM；不是蓝牙↔Wi-Fi混合多跳。隔离与每跳证据不足时只记录已观察的链路，不提前关闭#91或宣称Apple Mesh Alpha验收完成。
