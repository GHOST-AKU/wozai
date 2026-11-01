# PR #85 合并前工程质量审查

基线 HEAD：382a94447fdb7382f178923e11b0c9047dfdcebb；总 Epic #60。目标是独立研究模块可审查和 CI 可验证，不是本轮实现产品 UI、无线 adapter、FFI、DTN、NIM4-over-Mesh 或 Mesh Alpha。

## 代码审查和最小修补

- 新 P2：`Store::load` 把悬空 seen.json 链接及部分元数据错误视为无状态，可能重新展示旧消息。现在只有明确 NotFound 使用空状态；包括 dangling 的符号链接和其他错误都 fail closed。四项 store 用例中，悬空快照链接和元数据错误两项新增回归先失败后通过，保留fixture；既有 resources 检查继续适用。
- 新 P2：同方向、同身份多地址连接若在双方按不同顺序到达，本地 Vec 顺序可导致互相淘汰。现在保留方向优先，再按公钥顺序规范化握手 challenge 对排序；逆序双端 fixture 和次级 challenge 比较回归均通过，不改线协议。
- 协议审查未确认来源签名、ID、期限、逻辑跳数或环路预算绕过。新增独立 Python/cryptography 固定向量，避免 encode/validate 共享错误假设；正确长度坏签名与 UTF-8 原文也有覆盖。
- 新增多身份64来源桶/128全局准入、有效seen满不驱逐、重复发现共享16候选，以及丢弃首次真实Data帧后同ID重连重 offer 的测试；不以TCP handed冒充对端交付。
- [威胁模型](security.md) 明确 Sybil、时钟、本地文件系统、转发/显示/屏蔽政策、落盘次数、隐私与资源范围；不新增反Sybil算法或屏蔽功能。

审查也复核已有 relay-off、部分写取消、时钟回退、持久化失败先于展示、进程强杀重放和跨代去重。首次接纳路径锁定、可选发现失败细节不足、长期轮询/同步写成本、平台密钥库等边界仍如实保留，不伪装成新的完整能力。

## PR 范围、许可证与路径过滤

原基线252文件/29,844新增行中，197个许可文件约20,350行、15个历史证据文件约3,940行，占81.4%；剩余是代码、测试、CI与研究文档。它们分别有锁定依赖清单、原文出处、版本/摘要与历史标签，不为缩小diff删除必要通知或证据。

94个registry依赖与Cargo.lock一致；`.crate` SHA对应lock checksum，解包通知与repo原文比对。4份手动winapi通知通过固定SOURCE URL与同上游已锁定文本绑定。新增只读 `python3 mesh/tools/licenses.py --check`：锁图、metadata、inventory、markdown、归档/解包/原文漂移或缺失均失败，不下载许可或改文件。

Android/Linux/Windows的路径排除仅增加mesh/**、docs/mesh/**、mesh workflow；客户端源码、根构建输入或各自workflow混入时仍触发原检查。新增路径回归覆盖这些组合。Mesh工作流也覆盖.cargo/**及rust-toolchain配置。路径测试仅覆盖仓库实际使用的glob子集，不声称重写完整GitHub glob解释器。

## 独立 CI

基线382a944没有check-runs/Actions记录。新工作流不在默认分支注册，直接 `gh workflow run mesh.yml --ref research/nearby-mesh-engine` 返回404；因此本轮使用正常PR同步事件首次注册/触发。注册后最后的证据措辞修订使用skip-ci避免重复客户端打包，再显式dispatch专用workflow验证最终HEAD；不能用skip-ci代表成功。

专用workflow显式checkout请求的PR head SHA，并断言git HEAD相等；运行fmt、clippy、crate tests、release构建、只读许可检查、全部Python进程/控制器案例、两种隔离拓扑，上传实际binary/source快照与拓扑结果。job有15min timeout，权限仅contents:read，checkout不持久化credentials。日志与run链接在PR/Issues记录，只有实际完成结果才能称通过。本PR包含三份旧客户端workflow路径过滤编辑，这些编辑会触发对应客户端流程；若取消源文件未改的重打包，会在PR明确记录原因和状态，绝不写作通过。

本轮以远端专用workflow为最后一次代表性完整验证；本地优先定向回归，不重复重建未改Android/APK或整套正式客户端。历史60项/CPU数据仍绑定旧提交，不能当作本HEAD新执行结果。当前完整测试数和CI状态以PR中的run结果为准。

## #86–#92 对齐矩阵

| Issue | 已有源码/文档/证据 | 仍待完成；不能冒充的验收 |
| --- | --- | --- |
| #86 物理组网可行性 | research/mobile-transports/ADR/hardware-validation；多组STA/GO与混合BLE/Wi-Fi两候选、fallback、权限生命周期研究和复现计划 | 并发B双无线邻接的本机实测、实际MTU/吞吐/机型矩阵pending；研究文档不是设备成功 |
| #87 独立Lab | README；真实三进程、存活listener正对照、A/C无路由、双向/relay-off、loss/cycle/SIGKILL/partition用例；最新CI须附链接 | CI未通过前不写远端完成；不包含Android无线 |
| #88 公共协议 | protocol/ADR/security；完整入口验证、固定向量、去重/TTL/逻辑hop、资源测试和集成 | 显式lobby字段/协商、完整fuzz及逐邻居资源指标待后续；首到路径并非全网覆盖保证 |
| #89 连接管理 | manager/discovery/transport/main；同身份/方向challenge排序、重连、候选/连接预算及授权隔离 | 原生radio/权限/后台、电量策略和更完整发现/恢复诊断pending |
| #90 安全资源 | security威胁模型；坏签名/重放/多身份/重复发现/限额/持久化回归；安全诊断白名单 | 无完整Sybil抵抗/公平性、平台功耗/许可撤销、block/mute、平台密钥库；未新跑NIM4整套回归 |
| #91 真实三Android | hardware-validation计划、原生能力探针源码与此前编译证据 | **全部真实无线gate仍hardware validation pending，不关闭Issue，不称Mesh Alpha** |
| #92 NIM4接口RFC | ADR隔离边界，正式NIM4/Noise/设备身份无改动 | 独立接口RFC、失败/重排/可靠stream威胁表与兼容测试计划未交付，本轮不扩展 |

Issue验收逐项勾选/未勾选和源码/CI链接在各Issue更新；完整远期Issue未完成不阻止对独立研究模块进行review。#60只更新真正完成的Lab和CI部分，产品/无线发布门槛不变。

## 合并审查判定

Ready for review 的条件：本次P2修复与针对性回归、许可/范围审查通过；最新HEAD的专用远端CI真实成功；不存在影响该研究模块的未解决重要缺陷；声明过的远期边界清晰。若远端CI或重要问题未解决，保留Draft并在PR列阻塞。Ready不等于批准合并，也不替代维护者的最终review，不自行merge/发行。
