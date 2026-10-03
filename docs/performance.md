# 桌面客户端性能基线

首轮记录数据，不设置性能阈值。CI、容器／虚拟环境和用户实机分别保存，不能用 CI 数值宣称用户实机性能。Android 的移动端基线尚未采集；本文工具针对共用 Java/Swing 的 Windows、Linux 客户端。

## 自动测量

先构建当前提交的软件包与测试类，安装测量工具依赖（应用运行不需要 Python）：

```sh
python3 -m pip install psutil==7.2.2
sh desktop/tools/build.sh --package
python3 desktop/tools/performance.py measure \
  --image desktop/build/package/NearbyIM --test-classes desktop/build/tests \
  --environment-kind physical --device '填写机器型号、系统和显示会话' \
  --output desktop/build/performance-linux.json
```

Linux 无图形桌面的自动化环境用 `xvfb-run -a -s '-screen 0 2560x1800x24'` 包裹最后一条命令，并把 `--environment-kind` 改成 `ci` 或 `virtual`。Windows 先运行 `./desktop/tools/build.ps1 -Package`，将上述 `python3` 换成 `python` 即可；命令行不需要换行。不要把 Xvfb 标成实机屏幕。

在 GitHub Actions 手动运行 Windows 构建工作流，勾选 `include-linux`、`performance-baseline`，可同时生成两个平台的 JSON 和日志 artifacts。Linux 工作流也可单独勾选运行。既有构建默认不开启性能测量；首轮仅测量，不因数值高低阻止构建。发现流程失败、消息不能互传或回执未保存仍会失败，部分结果标成 `failed`，不会输出成功的零资源值。

| 指标 | 口径 |
| --- | --- |
| 首次启动 | 全新临时资料，启动实际软件包原生入口，到主窗口可见、完成绘制、Toolkit 同步和下一次 EDT 调度；20 ms 轮询含少量观测误差 |
| 后续启动 | 同一资料重启四次，每次为新 JVM；记录各次与中位数。系统缓存可能已热 |
| 真正冷启动 | **待实机采集**。首次启动没有清空操作系统缓存，不能称为冷启动；实机需重启系统后测量并记录方法 |
| Idle | 无通信、主窗口开启；稳定 30 s 后采样 30 s |
| Connected / LAN | 已认可并连接，主窗口显示会话，没有消息流；稳定 30 s 后采样 30 s |
| Active / LAN | 同机独立对端，每方向最多 5 条/s，每条 256 UTF-8 字节（64 个“中”及 64 个 ASCII 字符）；稳定 2 s 后采样 30 s。空历史开始，包含签名会话、原子落盘、回执、实际 GUI 渲染 |
| 内存 | 每 200 ms 获取目标进程及其子进程 RSS 总和，报告中位数和 P95；不是 Java 堆容量。对端及 Python 采样进程不计入 |
| CPU | 进程树 user+system CPU 时间差 / 墙钟时间，单核满载 = 100%，可超过 100%；报告整个测量段均值 |
| 发现／首次连接／重连 | 真实 mDNS 发现指定临时对端；发现失败填 null，仍测直接 TCP。连接计时包含 NIM2 握手、认可、信任落盘及 UI 就绪；重连沿用历史与信任。断开后等待对端 EOF 的 500 ms 不计入重连 |
| 包体 | 压缩分发文件字节数与 SHA-256、展开 app-image 逻辑文件字节数、内置 runtime 逻辑字节数；不是磁盘占用块数 |

自动 LAN 测量在同一机器的私有 IPv4 地址上运行，包含测试驱动线程的开销，不能替代两台实机网络延迟、无线／有线发现或蓝牙性能验收。连接和发现只有单次值，不能当成 P95；启动样本也不足以推断分布尾部。Active 期间先从空历史增长，磁盘上的消息都计数，GUI 保留最近 200 条；因此后续比较应使用同样时长与负载。

JSON 记录 OS、架构、CPU、内存、设备／显示备注、JRE、窗口缩放、字体、构建提交及是否有未提交改动、软件包校验和和原始样本。构建信息来自软件包 JAR 中的 `build-info.json`，不依赖测量时工作区恰好处于哪个提交。测量程序生成独立资料与身份，完成后删除；不读取用户聊天记录。正常启动未指定测量参数时不会安装探针或增加测量线程。

## 实机蓝牙与 LAN 采样

使用真实客户端完成连接后，运行只读附加采样。该模式不关闭用户客户端，不生成自动消息：

```sh
python3 desktop/tools/performance.py sample --pid 12345 \
  --phase connected --transport bluetooth --environment-kind physical \
  --device '填写两端设备、蓝牙适配器与系统' --dpi '屏幕分辨率、系统缩放和应用字体大小' \
  --notes '填写实际安装版本/提交、JRE、对端版本及连接方法' \
  --output desktop/build/performance-bluetooth-connected.json
```

Active 改成 `--phase active`，在采样期间维持并记录固定收发速率、字节数和历史规模；Idle 用 `--phase idle --transport none`。附加模式中的 `environment.source_commit` 只是测量工具所在工作区提交，**应用的构建/JRE 必须填入 notes**。为每种状态单独保存一份 JSON。蓝牙还需人工记录发现、首次认可连接与历史重连的起止条件、各次耗时，不能拿 TCP 数据替代。

## 首轮结果

采集后在本节附上构建提交、环境、数值及原始报告链接。CI 数据仅作为相同工作流的趋势参考；真冷启动、两台实机 LAN 和实际蓝牙仍需采集。首次没有门槛，后续应在相同设备、显示设置、构建方式和负载下至少重复三轮，再讨论稳定性与阈值。
