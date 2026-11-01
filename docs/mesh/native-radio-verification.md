# Native radio 首批实现与验证边界

从 #85 的 cdb028f 派生；本次只研发 BLE-only、Wi-Fi Aware-only，不做混合模式。三台手机的实际无线链路尚无测试，#91仍为 **hardware validation pending**。

## 实际执行

- 主机JVM5个程序通过：RadioConfig（互斥模式/标签/参数边界）、CoreBridge（真实loopback字节流/4096byte边界/8块队列/重复关闭）、BleChunks（5项）、CallbackBudget（4项，含32并发producer）、AwareOffer（60条断言）。计数口径不同，不将程序数、断言数与旧91项混加。
- Python控制器23项unittest通过：严格USB/参数/PSK与shell quoting、畸形JSON/重复键/极值/大小、事件不丢失/完整负例窗口、真实子进程IO/退出、模拟控制器全流程。模拟控制器fixture不当作设备成功；没有运行实际adb组网。
- BLE/Aware定向javac使用Android36 API；整模块初次Java+DEX编译成功。审查后新增cleanup barrier、终态诊断保留和callback限额，最终版本完整API/DEX编译状态以新PR专用CI记录为准；不能复用旧DEX冒充新HEAD。
- Android arm64 core真实交叉构建和ELF检查：[完整构建输入/工具链/摘要](../../mesh/evidence/native-radio-android-core.json)。Rust1.88/NDKr27c/API29，实际940,272bytes，SHA256 `cfdbe28813c0957a5726af4705b7e5838069f044ad81e6cf73113dc35d9241dc`；AArch64 PIE，interpreter `/system/bin/linker64`，libc/libdl，LOAD均16KiB对齐。最初4KiB构建因具体兼容问题修正后重建一次。没有在手机执行。原Rust源码/Cargo图未变，不重复跑旧核心全套构建/测试，不复用旧CI当新无线结果。

## 审查中修复

- BLE全局indication取消后旧ACK可能命中新link：保留旧操作tombstone至回调，缺失回调超时停adapter，不把ACK误记给新连接。
- 已完成ATT的延迟timeout仍持有payload：ACK/失败移除对应Runnable；额外callback最多128个，溢出只允许一个fail-closed shutdown；批量扫描拷贝有界。
- Aware discovery丢失后的重试复活旧PeerHandle：取消任务并检查候选对象/代际；关闭各session隔离异常。
- Runner启动失败却RESULT_OK、普通流量挤掉最终状态：失败状态在限额前更新，预留终态8条；cleanup barrier等待adapter已排队teardown并有界join线程。失败/超时不写成功。
- 增量编译可能混入已删除class：每次使用本次独立临时classes目录，jar/DEX不纳入旧目录；host测试递归包含AwareOfferTest，避免旧class掩盖缺测。
- Aware不可恢复注册/许可失败与attach重试耗尽显式terminal_failure，runner和控制器即使看到成功结束码也拒绝成功；新增实际子进程回归先失败后通过。
- 最终判定重新检查shutdown/drain后的完整历史，晚到重复展示或relay-off已过期ID晚到仍判失败；新增两场景host回归先失败后通过，不能只在停止前采样。
- 设备控制器严格核对native instrumentation结束码、reader健康和全TTL窗口；异常输出不回显PSK/正文或原始stderr；remote cleanup unconfirmed必须失败。

这些审阅不是正式第三方无线/密码学审计。Android API编译验证签名存在，不证明厂商回调时序、BLE并发双角色、Aware双NDP、radio权限恢复、长期后台/功耗正确。

## 仍未完成

真正3设备无基础设施每种radio独立验收；签名安装包交付集成；手机执行/配额兼容/真实MTU速率/吞吐/电池；20条每方向跨分钟完整报告；手工物理隔离和无AP/Internet证明；权限/radio/重启/锁屏实验。BLE保留3s核心握手期限，低MTU/拥塞可能失败，应实测后独立调整策略。Wi-Fi Direct/Classic fallback和长期前台/后台服务不在本轮已交付范围。不得称Mesh Alpha。

首轮专用CI的主机测试通过，但setup-android默认请求已从SDK仓库删除的tools包而失败（run41292271914）；已按action.yml显式选择platform-tools，保留失败历史，不将其称通过。后续最终HEAD的SDK/DEX状态以PR实际run链接为准。

修正SDK包选择后，bfe12bc的专用CI（run41292405523）实际通过完整host/API36/DEX及证据上传。其后只修正控制器最终drain判定，最终HEAD仍需新的真实CI结果；不把bfe12bc通过冒充新HEAD通过。
