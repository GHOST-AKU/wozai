# Nearby Mesh Lab v1：实验协议

本格式只用于独立预研，不是 NIM4/NIM5。JSON 名称属于实验 API，正式互操作前必须版本化审查。默认公共明文，无昵称可信、E2EE、位置证明或全局送达保证。

## 帧、握手与边界

TCP 每帧：`u32 big-endian JSON字节长度 || JSON UTF-8`。长度 1–32768，先验长度再读正文；JSON 使用拒绝未知字段的 serde 类型，解析失败断开。没有压缩或远程命令执行。

1. 双方发送 `{"type":"hello","version":1,"key":lowercase_hex_32_bytes,"challenge":random_hex_16_bytes}`。
2. 双方发送 `{"type":"auth","signature":hex_64_bytes}`，签名内容为 `NearbyMesh/neighbor/v1\0 || own_challenge[16] || remote_challenge[16] || own_public_key[32] || remote_public_key[32]`。对方颠倒 own/remote 验证其签名。重复 hello/auth、自己的 key、auth-before-hello、data/ping-before-auth 拒绝。
3. 消息帧 `{"type":"data","packet":{"message":Envelope,"route":[Hop,...]}}`；保活 `{"type":"ping"}`。签名握手不加密，证明假名私钥持有，不建立可信设备关系。

## 不可变 envelope

| 字段 | 约束 / 签名顺序 |
| --- | --- |
| domain | 固定字节 `NearbyMesh/public/v1\0`（末尾一个 NUL） |
| version, kind | 各 u8，均固定 1；kind=公开 UTF-8 文本 |
| origin | Ed25519 公钥 32 bytes |
| nonce | CSPRNG 16 bytes |
| created, expires | 各 u64 big-endian，Unix 秒 |
| max_hops | u8，1–8，默认 3 |
| body length | u32 big-endian，UTF-8 字节数 |
| body | 1–2048 bytes，不按字符数计；JSON 之外的签名输入是原始 UTF-8 |

签名输入就是上表拼接。`id=SHA256(input)` 全 32 bytes，`signature=Ed25519(input)`。JSON 的 id/key/nonce/signature 均小写 hex；JSON 排版/字段顺序不参与签名。JSON 的整数必须匹配 u8/u64 类型，溢出拒绝。必须满足 `created < expires <= created+600`、`created <= now+30`、`expires > now`，加法溢出拒绝。验证 ID、签名、版本和全部边界后才能展示/加入去重。

## 可增长的中继元数据

`Hop={"to":public_key_hex,"signature":signature_hex}`。`from` 按序从 `message.origin` 或上一 Hop.to 推导，不接受额外不受约束的字段。

- 初始摘要 `p0 = SHA256("NearbyMesh/route-root/v1\0" || id[32] || message.signature[64])`。
- 第 i 跳签名输入 `bi = "NearbyMesh/hop/v1\0" || p(i-1)[32] || to[32]`。
- `Hop.signature = Ed25519(from, bi)`；下一个摘要 `pi = SHA256(bi || Hop.signature[64])`。
- 必须 `1 <= route.len <= message.max_hops <= 8`。origin + 所有 to 不允许重复，链尾 to 是本机 key，末跳 from 是握手证明的入口邻居 key。
- 出口只能由当前链尾接收者签给下一个身份；起源的空 route 只在本地创建使用，网络空 route 拒绝。每增加一项消耗一次逻辑传输预算；没有可重置的 remaining_hops 字段。

已签名 envelope 永不可变；route 只能验证后追加。改大 max_hops/期限会破坏来源签名，删除/重排/改写任何 receipt 会破坏后续验证或链尾/邻居绑定。合法分支各自保留完整前缀。

这不是物理 hop attestation。起源可重新签发消息，持有合法旧前缀的中继可对其他邻居创建新的合法分支，串通端点可隧道通信；协议不计算私下搬运的距离。广播传播放大还依赖本地限额，不能只靠签名链承诺抗所有 DoS。链公开假名路径，增加体积与关联风险，正式部署需审查。

## 去重、重连与诊断

同 ID 在 expires 前只展示一次；持久索引容量 1024，满则拒收新 ID，直到到期腾出空间。当前来源/速率预算、队列策略见 ADR；重连可最多再次 offer 既有消息，接收端判重。不持久正文，不能把 seen 索引当作可恢复邮箱。

可导出诊断仅计数/限额；正常交互事件 `message` 含正文和路径用于本地 lab。`hop_queued` 表示选择路径并进入出口队列，`handed` 表示完整帧交给本地 socket；只有目标 `message` 事件才证明该节点验证/展示，在实验中由独立进程日志配对确认。都不代表其他未知节点已收到或已读。


## 2026-11-01 自审补充（不改变签名字节）

入口仍完整验证 envelope/route；已验证缓存可先排除访问过的出口，避免每轮对无法转发的路径重新验签。终点包不占转发队列。撤销未发送的 TX 后释放特定邻居的 offer，但保留最多三次准备次数；部分帧已发送则关闭链接，以保持 framing 一致。

TX 使用发送前新采样并被 Core 保留的单调时间下界。自动清理产生的到期通知有界保留到显式 tick，不能悄悄吞掉。NDJSON 本地 send 的 ttl/hops 缺省时才使用默认值；负数、浮点、字符串、bool、null 等类型返回 budget_type。
