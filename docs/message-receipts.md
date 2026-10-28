# 消息状态图标 / Message receipt icons

开发中：Issue #29；尚未发布。Android 与桌面的自己发出气泡，在右下方的时间旁绘制原生线条图标，图标不依赖系统字体。收到的消息只显示时间。

Development for #29, not published. Android and desktop draw native receipt strokes beside the outgoing bubble's bottom-right timestamp. Incoming bubbles retain a timestamp without an outgoing receipt. No icon font is required.

| 状态 / State | 图形 / Shape | 依据 / Evidence |
| --- | --- | --- |
| pending | 时钟 / Clock | 本地等待写入或写入中 / Queued or writing locally |
| sent | 单勾 / Single check | 完整 TEXT 记录的本地写入返回成功 / Complete TEXT record write returned successfully |
| delivered | 双勾 / Double check | 对端保存后的 ACK，文件为验证匹配的 COMPLETE / Saved peer ACK; validated COMPLETE for files |
| unknown / interrupted / paused / canceled | 圆圈提示 / Circled notice | 未确认或中断；不能据此断言失败 / Unconfirmed or interrupted, not proof of failed delivery |
| failed | 警示三角 / Warning triangle | 已有明确失败状态；保留附件重试入口 / Existing explicit failure; attachment retry remains available |

发送中不等于进入队列后立即“已发送”；本地写入也不等于对端保存。sent 只从 pending 提升，迟到或重复的写入事件不能覆盖 delivered。旧 pending/unknown/delivered 历史继续可读，无数据库 schema 或线上协议改动。状态仍以文字提供给屏幕阅读器，复制/测试用的消息可读表示保留语义文字。

Enqueue does not mean sent, and a local write does not mean peer persistence. A written event only promotes pending; late/duplicate events cannot overwrite delivered. Existing histories remain readable with no database schema or wire protocol change. Screen readers retain localized state descriptions and the semantic plain-text representation includes them.

当前协议没有已读回执，因此不提供高亮“已读”双勾或伪造已读状态。文字连接中断后的 unknown 仍是未确认，不能冒充明确失败；既有附件进度、暂停、取消及重试动作保持原语义。图标与文字均随现有字号/主题缩放；未改变平板分栏和吸附。

The protocol has no read receipt, so there are no highlighted read checks. An unknown text result after disconnect remains unconfirmed. Existing attachment progress, pause, cancel and retry retain their semantics. Icons follow the current text scale and theme; tablet splits and snapping are unchanged.
