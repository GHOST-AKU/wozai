# PR #93 代码质量、性能与体积审查

基线 `e98efa8274b1953ce43fb5709b090c620cca9e30`。本轮仅加固 BLE-only / Wi-Fi Aware-only 研究模块；不引入混合转发，不改Rust Core/依赖图/NIM4/正式客户端，不以编译或主机测试替代真实无线验收。

## 确认缺陷和修复

| 问题 | 影响和最小修复 | 证据 |
| --- | --- | --- |
| BLE `pumpServer` 的过期队列处理会递归进入调度 | 内层已为B提交indication后，外层可能继续为C提交并覆盖ACK归属；取队列后重新核对全局in-flight | 实际BleAdapter的host回归先观察`submissions=[B,C]`后修复；另覆盖旧link tombstone与错误地址ACK |
| CoreBridge按8个对象计满，而非32KiB字节预算 | 默认MTU23的小分片突发，约160bytes即可满而断连；改为固定32KiB BytePipe，按字节准入/保持顺序，已排队数据可合并写入 | 小分片突发回归先失败；8×4096上限、wraparound、源缓冲区复用、范围边界、关闭唤醒测试 |
| Aware原生fd与I/O并发close | 公共Os.close清除/关闭fd而未参与IoBridge异步关闭监视，已捕获整数fd的I/O可能碰上重用；操作期间保留所有权，shutdown取消，最后一个操作返回后才close | AwareSocketLifetime先复现提前release后23条host断言通过；begin/end无每次操作对象分配，关闭拒绝后续partial write |

Host Android doubles只模拟BLE调度边界，不能推导平台或厂商radio行为。Aware lease测试也不执行Android内核I/O；平台依据见下文，真机退出时序仍pending。

## 性能与资源改动

- BLE按ACK进度逐片生成，4KiB/MTU23仍需205次片段复制/确认，但不再预先保留205个片段数组和List。一次只生成并等待当前20byte片段的ACK，不再用List整批持有4096byte分片副本；局部变量/回调交接仍可能短暂持有旧对象，20bytes不是堆峰值。原Core读缓冲4096bytes仍存在，不能写成整个链路只有20bytes内存。没有测到无线吞吐提升。
- BytePipe有界复制，不为每个输入片段创建桥接clone；Aware使用counted-range入口，移除中间Arrays.copyOf。仅合并已排队字节，不添加计时等待。固定32KiB在每条桥接构造时预留，4邻居共128KiB；另外有4KiB writer、reader/egress、线程、allocator和OS缓冲。它改善容量语义和稳态分配，**不承诺RSS下降**。
- Aware已建立stream将SO_RCVTIMEO设0，取消原每秒一次空读超时；close通过shutdown唤醒阻塞read并保留fd至操作退出。listener仍1s超时，connect/write仍5s，外层尝试deadline保持。Rust原3s心跳/认证和15sidle不变，Core本身仍10ms轮询并有10s checkpoint，因此不能宣称零唤醒或手机省电百分比。转为长期Android服务前需要事件驱动和存储策略的独立设计/测量，不在此加固PR中改造整个核心。
- 不因微秒级主机helper数据发明无线速度。既有配额、签名、去重、持久化、中继授权和失败状态全部保留。

## 体积与性能测量

[完整体积/host成本报告](radio-size-audit.md)与[原始输入、源码、输出/摘要](../../mesh/evidence/radio-size-review.json)。Android arm64 core复用摘要匹配的940,272byte ELF；无Rust重建。e98efa8固定Java基线DEX为87,500bytes，同JAR用D8 release为86,600bytes（900byte/1.03%差异）。内部诊断阶段保留默认D8 source位置，不为900bytes切换编译模式；未删除运行时unwind/安全逻辑或许可证。

新源码新增BytePipe和原生fd生命周期保护，不能拿旧DEX当新HEAD的大小，也不预设新输出一定更小。最新完整Java/DEX构建的实际bytes/SHA在PR的最终HEAD CI输出与artifact记录；未构建或分发可安装APK，下载/安装占用不等于ELF+DEX原始字节和。

主机测量仅说明e98efa8的分片函数与helper成本，原始七轮分布保留；新版稳态分配减少来自代码所有权和对象数分析，没有冒充ART/手机CPU/电量实测或给出加速百分比。

## 验证方式与范围

定向RED→GREEN覆盖上述已确认缺陷。最终以专用native radio CI执行所有7个纯JVM程序、真实BLE调度host回归、23项Python控制器案例、Android36 Java/DEX编译；stdout/manifest同时记录最终HEAD与输出摘要。仅源自test-stubs的框架替身不打入DEX，CI源码快照包含它们，避免回归输入不可追溯。

Rust/正式客户端源码与Cargo输入未变化，复用既有验证，不重复构建APK或Rust release。真实BLE双角色、Aware双NDP、物理A/C隔离、权限/无线开关/重启、后台存活、厂商内核取消行为、吞吐和电池依然 **hardware validation pending**。#91不关闭，PR保持Draft，不发布/合并。

## 平台依据与残余风险

- [Android10 libcore native fd读取/close](https://android.googlesource.com/platform/libcore/+/refs/tags/android-10.0.0_r1/luni/src/main/native/libcore_io_Linux.cpp)：NET/IO_FAILURE_RETRY取整数fd，Linux_close直接清除Java fd并close；[IoBridge的特殊关闭机制](https://android.googlesource.com/platform/libcore/+/refs/heads/main/luni/src/main/java/libcore/io/IoBridge.java#285)不是本模块可依赖的公共API。
- [固定Android15内核shutdown](https://android.googlesource.com/kernel/common/+/refs/tags/android15-6.6-2024-11_r6/net/ipv4/af_inet.c#899)、[TCP读的RCV_SHUTDOWN检查](https://android.googlesource.com/kernel/common/+/refs/tags/android15-6.6-2024-11_r6/net/ipv4/tcp.c#2440)：先保存shutdown状态再唤醒等待者，覆盖shutdown先于阻塞read；listener/connect/write保留超时退路。不保证所有历史/厂商内核同一时序。
- BLE3s核心握手在低MTU/拥塞下仍可能失败；本轮不未经真机依据放宽安全期限。多邻居公平性、洪泛时的无线CPU/功耗和长时间flash成本未测，host微基准不能回答这些问题。
- 当前CLI IPC是研究模块选择，未来FFI、平台密钥库/签名安装、长期服务均需独立设计。构建输入/摘要可追溯，不称跨工具链bit-for-bit reproducible。
