# 公共 Mesh 文档导航

当前总览见[项目进度](../status.md)，Epic 为 [#60](https://github.com/GHOST-AKU/wozai/issues/60)。以下按用途组织既有资料，不移动已引用文件。

| 用途 | 文档 |
| --- | --- |
| 独立引擎运行 | [mesh-lab](../../mesh/README.md) |
| 研究与技术取舍 | [研究报告](research.md)、[ADR](adr-001.md)、[移动传输限制](mobile-transports.md) |
| 协议与安全 | [公共消息协议](protocol.md)、[安全边界](security.md)、[合并审查](merge-review.md) |
| 原生适配设计 | [设计](native-radio-design.md)、[实施计划](native-radio-plan.md)、[自动化证据](native-radio-verification.md) |
| BLE 故障与加固过程 | [设备不稳定分析](ble-device-instability.md)、[稳定性审计](ble-stability-audit.md)、[调度审查](native-radio-scheduling-review.md)、[质量审查](native-radio-quality-review.md) |
| Android Next | [初版接入记录](android-next-preview.md)、[诊断](android-diagnostics.md)、[radio 操作](../../mesh/android-radio/README.md) |
| Windows Next | [移植研究](windows-port.md)、[客户端](windows-client.md)、[radio 工具](../../mesh/windows-radio/README.md) |
| 验收与实际反馈 | [2026-11-08 实机反馈](field-validation-2026-11-08.md)、[正式硬件计划](hardware-validation.md)、[核心验证](validation.md) |
| 体积与资源 | [体积审查](radio-size-audit.md)、[原生质量审查](native-radio-quality-review.md) |
| Apple 后续实现 | [Draft #103](https://github.com/GHOST-AKU/wozai/pull/103)；其文档位于对应分支，不冒充 main 已集成 |

早期 GATT 失败、TCP 实验和后续 BLE CoC/Windows 用户实测分别属于不同阶段。保留原始证据，但阅读时注明版本；不要用某个阶段的 pending 否定后续成果，也不要用一次成功覆盖其他传输或操作系统。
