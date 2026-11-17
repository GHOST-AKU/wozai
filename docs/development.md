# 开发环境与清理

## 按模块准备环境

| 模块 | 环境入口 |
| --- | --- |
| Android / Next | JDK 17、仓库 Gradle wrapper、Android SDK；原生 Mesh 还需对应 Android Rust target 与 NDK，见 [Core 构建](../mesh/android-radio/ANDROID-CORE.md) |
| Desktop Java | JDK 17 与平台打包依赖，入口 `desktop/tools/build.sh` |
| Windows Next | Windows SDK / WinUI 3、.NET 8、JDK 17、Rust、CMake；入口 `windows-next/tools/build.ps1` |
| Apple | macOS、Xcode、Swift；设备安装另需签名。以所属分支 `apple/Package.swift`、Xcode 项目和 `.github/workflows/apple.yml` 为准 |
| Mesh / Terminal | 各自 Cargo.toml / Cargo.lock；Mesh 要求 Rust 1.88+、Python 3.11+；不要把两个独立 crate 的锁文件合并 |

安装依赖前检查现有工具和缓存，避免在有限容器磁盘中重复安装 SDK、Rust 或 JDK。平台 CI 是验证入口之一；Linux 本机没有 Xcode/Windows 蓝牙设备时，如实注明能力范围。

## 本地入口

在有预置工具链的维护环境中，可以先 source **仓库外**的环境脚本（例如 `/workspace/wozai-setup/env.sh`）；该路径是某台机器的约定，不是项目要求或安装到其他机器的脚本。

脚本应设置现有 `JAVA_HOME`、Android SDK、`GRADLE_USER_HOME`，并在使用预置 Rust 时同时配置 `CARGO_HOME`、`RUSTUP_HOME` 及其 `bin` 路径。不要改写 HOME/CODEX_HOME，也不要把代理口令、签名密钥或完整环境变量列表提交到仓库。

常规核对只需查询版本、目录与正在运行的任务。需要时检查容器的 cgroup CPU/内存限制；`lscpu`、`free` 显示的宿主可见资源不等于容器可用配额。没有任务时不常驻 Gradle、编译器或测试进程。

## 安全清理顺序

1. 检查 `git status`、活跃进程、磁盘及大目录；确认当前分支，保留未提交修改。
2. 先识别仓库外的临时实验、已验证安装完成的重复安装介质，以及已无用途的生成输出；逐项列路径与理由。
3. 工具链安装包要与已安装文件核对后才删除。精简安装可能缺少压缩包中的文件，不能只凭版本号相同认为是重复。
4. 保留依赖下载缓存、Gradle/Cargo 可复用构建缓存、工具链、APK/ZIP/IPA、签名资料、用户附件及正式/唯一验收证据。输出目录也可能包含这些，不能无条件 `clean` 或删除整个 target/build。
5. 记录删除清单、校验和与删除前后 `df`。容器 OverlayFS 下层文件删除可能只产生 whiteout，逻辑删除字节不能当作实际回收空间。

本机库存与清理结果放在仓库外，避免机器信息、日志和诊断混进产品源码。后续无设备条件时继续做可验证的软件工作，不反复构建同一源码以填补硬件证据。
