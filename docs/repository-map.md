# 仓库结构与代码归属

本轮保持源码路径不变。Gradle 同源复制、脚本、CI 和相对链接依赖现有布局；导航整理不需要重排客户端或复制通信核心。

| 目录 | 职责 / 构建边界 |
| --- | --- |
| `app/` | Android 原生 View 客户端，以及被 Next/Desktop 复用的 Java 协议、Noise glue、存储和平台通信代码；不是可整体删除的旧 UI |
| `app-next/` | Kotlin/Compose Android 客户端，Gradle `sharedJava` 从原核心生成同源编译输入；独立应用沙箱和身份 |
| `desktop/` | Java Desktop 客户端、平台原生蓝牙桥接与打包；也是 Windows Next 的私聊内核来源 |
| `windows-next/` | WinUI 3 C# 界面、JSONL Java bridge、Windows 公共 Mesh 适配和独立测试/打包工具 |
| `apple/` | Swift/SwiftUI iOS/iPadOS/macOS App、WozaiCore、Xcode 项目、Swift Package 与测试；Draft 功能以对应分支为准 |
| `terminal/` | 独立 Rust NIM4 TUI/CLI、自己的 Cargo.lock/测试与许可文件 |
| `mesh/` | 独立 Rust 公共 Mesh Core/CLI、原生 radio/probe、隔离实验、evidence 和依赖许可；自己的 Cargo.lock，与终端不是同一个 crate |
| `third_party/`、`licenses/` | 复用源码及依赖授权资料，和各 Rust 模块的 vendor-licenses 一起保留 |
| `i18n/` | 版本/语言注册与共享文案权威；原生派生资源由生成工具维护 |
| `tests/` | 跨实现、平台和历史回归验证；模块内测试保留在各自目录 |
| `tools/` | 共享构建、版本/资源生成、签名和验证工具；模块专用工具留在模块内 |
| `.github/workflows/` | 平台 CI、独立 Mesh CI 与发行流水线；跳过某平台必须依据明确路径和能力范围 |
| `docs/` | 当前导航、主题资料、历史设计、验收与发行说明；从 [docs/README.md](README.md) 进入 |
| `.agents/`、`AGENTS.md` | 仓库协作说明与产品工具技能，不能当缓存清理 |

## 输出与本地资料

- `build/`、各 `build/`、Rust `target/`、Apple DerivedData、.NET `bin/obj` 是生成输出；具体清理先检查里面是否保留交付包、状态数据或唯一诊断证据。
- 受 Git 跟踪的 `mesh/evidence/`、`docs/performance/`、截图、许可证是正式资料；不能按“大目录/文件数量”删除。
- 工具链、下载、临时实验、附件和已交付 artifacts 在本机可位于仓库外；不提交机器绝对路径、密钥、用户消息或环境变量转储。
- 开发分支保留所属功能，整理分支基于 main；不把其他 Draft 的代码顺手并入整理 PR。
