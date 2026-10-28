# 长消息边界与 NIM4 兼容 / Text capacity and NIM4 compatibility

Issue [#30](https://github.com/GHOST-AKU/wozai/issues/30) 的开发中方案；不代表已发布的 0.3.2 或 0.3.3-preview.1 已包含这些修改。

Development work for Issue #30; the published 0.3.2 and 0.3.3-preview.1 binaries do not contain these changes.

| 场景 / Case | 单条文字 / Message | 草稿 / Draft |
| --- | --- | --- |
| 新端完成能力协商 / New peers after negotiation | 32768 UTF-8 bytes (32 KiB) | 262144 UTF-8 bytes (256 KiB) |
| 旧端或协商未完成 / Old or not yet negotiated | 8192 UTF-8 bytes (8 KiB) | 262144 UTF-8 bytes (256 KiB) |

计数对象为原始文字的 UTF-8 字节，不是 UTF-16 单元、字符数或字素簇数。中文通常为 3 字节，单个 Emoji 通常为 4 字节，ZWJ 组合可能更长。发送不会 trim、规范化或分割文字。纯 U+0000…U+0020 空白文字不可发送；NBSP 等字符按现有 NIM4 语义保留。未完成或无效的 UTF-16 序列可以暂存在 Java 输入框中，但不得编码为消息发送。

Count the UTF-8 bytes of the original text, not UTF-16 units, characters or grapheme clusters. Chinese characters commonly take three bytes, individual Emoji four, and ZWJ sequences more. Sending does not trim, normalize or split the payload. Text consisting solely of U+0000…U+0020 is blank; NBSP retains existing NIM4 semantics. Java drafts can preserve unfinished UTF-16 input, but malformed sequences cannot be sent.

Android 和桌面正常输入不显示字节计数；超限时用自然语言提示，保留草稿，可以缩短、复制或作为文件发送。终端仍显示连接上限。超过 256 KiB 草稿预算的编辑整次拒绝，并提示原文保留；不截取粘贴文本的前半段。正常编辑直接保留输入法 spans。终端继续过滤不可显示的控制字符，避免终端转义执行。Android 草稿使用解析后的系统私有目录原子保存；真正的读写失败在当前会话显示一条可点按重试的状态，不反复弹出提示。

Normal Android and desktop input has no byte counter. Oversized messages receive a plain-language explanation and remain editable drafts: shorten, copy, or send as a file. The terminal composer still displays the connection limit. An edit beyond the 256 KiB draft budget is rejected as a whole with visible feedback; no paste prefix is silently retained. Accepted Android edits preserve the original IME spans. Terminal input still filters control characters to prevent escape execution. Android drafts are committed atomically below the resolved system-private directory; a real read/write failure appears as one retryable status in the affected conversation instead of repeated popups.

## 能力协商 / Capability negotiation

原 Noise 握手、能力位、NIM4 版本及 48 KiB 明文记录边界不变。旧实现拒绝未知能力位，所以不直接增加握手标志。两端授权后，通过已有的空 PONG 帧交换带有版本和连接绑定的 timestamp token；旧端本来就忽略 PONG timestamp。此交换不使用 TEXT、ACK 或用户历史记录，也不发送新 record type。

Keep the Noise handshake, capability bits, NIM4 version and 48 KiB plaintext record budget unchanged. Old implementations reject unknown handshake bits. After mutual approval, exchange versioned, connection-bound tokens in existing empty PONG frames; old peers already ignore their timestamps. This uses neither TEXT, ACK nor conversation history, and introduces no record type.

For the authenticated lowercase 64-hex connection generation `g`:

```text
request = 0x574f || SHA256(UTF8("wozai-text-capability-v1\0request\0" + g))[0:6]
response = 0x5752 || SHA256(UTF8("wozai-text-capability-v1\0response\0" + g))[0:6]
```

Concatenate in big-endian order into a positive 64-bit timestamp. With `g = "a" * 64`, the request is `574f57812e4e8cd3` and the response `575213fb17e249ae`.

每个连接最多主动发送一次 request、响应一次匹配 request。收到匹配 request 后，先放宽该连接的接收上限，再通过有序 writer 发送 response。只有主动 request 的匹配 response 才放宽发送上限。两个方向分别维护上限；普通、重复或旧连接的 PONG 不会意外启用扩容。静默旧端保持 8 KiB，新连接重新协商，FILE 专用通道不协商文字能力。

Send at most one request and one matching response per connection. On a valid request, enable the larger incoming limit before queuing its response through the ordered writer. Only a matching response to the local request enables larger outgoing text. Maintain separate directional limits. Ordinary, duplicate and stale-generation PONGs cannot accidentally enable expansion. Silent old peers stay at 8 KiB; reconnects start fresh, and dedicated FILE lanes do not negotiate text.

终端在互相 READY 后最多等待 1 秒能力协商，再发布本地 ready 事件，避免 CLI 的立即发送抢在能力确认之前；迟到的正确响应仍可继续提升到 32 KiB。这不是新握手超时，也不会为等待能力关闭连接。

The terminal allows up to one second after mutual READY for capability negotiation before publishing its local ready event, preventing CLI send-on-ready from racing the exchange. A later valid response can still upgrade capacity. This is not a new handshake timeout and never closes a connection for lacking the extension.

## 有界资源与存储 / Resource and storage bounds

- 32768-byte TEXT plus its 58-byte framing fits inside the unchanged 49152-byte plaintext record. No fragmentation, partial-message persistence or reassembly is introduced.
- Java retains the existing 32 unpersisted-message and 32 awaiting-receipt guards. Its writer control budget is 2 MiB, including active/queued writes, so 32 maximum-sized messages plus overhead fit. FILE budgets and fairness remain unchanged.
- Rust keeps its bounded record reader/command queues; decoding follows ordered plaintext processing so a capability echo cannot be overtaken by a larger message.
- Android and Java desktop draft journals store UTF-16 units losslessly with a checksum, explicit size bound and atomic replacement. Android uses a single application-scoped worker, coalesces writes and keeps large drafts out of saved-state Bundles. Disk persistence errors are visible; pending writes can still be lost if the OS forcibly kills the process before they finish.
- Desktop migrates valid legacy Properties drafts to bounded raw journals without deleting the original. Clearing a migrated draft is authoritative. Over-budget/corrupt legacy files are retained and reported rather than truncated. Message Properties reads allow 256 KiB for escaped 32 KiB text; settings keep their existing 64 KiB bound.
- Apple and terminal retain SQLite drafts and histories, with a 256 KiB budget on new draft writes. Persistence precedes delivery acknowledgement. Local oversized input is rejected before saving an outgoing message.

自动化测试覆盖计数边界、组合 Emoji、超长替换、草稿重启、32 KiB 历史、真实加密会话、固定旧版 NIM4 源码双向互通及 Java/Rust 和 Java/Swift 互通。CI 与 Issue 记录具体结果。输入法、屏幕阅读器和长历史的真机体验仍需单独验收；这些检查不等同于 #20 的完整 Unicode 塑形验收或 #9 的正式性能验收。

Automated checks cover byte boundaries, combined Emoji, oversized replacements, draft restarts, 32 KiB histories, real encrypted sessions, a pinned old NIM4 implementation in both roles, and Java/Rust plus Java/Swift interoperability. CI and the Issue track actual results. Hardware IME, accessibility and long-history experience still require acceptance; these checks do not complete #20 Unicode shaping or #9 performance acceptance.
