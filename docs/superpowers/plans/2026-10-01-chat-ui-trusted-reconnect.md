# 我在原生界面与可信重连 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 实现用户调整后的浅色原生首页，并让首次认可的设备在后续连接时无需重复同意。

**Architecture:** 继续使用 Java 原生 Activity 与前台连接服务。将密钥证明和会话内消息完整性置于 Android 无关的协议层；控制器持久化独立的设备信任并控制自动就绪。界面复用现有权限、绑定、草稿与保存回执流程。

**Tech Stack:** Java 17、Android API 26–36、SQLite、经典蓝牙 RFCOMM、TCP/NSD、JDK EC 签名、Android Keystore。

**Spec:** `docs/ui-design.md`；视觉目标为用户修订后的浅色首页 `exec-e63b369b-86f9-43c4-aa35-89b65b8350e5.png`。

## Global Constraints

- 应用名「我在」；首页左上「我在」，右上连接指示，不展示纸杯电话图标；启动图标保持原样。
- 聊天 / 附近 / 设置底部导航，聊天详情隐藏底部导航。
- 一对一文字、本机历史；不增加群聊、附件、语音或账号。
- 双方首次认可后才持久化信任；仅本次、拒绝和超时不授权未来来访。
- 后续省去应用同意弹窗，但验证设备身份、协议就绪和接收开关。
- 清空消息不清空信任；可以单独撤销信任。
- 不生成 APK，不触发现有手动 GitHub APK 工作流。
- 无 SDK 时记录 Android 编译和真机 QA 的阻塞，不把语法检查称为编译成功。

## Review Focus

- 同名或同 UUID 不同密钥的设备不能自动接入。
- 重放以前连接的证明或消息不能进入新的会话。
- 撤销信任、仅本次和数据库写入失败不能被迟到回调覆盖。
- 局域网 IP 改变仍按已记住身份解析；旧地址不能把设备标为已连接。
- 重连、键盘和生命周期变化不清空草稿，不把其他会话标为在线。

## Task 1: 带密钥证明的协议

**Files:** core 中新增 `DeviceIdentity.java` 与必要的认证通道类；修改 `FramedSession.java`；测试 `tests/SessionTests.java` 和新增认证测试；协议测试工具仅按需要调整。

**Interfaces:**
- Produces `DeviceIdentity(KeyPair)`、`DeviceIdentity.generate()`、公钥编码与签名能力。
- Produces `FramedSession(StreamConnection, String localId, String nickname, DeviceIdentity, Listener)`。
- `Listener.onHello(Frame)` 只在远端证明验证后发生；`remotePublicKey()` 返回规范 Base64 X.509 公钥。
- 保留 `approve()`、`isReady()`、保存后 ACK 及有界收发队列行为。

- [x] 写认证缺失、跨会话重放、篡改文字、单方同意、就绪即时收件、有界队列测试。
- [x] 先运行对应测试，确认新行为失败，再实现。
- [x] 随机挑战绑定双方身份与当前会话；后续每条帧有方向和序列绑定的签名，限制读取长度。
- [x] 不提供可绕过认证的生产旧构造路径；旧线协议明确拒绝，不能静默降级。
- [x] 运行 `sh tools/test-core.sh`，保留原协议校验与回执证明范围。

## Task 2: 信任、存储与直接重连

**Files:** `ChatController.java`、`ChatStore.java`、新增 `AndroidIdentity.java`、必要的纯 Java 信任策略/SQLite 语句类、必要的 LAN 解析入口与测试。

**Interfaces:**
- Consumes Task 1 的 DeviceIdentity 与 `remotePublicKey()`。
- Produces `approve(String token, boolean remember)`；原单参数重载按记住处理。
- Produces `isTrusted(String peerId)`、`preferredMode(String peerId)`、`reconnect(String peerId)`、`revokeTrust(String peerId)`。
- Produces `public String connectedPeerId`，只有就绪后非空；`public List<ChatStore.TrustedDevice> trustedDevices`。
- `TrustedDevice` 对 UI 提供 `id`、`name`、`time`、`mode`、`bluetoothAddress`。
- `Conversation` 保留三参数构造与 `id/name/time`，增加 `preview/outgoing/state`；时间来自最后消息，摘要只取本机已保存消息。

- [x] 用真实 JDK 签名和 SQLite 语句测试信任匹配、撤销、无回执/失败、历史摘要与升级保留消息。
- [x] 先观察新行为测试失败，再实现。
- [x] Android Keystore 中保持长期密钥；首次成功就绪且授权记住后保存独立信任。
- [x] 主动发起本身同意本次；来访已信任且公钥验证一致时自动就绪，其余显示首次授权。
- [x] 已知身份变更需要显式重新确认，不自动替换；旧历史不直接迁移为可信身份。
- [x] 蓝牙使用保存地址；LAN 通过 NSD 解析当前对方 UUID 并最终验证公钥，失败留在历史页。
- [x] 删除消息不删除信任；撤销在活动会话和异步存储之间一致生效。
- [x] 运行全部核心与新增存储/信任测试。

## Task 3: 原生界面

**Files:** `MainActivity.java`、主题和标准图标资源、必要的 UI 支持类。不得修改其他代理的协议、控制器或数据库文件。

**Interfaces:** Consumes Task 2 的控制器方法、连接身份、会话摘要和 TrustedDevice。

- [x] 浅色基准 #FAFCFA / #EEF3EF / #17211C / #58675F / #246B4E；系统深色有相应配色。
- [x] 聊天首页：左上我在、右上真实连接方式/状态、搜索、本机首字头像与平铺列表、扩展新聊天按钮。
- [x] 聊天 / 附近 / 设置底部导航；首页空态只有一个主要入口。
- [x] 附近按 LAN/Bluetooth 切换相关操作，扫描、接收、IP、发现权限状态完整。
- [x] 首次面板「同意并记住 / 仅本次 / 拒绝」；可信重连没有同意弹窗。
- [x] 历史会话打开后可直接连接；设备信息/设置能撤销信任。
- [x] 消息气泡按可用窗口宽度，键盘 Insets 正确，输入和发送间距 8dp；保持保存成功再清空草稿。
- [x] 简短状态/选中反馈动画，读历史收新消息不强制跳底，读屏标签和大字体。
- [x] 静态 XML/Java 解析、接口编译检查（若 SDK 可用）及同尺寸真实原生截图 QA（若运行环境可用）。不制造浏览器仿页冒充原生截图。

## Task 4: 集成与审阅

- [x] 合并独立改动并运行完整核心/存储测试，检查生成文件和差异。
- [x] 更新 README、协议兼容说明、真机清单及本轮验证记录。
- [x] 独立审查授权、密钥绑定、撤销竞态、SQLite 升级与 UI 生命周期。
- [x] 修复影响正确性的问题并运行相关回归与全套测试。
- [x] 保留 GitHub 手动编译工作流；整理功能分支及草稿 PR 的提交内容，不合并主分支。

## Validation outcome

16 核心/会话 + 12 认证 + 21 信任策略 + 6 SQLite 检查通过。Java 18 文件解析与 XML 23 文件引用检查通过；独立审查的两处 P2 问题已修复并复审。Android SDK/设备不可用，原生编译、AAPT、截图及双机验收未执行；没有生成 APK。
