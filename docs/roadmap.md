# 开发路线与优先级

更新：2026-11-17。当前源码基线及未合并工作见[项目进度](status.md)，文档入口见[导航](README.md)。应用版本以 `i18n/config.json` 为准；路线图不决定发布版本号。

## 当前基础

NIM4 加密私聊、文件传输和原生多平台客户端已有实现。Android Next、Windows Next 已接入独立公共 Mesh，所有者已反馈多设备及中继成功；正式无线验收记录仍待补齐。Apple 公共 Mesh 与 Apple/Windows BLE 私聊分别在 Draft PR 中，不算 main 已有功能。

## 后续工作及依赖

| 工作 | 状态与下一步 | 跟踪 |
| --- | --- | --- |
| 私聊直接发送、自动连接、可靠待发 | 全平台研究设计已写好，尚未实现。按联系人管理会话、身份绑定的持久待发队列、连接竞争与恢复需要一起设计实施；不能只自动点击重连 | [#101](https://github.com/GHOST-AKU/wozai/pull/101) |
| 多设备分别私聊 | 当前单活动会话；切换联系人不应打断其他聊天/文件。与自动会话管理共用基础，按有界资源和各端能力推进 | [#6](https://github.com/GHOST-AKU/wozai/issues/6) |
| 公共 Mesh 工程完善 | 保留已有实机成果，补足拓扑/relay-off/恢复/资源与平台限制记录；蓝牙、Aware、Direct、LAN 分别报告，不能相互代替验收 | [#60](https://github.com/GHOST-AKU/wozai/issues/60)、[#86–#92 索引](mesh/field-validation-2026-11-08.md) |
| Apple 公共 Mesh / BLE 私聊 | 实现与自动化已有；等待相应硬件，继续以 Draft 边界审查，不能因 CI 成功而声称无线互通已验收 | [#103](https://github.com/GHOST-AKU/wozai/pull/103)、[#108](https://github.com/GHOST-AKU/wozai/pull/108) |
| Android Next 体验与功能对等 | 已有 IME、气泡、Emoji、二维码和传输信息改进；剩余功能差异及设备矩阵按专项文档推进 | [Android Next](android-next.md)、[#94](https://github.com/GHOST-AKU/wozai/issues/94)、[#95](https://github.com/GHOST-AKU/wozai/issues/95) |
| 存储规模与性能 | Windows 小文件布局需基准和恢复语义比较；不能把已删除测试附件误称为泄漏，也不凭空迁移数据 | [#97](https://github.com/GHOST-AKU/wozai/issues/97) |

这是依赖关系与建议工作顺序，不是新增实施授权或发布日期承诺。缺少某平台设备只阻塞相应硬件验收；协议、存储、会话管理与其他平台的开发可继续推进。

## 持续约束

- 公共 Mesh 可被中继阅读；NIM4 私聊保持独立身份、安全确认和加密边界。Mesh 私聊需要独立接口/安全设计，不能直接缓存和重复发送 Noise 密文。
- 测试、CI、用户反馈与正式硬件验收分别记录，注明提交、平台和证据范围。未满足复合验收条件不关闭 Issue。
- 原生界面、五语言、无障碍、持久化与升级安全随功能实施，不推迟到发布前；必要测试按改动范围执行。
- 签名和版本权威遵循 [AGENTS.md](../AGENTS.md)；合并和发布分别授权。

[早期路线图历史快照](history/roadmap-before-2026-11-17.md)保留 NIM2 等原始叙述，供追溯，不再作为当前产品说明。
