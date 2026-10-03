# 我在 · NearbyIM · Windows 预览版 0.3.1

与 Android 0.2.0 及以后版本共用 NIM2 协议、设备身份验证、信任、文字聊天和送达回执。Windows 支持局域网及经典蓝牙 RFCOMM，界面沿用安卓的薄荷绿设计、Material 图标、会话头像、搜索、左右消息气泡，以及浅色／深色主题。0.3.0 将两端文案、语言注册表和格式契约统一，协议保持兼容。

## 使用与下载

目标平台 Windows 10/11 x64。从[最新构建页面](https://github.com/GHOST-AKU/wozai/actions/runs/37014761654)底部 Artifacts 下载 [NearbyIM-0.3.1-windows-x64](https://github.com/GHOST-AKU/wozai/actions/runs/37014761654/artifacts/11229477243)，解压后运行 `NearbyIM/NearbyIM.exe`。保持整个软件目录完整；包内包含 Java 17 运行时、思源黑体和 Windows 蓝牙桥接库，无需另外安装 Java 或字体。Windows 窗口与 EXE 使用同一纸杯电话主图；平台资源目录见[图标说明](app-icon.md)。

这是未签名的便携预览版，没有安装器、开机启动或托盘常驻。请将软件放在当前用户可读写的位置，例如 `D:\Apps\NearbyIM`，避免受保护的 `Program Files` 目录。

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

便携包默认将数据放在 **`NearbyIM.exe` 旁的 `data` 文件夹**，不依赖启动时的工作目录。因此软件放在 D 盘，数据也在 D 盘。软件根目录还有一个很小的 `.data-startup.lock` 用于启动／迁移互斥。

首次运行新版且便携目录尚无数据时，优先检查同级旧版 `WoZai/data`；没有旧便携资料时，再检查 `%LOCALAPPDATA%\WoZai`。程序锁定旧目录，将身份、信任、消息、草稿和设置完整复制到同盘临时目录，再原子迁移到 `data`；**旧目录原件保留**。旧版仍运行、目标不可写、文件损坏或迁移失败时会显示所选路径并停止启动，不悄悄切换目录或生成新身份。已有便携数据优先使用，不与旧目录合并。

不要同时使用两份复制后的身份目录聊天；旧目录仅作为迁移备份。升级时可将新程序文件复制进原软件目录并保留 `data`，或者将新版 `NearbyIM` 与旧版 `WoZai` 放在同一个父目录。若旧软件在其他位置，首次启动前先将其完整 `data` 复制到新版目录内。设置页可查看、打开实际数据位置。

Windows 私钥仍使用 DPAPI CurrentUser 保护。同一用户在本机移动文件夹可以继续使用身份；把软件和 `data` 复制到另一个 Windows 用户或另一台电脑，通常无法解密身份。这与数据可以存放在哪个盘是不同的事情。损坏或解密失败的身份不会自动替换。

高级启动参数 `-Dwozai.dataDir=<路径>` 仍优先于默认选择，适用于开发和明确指定存储位置。未打包的 Windows Java 启动继续使用用户数据目录；Linux 开发启动使用 `~/.local/share/wozai`。

每条消息原子保存，保存完成才发送回执。断线或重启后未收到回执的发送消息为「未确认」，不会自动重发。回执只表明对方保存，不能表明已读。「清空聊天记录」保留信任和草稿；「取消设备信任」撤销认可并断开该设备，保留聊天记录。局域网与蓝牙共用信任记录，昵称、IP 和系统配对均不代替公钥。

无需账号、云同步或遥测。局域网消息内容仍是明文，签名提供身份连续性和完整性；蓝牙使用系统安全 RFCOMM 配对与链路加密，没有额外的端到端内容加密。附件、群聊和互联网转发尚未实现。

## 界面、语言与无障碍

安卓配色映射在 `AppTheme` 中；FlatLaf 提供现代 Swing 控件样式，自绘气泡复用安卓视觉规则。Material 图标直接来自安卓已有的 Google 路径；Windows 启动器使用 `desktop/assets/icons/windows/nearbyim.ico`，基于共享主图导出，没有重画图案。安卓、Windows 与 Linux 图标文件的来源和目录约定见[多平台图标说明](app-icon.md)。

提供跟随系统／简体中文／英语／繁体中文／日语／韩语、浅色／深色主题、三个文字大小。两端共用 `i18n/messages` 文案目录和 ICU 格式，错误、状态、日期、权限说明、帮助与关于均通过显示层翻译，新增语言流程见 [多语言架构](i18n.md)。切换语言或主题保留连接、聊天和输入。会话按实际最后消息时间排序，搜索本机昵称；已连接状态来自真实会话，不根据 Wi-Fi 或配对记录推断。消息可选择复制，布局按窗口宽度重排。

0.2.2 统一使用 NearbyIM 英文品牌及启动器名称。软件内置 Noto Sans CJK SC（思源黑体），在进程内加载，不依赖系统中文字体或安装语言包。移除重复品牌页头，聊天只保留对方昵称、连接状态与必要操作；选中会话使用圆角高亮，输入框与发送键等高。设置采用宽度自适应分组，长路径和说明自动换行，不出现横向滚动条；滚轮步长至少 24 像素，启用平滑滚动。设置内提供与安卓一致的「使用说明」「关于我在」，补充 Windows 配对与便携数据说明。

键盘：Tab、方向键和 Space/Enter 操作控件，Ctrl+1/2/3 切换聊天／附近／设置，Enter 发送、Shift+Enter 换行，Ctrl+Q 退出。包内 Java Access Bridge 可通过 `NearbyIM\runtime\bin\jabswitch.exe -enable` 启用，随后重启应用。读屏、Windows 高对比度及不同 DPI 仍需人工验收。

PR #3 评审修复的[最新 Windows 包](https://github.com/GHOST-AKU/wozai/actions/runs/37089391450/artifacts/11262585401)已通过[平台验证](https://github.com/GHOST-AKU/wozai/actions/runs/37089391450)。打包并提前注册 Noto Sans CJK SC **Regular 与 Bold**，标题和头像使用真实粗体字形；头像文字沿用 FlatLaf 的系统抗锯齿与 HiDPI 绘制。应用「文字大小」仅缩放逻辑字号，显示器 DPI 由 Java2D 处理；未强制覆盖系统 LCD 或 fractional metrics。新增 `--text-diagnostics` 可导出实际字体、显示变换及控件文字渲染配置；100% / 125% / 150% / 200% 的实机验收与命令见 [device-test.md](device-test.md#windows-文字清晰度与-dpipr-3)。新增 Bold 资源会增加便携包体积，之前 0.3.1 下载的体积与校验值不代表评审修复后的包。

## 从源码构建

Windows 需要 Python 3、x64 JDK 17（`JAVA_HOME`）、CMake 3.20+、Visual Studio C++ Build Tools 和 Windows SDK。桌面构建不需要 Android SDK、Gradle 或 WiX：

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
java -cp 'desktop/build/classes:desktop/build/tests:desktop/build/lib/*' dev.ghost.wozai.LayoutTests desktop/build/gui-settings.png
```

Linux 仅验证 Java、局域网与界面，蓝牙明确显示平台不可用。依赖为 FlatLaf 3.6.2、JmDNS 3.6.2、SLF4J API / NOP 2.0.17、ICU4J 77.1；SHA-256 固定在 `desktop/dependencies.txt`。中文字体来自官方 Noto CJK 仓库，SHA-256 固定在 `desktop/font-dependencies.txt`。字体、许可与运行时 legal 文件随软件打包。重新导出图标时运行 `desktop/tools/export-icons.py`（开发工具需要 Pillow / CairoSVG，正常构建使用已保存的资源）。

## 验证记录 · 2026-10-02

0.3.1 新增完整繁体中文、日语、韩语文案，五种语言各 299 键。Windows 构建 [37014761654](https://github.com/GHOST-AKU/wozai/actions/runs/37014761654) 通过 61 项桌面、104 项目录/迁移、54 项蓝牙接口/JNI、3038 项文案格式与逐条字形检查，及真实 EXE、自带运行时/完整 JDK 的五语实时切换、打开对话框刷新、真实 mDNS 与布局检查。没有新增字体或依赖。便携 ZIP 为 54,223,619 字节（约 51.71 MiB），比 0.3.0 增加 384,087 字节（约 0.37 MiB）；上传 SHA-256 为 `7d05884598abd48e205a83cfa2149e785593c3bbf9b1ba59dc81280f4715ed9f`。

0.3.0 最终构建 [37003827341](https://github.com/GHOST-AKU/wozai/actions/runs/37003827341) 的四项任务全部通过，应用与测试源码为 `afaf682`，构建提交为 `00ebdbd`；后续仅更新交付文档和恢复手动工作流。Windows 通过 61 项桌面、104 项目录与迁移、54 项蓝牙接口/JNI、631 项翻译检查，及原生生命周期、真实 mDNS 和共享协议/信任检查。真正的 `NearbyIM.exe` 验证了自带运行时、DPAPI、便携目录和正常退出。

完整 JDK 和软件自带运行时都通过真实 GUI 检查：在连接及已打开对话框存在时切换语言，确认收发、回执、草稿、搜索、选择对象及滚动位置保留；布局检查继续验证字体、圆角高亮、输入栏等高、设置滚轮和大字号。原生安卓 API 26、34 各通过 120 项语言切换检查，包括真实 TCP、前台服务和已发布通知。截图见 [Windows 验证附件](https://github.com/GHOST-AKU/wozai/actions/runs/37003827341/artifacts/11224638245)，完整两端记录见 [verification.md](verification.md)。

下载 ZIP 为 53,839,532 字节，构建上传 SHA-256 为 `e4880e5864cd1af698c59538cb015c672079ea1f6c8c3e948c7b8f288fbf0524`。

用户已报告旧版 Windows 与实体安卓设备能运行并通过局域网通信。本轮新功能需以新的构建记录和真机测试为准。

自动检查覆盖旧桌面身份、信任、真实 TCP 互通与回执；便携路径、迁移、文件字节保真、旧文件保留、活跃进程锁、权限和异常目录；蓝牙地址、JNI 边界与不可用适配器状态；原生流关闭、发送、接收和句柄生命周期。蓝牙路由的签名、许可、保存后回执和撤销共用流程还通过模拟字节流验证。

界面检查通过真实窗口操作验证昵称搜索、安卓主题、语言与文字大小、草稿、双向消息和回执。新增布局检查覆盖 760×540 窗口、最大字号、反复重排后的稳定高度、真实滚轮移动、圆角像素、输入栏等高，以及使用说明／关于对话框。Windows 工作流另行验证真正的 `NearbyIM.exe`、自带运行时、中文标题、DPAPI 身份、默认便携目录与正常退出。

构建机没有蓝牙无线硬件。原生编译、JNI 与字节流测试不能证明实体 Windows ↔ Android 蓝牙互通，需要在真实适配器、配对和手机接收环境中验收。

0.2.1 构建记录：[36987324600](https://github.com/GHOST-AKU/wozai/actions/runs/36987324600)，应用源代码为 `2e4a8f9`。功能分支保留独立 Windows 手动工作流，专用构建分支借用已有的手动入口执行 Windows 检查，不改变 Android 工作流。

0.2.1 Windows 构建通过：55 项桌面检查、81 项便携目录与迁移检查、54 项蓝牙接口/JNI 检查，以及原生生命周期、蓝牙路由模拟、原有协议/信任、真实 mDNS 和界面操作。直接启动旧版 `WoZai.exe` 验证了默认软件旁目录、DPAPI、中文标题和正常退出；包内运行时的界面收发与浅色/深色检查也通过。

0.2.2 本地检查通过：54 项桌面检查、110 项目录与迁移检查、35 项无适配器蓝牙检查、蓝牙路由模拟及真实界面操作。NearbyIM 改名后的迁移检查同时放置旧便携版与较早用户目录，验证优先恢复最新便携身份和草稿，并逐文件比较副本与两个保留的源目录。

0.2.2 Windows 最终构建 [36995050370](https://github.com/GHOST-AKU/wozai/actions/runs/36995050370) 全部通过，应用源代码为 `09e7399`，后续仅更新下载和验证文档。55 项桌面、104 项目录迁移、54 项蓝牙接口/JNI 检查，以及原生生命周期、模拟蓝牙路由和原有协议/信任检查通过。真正的 `NearbyIM.exe` 验证了中文窗口、DPAPI、默认便携目录及正常退出；自带运行时和完整 JDK 的双向收发、主题、草稿检查通过，真实 mDNS、设置滚轮与布局、帮助和关于窗口检查通过。字体检查确认界面使用随软件打包的 Noto Sans CJK SC，并验证原始字体包含中文字符。构建页面同时提供中文聊天、设置、帮助、关于及浅色／深色截图。
