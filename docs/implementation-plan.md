# 我在实施计划

目标：交付原生安卓源码、可复现核心测试与 APK 构建说明。
技术：Java 17、Android 平台 API、SQLite、AGP 8.13.0、Gradle 8.13、compile/target SDK 36、min SDK 26。

1. 核心协议：`core/Protocol.java`、`Frame.java`、`FramedSession.java`、`StreamConnection.java`、`LocalEndpoint.java`。先写核心行为测试，再完成协议与会话。
2. 安卓传输：`transport/LanTransport.java`、`BluetoothTransport.java`、`Peer.java`。TCP 监听、NSD 队列解析、地址直连、蓝牙扫描/配对/监听、连接超时与资源释放。
3. 数据与生命周期：`ChatStore.java`、`ChatController.java`、`ChatService.java`。异步 SQLite、稳定身份、去重、持久化后 ACK、前台通知与服务停止。
4. 界面：`MainActivity.java`。连接方式切换、昵称、设备卡片、连接许可、历史会话、文字气泡、送达状态、权限拒绝处理、深浅主题和系统边距。
5. 验证与交付：执行核心测试、独立代码审查、文档中的双机测试矩阵、GitHub Actions 构建流程、ZIP 打包。

重点审查：权限被拒绝或撤销；蓝牙开关；拒绝连接与握手超时；正在连接时停止/切换；NSD 解析失败；数据库保存失败不回执；ACK 不得标记其他会话；旋转/后台时重复绑定；网络变化后需重启监听。
