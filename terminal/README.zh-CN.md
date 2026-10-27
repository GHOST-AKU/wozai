# NearbyIM Terminal · 我在

独立 Rust 终端客户端，主命令 **`nearbyim`**（Windows：`nearbyim.exe`），使用生产 **NIM4**。运行无需 Java/JRE、GUI 或 Android SDK；无账户、无遥测、无云端聊天服务器。

新源码构建统一为 **0.3.3-preview.2**；已发布 preview.1 的内部 `0.1.0-dev` 版本保持不变。参见 [0.3.3 更新规划](../docs/releases/0.3.3.md)。实现对应 [Issue #38](https://github.com/GHOST-AKU/wozai/issues/38)、[PR #41](https://github.com/GHOST-AKU/wozai/pull/41) 与用户提供的[设计手册 v0.2](docs/design-v0.2.md)。已有可运行代码和互通测试，但尚未完成真机、全部终端环境与性能验收。原有 GUI 和 v0.3.2 附件独立维护。

## 构建与启动

需要 Rust 1.88+、用于 SQLite 的 C 编译器；从仓库根目录执行：

```sh
cargo build --manifest-path terminal/Cargo.toml --locked --release
cargo install --path terminal --locked
nearbyim
```

首次 TUI 启动说明独立身份与本地数据，询问昵称、接收目录及监听策略。**默认不开启 LAN 监听，不自动接收文件。** CLI 查询可以创建身份，但不会开启监听。设置可以通过 `config list/get/set` 管理。

有线电脑和 Wi-Fi 设备可以使用同一个 LAN。多地址发现优先选择普通网卡的最长匹配网段，每次解析输出一个稳定地址，保留私有路由地址后备。参见[有线/Wi-Fi 局域网排查](../docs/lan-discovery.md)；真实路由器上的互发现与通信验收仍由 Issue #5 跟进。

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

多选文件使用独立身份绑定的持久队列：重复 `--file`（最多 256 个），CLI 等所有文件均收到保存回执才成功，并在单个 JSON 中返回全部结果。TUI 命令面板的 `files` 支持每行一个路径（`Ctrl+J` 换行），统一确认后入队。暂停当前任务会保留队列，取消当前任务后才能继续下一项；实际发送前重新检查源文件。图片 MIME 通过有界文件头读取识别，不只相信扩展名，不自动打开文件。

```sh
nearbyim --json send DEVICE_UUID --file ./first.png --file ./second.zip
```


## 头像

`nearbyim avatar set IMAGE` 选择 PNG、JPEG 或 WebP，应用方向信息并居中裁成方形静态 PNG（最大 256×256、128 KiB）。输入上限为 32 MiB、3200 万像素，解码分配预算为 128 MiB。`avatar reset` 恢复默认头像；`avatar show [DEVICE_UUID]` 查看资料；`avatar export OUTPUT.png [--peer DEVICE_UUID]` 导出已校验缓存，不覆盖已有文件。这些本地命令不会启动监听。TUI 面板支持 `avatar set PATH`、`avatar reset`、`avatar show` 和 `avatar export PATH`，后两者使用当前联系人；未选中联系人时使用自己。

在线修改通过现有 avatar-v1 协议同步，要求已认证的能力位及双方连接同意。头像使用独立的 4 KiB 分块、四块窗口、完整静态 PNG 校验与 SHA-256；坏图或存储失败不会终止聊天和普通文件。私有 SQLite 缓存绑定身份公钥，保留自己及最多 63 个远端身份（PNG 内容最多约 8 MiB），不会仅凭昵称或 UUID 替换头像归属。终端图形支持不同，可显式导出 PNG 查看；不宣称所有终端均支持内嵌图片。


## 离线身份二维码

`nearbyim identity qr DEVICE_UUID --output verification.png` 导出与 Android 相同的完整 `WOZAI-ID1:` 安全码 PNG，不覆盖文件；省略 `--output` 时输出单色终端图案（请使用浅色背景），`--json` 返回精确载荷。`identity check-qr DEVICE_UUID IMAGE` 比较有界 PNG/JPEG/WebP 与双方已知身份，不更改信任。`identity verify DEVICE_UUID --qr IMAGE` 仍要求交互终端、完整码匹配及明确确认，才记录人工核验，不会自动记住连接认可。

TUI 使用 `qr` 显示黑白二维码、`qr OUTPUT.png` 导出、`verify-qr IMAGE` 为选中身份明确执行比对核验。面板太窄时提示导出，不将二维码折行或裁掉。图片先有界解码/缩小，再识别；载荷不会被作为连接地址、URL 或新身份打开。Terminal 不宣称已实现相机扫描或完成跨设备扫码验收。


## 用户文件位置

`nearbyim files export TRANSFER_UUID OUTPUT` 将已完成文件流式导出到指定路径，核对已记录的整文件 SHA-256、强制写入并发布，不覆盖已有文件。TUI 对应 `export TRANSFER_UUID PATH`。文件被修改或截断会校验失败，不留下半成品导出。`files reveal TRANSFER_UUID` / TUI `reveal TRANSFER_UUID` 请求系统文件管理器显示保存位置；无图形环境可使用记录中的路径或导出。显示位置不会把文件内容传给 shell 或直接执行。

接收发布和导出在 Windows/Linux/macOS 使用原生排他重命名，只有系统缺少该操作时才使用硬链接后备。发布前保存检查点，以便中断后核验最终文件。自动化覆盖不覆盖、哈希变化、队列重启及真实 CLI 导出；移动存储和真机断电行为仍待实证。
