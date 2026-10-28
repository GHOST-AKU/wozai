# 我在 · NearbyIM · Linux

Linux 客户端与 Android、Windows 共用 NIM4 Noise 端到端加密协议、设备身份、应用内信任、文字／文件／照片和保存回执。包含局域网发现／地址连接、经典蓝牙 RFCOMM、历史重连、会话草稿、浅深主题、大字体与五种语言。软件包自带 Java 17 和 Noto CJK 字体。最新稳定版为 0.3.2；开发源码为 0.3.3-preview.2，新版 Linux 包尚未发布。

## 0.3.3 Linux 兼容基线 / Compatibility baseline

0.3.3 候选 x64 软件包以 **Ubuntu 22.04** 的发行版工具链（默认 GCC 11）构建，目标为 Ubuntu 22.04 与较新受测系统；不承诺任意 Linux 发行版兼容。仍需要 X11/XWayland、系统图形/字体/音频库及 GIO，使用蓝牙时需要 BlueZ。不要为运行软件安装编译器或额外的 Java。

打包前检查启动器、BlueZ JNI 和整个内置 JVM 的 ELF **所需符号**：GLIBC 不高于 2.35、GLIBCXX 不高于 3.4.30、CXXABI 不高于 1.3.13；超出或出现未知版本标签时直接失败。`dpkg-shlibdeps` 继续校验实际系统库并生成 deb 依赖，`runtime-requirements.txt` 记录真实要求；符号门槛不能代替实际启动测试。不随包覆盖系统 `libstdc++` 或 GLib，也不通过全局修改 `LD_LIBRARY_PATH` 绕过依赖。

CI 只构建一次，在没有编译器/宿主 JDK 的干净 Ubuntu 22.04、24.04 用户空间中验证**同一份 AppImage**：真实窗口、包内 JVM/JAR 的 NIM4 发现/认可/双向消息/保存回执、无 FUSE 启动、只读特殊路径、用户身份及菜单重启。结果和精确来源见 [#42](https://github.com/GHOST-AKU/wozai/issues/42)；容器/Xvfb 不能代表实体蓝牙、Wayland 桌面或所有发行版验收。

构建侧的包验证使用 Python 3.12，保留 tarfile 的 `data` 安全过滤：Ubuntu 22.04 系统 Python 3.10 的过滤器回移版本会误判 JVM 许可证的合法相对符号链接。应用本身不依赖 Python。手动工作流可选择 `packaging-only`，仅在核心源码不变、已另行记录通过提交与源码比较时复用核心检查；默认及发布复用调用仍执行完整检查。构建成功后立即留存候选，再做 GUI 检查，避免后续验证失败丢失已生成的包。

**已发布 0.3.2 的 AppImage 在 Ubuntu 22.04 有 `GLIBCXX_3.4.32 not found` 启动缺陷。** 以下稳定版下载不包含本候选修复；候选构建通过不等于已发布，也不等于上游 AppImage 目录已重新验收。

English: candidate 0.3.3 x64 packages use the Ubuntu 22.04 distribution toolchain (default GCC 11). Packaging rejects required ELF versions above GLIBC 2.35, GLIBCXX 3.4.30 or CXXABI 1.3.13, including requirements inside the bundled JVM; exported definitions are not mistaken for system requirements. Actual dependencies remain derived by `dpkg-shlibdeps`. Do not install a compiler/JDK to run the package, replace system C++/GLib libraries, or change global library paths. X11/XWayland and the documented system GUI/GIO/audio libraries remain required; Bluetooth needs BlueZ.

CI builds once, then checks the identical AppImage in fresh Ubuntu 22.04/24.04 runtime-only userspaces: native window, bundled-JVM NIM4 discovery/consent/messages/saved receipts, no-FUSE execution, read-only special paths, private identity and menu restart. Exact results stay in #42. Published v0.3.2 still has the Ubuntu 22.04 `GLIBCXX_3.4.32` failure; these development changes are not a new release or upstream catalog acceptance. Container/Xvfb checks do not establish physical Bluetooth, Wayland or arbitrary-distribution support.

Build-side package verification uses Python 3.12 and retains the tarfile `data` safety filter; Ubuntu 22.04's Python 3.10 backport misinterprets valid relative JVM license symlinks. The application has no Python dependency. Manual `packaging-only` runs require separately recorded passing core evidence and an unchanged-source comparison; default and reusable release calls keep the full checks. Upload the candidate immediately after building so a later GUI-test failure does not discard it.

## 运行与安装

从 [稳定版 0.3.2](https://github.com/GHOST-AKU/wozai/releases/tag/v0.3.2) 下载 AppImage、tar.gz 或 deb，校验 `SHA256SUMS.txt`，并留意上面的 Ubuntu 22.04 限制。应用内部版本为0.3.2，通信双方均须升级至NIM4，不能与0.3.1的NIM3互通。支持多选任务、暂停／继续、失败重发及打开保存位置，单文件上限10 GiB，照片可在应用内查看，完整保存后确认送达。图标来源与多尺寸接入见[图标说明](app-icon.md)。

首发构建为 Linux x64。需要满足包内 `runtime-requirements.txt` 所列的系统运行库版本、图形桌面（X11，或启用 XWayland 的 Wayland 桌面）、系统 X11／字体／音频库和 BlueZ 蓝牙服务。提供 AppImage、tar.gz 应用目录及 Debian／Ubuntu 的 deb 包；蓝牙需要支持经典蓝牙的适配器。ARM64 可在对应架构的 Linux 上从源码构建，尚未发布或验证其软件包。

AppImage 无需安装：

```sh
chmod +x NearbyIM-0.3.2-linux-x64.AppImage
./NearbyIM-0.3.2-linux-x64.AppImage
```

使用固定版本的官方静态type-2 runtime，无需额外安装Java或libfuse2；仍需要主机图形/GIO等系统库，并非任意Linux发行版都兼容。系统不允许FUSE挂载时，可执行 `./NearbyIM-0.3.2-linux-x64.AppImage --appimage-extract-and-run`。注册当前用户应用菜单可使用 `./NearbyIM-0.3.2-linux-x64.AppImage --install-desktop`；入口指向原AppImage文件，图标保存在用户目录，退出卸载临时挂载不影响入口。移动AppImage后应重新注册。

解压 `NearbyIM-0.3.2-linux-x64.tar.gz` 后，运行 `NearbyIM/bin/NearbyIM`。必须保留完整目录，无需额外安装 Java。软件可放在只读的程序目录，数据存入用户目录。

Debian／Ubuntu 安装方式：

```sh
sudo apt install ./NearbyIM-0.3.2-linux-x64.deb
```

deb 包安装到 `/opt/nearbyim` 并提供应用菜单图标。tar.gz 可手动注册当前用户的应用菜单入口：

```sh
./NearbyIM/bin/NearbyIM --install-desktop
```

入口写入 `$XDG_DATA_HOME/applications/nearbyim.desktop`，未设置时为 `~/.local/share/applications/nearbyim.desktop`。移动程序目录后重新注册即可。卸载 tar.gz 时删除程序目录与这个入口；`apt remove nearbyim` 移除 deb 程序，均保留聊天数据。

## 局域网

1. 电脑和另一台设备加入同一局域网；电脑可以接网线，另一台设备连接路由器的 Wi-Fi。双方开启「附近 → 局域网 → 开启接收」。
2. 选择自动发现的设备，或输入对方显示的完整 IP 与端口。
3. 接收方首次选择「同意并记住」或「仅本次」。之后可以从历史直接重连，并校验记住的公钥。

防火墙需允许应用的 TCP 接收端口和 mDNS UDP 5353。访客网络／热点的客户端隔离可能阻断通信。换网后重新开启接收以更新地址；发现受阻时可用地址直连。

后续开发中的自动网卡更新与「复制局域网诊断」见[局域网互通排查](lan-discovery.md)，这些改进尚未包含在本文的旧版下载包中。

## 蓝牙

1. 通过桌面蓝牙设置开启适配器。确认 BlueZ 服务运行；GNOME、KDE 或 Blueman 提供系统配对代理与配对提示。
2. 双方在「附近 → 蓝牙」开启接收；被搜索的一方在系统设置中允许被发现，Android 使用应用内「允许被发现」。
3. 搜索并选择设备后发起聊天。首次连接触发 BlueZ 配对；若桌面没有配对代理，先在系统设置中完成配对。
4. 接收方在应用内同意。系统配对记录不会直接获得应用内信任；记住身份后可以从历史重连。

Linux 使用 BlueZ 的 `Adapter1`、`Device1` 与 `ProfileManager1`，由系统分配 RFCOMM 通道与发布 SDP 服务。整个进程共享一个双向 Profile，允许接收期间发起连接，并在停止接收时保留已有聊天。服务 UUID 与 Android／Windows 相同：`90c649e1-c095-4b22-8bc3-35e4c9c7b372`。应用不需要 root 或 `bluetoothd --compat`，不修改系统可发现性或自动开启适配器。系统策略必须允许当前用户访问蓝牙服务。

搜索最长 10 秒，可取消并立即重新搜索；取消连接会停止待处理的系统配对／连接。停止接收中断等待中的接入，保留已经建立的聊天；退出或「停止所有接收与连接」释放会话与原生资源。BlueZ 服务停止会让正在等待的操作退出；服务恢复后可重新开启接收。

## 本地数据与消息状态

数据默认位于 `~/.local/share/wozai`，设置了绝对路径的 `XDG_DATA_HOME` 时使用 `$XDG_DATA_HOME/wozai`；相对的 XDG 路径按标准忽略。保留旧 Linux 开发默认路径，不因安装或移动程序生成新身份。`-Dwozai.dataDir=<路径>` 仍可明确指定数据位置。改变 XDG 配置前应退出应用并将完整旧资料移到目标路径，避免把不同目录当作同一个身份。

数据目录权限为 0700，私钥与保存文件为 0600。Linux 私钥没有额外加密，依赖当前用户的文件访问权限；Windows DPAPI 身份不能直接复制到 Linux。身份损坏、目录不可写或重复运行时，应用保留原资料并显示错误，不重新生成身份。

收到消息并原子保存后才发送回执。「已送达」表示对方保存，不代表已读。断线后没有回执的发送消息为「未确认」，不会自动重发。清空记录保留设备信任，取消信任会断开该设备并保留历史。退出后需重新开启接收。

文字、文件和照片通过NIM4 Noise会话端到端加密；可在聊天详情比较安全码或扫描二维码核验对端身份。加密状态与身份已核验是不同状态，蓝牙系统配对不替代应用内认可。每台设备独立设置头像，并向已认可联系人提供头像。没有账号、云同步、遥测、群聊或互联网中继。

文件路径优先复用有可靠证据的已有端点热点，再尝试P2P、普通LAN、已有蓝牙；聊天气泡显示实际文件通道。临时P2P建网目前仍有用户真机失败反馈，桌面原生Direct和App自动临时热点/加入尚未完成，不能把LAN传输当作P2P成功。实体蓝牙、长期内存增长等验收边界保留在#7/#9/#23。

## 从源码构建

可分发 x64 包使用干净 Ubuntu 22.04 环境，需要 Linux JDK 17、Python 3、默认 C++17 编译器、pkg-config、GIO 开发包、D-Bus 工具；打包还需要 `dpkg-dev`、`binutils`、`squashfs-tools` 和 `desktop-file-utils`。开发依赖可通过 `build-essential libglib2.0-dev dbus-x11 dpkg-dev binutils squashfs-tools desktop-file-utils` 安装；图形自动验证还需要 `xvfb xauth x11-utils python3-pil libasound2`。完整构建与同包跨系统检查步骤见 `.github/workflows/linux.yml`。其他宿主系统的开发构建仍受 ABI 门槛约束，不能据宿主可运行推断符合分发基线。

```sh
export JAVA_HOME=/path/to/jdk-17
sh desktop/tools/build.sh --package
```

构建下载并验证依赖与字体 SHA-256，编译 BlueZ JNI，运行桌面测试以及独立 D-Bus／真实文件描述符的蓝牙协议测试，再生成 `desktop/build/NearbyIM-0.3.3-preview.2-linux-x64.tar.gz`、同版本 AppImage 和可用时的 deb 包。版本来自 `i18n/config.json`。`sh desktop/tools/build.sh --run` 运行开发界面。

English build instructions: use a clean Ubuntu 22.04 x64 environment, JDK 17 and its default C++17 toolchain with the build dependencies above. Run `sh desktop/tools/build.sh --package` with `JAVA_HOME` set to the JDK. The workflow lists the complete setup and cross-distribution runtime checks. Filenames use the shared preview version from `i18n/config.json`; developer builds on newer hosts must still meet the ABI gate. Building on a newer host alone is not evidence of baseline compatibility.

验证范围、实际环境与待完成的设备验收见 [Linux 验证记录](linux-verification.md)。

### Language and emoji / 语言与表情

Desktop Settings supports Follow system, 简体中文, 繁體中文, English, 日本語 and 한국어. On Linux, the first nonblank LC_ALL, LC_MESSAGES, LANG wins; malformed/unsupported values fall back to English. Explicit script wins over Chinese region. A saved manual choice overrides the environment; switching back to Follow system restores locale detection. Changes take effect after settings are successfully saved, with drafts/connections retained. Stock Swing dialogs use the same UI locale; external OS windows follow the desktop session.

The composer emoji button opens a native searchable categorized picker (Unicode 16 / CLDR 47). Select then Insert/Enter/double-click; use Variants for skin tones. Multiple insertions do not send the message. The last 30 selections stay in local profile settings. Glyphs use the system fallback, with semantic labels; no emoji font/images/network service is added. [Data and update procedure](../i18n/emoji/README.md).

桌面设置保留五语和“跟随系统”。Linux 按首个非空 LC_ALL、LC_MESSAGES、LANG 解析，未知/无效值回退英文；中文显式脚本优先于地区。手动选择优先，保存成功后才刷新，失败恢复原选择。草稿、连接和已有昵称不随语言改变；外部系统窗口跟随桌面会话。表情按钮可分类/搜索、连续插入、选择肤色变体，最近30项仅存本地；不会自动发送，也不附带表情字体或图片。实际字形与桌面会话/无障碍体验仍需各环境验收。
