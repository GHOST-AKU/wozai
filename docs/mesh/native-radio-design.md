# Android 独立无线多跳：BLE 与 Wi-Fi Aware

目标：#60/#86/#89/#91，在真实 Android 上分别研发蓝牙多跳和 Wi-Fi 多跳。**本轮不做混合多跳**；用户已授权自主选型和实现。#85 保留为独立核心审查，不向其中追加无线功能；本分支从 #85 的 cdb028f 派生，新 PR 以该分支为依赖。

## 选择与边界

- 蓝牙采用系统 BLE 广播/扫描、GATT write-with-response + indication/CCCD，支持默认 MTU23（20byte数据）及协商 MTU。Classic RFCOMM 是后续替代，非本轮同时交付项；不实现 Bluetooth SIG Mesh provisioning/model，也不声称与其互通。
- Wi-Fi 首先实现 Android29+ Wi-Fi Aware NAN discovery + NDP，分别 Network-bound socket，避免共同 AP。Wi-Fi Direct 单 GO 组不能自动证明无基础设施多跳，B 多组/STA并发依机型变化，不将其假设为已可用。Aware 不支持/并发配额不足须报失败，不能回退普通 WLAN 后宣称通过。
- 不为两条物理通路重新实现签名和路由，不引入 Java 密钥、Noise 或 NIM4。复用现有 Rust mesh-lab；每条无线邻接在同一设备内桥接到 localhost TCP。TCP 仅是设备内 IPC（Aware 本身也可使用TCP）；A/B/C设备之间必须经过被选无线电。这个阶段采用独立进程桥，避免先引入 JNI/FFI 的依赖和线程边界；其额外拷贝和进程成本待实测。
- 每次仅 `ble` 或 `aware` 模式，最多4个允许的邻居标签。标签用于实验连接方向/拓扑过滤，不是签名身份或真实人物信任。双端仍由 Rust Hello/Auth 认证；公共消息不提供 E2EE。Aware 实验 PSK是链路准入信息，不当作NIM4身份，不记录其值。

## 模块和数据路径

`mesh/android-radio`：原生无 Activity instrumentation runner、两种 adapter、设备内 CoreBridge、参数验证、纯 Java 边界测试。`mesh/tools/build-android-core.sh`：使用固定 NDK/SDK 构建 Android arm64 Rust CLI，不复制 Linux ELF 冒充 Android 程序。

A/B/C 各一个 Rust进程+一个选定native adapter；B显式 --relay。两条无线连接的字节由 B 的 Rust 解帧/验证/签名receipt/转发，再经B另一条无线连接发出；native桥不能修改route，也不能绕过relay-off。上层有界core保持不变。每桥设备内入队最多8×4096bytes，出站worker同步背压；native操作有deadline，取消关闭桥/无线资源。连接建立≠已验证Mesh消息交付。

## 交付与不变量

仅内部分离源码/Java+DEX/Android CLI，无GUI，无正式客户端改动，不引入安装用临时签名。任何提供owner安装的APK仍必须通过仓库持久证书校验，本轮不提供安装APK。实验由有合法签名/部署环境的设备开发者执行；无ADB设备即hardware validation pending。

测试先覆盖配置互斥、未知/畸形标签、队列满/超长/关闭竞态、原始字节流、BLE分片和Aware发现解析；再编译Android36 API/DEX及arm64 CLI。主机socket/分片测试只能证明软件边界，不能证明无线连接、机型兼容、耗电或后台存活。

## 真实验收

分别运行 `ble-only`、`aware-only` 两轮，每轮独立三台Android，无Internet/共同AP/USB数据捷径。先建立B两个同时存在的无线邻接，证明A/C不能直连；应用allowlist仅算受控拓扑条件，不能替代物理隔离。双向每侧至少20条（含2048byte/Emoji），按完整ID核对Rust路径与native每链bytes；B off确认后新ID必须在TTL内不能越过B。验证断链/权限撤销/重启/无线开关，归档失败。没有设备结果不勾选#91、不称Mesh Alpha。
