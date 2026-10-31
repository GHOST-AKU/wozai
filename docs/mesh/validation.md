# 验证证据与实测说明

日期：2026-10-31。验收范围是独立引擎预研。**hardware validation pending**；没有无线吞吐、手机电池或后台存活成绩。

## 版本与可复现输入

已验证实现提交：[`83a9dc5e6e13ea3b19bccc6a93dc3a7d60f7e0ce`](https://github.com/GHOST-AKU/wozai/commit/83a9dc5e6e13ea3b19bccc6a93dc3a7d60f7e0ce)。后续提交仅整理文档/证据时复用这一次构建，按 `mesh/evidence/source-manifest.json` 的逐文件 SHA-256 检查源码相同，不重新构建相同客户端/二进制。

- Rust：1.88.0，Linux x86_64，release + strip + thin LTO；实际构建 17.62s（本环境首次 release 构建，不是跨机器可比成绩）。
- 二进制：**1,856,304 bytes**；SHA-256 `358f2a8fa5d419d0d89d1e50a3b9955397e6e774c95cb069b606d0f6578dff39`。
- Linux 6.18.57 / glibc 2.41，宿主报告 AMD EPYC 9V45；cgroup CPU quota `200000/100000`（2 CPU 配额），cpuset `0-2`，内存上限 8 GiB。不能把宿主标称 96 cores 当作实验可用核心数。
- [完整输入/环境清单](../../mesh/evidence/source-manifest.json)、[命令与检查原始输出](../../mesh/evidence/verification.txt)。

实际 main 基线 `6d9a50bb3d4d8ee4880cce26e23b9385d75f0e50` 与实现提交对比：`app/terminal/desktop/apple/third_party/i18n`、根 Gradle 输入没有变化。没有把其他分支的构建结果写成本次新执行的 NIM4 回归；本轮也没有重新构建正式客户端。

## 本地检查

| 检查 | 结果与范围 |
| --- | --- |
| cargo fmt --check / clippy --all-targets -D warnings | 通过 |
| cargo test --locked | **21** 项通过：11 协议/路由 + 10 帧/资源/持久化 |
| Python 独立进程测试（release binary） | **9** 项通过：双向、中继开关、环路、重启/身份/去重、坏帧/坏握手、连接重置、竞争去重、state lock |
| 竞争条件定向检查 | 修复后连续 3 次通过；为调查已观察到的时序失败而执行，不替代完整套件 |
| Android capture parser | **2** 项通过，保留权限失败/过滤原始输出 |
| Android Java/DEX | API36 编译通过；使用部分兼容旧版本的 deprecated 观察 API，编译提示已保留；没有运行/提供 APK |
| YAML / diff / source hashes | 四个涉及的 workflow YAML 可解析；自有改动无空白错误；上游许可证原文保持不变；记录的源码摘要一致 |

总计 32 项自动化测试，不包含下面两个多进程拓扑脚本的额外断言，也不把这些测试计作真实设备测试。具体命令：

```sh
cargo fmt --manifest-path mesh/Cargo.toml --check
cargo clippy --manifest-path mesh/Cargo.toml --locked --all-targets -- -D warnings
cargo test --manifest-path mesh/Cargo.toml --locked
cargo build --manifest-path mesh/Cargo.toml --locked --release
MESH_BINARY="$PWD/mesh/target/release/mesh-lab" python3 -m unittest discover -s mesh/tests -p test_lab.py -v
python3 -m unittest discover -s mesh/android-probe/tests -v
bash mesh/android-probe/build.sh
MESH_BINARY="$PWD/mesh/target/release/mesh-lab" python3 mesh/tools/lab.py --output mesh/target/isolated-lab.json
MESH_BINARY="$PWD/mesh/target/release/mesh-lab" python3 mesh/tools/lab.py --discovery --output mesh/target/discovery-lab.json
python3 mesh/tools/evidence.py --output mesh/target/source-manifest.json
```

本 Draft 的提交使用 `[skip ci]`，**没有宣称 GitHub Actions 已通过**：本次新增 Mesh 专用 workflow，并为三份原客户端 workflow 添加 Mesh 路径排除；这些旧 workflow 文件本身也在 PR diff 中，直接触发会重复打包未变客户端。这里用本地完整 Mesh 证据完成预研检查。新 Mesh CI 面向后续更改；正式合并前由维护者选择所需远端验证，不将 skip 当作 required check 的通过。

## 隔离实验与实际数据

两个实验分别顺序执行，避免相互作为额外 benchmark 负载：

| 模式 / 原始证据 | 消息样本 | 最小 / 中位 / 最大延迟 | 整个拓扑脚本耗时 |
| --- | --- | --- | --- |
| [配置候选 TCP](../../mesh/evidence/isolated-release.json) | 12，双向各 6 | 6.87 / **11.12** / 13.63 ms | 2.05s |
| [DNS-SD 自动发现 + TCP](../../mesh/evidence/isolated-discovery-release.json) | 12，双向各 6 | 7.63 / **11.42** / 14.15 ms | 2.23s |

延迟从 Python 写入源节点 stdin 前，到目标独立进程的验证/展示事件被测试驱动读取时止；包含本地排队、签名、TCP 两跳、持久去重写入和事件/pipe 开销。样本为 5 bytes 的 `lab-N` 公共文本，计时开始前已建立邻接，不包含冷启动发现/建连耗时；本机短样本不是无线指标、吞吐/规模基准或尾延迟保证。

每个实验都记录三个独立 PID、两个 /30 子网路由与实际 `[A,B,C]` / `[C,B,A]` 路径；A→C、C→A `connect_ex=101`（网络不可达），B 内核 IP forwarding=0。两次都通过新 ID 的 relay-off 到期负例、veth down/up 分区恢复、B 同身份重启后重连及无重复展示断言。没有共享 AP、没有无线电；这是 OS 隔离的 TCP overlay，不是无线 Mesh 通过。

配置候选实验实测（`/proc/PID/status` 与 `/proc/PID/stat`）：

| 进程采样点 | RSS / HWM | 累计 CPU 秒 | RX / TX bytes | 待发峰值 |
| --- | --- | --- | --- | --- |
| A，脚本末尾 | 2228 / 2228 KiB | 0.00 | 7013 / 11052 | 7 |
| B，重启前 | 2128 / 2128 KiB | 0.05 | 10354 / 12475 | 13 |
| B，重启后 | 2116 / 2116 KiB | 0.00 | 10356 / 1547 | 1 |
| C，脚本末尾 | 2212 / 2212 KiB | 0.00 | 7009 / 9658 | 7 |

CPU 由系统 tick 累积读数，0.00 不代表零开销。B 两个采样属于不同进程，不相加为同一进程峰值。DNS-SD 模式的主节点 HWM 为 2256–2284 KiB（含重启前后采样），**不包含 DNS-SD 子进程 RSS**；其 96 MiB 是 OS 配置的地址空间上限，不能写作实际内存消耗。峰值是本次低负载运行的观测，不是饱和洪泛测试结果。没有测长期稳定性、PSS、存储写入字节量或能耗。

默认资源上限与失败策略见 ADR：8 条接纳链接、4 个拨号任务、16 候选、64 待发、1024 去重、32 KiB/frame、8 TX/link、每消息 8 个出口/每出口 3 次 offer、入口/来源/全局预算。单元测试分别验证准入失败、缓存满不驱逐、慢读出口队列、帧长度先验拒绝、每秒帧预算和连接超时；它们验证边界，不提供“手机可支持 N 个节点”的性能承诺。

## 审查发现、修复与剩余限制

独立审查发现连接刚建立就 reset 可把 per-link 初始化错误传播为进程退出；已以真实进程回归复现并改为拒绝该连接。作者后续检查修复了时钟高水位持久化、EOF 前完整帧丢失、双方重复拨号时认证帧尚未写完便关闭以及把单 link 关闭误报为邻居离线的问题。保留原回归测试，未通过删除测试掩盖失败。

独立审查会话未完成全分支审阅；最终修复由作者自审和自动化验证，不称为完整第三方安全审计。后续仍需要密码学协议、native 生命周期与硬件数据路径的独立审查。

其余边界：无原生 radio 数据 adapter/FFI；无可靠 DTN 或正文持久化；无平台密钥库；无跨平台构建成绩；无手机功耗/后台常驻或无线验收。签名链保护逻辑预算，不证明物理邻接、不防串通隧道/Sybil。源消息签名不提供 E2EE。原生探针、两种替代无线拓扑和下一阶段步骤见 [硬件计划](hardware-validation.md)。

初期 `isolated-debug.json`、`isolated-discovery-debug.json` 是最终修复前探索运行，保留为过程证据，没有完整源码摘要，不用于最终 revision 的验收/性能对比。
