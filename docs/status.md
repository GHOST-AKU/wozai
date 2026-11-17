# 项目进度与证据边界

核对日期：**2026-11-17**。实现基线为 main `f59853eac6d08285090a2be033fe973fbffbdc30`；本文整理分支只改文档。GitHub 后续变化请重新核对，不能用本快照代替最新源码审查。

## 版本与客户端

- 最新正式发行记录为 [v0.3.3](https://github.com/GHOST-AKU/wozai/releases/tag/v0.3.3)，复用 preview.4/build15 安装包。正式版能力不随 main 或 Draft PR 自动增加。
- main 的 `i18n/config.json` 为 **0.4.0-preview.7 / build22**，是开发版本权威；客户端构建/签名来源见各阶段记录，不重新给旧包改名。
- Android 私聊有经典蓝牙 RFCOMM、LAN、NIM4 加密、身份信任、消息回执和文件传输；Android Next 复用同一 Java 核心，界面为 Kotlin/Compose。
- Android Next 公共 Mesh 使用独立 Rust Core；BLE 已发展到 CoC，Wi-Fi Aware 为另一独立模式。Windows Next 公共 Mesh 使用 RFCOMM。不能把公共 Mesh 的 CoC、私聊 RFCOMM 和 Apple 新 GATT 私聊说成同一条链路。
- Apple 原生客户端和 Rust Terminal 已有 NIM4 LAN 私聊及附件能力；Apple 新增的公共 Mesh/蓝牙私聊仍在下列 Draft PR，未进入 main。

## 已合并主线

| PR | 交付 |
| --- | --- |
| [#83](https://github.com/GHOST-AKU/wozai/pull/83) / [#84](https://github.com/GHOST-AKU/wozai/pull/84) | Android Compose Next / Windows WinUI Next |
| [#85](https://github.com/GHOST-AKU/wozai/pull/85) | 独立 Mesh Core、协议、隔离 TCP 实验与测试 |
| [#93](https://github.com/GHOST-AKU/wozai/pull/93) / [#100](https://github.com/GHOST-AKU/wozai/pull/100) | 原生无线研究与适配代码；#100 承接审查后的集成 |
| [#96](https://github.com/GHOST-AKU/wozai/pull/96) / [#99](https://github.com/GHOST-AKU/wozai/pull/99) | Android Next 公共 Mesh 与 CoC 加固 / Windows Next 公共 Mesh |

## 未合并工作

| PR | 当前范围 | 尚未完成 |
| --- | --- | --- |
| [#101](https://github.com/GHOST-AKU/wozai/pull/101) | 两份全平台研究设计：私聊直接发送、自动连接、可靠待发 | 产品代码未实现；原请求为先研究，不写正式代码 |
| [#103](https://github.com/GHOST-AKU/wozai/pull/103) | Apple 原生公共 BLE Mesh、嵌入式 Core、UI 与自动化 | Apple 真机无线及签名分发验收；与 #108 集成时需处理共享权限/界面资源 |
| [#108](https://github.com/GHOST-AKU/wozai/pull/108) | Apple 蓝牙私聊及 Windows GATT 直接连接，复用 NIM4；原生构建和协议互通证据已有 | iPhone↔Windows、Apple↔Apple 真机无线待验；现有 Android 私聊未实现这个新 GATT 服务 |

三者在核对时均为 Draft。各自的编译来源、测试命令和硬件边界见 PR；没有在本轮重新构建或重新测试。

## 已有实机成果与仍缺证据

所有者已反馈 **Android 多设备 Mesh 稳定连接、中继成功，以及 Windows/Android Mesh 与中继成功**。不能再笼统称为“只有 TCP 模拟”。[实机反馈索引](mesh/field-validation-2026-11-08.md)记录了反馈上下文和对应构建。

正式 [#91](https://github.com/GHOST-AKU/wozai/issues/91) 验收仍缺完整三设备物理隔离、无共同 AP、relay-off、恢复和资源证据。Wi-Fi Aware/Direct 及 Apple 新路径没有因上述蓝牙成功反馈而自动通过。公共消息不是端到端加密，公共邻居不自动成为 NIM4 可信联系人。

## 实际产品缺口

- 当前私聊仍为单活动会话；Android `ChatController.active` / Desktop `current` 共享连接名额，`canSend()` 依赖当前会话 ready。已记住设备的手动重连已存在，完整自动会话/可靠待发尚不存在。
- [#6](https://github.com/GHOST-AKU/wozai/issues/6) 多设备分别私聊与 #101 的会话管理相关；不能用自动点一次重连代替持久队列、连接竞争、资源限制、撤销和失败状态设计。
- 公共 Mesh 多跳成功不等于 NIM4 私聊能多跳。接口与安全设计另由 [#92](https://github.com/GHOST-AKU/wozai/issues/92) 跟进。
- Android Next 的剩余功能差异/设备验收、Windows 小文件存储扩展性分别见 [#94](https://github.com/GHOST-AKU/wozai/issues/94)、[#95](https://github.com/GHOST-AKU/wozai/issues/95)、[#97](https://github.com/GHOST-AKU/wozai/issues/97)。未关闭 Issue 不代表从零开始，也不代表其所有子项都未做。

下一步依赖关系见[路线图](roadmap.md)。没有其他平台设备只阻塞相应硬件验收，不应被描述为整个开发任务停工。
