# 我在 · Windows 预览版 0.2.1

与 Android 0.2.0 共用 NIM2 协议、设备身份验证、信任、文字聊天和送达回执。Windows 支持局域网及经典蓝牙 RFCOMM，界面沿用安卓的薄荷绿设计、Material 图标、会话头像、搜索、左右消息气泡，以及浅色／深色主题。安卓应用源码和协议保持兼容。

## 使用与下载

目标平台 Windows 10/11 x64。下载 GitHub Actions 的 `wozai-windows-x64` artifact，解压后运行 `WoZai/WoZai.exe`。保持整个软件目录完整；包内包含 Java 17 运行时和 Windows 蓝牙桥接库，无需另外安装 Java。原版安卓图标同时用于窗口和 EXE 启动器。

这是未签名的便携预览版，没有安装器、开机启动或托盘常驻。请将软件放在当前用户可读写的位置，例如 `D:\Apps\WoZai`，避免受保护的 `Program Files` 目录。

### 局域网

1. 电脑与安卓设备加入同一 Wi-Fi 或热点，在「附近 → 局域网」开启接收。
2. Windows 防火墙提示时，允许应用在所用的私人网络接收连接。
3. 选择发现的设备并连接，或输入对方提供的完整 IP 与端口。
4. 首次来访可「同意并记住」「仅本次」或「拒绝」；以后从历史会话直接重连，并验证同一设备公钥。

停止局域网接收会停止 TCP 监听与 mDNS，当前聊天可以继续。发现使用安卓相同的 `_nearbyim._tcp.local.` 服务与 `id` / `name` 属性。优先通过发现更新地址；失效的旧地址需重新查找或输入。

### 蓝牙

1. 两端开启系统蓝牙。电脑需要支持经典蓝牙的适配器及 Microsoft Windows 蓝牙栈。
2. 在安卓「附近 → 蓝牙」开启接收，需要搜索时允许被发现。
3. Windows「附近 → 蓝牙」搜索设备，选择手机后连接。需要系统配对时，使用页面上的「Windows 蓝牙设置」完成，并确认两端配对提示。
4. Windows 也可开启蓝牙接收，由安卓发起。若安卓未发现电脑，在 Windows 蓝牙设置的“更多蓝牙设置”允许设备发现此电脑；配对过的已知地址可直接重连。

Windows 原生库使用 AF_BTH / RFCOMM、SDP 服务 UUID `90c649e1-c095-4b22-8bc3-35e4c9c7b372`，与安卓一致。系统配对不等于应用内信任；所有蓝牙字节进入同一签名握手、许可、保存及回执流程。扫描可能包含没有安装“我在”的设备，它们不能聊天。停止搜索会丢弃扫描结果；系统查询仍可能在有界的查询周期结束后返回。不会自动无限扫描或重连。

两种接收可分别开启。「设置 → 停止所有接收与连接」同时停止监听、发现和聊天。仅支持一位活动聊天对象，新的来访不会替换当前连接。退出会保存草稿并关闭资源。

## 数据位置与旧版迁移

便携包默认将数据放在 **`WoZai.exe` 旁的 `data` 文件夹**，不依赖启动时的工作目录。因此软件放在 D 盘，数据也在 D 盘。软件根目录还有一个很小的 `.data-startup.lock` 用于启动／迁移互斥。

旧预览版使用 `%LOCALAPPDATA%\WoZai`。首次运行新版且便携目录尚无数据时，会锁定旧目录，将身份、信任、消息、草稿和设置完整复制到同盘临时目录，再原子迁移到 `data`；**旧目录原件保留**。旧版仍运行、目标不可写、文件损坏或迁移失败时会显示所选路径并停止启动，不悄悄切换目录或生成新身份。已有便携数据优先使用，不与旧目录合并。

不要同时使用两份复制后的身份目录聊天；旧目录仅作为迁移备份。升级时可将新程序文件复制进原软件目录并保留 `data`，或者移动完整的软件目录。设置页可查看、打开实际数据位置。

Windows 私钥仍使用 DPAPI CurrentUser 保护。同一用户在本机移动文件夹可以继续使用身份；把软件和 `data` 复制到另一个 Windows 用户或另一台电脑，通常无法解密身份。这与数据可以存放在哪个盘是不同的事情。损坏或解密失败的身份不会自动替换。

高级启动参数 `-Dwozai.dataDir=<路径>` 仍优先于默认选择，适用于开发和明确指定存储位置。未打包的 Windows Java 启动继续使用用户数据目录；Linux 开发启动使用 `~/.local/share/wozai`。

每条消息原子保存，保存完成才发送回执。断线或重启后未收到回执的发送消息为「未确认」，不会自动重发。回执只表明对方保存，不能表明已读。「清空聊天记录」保留信任和草稿；「取消设备信任」撤销认可并断开该设备，保留聊天记录。局域网与蓝牙共用信任记录，昵称、IP 和系统配对均不代替公钥。

无需账号、云同步或遥测。局域网消息内容仍是明文，签名提供身份连续性和完整性；蓝牙使用系统安全 RFCOMM 配对与链路加密，没有额外的端到端内容加密。附件、群聊和互联网转发尚未实现。

## 界面、语言与无障碍

安卓配色映射在 `AppTheme` 中；FlatLaf 提供现代 Swing 控件样式，自绘气泡复用安卓视觉规则。Material 图标直接来自安卓已有的 Google 路径；Windows ICO 来自安卓原始启动图，没有重画图案。

提供简体中文／英语、浅色／深色主题、三个文字大小。切换语言或主题保留连接、聊天和输入。会话按实际最后消息时间排序，搜索本机昵称；已连接状态来自真实会话，不根据 Wi-Fi 或配对记录推断。消息可选择复制，布局按窗口宽度重排。

键盘：Tab、方向键和 Space/Enter 操作控件，Ctrl+1/2/3 切换聊天／附近／设置，Enter 发送、Shift+Enter 换行，Ctrl+Q 退出。包内 Java Access Bridge 可通过 `WoZai\runtime\bin\jabswitch.exe -enable` 启用，随后重启应用。读屏、Windows 高对比度及不同 DPI 仍需人工验收。

## 从源码构建

Windows 需要 x64 JDK 17（`JAVA_HOME`）、CMake 3.20+、Visual Studio C++ Build Tools 和 Windows SDK。桌面构建不需要 Android SDK、Gradle 或 WiX：

```powershell
./desktop/tools/build.ps1 -Run
./desktop/tools/build.ps1 -Package
```

CMake 构建 x64 JNI 蓝牙 DLL，并静态链接 MSVC 运行时。脚本执行 Java 与原生生命周期检查，再用 `jpackage` 打包。Windows 启动器及 DLL 必须在 Windows 上构建。

Linux 开发需要 JDK 17、Python 3：

```sh
sh desktop/tools/build.sh
sh desktop/tools/build.sh --run
java -cp 'desktop/build/classes:desktop/build/tests:desktop/build/lib/*' dev.ghost.wozai.DiscoveryTests
# 有桌面显示或 Xvfb：
java -cp 'desktop/build/classes:desktop/build/tests:desktop/build/lib/*' dev.ghost.wozai.GuiTests desktop/build/gui.png
```

Linux 仅验证 Java、局域网与界面，蓝牙明确显示平台不可用。依赖为 FlatLaf 3.6.2、JmDNS 3.6.2、SLF4J API / NOP 2.0.17；SHA-256 固定在 `desktop/dependencies.txt`。许可与运行时 legal 文件随软件打包。重新导出图标时运行 `desktop/tools/export-icons.py`（开发工具需要 Pillow / CairoSVG，正常构建使用已保存的资源）。

## 验证记录 · 2026-10-02

用户已报告旧版 Windows 与实体安卓设备能运行并通过局域网通信。本轮新功能需以新的构建记录和真机测试为准。

自动检查覆盖旧桌面身份、信任、真实 TCP 互通与回执；便携路径、迁移、文件字节保真、旧文件保留、活跃进程锁、权限和异常目录；蓝牙地址、JNI 边界与不可用适配器状态；原生流关闭、发送、接收和句柄生命周期。蓝牙路由的签名、许可、保存后回执和撤销共用流程还通过模拟字节流验证。

界面检查通过真实窗口操作验证昵称搜索、安卓主题、语言与文字大小、草稿、双向消息和回执。Windows 工作流另行验证真正的 `WoZai.exe`、自带运行时、中文标题、DPAPI 身份、默认便携目录与正常退出。

构建机没有蓝牙无线硬件。原生编译、JNI 与字节流测试不能证明实体 Windows ↔ Android 蓝牙互通，需要在真实适配器、配对和手机接收环境中验收。
