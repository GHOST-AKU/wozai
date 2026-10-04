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

在 GitHub Actions 手动运行 Windows 构建工作流，勾选 `include-linux`、`performance-baseline`，可同时生成两个平台的 JSON 和日志 artifacts。Linux 工作流合入默认分支后也可单独勾选运行；当前开发分支先通过 Windows 工作流的 include-linux 入口调用。既有构建默认不开启性能测量；首轮仅测量，不因数值高低阻止构建。发现流程失败、消息不能互传或回执未保存仍会失败，部分结果标成 `failed`，不会输出成功的零资源值。

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

2026-10-03（北京时间），应用 0.3.1，干净构建提交 `3eb1eb40647cd8454383f9c4658524c8546d1f75`。原始数据：[Linux 虚拟环境](performance/2026-10-03-linux-virtual.json)。Debian 13 x64 容器、Xeon Platinum 8573C、3 个可见逻辑 CPU、约 9.7 GiB 可见内存；Temurin 17.0.16，自带运行时，Xvfb 2560×1800，系统／FlatLaf 缩放均为 1.0，Noto Sans CJK SC。这是单轮实验数据。

| Linux 虚拟环境状态 | RSS 中位数 / P95（MiB） | CPU 均值（单核 = 100%） |
| --- | --- | --- |
| Idle | 116.18 / 116.25 | 0.30% |
| Connected / LAN | 131.41 / 131.46 | 0.50% |
| Active / LAN | 303.29 / 339.04 | 130.03% |

新资料首次启动 1.896 s；四次后续启动分别为 1.791、1.768、1.707、1.750 s，中位数 1.759 s。mDNS 发现 2.413 s，首次认可连接 110.61 ms，历史可信重连 89.04 ms。发送 158 条均已保存回执，收到 157 条；两个方向独立调度，不要求条数相同。各资源状态测量约 30 s，每 200 ms 采样；Active 因调度延迟有 149 个点，其余 150 个。

分发 `.deb` 为 59.34 MiB、`.tar.gz` 为 68.06 MiB；展开 app-image 120.98 MiB，其中自带 runtime 76.46 MiB。SHA-256 与构建元数据见原始 JSON。

Active 的 CPU 和 RSS 明显上升；先保留这组结果作为定位与优化前的参照，单轮虚拟环境数据不能确定实机开销或归因。CI 数据仅作为相同工作流的趋势参考；真冷启动、两台实机 LAN 和实际蓝牙仍需采集。首次没有门槛，后续应在相同设备、显示设置、构建方式和负载下至少重复三轮，再讨论稳定性与阈值。


### 同一提交的 CI 结果

[Windows 与 Linux 构建及性能工作流](https://github.com/GHOST-AKU/wozai/actions/runs/37130406468)全部通过。原始报告：[Windows CI](performance/2026-10-03-windows-ci.json)、[Linux CI](performance/2026-10-03-linux-ci.json)。两端均为自带 Temurin 17.0.20.1、Noto Sans CJK SC，构建提交与上面的虚拟环境相同；CI 运行器不同，不能据此比较操作系统的快慢。

Windows 为 Server 2025 x64、EPYC 9V45、2 个可见逻辑 CPU、约 8 GiB 内存，桌面会话的系统缩放 1.0、FlatLaf 缩放 1.25。Linux 为 Ubuntu 24.04 x64、EPYC 7763、2 个可见逻辑 CPU、约 7.75 GiB 内存，Xvfb 2560×1800，系统及 FlatLaf 缩放均为 1.0。

| CI 指标 | Windows | Linux |
| --- | --- | --- |
| 新资料首次启动 / 后续启动中位数（s） | 2.125 / 1.875 | 2.003 / 1.753 |
| Idle RSS 中位数 / P95（MiB） | 118.26 / 120.15 | 120.90 / 121.00 |
| Connected / LAN RSS 中位数 / P95（MiB） | 117.73 / 118.93 | 136.57 / 136.62 |
| Active / LAN RSS 中位数 / P95（MiB） | 171.28 / 253.31 | 251.10 / 336.86 |
| Idle / Connected / Active CPU 均值（%） | 0.42 / 0.57 / 60.68 | 0.20 / 0.47 / 133.03 |
| mDNS 发现（s） | 7.159 | 2.412 |
| 首次连接 / 历史重连（ms） | 131.46 / 110.32 | 140.49 / 114.11 |
| 展开 app-image / runtime（MiB） | 110.66 / 67.50 | 122.06 / 77.53 |

Windows ZIP 65.36 MiB；Linux deb 61.47 MiB、tar.gz 68.53 MiB。Windows 发 96／收 98 条，Linux 发 157／收 157 条；所有发送均有持久化回执。

驱动每次发送完成后等待 200 ms，因此每方向 5 条/s 是上限，实际吞吐受保存与发送耗时影响，不能把这些 CPU 值解释为相同消息吞吐下的平台对比。采样段之外还有 2 s 的 Active 稳定期和命令调度／尾部收发，消息总数涵盖整段活动。原始 JSON 保留每个资源采样点及计数；实机和后续优化比较需匹配环境、实际吞吐及历史规模。

首轮报告保留测量时 `3eb1eb4` 构建的原始数值与校验和；其后追加的 Linux 蓝牙接收队列修复在 PR 中单独验证，后续性能报告应继续注明各自实际构建提交。

## 2026-10-04 优化对比

优化前为干净构建 `ef4bae6`，优化后为干净构建 `c7f88c1`；后续 `34ee105` 只增加 Android instrumentation 的 R8 保留规则，与所测桌面代码相同。测量时工作区可能继续编辑，应用版本以 `distribution.build` 中的软件包构建元数据为准，两组软件包都没有未提交改动。

使用同一 Debian 13 x64 容器、Temurin 17.0.16、自带 runtime、Xvfb 2560×1800、缩放 1.0、Noto Sans CJK SC，优化前后各重复三轮。每轮使用独立空资料；Idle 和 Connected 先稳定 **10 s**，再采样 30 s，Active 稳定 2 s、采样 30 s，负载仍为每方向最多 5 条/s、256 UTF-8 字节。这里的稳定时间与首轮基线的 30 s 不同，应比较本节两组数据。每轮结束后才开始下一轮。系统页缓存没有清空，也没有固定 CPU 频率。

原始报告：优化前 [1](performance/2026-10-04-optimization-linux-before-1.json)、[2](performance/2026-10-04-optimization-linux-before-2.json)、[3](performance/2026-10-04-optimization-linux-before-3.json)；优化后 [1](performance/2026-10-04-optimization-linux-after-1.json)、[2](performance/2026-10-04-optimization-linux-after-2.json)、[3](performance/2026-10-04-optimization-linux-after-3.json)。保留全部资源采样点、启动样本、计数和包校验和。

下表对各轮的 RSS 中位数、CPU 均值及后续启动中位数再取三轮中位数；没有把三个 P95 合并为总体 P95。CPU 仍以单核满载为 100%。

| 指标 | 优化前 | 优化后 | 变化 |
| --- | ---: | ---: | ---: |
| Idle RSS（MiB） | 119.28 | 137.79 | +15.52% |
| Connected / LAN RSS（MiB） | 137.59 | 147.66 | +7.31% |
| Active / LAN RSS（MiB） | 273.29 | 237.30 | -13.17% |
| Idle CPU（%） | 0.27 | 0.30 | +12.49% |
| Connected / LAN CPU（%） | 0.93 | 1.03 | +10.71% |
| Active / LAN CPU（%） | 118.26 | 48.80 | -58.74% |
| 后续启动中位数（s） | 1.817 | 1.837 | +1.10% |

逐轮结果用于查看波动：

| 组别 / 轮次 | Idle / Connected / Active RSS（MiB） | Active CPU（%） | 后续启动中位数（s） | 发送 / 保存回执 / 接收 |
| --- | --- | ---: | ---: | --- |
| 优化前 / 1 | 117.88 / 137.59 / 265.47 | 120.27 | 1.817 | 158 / 158 / 158 |
| 优化前 / 2 | 130.29 / 137.88 / 273.29 | 118.26 | 1.964 | 156 / 156 / 157 |
| 优化前 / 3 | 119.28 / 136.17 / 292.79 | 115.68 | 1.791 | 158 / 158 / 158 |
| 优化后 / 1 | 137.79 / 147.66 / 250.33 | 51.80 | 1.827 | 157 / 157 / 158 |
| 优化后 / 2 | 137.27 / 145.59 / 237.30 | 45.86 | 1.837 | 158 / 158 / 157 |
| 优化后 / 3 | 142.02 / 151.36 / 236.92 | 48.80 | 1.862 | 158 / 158 / 158 |

六轮吞吐接近，所有发送均得到持久化保存回执。活跃聊天的 CPU 和 RSS 降低，但 **Idle RSS 增加约 15.5%，Connected RSS 增加约 7.3%**；因此不能声称所有状态都更省内存。JVM 分配、格式缓存与 runtime 压缩同时发生变化，当前实验不能单独归因。空闲 CPU 的绝对值仍低，但也没有测得下降。后续启动基本持平，不能据此宣称启动加速。这里是三轮容器数据，尚不能推断实机效果、长期历史、尾部延迟或蓝牙性能。

### 包体与实现

桌面聊天行按消息 UUID 和方向复用，追加只创建新行，回执只更新状态，保留旧消息的选择；删除、排序和日期分隔符同步更新。气泡尺寸按宽度、字体和内容缓存，语言、时区、主题和字体大小改变会失效。窗口使用消息列表比较，避免每次将全部历史拼接成字符串。ICU formatter 按语言和模板缓存，仅日期模板检查时区变化；Android 使用最多 256 项的 LRU，复用有效语言上下文，不缓存 Activity。

桌面 runtime 使用 `jlink --compress=1` 的常量池共享；Linux tar 使用 gzip -9，deb 使用 xz -9 且单线程压缩。模块、五语资源、完整 CJK 字体和现有图标均保留。以下为同一 JDK 的本地实际构建，逻辑展开体积不等于文件系统占用块数。

| Linux 包体 | 优化前（MiB） | 优化后（MiB） | 变化 |
| --- | ---: | ---: | ---: |
| 展开 app-image | 121.00 | 107.10 | -11.48% |
| 内置 runtime | 76.46 | 62.56 | -18.18% |
| tar.gz 下载 | 68.06 | 67.25 | -1.20% |
| deb 下载 | 59.35 | 59.16 | -0.31% |

展开目录明显缩小，压缩下载文件仅小幅缩小。试验过 runtime ZIP 压缩（`--compress=2`），它使展开目录更小，但下载包反而更大，并出现启动代价，因此最终采用常量池共享；该试验数据不作为最终收益。压缩级别提高也会增加打包耗时。

Android release 开启 R8 和资源裁剪，新增同样优化、不可调试、使用调试签名的 preview 构建；debug 保留调试用途。preview 的 instrumentation 保留规则保障独立测试 APK 调用公开接口及必要的 UI 反射入口，mapping 单独上传，避免改变 APK artifact 的目录结构。五语资源保留、关闭语言 split，支持离线切换。

同一 AGP 8.13.0 / Build Tools 35.0.0 工具链：优化前 `ef4bae6` 未裁剪的本地 unsigned release 为 **1,151,284 字节**；最终 `34ee105` CI signed preview 为 **604,300 字节**，减小 **47.51%**。这比较的是 release 配置与优化后的 preview，不是已发布旧 debug 包的体积；签名和 preview 的测试保留规则也不同。[包体元数据与 SHA-256](performance/2026-10-04-optimization-android-size.json)可核对最终 artifact。正式 unsigned release 使用更少的保留规则，本轮原生验收针对 optimized preview，不能直接当作正式签名 release 的全量运行验收。

### 自动验收与 CI 观察

[Windows / Linux 完整 CI](https://github.com/GHOST-AKU/wozai/actions/runs/37140881078)来自 `4ea2ad0`，其桌面代码与本节本地优化后相同。原始数据：[Windows CI](performance/2026-10-04-optimization-windows-ci.json)、[Linux CI](performance/2026-10-04-optimization-linux-ci.json)。包含打包运行时、实际 GUI、mDNS、签名会话、持久化回执及性能测量；Windows 原生文字像素在 100% / 125% / 150% / 200% 缩放下验证，保留字体与灰度抗锯齿。CI 的资源阶段使用默认 30 s 稳定期，与本节本地三轮的 10 s 不同。

| 优化后 CI 指标 | Windows | Linux |
| --- | ---: | ---: |
| 展开 app-image（MiB） | 96.77 | 108.16 |
| runtime（MiB） | 53.60 | 63.60 |
| Idle RSS（MiB） | 129.66 | 148.37 |
| Connected RSS（MiB） | 130.50 | 145.59 |
| Active RSS（MiB） | 176.87 | 220.04 |
| Active CPU（%） | 88.62 | 51.00 |
| 后续启动中位数（s） | 3.438 | 1.719 |

Windows 本轮发 / 收 / 保存回执为 112 / 112 / 112，Linux 为 157 / 158 / 157。Windows 的 CPU 与启动时间未比首轮 CI 下降；本轮吞吐、宿主负载和运行器状态不同，单次 CI 不能归因或据此宣称 Windows 更快。Windows、Linux 之间也不能互相比性能。

新增 16 项消息行复用与缓存失效回归，先验证旧实现追加消息会重建已有行，再验证修复；五语 formatter 检查增至 3,190 项，包含时区缓存更新。[Android 最终 CI](https://github.com/GHOST-AKU/wozai/actions/runs/37141448276)来自 `34ee105`，编译、Lint、签名及对齐检查通过，API 26 / 34 对实际 optimized preview 各通过 **186 项原生检查**，覆盖并发复数格式化、时区更新、语言上下文复用和既有连接／重建流程。没有采集 Android CPU / RSS 或真机蓝牙数据。

这些优化已随 PR #4 合并到 main；已发布 `v0.3.1-preview.1` 的资产没有更新。性能采样时尚未重新取得正式图标包；随后收到原 ZIP 并核对 41 项原始导出，桌面多尺寸窗口图标接入见[图标说明](app-icon.md)。本节性能数据没有随该图标改动重新采集。长期 Android 签名及双机射频验收继续在现有 Issues 中跟踪。

采样后补充 BlueZ 断开超时保护修复，构建提交 `61789e0` 的[最终 Windows / Linux CI](https://github.com/GHOST-AKU/wozai/actions/runs/37143201285)全部通过：原生／JNI 联合 378 项、实际消息与存储链路 16 项，以及软件包、GUI、mDNS 和四种缩放。该修复改变蓝牙清理路径，没有重新采集 LAN 性能；本节优化对比继续保留原始 `c7f88c1` 软件包的数值与校验和。


<a id="pr-5linux-runtime-与空闲内存2026-10-04"></a>

## PR #10：Linux runtime 与空闲内存（2026-10-04）

[PR #10](https://github.com/GHOST-AKU/wozai/pull/10) 跟踪 [Issue #9](https://github.com/GHOST-AKU/wozai/issues/9)。本轮从 PR #4 的合并提交 `ce972fc6971e554b0453e94d1d5d9a4e31a8e7f1` 开始，包含最后提交 `64fcce8`、正式多尺寸图标和蓝牙清理修复。先完成干净构建，再复制 app-image，仅替换 runtime。两组应用 JAR 的 SHA-256 都为 `22c7f6ec4a7bf8a9004bb714ada5090075ed245092c966e13d87147366899e1d`，构建元数据均为干净 `ce972fc`。实验软件包没有重新生成压缩分发文件，JSON 的 `distribution.packages` 为空。

Debian 13 x64 容器、Xeon Platinum 8370C、3 个可见逻辑 CPU、约 9.7 GiB 可见内存，Temurin 17.0.16，Xvfb :98 2560×1800×24，系统／FlatLaf 缩放 1.0，内置 Noto Sans CJK SC。与上一节的 Xeon 8573C 不同，不能直接把本节与旧报告相减作为实机或同设备回归。正式样本无 NMT、堆限制或显式 GC 参数。Idle / Connected 先稳定 10 s、采样 30 s；Active 稳定 2 s、采样 30 s，仍为每方向最多 5 条/s、256 UTF-8 字节。每轮独立空资料、五次新 JVM 启动；不控制系统页缓存或 CPU 频率。没有并发打包或其他测量任务。

唯一变量是 `jlink --compress=1`（常量池共享）或 `--compress=0`。模块、裁剪选项与五语列表完全相同；应用的 ICU formatter 缓存、消息行复用和正式图标均保留。顺序为 **1→0、0→1、1→0**，前一轮正常退出后才启动下一轮。

原始 JSON（保留每个采样点、流量、回执、应用构建信息、runtime 模块文件 SHA-256 与 release 元数据）：常量池共享 [1](performance/2026-10-04-runtime-compress-1-1.json)、[2](performance/2026-10-04-runtime-compress-1-2.json)、[3](performance/2026-10-04-runtime-compress-1-3.json)；不共享 [1](performance/2026-10-04-runtime-compress-0-1.json)、[2](performance/2026-10-04-runtime-compress-0-2.json)、[3](performance/2026-10-04-runtime-compress-0-3.json)。

对各轮中位 RSS、均值 CPU 和后续启动中位数再取三轮中位数；没有合并各轮 P95。

| 指标 | 常量池共享 | 不共享 | 变化 |
| --- | ---: | ---: | ---: |
| Idle RSS（MiB） | 125.69 | 108.04 | -14.04% |
| Connected / LAN RSS（MiB） | 149.47 | 129.28 | -13.51% |
| Active / LAN RSS（MiB） | 232.75 | 212.75 | -8.59% |
| Idle CPU（%） | 0.33 | 0.33 | 基本持平 |
| Connected CPU（%） | 1.23 | 1.30 | +5.41% |
| Active CPU（%） | 56.17 | 58.53 | +4.21% |
| 后续启动中位数（s） | 1.795 | 1.826 | +1.68% |
| 展开 app-image（MiB） | 106.82 | 120.72 | +13.90 MiB |
| 展开 runtime（MiB） | 62.56 | 76.46 | +13.90 MiB |

| 配置 / 轮次 | Idle / Connected / Active RSS（MiB） | Active CPU（%） | 后续启动中位数（s） | 发送 / 保存回执 / 接收 |
| --- | --- | ---: | ---: | --- |
| 共享 / 1 | 133.81 / 150.07 / 221.80 | 60.43 | 1.873 | 158 / 158 / 157 |
| 共享 / 2 | 123.81 / 149.47 / 233.04 | 55.20 | 1.795 | 158 / 158 / 158 |
| 共享 / 3 | 125.69 / 148.76 / 232.75 | 56.17 | 1.713 | 158 / 158 / 157 |
| 不共享 / 1 | 108.89 / 129.28 / 213.84 | 60.57 | 1.826 | 158 / 158 / 158 |
| 不共享 / 2 | 107.39 / 130.67 / 212.75 | 58.53 | 1.843 | 158 / 158 / 158 |
| 不共享 / 3 | 108.04 / 127.17 / 200.57 | 55.23 | 1.580 | 158 / 158 / 158 |

每个配对都测到 Idle / Connected RSS 降低，三轮范围不重叠。单变量结果将当前版本的这部分常驻内存差异归因到 runtime 常量池共享，不能把旧报告的全部增长归因于它，也不能据此证明 JVM 堆泄漏。活跃状态 RSS 降低；CPU 中位数小幅上升，两组逐轮 Active CPU 范围重叠，不能声称 CPU 或启动加速，也不足以证明 CPU 完全无回归。吞吐接近、全部回执已持久化；消息复用和 formatter 缓存代码没有回退。

Linux 打包改为 `--compress=0`，保留 gzip / xz -9。取舍是展开安装体积增加约 13.90 MiB。Windows 没有同条件三轮对照，本轮保留其既有配置；Android、蓝牙真机和原签名密钥不在本节性能验收范围内。Issues #5–#8 的进展／阻碍记录仍保留在对应 Issue。

### 复现单变量实验

先固定一个干净提交并执行 `sh desktop/tools/build.sh --package`，保存原始 app-image。将它复制到临时实验目录的 `compress-1/NearbyIM` 和 `compress-0/NearbyIM`；仅移除这两个实验副本的 `lib/runtime`，保留原始软件包。使用构建该包的同一 JDK，显式生成两种配置，不依赖当前打包脚本的默认压缩级别：

```sh
for level in 1 0; do
  "$JAVA_HOME/bin/jlink" --output "/path/to/experiment/compress-$level/NearbyIM/lib/runtime" \
    --add-modules java.base,java.desktop,java.logging,jdk.crypto.ec,jdk.accessibility,jdk.localedata \
    --strip-debug --no-man-pages --no-header-files --compress="$level" \
    --include-locales=zh-Hans,en,zh-Hant,ja,ko
done
```

本节历史实验的共享组直接复制 `ce972fc` 原包，不共享组才替换 runtime。复现本节所测应用需固定 `ce972fc`、Temurin 17.0.16 与显示／测量条件；其他提交或 JDK 的实验应另存报告，不标成这批历史样本。不要更改两组应用 JAR、图标、模块、JDK、测量参数或显示环境。两组分别调用本文 `performance.py measure`，加 `--settle 10 --seconds 30`，按 1→0、0→1、1→0 顺序各三轮。核对两组 JAR 相同、runtime 模块与语言一致，并比较完整 runtime 文件路径及逐文件 SHA-256；本轮自审对保留的实验副本核对，唯一内容差异为 `lib/modules`，记录见 [runtime-comparison.json](performance/2026-10-04-runtime-memory-diagnostics/runtime-comparison.json)。压缩级别不写入 Java 应用构建元数据，必须像本节 JSON 的 `experiment` 字段一样单独注明，不能把实验的 runtime 替换描述成原始 main 打包结果。


### 内存分类与持续观测

[原始诊断记录与复现步骤](performance/2026-10-04-runtime-memory-diagnostics/README.md)使用上述相同 `ce972fc` 应用 JAR，独立新 JVM／资料，空闲和已连接无消息各观测 180 s，每种配置／状态各一次。两组均额外启用 `-XX:NativeMemoryTracking=summary`，在 30 / 90 / 180 s 用同一 JDK 的 `jcmd` 读取 NMT 与堆信息，并读取进程映射；最后才生成会触发完整 GC 的 live histogram。仅连接段另每 5 s 采 RSS。**诊断有额外开销，不混入前面的六组正式样本**；NMT committed 不等于 RSS，也不覆盖全部 runtime 文件映射。

| 诊断状态 / 配置 | RSS 30 / 90 / 180 s（MiB） | runtime `lib/modules` 映射 RSS（MiB，三点相同） | 堆已提交 30 / 180 s（MiB） | 末尾完整 GC 后存活对象字节 |
| --- | --- | ---: | --- | ---: |
| Idle / 共享 | 131.32 / 131.81 / 139.40 | 21.30 | 48 / 48 | 10,341,160 |
| Idle / 不共享 | 109.34 / 109.85 / 119.04 | 1.25 | 48 / 48 | 10,339,304 |
| Connected / 共享 | 148.45 / 150.33 / 155.95 | 20.19 | 40 / 40 | 10,794,616 |
| Connected / 不共享 | 135.49 / 158.93 / 140.70 | 1.25 | 48 / 48 | 10,794,696 |

关闭常量池共享后，Idle 的 runtime 文件驻留减少约 **20.05 MiB**，Connected 减少约 **18.94 MiB**。Idle 堆提交容量相同；两组完整 GC 后的存活堆分别相差不到 2 KiB 和 80 字节。文件映射差异与三轮 RSS 降幅的量级一致，证据支持当前常驻内存差异主要来自 runtime 文件驻留，未指向 formatter 缓存或消息行复用的存活堆增长。不依据 NMT reserved 的巨大虚拟地址空间判断 RSS；不依据一次完整 GC 证明没有泄漏。

**尚未解决：** 两组带诊断参数的进程在 180 s 内仍有匿名内存增长；完整的 Connected 每 5 s 采样中，共享配置观测峰值为 **173.36 MiB（85.01 s）**，不共享为 **162.54 MiB（90.36 s）**，两组都在峰值后回落。上表只列 30 / 90 / 180 s 快照，不能用其中最大值代表全段采样峰值；离散采样也不能保证捕捉瞬时最大值。诊断附加、JIT、GC、页面驻留及应用周期任务可能参与，当前记录不能将它们逐项归因，也不能证明无诊断参数的长期增长趋势。本轮确认并降低启动／稳定阶段的 runtime 驻留开销，**没有宣称消除所有增长或长期无泄漏**。Issue #9 保持 OPEN，后续需无诊断参数的长时对照、多次 live-heap／native 分解和实机验证；目前不以强制 GC 或武断堆上限作为修复。

### 本轮构建与 GUI 验证

修改 Linux 打包配置的提交 `ca6a6c8fb28debc091f9973510ea0bf3fd376b09` 已通过 [Linux 完整 CI](https://github.com/GHOST-AKU/wozai/actions/runs/37169441304)：协议／信任／文案与源码检查、桌面测试、41 项正式图标原始导出核对、16 项窗口图标检查、378 项 BlueZ 原生模拟检查、16 项 JNI／NIM2／保存回执检查、实际 mDNS、完整 JDK 与包内 runtime GUI、四档缩放、真实便携启动器和只读移动目录／身份重启／菜单入口。正常消息收发、保存回执、语言／主题／字号、选择与滚动、草稿和退出均通过。射频设备仍不可用，这些检查不证明真机蓝牙配对或互通。

CI 使用其独立运行器、JDK 和默认 30 s 稳定期；不能与本地 10 s 稳定期的对照混算。[性能 JSON 与日志 artifact](https://github.com/GHOST-AKU/wozai/actions/runs/37169441304/artifacts/11290738058)、[软件包](https://github.com/GHOST-AKU/wozai/actions/runs/37169441304/artifacts/11289719521)、[GUI 验证附件](https://github.com/GHOST-AKU/wozai/actions/runs/37169441304/artifacts/11289699673)均上传成功。工作流日志可读取，但当前任务拉取 artifact 时其存储下载端返回 HTTP 403，因此没有将 CI 原始 JSON 复制到仓库，也没有摘录未读取的 CI RSS／CPU 数值。本文六组本地 JSON 和四组完整诊断记录均已保存。后续补充诊断与验证说明只改变文档／证据，不改变该 CI 所测的应用代码和打包配置。
