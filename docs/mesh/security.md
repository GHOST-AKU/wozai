# 公共 Mesh v1 威胁模型与合并审查边界

适用范围：独立 Rust Core + Linux TCP lab，关联 #60 / #88 / #90；不认证真实身份、物理邻接或无线可用性。公共正文对邻居/中继可读，当前没有 E2EE、屏蔽 API、可靠 DTN、平台密钥库或后台服务。

## 资产、信任与攻击者

- 保护来源/内容完整性、固定期限/逻辑跳数、单节点有界资源、未过期 ID 的去重，以及既有 NIM4 密钥/信任/会话的隔离。
- 网络对端和发现广告不可信。攻击者可生成很多密钥、发送任意字节、重放包、断开连接、发布虚假地址、延迟/丢弃消息或串通隧道。
- 主机提供的时间、配置/CLI、中继授权和文件系统权限是本地信任边界。代码拒绝损坏状态/符号链接，但不能抵御同权限攻击者直接更改私钥、二进制或删除整个 profile。seed 是本地0600明文，非硬件防提取。
- 来源签名仅证明假名密钥持有。没有全局成员数、可信昵称、距离或已读回执；不会把发现/连接提升为 NIM4 私聊信任。

## 不变量与回归证据

| 场景 | 保证 / 限制 | 主要验证 |
| --- | --- | --- |
| 篡改来源、正文、期限、版本、最大预算 | 先验证规范摘要/Ed25519，拒绝后不展示/中继 | tests/core.rs、独立 Python/cryptography 固定向量 tests/vectors.rs |
| 重放、环路、删除/重置 hop | 有效期内持久 seen；签名 receipt 完整前缀、接收者与认证邻居绑定；禁止路径重复 | core.rs、SIGKILL 后 exact-ID duplicate、cycle/分区进程案例 |
| 时间回退/跳变 | 所有相关入口与 TX 前保留主机时间下界；中继不能修改既有envelope期限，runtime不会自动续期；起源仍能重新签发新ID | core/resources/main 的 clock regressions；缺乏可信网络时间时可能拒绝合法不同钟节点 |
| 超长/坏格式/截断/慢连接 | 先检查帧长度；拒绝未知字段、重复握手、坏签名；deadline 和 RX/TX 上限 | resources.rs、test_lab.py |
| 多身份洪泛 | 入口32frame/s/128KiB/s每连接、每次连续运行Core实例全局128条/有效时钟自然分钟、每来源16条/分钟、64来源bucket | tests/policy.rs、core/resources；不能防Sybil抢占邻居、审查或实现公平服务 |
| 重复发现/并发重复连接 | 16候选、4拨号任务、8接纳链接；双方按方向与规范challenge对选择同一连接 | tests/manager.rs、main.rs逆序双端fixture与进程竞争案例 |
| 本地状态损坏/悬空链接 | 仅真正NotFound视新状态，其他错误fail closed；先同步去重再展示/转发 | tests/store.rs、resources.rs、test_persistence.py |
| 撤销中继/丢包/断连 | 未发transit撤销并释放offer；部分帧关闭连接维护framing；每出口最多3次准备 | resources/core、test_loss.py丢弃一次真实frame再重连 |
| 诊断/采集泄漏 | diagnostics仅计数/限额；probe采集严格事件字段类型白名单、8KiB行/1MiB总量/timeout | Android capture 9项本地fixture；常规lab stdout仍含公共正文、公钥和路径，不是安全导出 |

## 配额与持久化

默认64条pending、1024个seen ID，8条接纳链接另加最多4拨号任务；每链接8个TX frame，每frame至多32768bytes。因此TX用户态序列化数据不超过8×8×(32768+4)bytes，另有RX、路由/索引、库/allocator及内核socket缓冲；不能把这个公式当RSS或内核全内存上限。入口字节预算含握手。自然分钟边界允许相邻窗口burst，不是滑动窗口。

seen满时拒绝新ID，不能驱逐有效ID。到期通知至多64个等待消费，满时背压；缓存每消息最多8出口、每出口3次准备。每个成功接纳的新消息同步写有界全量快照并fsync/rename/目录同步；每个连续运行Core实例、每个有效时钟自然分钟至多128次新消息写入，另有10s周期checkpoint、到期和退出写入。重启会重置来源/全局速率桶，异常重启后仍可接纳新ID；seen继续持久化，因此前述128并非跨重启的fsync总次数上限。没有测长期flash磨损或手机功耗，不把次数预算写成能耗成绩。正文仅内存，重启可能丢失转发工作；seen不是可恢复邮箱。

## 转发、显示与授权政策

当前所有通过验证且符合准入限额的公共消息都可显示，没有nickname信任或block/mute实现。relay默认关闭，只有本地 --relay / relay(enabled=true)启用。关闭仅停止第三方转发，不阻止本机收件或自己的发送；已写入内核的字节不能撤回，负例必须用off确认后新ID。GUI同意、每网络策略、电量/后台控制及native权限撤销验收仍pending #89/#90/#91。

只采用同ID第一次接纳的路径：后到的更短路径不会替换缓存，首份耗尽预算也不会因后续副本重新传播。这会限制菱形/环图覆盖；受控泛洪是best effort，不承诺最短路、全网覆盖/可靠交付，当前没有为掩盖限制扩展路由功能。

## 合并与发布条件

研究模块可单独审查/合并的条件是：重要代码缺陷修复、针对性回归、锁定依赖和许可证可核对、最新HEAD专用CI真实通过、源文件/证据可追溯，且NIM4正式行为无变化。#86–#92的未完成产品/无线/接口任务不等同于独立模块的合并阻塞。

#91的真实无基础设施三台Android双向多跳仍为 **hardware validation pending**；任何TCP/loopback/netns成功都不能勾选其无线gate或宣称Mesh Alpha。没有正式第三方密码学认证。
