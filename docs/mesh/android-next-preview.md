# 我在next 0.4.0-preview.1（Android arm64）

基于已合入main的#83（Kotlin/Compose Next）和#84后的基线70303ae，组合#85独立引擎与#93 BLE/Aware原生适配。用户明确要求0.4.0preview，因此将权威版本i18n/config.json设0.4.0-preview.1/build16，同步派生版本；没有重新发布旧版或构建其他平台发行包。

## 可操作能力

在「附近」开启公共Mesh，选择BLE蓝牙或Wi-Fi Aware，两种模式互斥。可发送最多2048 UTF8bytes公共文字，Core验证来源/期限/逻辑路径，显示直接认证邻居、排队/交邻居未确认/过期状态。中继默认关闭，需用户明确允许。附近页同时保留原「私聊设备」入口；NIM4、Noise、原设备信任/密钥、私聊和文件传输内核不改动。

Mesh有独立前台服务、可停止通知、身份/seen目录files/mesh-v1及有界200条内存时间线。广播label是独立随机实验标识，不使用蓝牙名或NIM4身份。公共发现接受陌生合规label但邻居/队列预算保持；自动连接不代表信任。Aware公共lobby口令不是成员秘密或E2EE；中继可读公共正文。

Rust以APK内lib/arm64-v8a/libmesh_core.so形式安装到系统提取的nativeLibraryDir，然后私有stdin/stdout启动；不从可写数据目录执行，不下载代码。Core只在设备内loopback监听，原生每邻接承载实际BLE或独立NDP字节；无普通LAN fallback、ADB转发或混合radio。生产APK不包含RadioLab instrumentation或host Android doubles。

## 安装与使用

这是**arm64专用APK**。Mesh需要Android10+及对应BLE/Aware硬件；Android8/9 arm64仅原有客户端能力，Mesh明确不支持。非arm64无本轮APK。沿用dev.ghost.nearbyim.next包名、仓库持久证书和增加的versionCode，可覆盖此前同证书next，不要求卸载或清数据。不要将原NearbyIM包的数据复制到next。

1. 安装PR中已通过证书/版本/源码与ELF校验的APK。
2. 在每台设备进入「附近」，选相同模式，确认公共可读告知，再授权蓝牙/附近Wi-Fi/通知。Android10/11 BLE还需打开系统位置设置。
3. 选择中间设备明确开启中继；其余设备可以保持中继关闭。收件验签不等于真实人物身份；交邻居不等于全网送达/已读。
4. 通过按钮或前台通知停止；不自动开机运行或恢复中继。Aware不支持、radio不可用、权限撤销、Core异常均给失败/停止状态，不回退其他传输。

## 软件验证与构建

全量next测试覆盖既有能力及新UI/消息确认/队列界限/异常输出/生命周期代次，原生host回归覆盖公开候选过期回收。新增可重现RED→GREEN：公共页未接入、旧相同正文不能清新草稿、queued→handed合并仍能确认、legacy位置开关、重复/拒绝包非致命、未完成握手不计邻居。

版本与原生打包校验：tools/version.py --check、tools/generate-i18n.py --check、tools/package-android-mesh.py及单元fixture。使用已验证的940,272byte、16KiB对齐Android29 ELF（来源/摘要见mesh/evidence/native-radio-android-core.json）；CI独立构建后记录实际源码/二进制hash，再验证APK所含core/许可证和extractNativeLibs。

安装构建使用既有android-next.yml signed=true，先跑next单测，再调用build-signed-next.py生成非debug release、lintRelease，并用verify-android-signature.py --next --source-commit校验持久证书/包名/0.4.0-preview.1/build16/源码/内嵌core；签名秘密只在CI步骤。实际测试数、lint结果、APK摘要/大小/下载和CI HEAD链接以PR最终证据为准，本页不预先声称成功。

## 审查修复和风险

服务退出与新进程启动有全局owner gate，代次检查拒绝旧回调；清理不阻塞UI。stdout/命令/诊断/历史均有上限；普通core输出不写日志或正文诊断，异常信息使用固定code。草稿根据提交前完整ID集合确认新own消息，兼容StateFlow合并并保存待确认关联，避免相同正文误清或旋转恢复旧草稿。仅完全就绪neighbor_up进入邻居数；duplicate/rejected是Core已处理的非致命事件。过期断连BLE候选回收、原生API29门禁及权限撤销catch保持。

**hardware validation pending。** 没有真实手机/三机无线证据，不称Mesh Alpha。Wi-Fi Direct尚未接入，Wi-Fi部分是Aware；BLE3s握手可能受低MTU/拥塞影响，B双角色/双NDP、机型兼容、权限/radio变化、锁屏/后台存活和电池都需真机实验。前台服务不保证厂商后台无限常驻；Core仍10ms轮询/3s心跳/10s checkpoint，没有手机功耗优化成绩。消息正文不持久，重启可丢失未完成转发工作；本地来源密钥非硬件keystore。#91保持open且真实无线gate不勾选。

本轮交付预览APK，不合并main、不发布正式Release、不升级NIM5，也不声称NIM4-over-Mesh私聊。

## 本地集成验证记录

在签名构建前，41项next单元/Compose/Robolectric测试通过，lintDebug为0错误/68警告（含既有内核/API弃用、布局/内部调试提示；未创建lint baseline）。原生7个host程序及3项BLE实际调度逻辑fixture通过；打包校验2项通过，版本/i18n检查通过。独立源代码审查无Critical/Important未解决问题，允许进入一次签名CI；不代表Android硬件认证。

新接口进入APK后，旧lab中的异步权限入口首次接受app lint检查：保留真实权限检查/撤销异常处理，以局部MissingPermission说明标注静态分析无法跟踪的guarded()/cleanup入口；Aware明确API29门禁。没有关闭项目级权限/API检查或放宽测试断言。Core普通duplicate/rejected事件不得触发整个Mesh关闭；新增回归已先复现后通过。
