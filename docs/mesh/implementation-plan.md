# Nearby Mesh 预研发实施计划

**Goal:** 在独立研发分支实现可执行、可测试的公共消息 Mesh 引擎，提供源码调研与真实硬件验证入口。
**Architecture:** Mesh Core 不依赖平台或 GUI；连接管理、物理传输和本地持久化在外层。公共消息仅签名，不宣称 E2EE。TCP 实验拓扑与无线验收分开。
**Tech stack (研发选择):** Rust 1.88、Ed25519-dalek、SHA-256、serde；Python 标准库驱动独立进程与 Linux network namespace。
**Spec:** 用户本轮研发任务、Issue [#60](https://github.com/GHOST-AKU/wozai/issues/60)、本目录 ADR 与协议文档。

## Global constraints

- 不修改正式客户端、NIM4、Noise、身份、版本号或 GUI；不合并 main、不发布。
- 代码注释英文；PR 中英双语；研究、证据进入正式文档及 PR。
- 用户授权自主选型与可逆实验，不重复要求方案审批。
- 每个行为先写可失败测试；按 mesh 独立改动执行必要检查。
- 无真实设备证据时必须标为 `hardware validation pending`。

## Review focus

- 不能通过移除中继元数据重新获得跳数预算；明确串通/隧道仍不可防。
- 断连时部分帧、慢连接、队列满和连接竞争不得突破上限或阻塞全局事件循环。
- 去重容量耗尽时拒收新消息，不能驱逐仍有效的 ID 后重复展示。
- 重启不能导致有效期内旧消息重复展示；已丢失的内存队列不能宣称可靠恢复。
- 诊断不导出正文、私钥或可恢复的私人聊天数据；所有消息状态不等同全局送达。

## Task 1: 调研与协议边界

Files: `docs/mesh/research.md`, `adr-001.md`, `protocol.md`。

- [x] 检查现有构建与客户端接口、Issue；固定上游源码 SHA、许可证与来源。
- [x] 比较 Rust、Kotlin、libp2p 与其他方案，确定协议、安全及资源上限。
- [x] 给出逐跳签名/期限/重放/故障的测试接口与验收标准。

## Task 2: 独立核心

Files: `mesh/Cargo.toml`, `src/{lib,protocol,core}.rs`, `tests/core.rs`。
Interfaces: `Envelope::new/validate`, `Packet::append/validate`, `Core::receive/originate/tick`；不做平台 I/O。

- [x] 先写边界、篡改、跳数删改、去重容量、限流、队列及期限测试并观察失败。
- [x] 使用成熟密码库实现规范化签名、append-only hop receipts 与有界泛洪。
- [x] 执行 `cargo test --manifest-path mesh/Cargo.toml --test core`，Expected: 全通过。

## Task 3: mesh-lab 与隔离验收

Files: `src/{main,transport,store,manager}.rs`, `tests/runtime.rs`, `tools/lab.py`。
Interfaces: CLI `keygen/run`；stdin/stdout NDJSON 控制：send/neighbors/status/relay/diagnostics/quit。

- [x] 先写独立进程 CLI、重启、坏帧与隔离验收测试，观察缺失功能失败。
- [x] 实现身份持久化、双向 challenge 签名握手、有界非阻塞 TCP 与重连。
- [x] 使用 network namespaces + veth 构建 A—B—C；无法获得特权时采用隔离 Unix socket 目录替代并准确标注。
- [x] 验证双向、relay off、循环、重复、B 重启、分区恢复、过期及资源压力。
- [x] 测量实际延迟、RSS、CPU、字节和队列峰值，保留机器信息及原始证据。

## Task 4: Android 原生能力探索与交付

Files: `mesh/android-probe/`, `docs/mesh/hardware-validation.md`, `mesh/README.md`, mesh-only CI。

- [x] 检查 adb/设备，提供无需改动正式客户端的原生探针/实验命令。
- [x] 记录 Wi-Fi Direct/Aware/BLE/Classic 及 Apple 限制；给出至少一种替代无线拓扑。
- [x] 独立审查安全与连接管理，修复重要问题；执行 fmt/clippy/test 与隔离实验。
- [x] 提交分支、推送、创建中英双语 Draft PR，附证据及已知限制，不关闭 #60。


## 执行记录

实现与冻结源码：`83a9dc5`。本地验证为 21 Rust + 9 release 独立进程 + 2 probe parser；两次隔离拓扑脚本通过，原始记录及实测方法见 `validation.md`。源码对应的正式客户端/NIM4/Noise 输入未变，无无关客户端构建。

研发判断：使用原生 Instrumentation 探针探索 Android API，未实现 radio 数据 adapter/FFI；没有真机，严格无线 gate 保留 hardware validation pending。正文队列仅内存，不把持久去重当作可靠 DTN。独立审查提前结束，作者修复重要发现并保留回归测试；完整外部审查仍待后续。用户已授权只推送独立研发分支和 Draft PR，不合并或发行。

交付：[Draft PR #85](https://github.com/GHOST-AKU/wozai/pull/85)，基线 `6d9a50b`，保留研发分支 `research/nearby-mesh-engine`，未合并/发行。

2026-11-01 自审/性能跟进已实现，见 [审查与实测](review-2026-11-01.md)：36 Rust + 15 进程/驱动 + 9 采集测试，共60项；两种补强拓扑通过。协议签名字节、依赖图与正式客户端未变。
