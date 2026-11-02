# Android Next 0.4.0 preview 接入设计

用户要求：使用最新已合入main的安卓我在next，交付可安装Mesh preview APP；此前明确不做混合多跳。已有自主实施授权，本轮按该授权选定接口/版本，不逐项等待确认。

基线main70303ae（#83/#84），复用#85/#93核心与原生适配。版本0.4.0-preview.1/build16，i18n/config.json为权威并同步派生文件；这是用户对仓库旧0.3.3开发版本惯例的明确覆盖，不替换旧发布资产。

## 架构取舍

先用私有子进程stdin/stdout控制Rust，设备内loopback承载原生无线桥，保留已验证协议而不同时引入FFI。arm64 Android29核心以libmesh_core.so名字作为原生可执行文件打包到nativeLibraryDir，使用系统提取库目录执行，禁止下载或从可写data目录exec。包仍dev.ghost.nearbyim.next、同持久签名，可覆盖preview.4，不清数据。本轮APK仅arm64可安装；arm64 Android26–28可运行既有NIM4界面但Mesh给明确不支持信息。其他ABI没有本轮安装包，不承诺可安装后提示。

MeshService前台connectedDevice服务，显式用户启动/停止，不开机自启或自动恢复中继，停止回收radio/子进程/线程。UI只观察StateFlow，不持有无线连接。身份/seen独立files/mesh-v1，无NIM4密钥复用；公共正文仅有界内存200条，不伪装全历史同步。

原生BLE/Aware增加明确publicMesh工厂：允许发现陌生合规label，但最多4邻居与原预算保持；标签不认证身份，仍由Core签名握手认证假名。BLE与Aware互斥，选定模式运行时禁切换。Aware使用公开固定实验lobby passphrase，仅链路准入/加密，不是成员秘密或E2EE。设备feature/权限/radio不满足报错，不回退LAN装作成功。

## 状态/接口

package dev.ghost.nearbyim.mesh:
MeshMessage(id:String, body:String, origin:String, hops:Int, own:Boolean, state:String, createdMillis:Long)
MeshSnapshot(running:Boolean=false, starting:Boolean=false, mode:String="ble", relay:Boolean=false, localKey:String="", neighbors:List<String>=emptyList(), messages:List<MeshMessage>=emptyList(), error:String="", notice:String="", diagnostics:String="")
MeshClient.state: StateFlow<MeshSnapshot>; start(Context,mode:String,relay:Boolean); stop(Context); send(Context,text:String); setRelay(Context,enabled:Boolean).
MeshService是唯一连接owner；命令事件按generation隔离，旧reader/callback不能覆盖新session；输出/队列/日志有界，错误不输出私钥/正文/系统原始地址。

## UI与安全

附近默认公共timeline，私聊设备原页仍可访问，保留NIM4会话/文件/信任语义。第一次每次开启先确认公共内容可被邻居/中继阅读；中继默认off单独opt-in，权限拒绝能重试，后台由前台通知可见并可停止。状态区分启动/无邻居/直接邻居/排队/已交邻居未确认/过期，不能显示全网在线或送达保证。正文UTF8最多2048bytes、ttl600/hops3；200条历史/完整ID去重，发送成功排队后清草稿。

本轮使用现有Compose风格，不更换颜色主题/GUI框架。zh-Hans/en提供完整字符串，其他现有语言提供本地化或明确fallback，字体/IME/滚动用现有Scaffold安全区规则。

## 验证与交付

参数公共发现、事件状态、UTF8/去重/有界列表、旧generation/停止、权限/界面正负例测试；真实AndroidELF/16KiB对齐与APK包含core/许可校验。运行next全部单测/lint，一次签名release构建，既有verify-android-signature.py --next校验持久证书/版本/源码。秘密只在已有CI签名步骤，不新造临时密钥。下载交付以实际成功artifact链接为准，不合并或发正式Release。

无连接真实设备时，明确hardware validation pending，#91不关闭，不称Mesh Alpha；不能从Robolectric/子进程软件测试推导三机无线成功。BLE3s握手/低MTU、Aware配额/双邻接、厂商后台与能耗依然真机风险。
