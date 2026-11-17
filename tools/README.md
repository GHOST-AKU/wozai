# 共享工具导航

保留现有工具文件名和调用路径。模块专用工具在 `desktop/tools/`、`windows-next/tools/`、`mesh/tools/` 和 `terminal/`；不要为了归类移动它们导致 CI 失效。

| 用途 | 入口 / 说明 |
| --- | --- |
| 应用版本 | `version.py --check` / `--write`；权威为 `i18n/config.json` |
| 共享翻译 | `generate-i18n.py`、`check-i18n.py`；Windows Next 还有自己的 UI catalog 生成工具 |
| Android 安装交付 | `build-signed-android.py`、`build-signed-next.py`、`verify-android-signature.py`；使用持久证书规则 |
| Mesh 原生打包 | `package-android-mesh.py`；构建输入与源码记录见模块文档 |
| Apple 资源 | `validate-apple-localizations.py`；安装/构建见 Apple 文档和工作流 |
| 核心与平台验证 | `test-core.sh` / `test-core.ps1`、`test-android-*.sh`、`test-apple-*.sh`；按改动选择，不全量轮跑 |
| 图标与 Emoji | `check-app-icons.py`、`check-emoji.py` 及对应生成工具；保留来源/许可证 |
| 传输性能 | `benchmark-transfer.py`；只有实际运行的测量才可作为性能证据 |

先读工具的 `--help` 或脚本说明，确认适用平台、输入和是否会构建/签名。此导航不要求执行工具；纯文档整理只检查文档与 diff。
