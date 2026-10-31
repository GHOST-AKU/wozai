# Nearby Mesh / mesh-lab

独立、无 GUI 的公共消息 Mesh 预研。**实验协议，公共明文；hardware validation pending。** 不修改/导入 NIM4、Noise、正式客户端身份或私聊信任，不以 NIM5 为目标。

需要 Rust 1.88+、Python3；独立 Cargo 项目，不加入 Android Gradle。模块代码使用仓库 AGPL-3.0-only，依赖原许可与通知见 [THIRD_PARTY](THIRD_PARTY.md)。

```sh
cargo build --manifest-path mesh/Cargo.toml --locked --release
cargo test --manifest-path mesh/Cargo.toml --locked
python3 -m unittest discover -s mesh/tests -p 'test_*.py' -v
python3 -m unittest discover -s mesh/android-probe/tests -v
```

Python 测试默认使用 debug binary；使用 release 时先设置：

```sh
export MESH_BINARY="$PWD/mesh/target/release/mesh-lab"
```

启动独立节点（每个进程使用独立 state 目录）：

```sh
mesh/target/release/mesh-lab run --state /tmp/mesh-b --listen 127.0.0.1:39771 --relay
mesh/target/release/mesh-lab run --state /tmp/mesh-a --listen 127.0.0.1:39772 --peer 127.0.0.1:39771
mesh/target/release/mesh-lab run --state /tmp/mesh-c --listen 127.0.0.1:39773 --peer 127.0.0.1:39771
```

在各进程 stdin 输入一行一个 JSON：

```json
{"cmd":"send","text":"Hello offline 🌍","ttl":30,"hops":3}
{"cmd":"neighbors"}
{"cmd":"status"}
{"cmd":"diagnostics"}
{"cmd":"relay","enabled":false}
{"cmd":"quit"}
```

`queued` 是本地排队，`handed_to_neighbor_unconfirmed` 只表示写入本地 socket，`message` 表示该进程验证后展示，`expired_unconfirmed` 是到期。不要把它们当成全网送达/已读回执。错误有明确 reason；重启保留身份与未过期去重，正文队列不持久，异常重启报告丢失队列数量。默认 relay 关闭；`--relay`/本地 `relay` 命令是明确参与授权。

以上 loopback 命令仅便于交互，没有 OS 拓扑隔离，不能作为 A/C 不可达的证据。严格独立进程实验：

```sh
MESH_BINARY="$PWD/mesh/target/release/mesh-lab" python3 mesh/tools/lab.py --output mesh/target/isolated-lab.json
MESH_BINARY="$PWD/mesh/target/release/mesh-lab" python3 mesh/tools/lab.py --discovery --output mesh/target/discovery-lab.json
```

Linux 需要 `ip`、`mount`、`unshare`、`sysctl` 及可用的 user/network/mount namespaces。脚本在私有 user/mount/net namespace 中创建 A/B/C 和两条 veth；B 的内核 forwarding=0，A/C 没有互通路由，真实进程只经 B 应用层转发。验证双向、关闭中继负例、分区恢复、B 重启与去重，导出实际延迟/RSS/CPU/字节计数。环境拒绝 namespace 时命令必须失败，不能降为同 LAN 后宣称通过。

`--discover` 是 Linux 可选 DNS-SD：随机服务名、同本地子网地址筛选、候选上限、独立 worker 的 96 MiB 地址空间上限。发现只是候选，后续仍要签名握手；它不创建 Wi-Fi/蓝牙，不授予 NIM4 信任。默认配置地址模式不发送 LAN 发现流量。适配其他平台应使用原生发现 API。

安全诊断导出应只保存 `diagnostics` 事件。普通 lab stdout 包含**公共正文、稳定公共键及路径**，不适合直接分享。身份 seed 是本地 0600 明文，尚未集成平台密钥库。抗重放是有界有效期策略，非 Sybil 防护；签名链只验证逻辑预算，不证明物理无线距离或阻止串通隧道。

文档： [系统调研](../docs/mesh/research.md)、[移动传输](../docs/mesh/mobile-transports.md)、[ADR](../docs/mesh/adr-001.md)、[协议](../docs/mesh/protocol.md)、[硬件计划/原生探针](../docs/mesh/hardware-validation.md)、[验证与测量](../docs/mesh/validation.md)。

当前实际数据适配只有 TCP。BLE/Wi-Fi Direct/Aware/Classic 与 Apple 数据路径、FFI、后台常驻、可靠 DTN、端到端加密私聊都没有实现。原生探针只观察能力/回调，不能代替真实无线 A–B–C 验收。


本轮自审修复、补强验收及同负载性能对照见 [2026-11-01 报告](../docs/mesh/review-2026-11-01.md)。可复现手动 TCP 进程基准（独立 user/net namespace，未使用无线）：

```sh
python3 mesh/tools/bench.py --binary "$PWD/mesh/target/release/mesh-lab" --output mesh/target/bench.json
python3 mesh/tools/evidence.py --build-profile release --output mesh/target/source-manifest.json
```

基准为每种负载三次独立试验，分别比较无消息与保留 28 条缓存后的静默 CPU、5/2048 bytes 消息延迟及主进程 RSS/PSS。它不是吞吐、手机功耗或无线验收。要做前后对照，应对旧版构建和新版二进制使用同一份 bench.py；本轮复用了已保存且摘要匹配的旧 release，没有重新构建旧版。

安全导出工具现在对事件、字段、类型和 token 格式使用白名单，并将原始输出限制为每行 8 KiB、总计 1 MiB；采集控制端在 Linux/POSIX 验证。普通 lab stdout 仍含公开正文/身份路径，分享时只用 diagnostics 或脱敏证据。
