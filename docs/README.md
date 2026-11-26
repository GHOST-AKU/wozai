# 开发文档导航

先读[项目进度与证据边界](status.md)，再按主题进入。这里区分当前导航、历史记录和未合并方案，避免从某篇旧实验报告推断整个产品状态。

| 目的 | 入口 |
| --- | --- |
| 当前实现、开放 PR、下一阶段 | [项目进度](status.md)、[路线图](roadmap.md) |
| 源码职责、共享核心、构建边界 | [仓库结构](repository-map.md) |
| 开发环境与本地清理 | [开发环境](development.md)、[协作规则](../AGENTS.md) |
| 正式版用户功能与下载 | [主 README](../README.md)、[0.3.3 发行说明](releases/0.3.3.md) |
| Android Next | [公共聊天体验](android-next-public-chat.md)、[客户端说明与对等审计](android-next.md)、[IME 验收](android-next-ime-acceptance.md) |
| Windows Next | [客户端说明](windows-next.md)、[本机 IPC 契约](../windows-next/PROTOCOL.md) |
| Apple / Linux / Desktop | [Apple](apple.md)、[Linux](linux.md)、[Windows](windows.md) |
| 终端客户端 | [中文说明](../terminal/README.zh-CN.md)、[架构](../terminal/docs/architecture.md) |
| 公共 Mesh | [专题导航](mesh/README.md)、[独立节点操作](../mesh/README.md) |
| 私聊安全与身份 | [安全码](identity-verification.md)、[文字](text-messages.md)、[送达](message-receipts.md)、[已读](read-receipts.md) |
| 文件、路径与诊断 | [传输诊断](transfer-diagnostics.md)、[LAN 发现](lan-discovery.md)、[诊断分享](diagnostics-sharing.md) |
| 版本、翻译、签名和许可证 | [i18n](i18n.md)、[Android 签名](android-signing.md)、[原生预览安装](releases/native-preview-install.md)、[依赖通知](../THIRD_PARTY_NOTICES.md) |
| 历史设计与验收 | [早期路线图](history/roadmap-before-2026-11-17.md)、[实施记录](implementation-ledger.md)、[验证记录](verification.md)、`superpowers/specs/`、`superpowers/plans/` |

## 文档维护方式

- `status.md` 是注明日期的总览；具体能力仍以相应提交、PR 和源码为准。正式发行包、main、开发分支分别描述。
- 专题文档保留实测、失败记录和历史源码链接，不因合并或新版本删除证据；旧状态加范围说明。
- 未合并设计链接至所属 PR/分支，不复制另一份到 main 制造两个权威源。计划不等于已实现，自动化不等于射频验收。
- 新源码继续放入所属模块，构建输出使用现有被忽略目录；临时调查和机器清理报告放仓库外。工具入口见[tools 导航](../tools/README.md)。
