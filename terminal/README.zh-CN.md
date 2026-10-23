# NearbyIM Terminal · 我在

独立 Rust 终端客户端，主命令 **`nearbyim`**（Windows：`nearbyim.exe`），使用生产 **NIM4**。运行无需 Java/JRE、GUI 或 Android SDK；无账户、无遥测、无云端聊天服务器。

当前为 **0.1.0-dev 开发版本**，对应 [Issue #38](https://github.com/GHOST-AKU/wozai/issues/38)、[PR #41](https://github.com/GHOST-AKU/wozai/pull/41) 与用户提供的[设计手册 v0.2](docs/design-v0.2.md)。已有可运行代码和互通测试，但尚未完成真机、全部终端环境与性能验收。原有 GUI 和 v0.3.2 附件独立维护。

## 构建与启动

需要 Rust 1.88+、用于 SQLite 的 C 编译器；从仓库根目录执行：

```sh
cargo build --manifest-path terminal/Cargo.toml --locked --release
cargo install --path terminal --locked
nearbyim
```

首次 TUI 启动说明独立身份与本地数据，询问昵称、接收目录及监听策略。**默认不开启 LAN 监听，不自动接收文件。** CLI 查询可以创建身份，但不会开启监听。设置可以通过 `config list/get/set` 管理。

```sh
nearbyim config set receive_dir /home/me/Downloads/NearbyIM
nearbyim peers scan --timeout 8s
nearbyim chat DEVICE_UUID
nearbyim chat --address 192.168.1.20:47832
nearbyim listen --lan --timeout 0
nearbyim send DEVICE_UUID --text '你好'
printf '你好\n第二行' | nearbyim --json send DEVICE_UUID --stdin
nearbyim send DEVICE_UUID --file ./demo.zip
nearbyim files list
nearbyim files resume TRANSFER_UUID
nearbyim files cancel TRANSFER_UUID
nearbyim chats list
nearbyim chats show DEVICE_UUID
nearbyim identity verify DEVICE_UUID
nearbyim trust revoke DEVICE_UUID
nearbyim doctor --json
```

`chat` 是 TUI；`connect` 是前台行式客户端，退出即断开，使用 `/quit`、`/file PATH`、`/pause ID` 等操作。`listen` 显式启动前台接收，默认受操作超时限制；`--timeout 0` 表示直到中止。不启动后台服务；同一 Profile 独占，冲突报 `PROFILE_BUSY`。一次只保留一个活动会话，切换前明确断开，文件检查点保留。SSH 下扫描与连接发生在运行程序的远端机器。

## 终端操作

- Tab 切换焦点；非编辑区方向键/j/k；Enter 打开或发送。
- Ctrl+K（Ctrl+P 别名）命令面板；`?` 帮助；`/` 或 Ctrl+F 搜索。
- Ctrl+J / Alt+Enter 换行；粘贴中的换行不会自动发送。
- 字素簇编辑中文/Emoji/ZWJ，文本上限为 **8192 UTF-8 字节**。
- 非编辑区 `a` 输入附件路径；发送前确认接收端和大小。
- Esc 返回/取消扫描；Ctrl+C 或非编辑区 q 退出；活动文件需确认暂停退出。
- Quiet Terminal 的 midnight、paper、sage、mono、system 主题；`--no-color`、`NO_COLOR`、`--ascii`；颜色回退可配置。
- ≥110 列三栏，72–109 双栏，45–71 单栏；不足 45 列/16 行显示增宽提示。

面板命令：`chats`、`scan`、`transfers`、`security`、`settings`、`connect HOST:PORT`、`disconnect`、`listen on|off`、`file PATH`、`pause/resume/cancel ID`、`code`、`verify FULL_CODE`、`unverify`、`search TEXT`、`set KEY VALUE`、`help`、`quit`。原生 IME 与复杂 Emoji 的表现仍取决于终端，需要实测，不能用自动化字宽测试代替全部平台验收。

## 安全、文件和脚本

首次连接必须“仅本次/记住/拒绝”。记住认可属于 TOFU，**不等于人工核验**；安全码应通过另一可信渠道比对。脚本与 JSON 不能跳过首次信任或身份变化。根密钥变化会阻断；先调查，再显式撤销或忘记旧身份。

配置目录使用独立的系统本地数据目录 `nearbyim-terminal`，可用 `--profile` 指定另一套终端身份，不能指向 GUI 数据目录。Unix 目录 0700、身份文件 0600；Windows 私钥用当前用户 DPAPI。**消息历史、收到的文件并不因此获得磁盘加密。** 身份损坏/丢失会停下，不会静默换密钥。客户端关闭后备份整个 Profile；Windows 跨账号复制 DPAPI 私钥不属于支持的迁移方式。

文件通过 NIM4 附件 v2 流式收发，32 KiB 分片、1 MiB 块哈希、整文件 SHA-256、字节流控、强制落盘检查点及保存回执，最大 **10 GiB**。默认询问接收；只有显式开启 `auto_receive_trusted` 才自动接收记住设备的文件。文件名加 UUID、安全化并禁止覆盖；不自动执行。断开后保留 `.nearbyim-*.part` 及身份绑定的检查点；恢复会核对前缀，拒绝源文件变化、损坏前缀和已取消任务。未实测的平台、文件系统、10 GiB 或断电恢复路径不宣称通过。

发送只有收到持久保存回执才成功；“已保存”不是“已读”。超时/断开记为未确认，不擅自重发文字。JSON 每次输出一个对象，含 schema/ok/status/operation/标识符/ISO 时间/错误信息；非 TTY 无全屏 UI 或 ANSI 动画。退出码：0 完成；2 输入错误；3 不存在；4 占用；5 网络不可达；6 需交互；7 身份不匹配；8 协议/握手失败；9 本地 I/O；10 超时未确认；11 用户取消。`status` 返回 `not_running`，不伪装后台在线。

## 验证与分发

```sh
cargo test --manifest-path terminal/Cargo.toml --locked
terminal/tools/test-interop.sh
python3 terminal/tools/test-pty.py terminal/target/release/nearbyim
python3 terminal/tools/package.py
```

互通测试单独需要 Java 17，调用仓库生产 Java Noise 通道、编码器和文件引擎，覆盖真实 TCP 双角色、双向文本/文件/保存回执。CI 独立构建 Windows/Linux x64 包及哈希，内含文档、补全和依赖许可。Linux 包基于 Ubuntu 22.04/glibc 2.35+，不声称全静态通用。正式真机矩阵、Windows Terminal、SSH/tmux、性能证据继续记录在 Issue #38 / PR #41；不建 TODO 文件。

经典蓝牙 RFCOMM、原生 Wi-Fi Direct、多活动会话、IPC、后台服务尚未实现。Rust 是已实现候选，不编造 Go 对照、冷启动或比 GUI 更轻的结论。[完整英文手册](README.md) · [架构与安全边界](docs/architecture.md)。

历史按本地插入顺序分页，避免对端时钟偏差隐藏消息。`chats show DEVICE_UUID --limit 100 --before ROW_CURSOR` 返回 next_before/has_more；TUI 用 `older` / `latest` 翻页，内存不无限保留历史。`language=zh-CN` 已有初步界面标签翻译，部分帮助和协议诊断仍为英文。
