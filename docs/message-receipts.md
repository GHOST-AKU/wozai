# 消息状态图标 / Message receipt icons

开发中：Issue #29；尚未发布。Android 与桌面的自己发出气泡，在右下方的时间旁绘制原生线条图标，图标不依赖系统字体。收到的消息只显示时间。

Development for #29, not published. Android and desktop draw native receipt strokes beside the outgoing bubble's bottom-right timestamp. Incoming bubbles retain a timestamp without an outgoing receipt. No icon font is required.

| 状态 / State | 图形 / Shape | 依据 / Evidence |
| --- | --- | --- |
| pending | 时钟 / Clock | 本地等待写入或写入中 / Queued or writing locally |
| sent | 单勾 / Single check | 完整 TEXT 记录的本地写入返回成功 / Complete TEXT record write returned successfully |
| delivered | 双勾 / Double check | 对端保存后的 ACK，文件为验证匹配的 COMPLETE / Saved peer ACK; validated COMPLETE for files |
| read（文字 / text） | 高亮双勾 / Highlighted double check | 对端真实可见事件生成回执且本端已保存 / Peer display event produced a read receipt, persisted locally |
| unknown / interrupted / paused / canceled | 圆圈提示 / Circled notice | 未确认或中断；不能据此断言失败 / Unconfirmed or interrupted, not proof of failed delivery |
| failed | 警示三角 / Warning triangle | 已有明确失败状态；保留附件重试入口 / Existing explicit failure; attachment retry remains available |

发送中不等于进入队列后立即“已发送”；本地写入也不等于对端保存。sent 只从 pending 提升，迟到或重复的写入事件不能覆盖 delivered。旧 pending/unknown/delivered 历史继续可读，原有历史继续兼容；新增已读扩展的存储与协议边界另见已读说明。状态仍以文字提供给屏幕阅读器，复制/测试用的消息可读表示保留语义文字。

Enqueue does not mean sent, and a local write does not mean peer persistence. A written event only promotes pending; late/duplicate events cannot overwrite delivered. Existing histories remain readable with the new read extension and persistence boundaries documented separately. Screen readers retain localized state descriptions and the semantic plain-text representation includes them.

旧版／未协商客户端和先前23978cf包不提供高亮已读；preview.3开发实现只在真实已读回执成功保存后高亮双勾，参见 [已读说明](read-receipts.md)。文字连接中断后的 unknown 仍是未确认，不能冒充明确失败；既有附件进度、暂停、取消及重试动作保持原语义。文字遵循系统字号，Android图标按屏幕密度绘制，桌面图标随现有字号比例缩放；两端均适配主题；未改变平板分栏和吸附。

Older/unnegotiated peers and the prior23978cf client have no highlighted read state. The preview.3 development implementation highlights checks only after a real read receipt is persisted; see [read receipts](read-receipts.md). An unknown text result after disconnect remains unconfirmed. Existing attachment progress, pause, cancel and retry retain their semantics. Text follows the system font size. Android icons use density-scaled coordinates; desktop icons follow the existing text scale. Both adapt to the theme; tablet splits and snapping are unchanged.

后续真实已读回执的开发设计及兼容边界见 [read-receipts.md](read-receipts.md)；本页前述23978cf客户端仍不含已读。See [read-receipts.md](read-receipts.md) for the subsequent real-read extension; the previously verified23978cf client does not include it.
