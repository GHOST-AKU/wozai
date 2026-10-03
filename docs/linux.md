# 我在 · NearbyIM · Linux 预览版 0.3.1

Linux 客户端与 Android、Windows 共用 NIM2 签名协议、设备身份、应用内信任、文字聊天和保存回执。包含局域网发现／直连、经典蓝牙 RFCOMM、历史重连、会话草稿、浅深主题、大字体与简体中文、英语、繁体中文、日语、韩语。软件包自带 Java 17 和 Noto CJK 字体。

## 运行与安装

当前 PR #4 的[正式图标构建验收](https://github.com/GHOST-AKU/wozai/actions/runs/37162806254)已通过，可下载 [NearbyIM-0.3.1-linux-x64](https://github.com/GHOST-AKU/wozai/actions/runs/37162806254/artifacts/11287789267) artifact，包含 tar.gz 和 deb。这是 CI 软件包，已有 GitHub Release 资产尚未更新；图标来源与多尺寸接入见[图标说明](app-icon.md)。

首发构建为 Linux x64。需要满足包内 `runtime-requirements.txt` 所列的系统运行库版本、图形桌面（X11，或启用 XWayland 的 Wayland 桌面）、系统 X11／字体／音频库和 BlueZ 蓝牙服务。提供 tar.gz 应用目录及 Debian／Ubuntu 的 deb 包；蓝牙需要支持经典蓝牙的适配器。ARM64 可在对应架构的 Linux 上从源码构建，尚未发布或验证其软件包。

解压 `NearbyIM-0.3.1-linux-x64.tar.gz` 后，运行 `NearbyIM/bin/NearbyIM`。必须保留完整目录，无需额外安装 Java。软件可放在只读的程序目录，数据存入用户目录。

Debian／Ubuntu 安装方式：

```sh
sudo apt install ./NearbyIM-0.3.1-linux-x64.deb
```

deb 包安装到 `/opt/nearbyim` 并提供应用菜单图标。tar.gz 可手动注册当前用户的应用菜单入口：

```sh
./NearbyIM/bin/NearbyIM --install-desktop
```

入口写入 `$XDG_DATA_HOME/applications/nearbyim.desktop`，未设置时为 `~/.local/share/applications/nearbyim.desktop`。移动程序目录后重新注册即可。卸载 tar.gz 时删除程序目录与这个入口；`apt remove nearbyim` 移除 deb 程序，均保留聊天数据。

## 局域网

1. 电脑和另一台设备加入同一 Wi-Fi 或热点，双方开启「附近 → 局域网 → 开启接收」。
2. 选择自动发现的设备，或输入对方显示的完整 IP 与端口。
3. 接收方首次选择「同意并记住」或「仅本次」。之后可以从历史直接重连，并校验记住的公钥。

防火墙需允许应用的 TCP 接收端口和 mDNS UDP 5353。访客网络／热点的客户端隔离可能阻断通信。换网后重新开启接收以更新地址；发现受阻时可用地址直连。

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

局域网内容仍为明文；NIM2 签名提供身份连续性与完整性，不提供内容加密。蓝牙要求系统认证配对并使用 BlueZ 链路安全。没有账号、云同步、遥测、附件、群聊或互联网中继。

## 从源码构建

需要 Linux JDK 17、Python 3、C++17 编译器、pkg-config、GIO 开发包、D-Bus 工具；打包还需要 `dpkg-dev` 和 `binutils`。Debian／Ubuntu 开发依赖通常可通过 `build-essential libglib2.0-dev dbus-x11 dpkg-dev binutils` 安装；图形自动验证还需要 `xvfb xauth`。

```sh
export JAVA_HOME=/path/to/jdk-17
sh desktop/tools/build.sh --package
```

构建下载并验证依赖与字体 SHA-256，编译 BlueZ JNI，运行桌面测试以及独立 D-Bus／真实文件描述符的蓝牙协议测试，再生成 `desktop/build/NearbyIM-0.3.1-linux-x64.tar.gz` 和可用时的 deb 包。`sh desktop/tools/build.sh --run` 运行开发界面。

验证范围、实际环境与待完成的设备验收见 [Linux 验证记录](linux-verification.md)。
