# 文件尝试摘要 / File attempt summaries

Issue #32 开发中，仅适用于 Android/桌面共享 Java V2 文件实现；不是性能改善或真机吞吐验收。旧版 V1、Apple、Rust 不宣称已新增同一摘要。

Development for #32 covers the Java V2 implementation shared by Android and desktop. This is observability, not evidence of improved performance or physical-radio acceptance. It does not claim equivalent summaries for legacy V1, Apple or Rust.

诊断保留 `transfer_summary={JSON}` 行。每个本地发送/接收尝试的 pipeline_counter_id 对应所属 V2 组件计数器，带随机 attempt_id，使用原 transfer_id 对应同一文件；两端 attempt_id 不相同。暂停、来源暂不可用会结束本次尝试，恢复建立新尝试。每个 pipeline 实例最多保留32条完成摘要和当前活动尝试；不写入用户文件、路径、URI、地址、设备身份或文件名，不持久化到磁盘。

Each `transfer_summary={JSON}` is one local send/receive attempt, with pipeline_counter_id linking its V2 component counters, a random attempt_id and the existing transfer_id for cross-endpoint correlation. Endpoint attempt IDs differ. Pause/source unavailability ends an attempt; resume starts another. A pipeline retains at most 32 finished summaries plus active attempts, in memory only. No filename, path, URI, address or device identity is included.

| 字段 / Field | 口径 / Meaning |
| --- | --- |
| start_unix_ms / end_unix_ms | 起点墙钟；终点由该起点加单调耗时估算，避免系统时钟调整改变耗时 / Wall-clock start, end estimated from that start plus monotonic elapsed time |
| elapsed_ms / phase_ms | 从任务创建到本端保存成功或收到有效完成回执；互斥状态阶段；不含任务创建前的文件选择、来源准备/快照、路由准备和排队时间 / Task creation to local saved completion or validated peer completion; exclusive state phases, excluding selection, source preparation/snapshots, routing and queueing before task creation |
| attempt_data_bytes | 发送端成功交给文件wire队列的DATA；接收端实际写入的DATA，不是加密线速 / Sender DATA accepted by its wire queue; receiver DATA written; not encrypted wire throughput |
| resumed_prefix_bytes | 本次恢复前已存在的前缀，不计为本次传输有效载荷 / Previously existing resume prefix, excluded from this attempt's payload |
| confirmed_payload_bytes | 本次DATA中已由对端CREDIT/COMPLETE确认写入或本端实际写入的字节 / Attempt DATA covered by peer CREDIT/COMPLETE or local writes |
| average_mib_s | confirmed_payload_bytes / 单调耗时，除以1048576；含检查、等待、验证、历史保存 / Confirmed payload per monotonic elapsed second divided by1048576, including checking, waits, verification and history saving |
| peak_1s_mib_s | ACK/写入进度采样的10个100ms桶之和，固定除以1秒与1048576；不足1秒不宣称峰值 / Ten 100ms buckets of acknowledged/written progress divided by one second and1048576; no peak claim before a full second |
| sha256 / local_hash_verified | 完整文件哈希和真实校验事件；存储失败与哈希匹配独立 / Whole-file hash and actual verification event, independent of persistence failure |
| peer_complete_received | 仅发送端收到有效且哈希匹配的COMPLETE后为true / True only on a sender's validated matching COMPLETE |
| state / terminal | 实际结果；包括paused、sourceUnavailable、history_save_failed；完成后摘要冻结 / Actual outcome, including paused, sourceUnavailable and history_save_failed; completed summaries freeze |

恢复后整文件大小不等于本次新传字节；检查前缀会再次读源并计算哈希。累计 source_read 因此可以超过文件通道 DATA。发送入队后失败的未确认字节不计入有效吞吐。零字节或零耗时返回有限的0，不产生NaN/无穷值。完整摘要结束后导出诊断，空闲 socket 等待不会继续拉长文件耗时。

Resume prefixes can be reread and hashed, so source_read can exceed transmitted DATA. File size is distinct from newly transferred payload. Queued but unconfirmed bytes do not inflate goodput. Empty/zero-duration cases yield finite zero rates. Finished summaries are frozen, so idle socket waits cannot extend file duration.

原 stage 计数器通过 counter_component 和随机 counter_instance_id 区分来源/重建，继续保留，作用域为其所属组件实例，自构造开始累计，不能相加当成墙钟关键路径。writer_queue/receive_queue 是排队等待，credit_wait 是背压；socket_header 包含等待下一条网络消息，不单独证明解析瓶颈。wire 计数可能包括控制、探测、包头及加密开销；队列 bytes 是保留内存预算，不等于有效文件大小。并发发送和接收共用的阶段计数不冒充单文件计数；每次尝试仅独立归属其有效载荷和状态墙钟阶段。

Existing stage counters identify their owner and reset instance with counter_component and a random counter_instance_id, and remain cumulative within their owning component since construction. Overlapping totals are not additive wall-clock critical paths. Writer/receive queues measure queue wait, credit_wait is backpressure, and socket_header includes waiting for another network record. Wire bytes can include control/probe/framing/encryption overhead; queue bytes describe retained memory. Shared send/receive stage counters are not relabeled as per-file counters; only attempt payload and exclusive state durations have independent attribution.
