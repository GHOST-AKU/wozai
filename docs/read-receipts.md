# 已读回执 / Read receipts

## 中文

0.3.3-preview.3 开发中的原生客户端通过独立协商交换文字消息的真实已读回执。默认开启，可在设置中关闭。双方都开启且本次加密连接协商成功才交换；旧版或未支持的终端继续只有已发送／已送达，不断开连接、不改变长消息协商、Noise 握手或能力位。

已读表示应用已在前台、有焦点的聊天中稳定显示对应文字，不保证对方理解了内容。进入可见区域至少一半（超长消息以半个视口为阈值）并稳定停留至少250毫秒才计入。后台收到、通知预览、锁屏、其他会话、屏外内容和快速滚动不会产生已读。它不是下载完成或文件已打开的证明；本轮文字回执不改变附件完成状态。

- 等待时钟：尚未完成实际写入。
- 单勾：本端已实际写出。
- 灰色双勾：对方已保存，返回送达确认。
- 高亮双勾：对方产生真实已读回执，本端成功保存。Apple 保持现有原生状态标签，以主题强调色显示“已读”；图标来源调整另行考虑。

关闭后立即停止新的已读收发、撤销尚未写出的旧策略回执并清除待补发阅读队列；已经进入网络的记录无法撤回。已确认的历史已读保留。重新开启不补报关闭期间的阅读。离线期间在本机开启设置时显示的文字可保留待补发事实，重连后仍须双方开启；不是以最后阅读时间推断整段消息。

本机阅读事实先落盘，随后按最多32个具体UUID发送。对端将确切的己方消息标记为已读，落盘后再确认；收到确认才移除本机待补发项。进程退出或断线未确认的项会在后续可用会话重试，重复回执幂等。迟到的单勾、送达或断线事件不能降级已读。没有上传精确阅读时间、阅读顺序或滚动位置。

### 协议边界

扩展只存在于双方同意后的NIM4聊天连接，专用FILE流禁止使用。使用空PONG的时间戳探测，不占用TEXT/ACK或写入聊天记录：

```
request  = 0x5251 || SHA256(UTF8("wozai-read-receipt-v1\0request\0" + generation))[0:6]
response = 0x5253 || SHA256(UTF8("wozai-read-receipt-v1\0response\0" + generation))[0:6]
```

代际generation来自现有认证连接摘要。只有收到本代际请求才回应，收到自己已发请求的回应后才发送类型20的扩展；在未协商时绝不向旧端发送未知记录。普通心跳或其他会话的token不启用扩展。接收未知扩展前必须已发本代际回应且双方同意聊天。

类型20沿用NIM4二进制包装，id/body为空、timestamp/offset为0。载荷为网络字节序：version:u8=1、kind:u8(0=POLICY/1=READ/2=ACK)、发送方策略epoch:u64、对方epoch:u64、count:u8。POLICY的对方epoch为0，count为开关0/1，无UUID。READ/ACK的epoch均为正，count为1..32，后接每个UUID的16原始字节；总长19..531字节。epoch不超过有符号64位正数。重复UUID、尾随数据和无效元数据拒绝。

策略epoch随本机开关变化而增加；READ/ACK必须同时匹配双方当前epoch和开关。队列在实际写出前再次检查。每个方向最多一批未确认阅读，重复待处理READ/ACK不重复排队到存储层，策略通知合并，防止控制记录造成无界待写任务。先读存储再确认，不以收到网络包冒充持久化成功。

## English

The native preview.3 implementation negotiates real **text-message** read receipts, enabled by default with an optional setting. Both peers must enable them on the current authenticated NIM4 chat connection. Unsupported/older peers retain sent/delivered states without changing the handshake, capability bits, text capacity, or connection mode. Dedicated FILE lanes reject the extension.

Read means the app stably displayed that exact text in the focused foreground conversation, not proof of human comprehension. At least half the message must be visible (capped at half the viewport for very long messages) for250ms. Background delivery, notification previews, locked screens, other conversations, offscreen rows and quick scrolling do not qualify. Attachment completion/opening semantics are unchanged. Android/desktop highlight their existing double checks; Apple retains its native status label and highlights Read.

Turning the setting off revokes queued old-policy records and clears pending local read receipts. Records already handed to the network cannot be recalled. Confirmed historical read states remain; enabling does not backfill observations made while disabled. Locally enabled offline observations may be retained, but transmission still requires mutual opt-in after reconnect.

Local observations are durable before sending up to32 exact UUIDs. The sender durably stores read before acknowledging; only that confirmation retires the receiver's pending outbox. Reconnect/restart and duplicate packets are idempotent, and late sent/delivery/disconnect events cannot downgrade read. No precise reading timestamp, scroll position, reading order, or timestamp-based history watermark is transmitted.

The protocol above specifies the generation-bound, legacy-safe PONG tokens and bounded type20 binary packets. Both current policy epochs gate READ/ACK and queued writes. Each direction permits only one unconfirmed batch; duplicate pending content and policy callbacks cannot grow an unbounded storage queue. Storage failure never produces a successful receipt confirmation. Implementation, automated verification, hardware acceptance, merge and publication remain separate milestones; see [#29](https://github.com/GHOST-AKU/wozai/issues/29) for actual evidence.
