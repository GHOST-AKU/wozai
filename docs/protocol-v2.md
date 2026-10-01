# NIM2 设备身份与消息封装

0.2.0 的 `FramedSession` 必须使用 NIM2，没有无认证构造入口，也不会回退到旧版裸 NIM1 帧。两端必须升级。内层帧的 UUID、UTF-8、长度、许可、回执和心跳规则仍由 `Protocol` / `FramedSession` 校验。

本机长期 P-256 签名密钥保存在 Android Keystore。公钥为规范 Base64 X.509 编码，ECDSA 使用 SHA-256；生产代码不导出私钥。首次认识由用户决定信任，公钥不是经过认证的真实姓名。

## 线格式

整数为大端。外层为 `int32 payloadLength | int32 0x4e494d32 | byte 2 | byte kind | fields`。长度在分配内存前校验。长度前缀字节串写作 `len(bytes) | bytes`。

| kind | 内容 | 最大 payloadLength |
| --- | --- | --- |
| OFFER = 1 | 完整内层 HELLO、规范 X.509 公钥、32 字节随机 nonce | 512 |
| PROOF = 2 | DER ECDSA 签名 | 96 |
| RECORD = 3 | int64 序列、完整内层帧、DER ECDSA 签名 | 9344 |

公钥最多 256 字节，签名最多 80 字节，HELLO 内层最多 190 字节，其他内层帧最多 9220 字节。外层 payload 包含魔数、版本、kind 及其 fields。nonce 每次连接重新生成。

对于发送者 S、接收者 R，方向绑定值为：

```text
binding = SHA256("wozai-session-v2\0"
                 || len(offerS) || offerS
                 || len(offerR) || offerR)
proofInput = "wozai-proof-v2\0" || binding
recordInput = "wozai-record-v2\0" || binding
              || int64 sequence || len(innerFrame) || innerFrame
```

`offerS` 与 `offerR` 为完整 OFFER payload。序列从 0 开始，接收时必须等于下一期待值，验证签名成功后才交付内层帧。双方交换 OFFER 和 PROOF，只有远端证明验证成功才触发 `onHello`。READY 也走签名记录。签名绑定双方 OFFER、方向和序列，因此以前的证明、跨会话帧、重复帧与反射帧不能复用。

## 授权与回执

控制器校验预期 UUID 与已保存公钥。相同 UUID 换密钥会拒绝连接；用户需先撤销旧信任，再主动重新认识。NSD 的 UUID、昵称、IP 与蓝牙地址都只是发现或路由线索，不替代密钥证明。

主动点击连接同意本次；首次来访显示「同意并记住 / 仅本次 / 拒绝」。记住授权仅在双方 READY 后落库，撤销与取消会使仍在途的授权失效。旧消息不会自动成为信任，清空消息不撤销信任。

收件保存成功才 ACK。收件和待回执发送各最多 32 条，输出操作队列最多 64 条。签名不提供机密性：局域网消息内容仍是明文，没有端到端加密。
