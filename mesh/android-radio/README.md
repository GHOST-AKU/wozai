# Android BLE / Wi-Fi Aware Mesh radio lab

独立原生无线**数据适配源码**，与 `android-probe` 的能力观察不同。当前有 BLE LE CoC 和 Wi-Fi Aware NDP 两种互斥模式，均连接既有独立 Rust Core；不做 BLE↔Wi-Fi 混合中继，不修改 NearbyIM/NIM4/Noise，不实现 Bluetooth SIG Mesh 标准。

**hardware validation pending。** 本环境无已连接 Android 设备；Java/API/DEX 编译、主机测试和 Android ELF 构建不能证明实际收发/双邻接。没有提供安装 APK、真机连接、无线吞吐或电量成绩。

## 构建

JDK17、Android platform36/build-tools35.0.0（minAPI29）：

```sh
JAVA_HOME=/path/to/jdk17 bash mesh/android-radio/test.sh
JAVA_HOME=/path/to/jdk17 ANDROID_SDK_ROOT=/path/to/sdk bash mesh/android-radio/test-ble-host.sh
JAVA_HOME=/path/to/jdk17 ANDROID_SDK_ROOT=/path/to/sdk bash mesh/android-radio/build.sh
python3 -m unittest discover -s mesh/android-radio/tests -p 'test_*.py' -v
ANDROID_NDK_HOME=/path/to/android-ndk-r27c mesh/tools/build-android-core.sh
```

[Android core 构建/ELF检查](ANDROID-CORE.md)。不要把 Linux x86_64 binary 复制到手机冒充 Android 程序。core 必须省略 `--discover`，只监听 `127.0.0.1:39770`；无线发现由 native 处理。

默认生成 Java classes JAR 和 `classes.dex`。`build.sh --unsigned-internal-apk` 仅组装**内部未签名**包，不是安装交付，不创建签名密钥。给 owner 安装前必须接入仓库持久证书验证；现有 `tools/verify-android-signature.py` 尚不支持此新包，签名交付集成是明确 pending，不能使用临时 debug 签名或让用户卸载正式 App。具备授权签名/测试部署环境的开发者可基于此源码准备独立包 `org.nearbymesh.radio`；本轮无安装/权限授予副作用。

## 数据路径与限额

每台设备一个 `mesh-lab` + 一个 native instrumentation。设备之间每条邻接走 BLE 或独立 Aware NDP；设备内 localhost TCP 仅传递原始 Mesh wire 流，native 不自行签名/路由，不授予 NIM4 信任。B Core 的 `--relay` 决定转发；普通公共消息可被中继阅读。

- BLE：API29+公开 LE CoC socket；广播新 UUID、动态 PSM 与有界实验 label，按 label 确定拨号角色，接收无需先扫描到对端。两端必须同时升级，不兼容旧 GATT preview，不回退 Classic/LAN。最多4个待建或活动邻接，共享4个IO worker；建连/前言15s、单次写5s超时，关闭socket解除阻塞。Core身份验证仍独立执行，实验label和未配对无线链路不提供身份信任或保密性。旧GATT保存在research/gatt，仅供回归研究。硬件多邻接和冷启动验收仍pending。
- Aware：API29+、FEATURE_WIFI_AWARE与可用资源；publish/subscribe协商，带实验PSK的每邻居NDP，使用系统 Network 和 scopedIPv6/port，socket显式Network绑定，接收端核验NDP本地/对端地址。最多4邻居，NDP20s/尝试40s/启动15sdeadline，有界worker/重试。B双NDP由系统/机型配额决定；unsupported/unavailable必须保留，**不能回退普通Wi-Fi**。
- CoreBridge：每邻居预留固定32KiB ingress字节环+最多一个4096byte在写块，egress单4096byte块背压；另有OS socket/GATT缓冲、native对象和Core资源，不能把32KiB当进程RSS。按字节准入，已排队小分片可合并写入；队列溢出关闭连接。取消/退出回收socket、GATT、NDP、发现session；runner最长3600s，不是长期后台服务。
- native诊断限2048普通事件+8生命周期事件，报告dropped_events；bytes是观察到的计数，截断日志不能用来推算全量吞吐。JSON仅label/阶段/状态/bytes，不记录MAC、IP、PSK或正文；Rust普通stdout仍含公共正文/公钥，控制器导出移除body，公开身份/路径仍可关联。

## 三设备实验：两种模式分开运行

前提：三台**真实**Android、已合法部署的授权签名runner、真实Android core；用户已在系统授权对应蓝牙/附近Wi-Fi/旧版位置权限并启用必要radio。工具不会安装、授予权限、更改radio、设置adb forward/reverse或USB网络。Aware PSK只用实验随机值，CLI参数在设备本地可见，不复用正式秘密。

仅将核心复制到授权测试设备（以下SERIAL仅占位符）：

```sh
adb -s SERIAL push mesh/target/aarch64-linux-android/release/mesh-lab /data/local/tmp/nearby-mesh-lab
adb -s SERIAL shell chmod 700 /data/local/tmp/nearby-mesh-lab
```

分别执行，不同时运行：

```sh
python3 mesh/tools/radio-lab.py --mode ble --a USB_A --b USB_B --c USB_C --output mesh/target/ble-devices.json
# PSK文件内容8–63 ASCII bytes、不带换行；文件权限应为0600。
python3 mesh/tools/radio-lab.py --mode aware --a USB_A --b USB_B --c USB_C --passphrase-file /private/path/lab-psk --output mesh/target/aware-devices.json
```

控制器选择明确USB设备，启动三个Core与三个有界runner，要求B同时有两个认证邻居；默认双向各12条（含2048byte/Emoji）并验证全ID/路径，B off确认后新消息全TTL窗口不能越过B，到期后重新启用发送新ID；导出限额/清理结果。默认180s native生命周期会等待到结束；全程240s deadline。两轮需要分别等清理完成，不能复用仍占用39770的旧进程。每来源16/min预算，累计每侧20条验收应跨自然分钟重复执行，不删除状态或取消限额凑数。

**自动化只能检查数据路径，不能自动证明物理A/C隔离、无共同AP/Internet。** 必须另外记录：机型/OS/固件、B两个实际无线接口/角色、禁用蜂窝/Internet/共同AP/USB捷径、A/C距离或屏蔽以及直接尝试失败。label allowlist只是受控实验条件，不能当物理隔离。再手动完成权限撤销、radio开关、断链/恢复、B重启、锁屏/后台及功耗记录。若 cleanup unconfirmed，保存失败并检查本次远端进程，不用全局pkill/清空数据掩盖。

## 来源与许可

模块采用仓库AGPL-3.0-only，继续使用锁定Cargo依赖及原许可证；无Bitchat或闭源实现代码复制。BLE依据[Android数据收发](https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data)、[MTU](https://developer.android.com/reference/android/bluetooth/BluetoothGatt#requestMtu(int))、[indication串行回调](https://developer.android.com/reference/android/bluetooth/BluetoothGattServerCallback#onNotificationSent(android.bluetooth.BluetoothDevice,int))、[权限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)。参考Bitchat的[固定源码版本](https://github.com/permissionlesstech/bitchat/blob/04995f68e470a18d1fc99f86fa326c2a87bab4f2/bitchat/Services/BLE/BLEService.swift)，没有照搬其469byte假设。

Aware依据[官方指南](https://developer.android.com/develop/connectivity/wifi/wifi-aware)、[Network绑定](https://developer.android.com/reference/android/net/Network#bindSocket(java.io.FileDescriptor))、[scoped IPv6元数据](https://developer.android.com/reference/android/net/wifi/aware/WifiAwareNetworkInfo#getPeerIpv6Addr())及[AOSP数据路径实现](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/master/service/java/com/android/server/wifi/aware/WifiAwareDataPathStateManager.java)。API签名通过SDK36编译验证；平台支持和回调实际行为仍需设备复现。

后续质量/性能/体积审查见 [quality review](../../docs/mesh/native-radio-quality-review.md)，包含真实调度缺陷、资源保护和原始测量边界。

LE CoC API sources: [listenUsingInsecureL2capChannel](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#listenUsingInsecureL2capChannel()), [createInsecureL2capChannel](https://developer.android.com/reference/android/bluetooth/BluetoothDevice#createInsecureL2capChannel(int)). Run `bash mesh/android-radio/test-coc-integration.sh` with a built host `mesh-lab`; it exercises three real Core processes over pipe radio doubles, not RF.
