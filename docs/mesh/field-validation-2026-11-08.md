# Mesh 实机反馈与 PR 审查索引（2026-11-08）

记录日期为 2026-11-08；实际实验时间未提供。本记录引用仓库所有者在研发对话中的反馈，无可公开引用的对话 URL，未附成功场次的诊断包。它是用户实机确认，不是维护者独立复现报告。

## 已获得的实机反馈

- Android preview.6 阶段，所有者反馈：“最新的这版aPk实现了稳定的mesh连接，而且是多台设备的。” 对 A/C 不直连、经 B 双向中继的追问，回答“没问题的”。这是 Android BLE CoC 多设备及所描述中继场景的用户确认，不代表旧 GATT 适配器通过验收。
- Windows Next 与配套 Android preview.7 交付后，所有者反馈：“太棒了，mesh和中继都成功了”。这是该交付上下文中的 Mesh/中继实机成功反馈；具体设备角色、数量、方向和持续时间未记录。
- 早期失败场次的 Xiaomi 23043RP34C / M2102J2SC 诊断不能自动归属于这两次成功场次。没有新性能、耗电或成功率测量。

上述反馈应保留，不能继续笼统称为“没有任何实机成功”。但没有完整设备清单、系统/控制器版本、物理隔离条件、无共同 AP/互联网证明、逐跳路由日志、relay-off 负例、故障恢复及资源测量。#91 的正式三台 Android 无基础设施验收仍为 **hardware validation pending**；Windows 中继成功不能替代该验收。Wi-Fi Direct/Aware 无新增实测结论；不推断蓝牙与 Wi-Fi 混合多跳。

## 可追溯交付与自动化证据

| 交付 | 来源与验证 | 边界 |
| --- | --- | --- |
| Android preview.6 | 来源 `9295f0cf0a7c8ad335abce1140fae4f46a8c37a6`；[CI 41633611401](https://github.com/GHOST-AKU/wozai/actions/runs/41633611401) | CoC 阶段的字段反馈，不是旧 GATT |
| Android preview.7 | 来源 `c70ed8788f6eec6b78b5f749833dbbf025699873`；[签名 CI 42372287886](https://github.com/GHOST-AKU/wozai/actions/runs/42372287886)，50 tests，lint 0 errors / 76 warnings | APK SHA256 `69d719a6778167969adf80e63fd842b2c0bfd5f07877ad8dd13eb32190b7accb`；3,877,020 bytes |
| Windows preview.7 | 编译来源 `51ffb70c4da526c5a06e30f9dfff0e82a385e165`；[完整 CI 42372953761](https://github.com/GHOST-AKU/wozai/actions/runs/42372953761)，242 bridge checks、27 C# tests | 自动化不证明射频链路 |
| Windows 最终 ZIP | 通知/打包来源 `0f519bf7e2dfc7f418ca7f48dfc5eda610253e86`；[CI 42373938848](https://github.com/GHOST-AKU/wozai/actions/runs/42373938848)，[artifact](https://github.com/GHOST-AKU/wozai/actions/runs/42373938848/artifacts/13573919678) | 复用经来源比较的二进制，27 tests 未重跑；ZIP 123,108,287 bytes，SHA256 `a984750e34f955bb3b32468cbb4accf97e74ff9b3c2930c0c4aa0336ac2bf455` |

这是已有运行与产物的索引，不是当前文档提交的新 CI。没有为本次记录重建客户端；不宣称不同构建环境的二进制逐字节可复现。

## PR 依赖与审查范围

顺序：[#85](https://github.com/GHOST-AKU/wozai/pull/85) → [#93](https://github.com/GHOST-AKU/wozai/pull/93) → [#96](https://github.com/GHOST-AKU/wozai/pull/96) → [#99](https://github.com/GHOST-AKU/wozai/pull/99)。四个头提交形成真实祖先链；不需要复制提交或删除历史证据。

| PR | 本次整理前头提交 | 审查边界 |
| --- | --- | --- |
| #85 | `cdb028f68aaa9c82e0cc9c897314fe2e7becc03f` | 独立 Rust Core / TCP lab；263 files，含许可证和证据，不含本次无线成果 |
| #93 | `6ef58e28ebf895d059cbdf33908c7255c35ef788` | 以 #85 为基线；51 files；GATT 历史研究、共享桥接、Aware/工具；不能用 CoC 成功替其验收 |
| #96 | `9295f0cf0a7c8ad335abce1140fae4f46a8c37a6` | 从 main 改以 #93 为基线：377 → 156 files；Android Next 集成及 CoC 修复 |
| #99 | `0f519bf7e2dfc7f418ca7f48dfc5eda610253e86` | 以 #96 为基线；Windows Next / RFCOMM 与 Android 兼容；本次增加文档 |

#85 与当前 main 的本地 `git merge-tree --write-tree` 试合并没有文本冲突；这不替代 GitHub 合并状态、最新 CI 或完整工程质量审查。保留 #85 现有 Ready 状态，#93/#96/#99 保留 Draft。本次不合并、不发布，不修改客户端或 NIM4。

## Epic / 专项 Issue 对齐

| Issue | 当前成果与仍待完成的条件 |
| --- | --- |
| #60 | 独立 Core、Android/Windows 客户端已有实现及用户实机反馈；正式无线 gate、耗电/后台/兼容性矩阵未完成 |
| #86 | 有原生传输实现；完整硬件并发/系统能力及吞吐矩阵 pending |
| #87 | 现有 TCP Core 验收已勾选；不扩大到无线验收 |
| #88 | 核心安全/有界转发已有测试；完整每邻居预算可观测性 pending |
| #89 | 连接与恢复实现已有进展和用户成功反馈；权限变化、后台、电池及故障矩阵 pending |
| #90 | 威胁模型/恶意输入已有证据；完整资源观测、实际电量策略和持续 NIM4 回归 gate pending |
| #91 | 用户确认多设备 Mesh/中继成功；正式可复现的三 Android 隔离、relay-off、恢复与资源证据 pending，保持 OPEN |
| #92 | 保持 NIM4 隔离边界；未来 RFC/API/威胁分析 pending，本轮不扩展 |

不因部分成果而勾选复合验收条件。后续优先整理现有诊断与自动化，补齐正式实验记录；不要求所有者为本次文档整理重新反复测试。
