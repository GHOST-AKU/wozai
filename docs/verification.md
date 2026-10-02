# 验证记录 · 2026-10-02

## 当前源码 0.3.1 · 新增繁体中文、日语与韩语

在现有共享架构中新增 `zh-Hant`、`ja`、`ko`，连同简体中文与英文，每种语言完整覆盖 299 个文案键。应用与测试源码为 `c8f1bdbdc6c4915119c332140ce8dc148ee456c9`，构建提交为 `3a4ebac20dc5f9bfcaf113544eb210d8c7b31bcd`。[最终运行 37014761654](https://github.com/GHOST-AKU/wozai/actions/runs/37014761654)的 Android 编译、Windows 打包、API 26 和 API 34 原生验证四项任务全部成功；后续提交仅更新交付文档和恢复手动触发。

| 检查 | 实际结果 | 证明范围 |
| --- | --- | --- |
| 文案及语言元数据 | 五种语言各 299 键；8 项生成器测试、43 项语言解析检查通过；339 处字面消息键引用，0 错误 | 参数契约、生成一致性、台港澳繁体别名、日/韩地区和原生脚本、旧偏好与系统回退 |
| Windows 格式与字体 | 3038 项通过 | 全目录 ICU 模式、日期/复数、字面昵称、缺键回退、逐条现有字体字形覆盖；未增加字体或 ICU 依赖 |
| Android 编译与资源 | 主 APK/test APK 和 Lint 成功，0 错误、4 项保留警告 | 0.3.1 / code 5，minSdk 26、targetSdk 36；原生资源完整包含 en、zh-Hans、zh-Hant、ja、ko |
| Android API 26 与 34 | 各 135 项通过，原始结果码 -1 | 三种新语言的原生文案、ICU 计数、日期、用户参数及帮助段落；原有中英文真实 TCP、许可等待/重建、草稿/光标/阅读位置、前台服务/通知及保存回执继续通过 |
| Windows 实际运行 | 61 项桌面、104 项数据/迁移、54 项蓝牙 JNI，原生生命周期通过 | 实际 EXE、自带运行时、DPAPI 和便携路径；真实 mDNS、协议与信任回归 |
| Windows 五语界面切换 | 完整 JDK 和自带运行时的真实 GUI 检查通过 | 活跃连接、草稿及回执保留；繁体、日语、韩语的操作与已打开帮助/关于/信任窗口更新；原有布局/滚轮检查通过 |

Windows ZIP：[NearbyIM-0.3.1-windows-x64](https://github.com/GHOST-AKU/wozai/actions/runs/37014761654/artifacts/11229477243)，54,223,619 字节，上传 SHA-256 `7d05884598abd48e205a83cfa2149e785593c3bbf9b1ba59dc81280f4715ed9f`。约 51.71 MiB，比 0.3.0 增加 384,087 字节（约 0.37 MiB）。

Android ZIP：[NearbyIM-0.3.1-android-debug](https://github.com/GHOST-AKU/wozai/actions/runs/37014761654/artifacts/11229167650)，760,215 字节，下载 SHA-256 `04e91c9034a0d7e557ebb47c4071aca6bd4ad1fe08607fac2cf5ea013a7f0e8c`。主 APK 为 853,580 字节，SHA-256 `238b97e8d1a1e49085abbcb41a12789ceee6cae6d5db8c1dab0a06db969dfc66`；ZIP CRC、构建校验值、v2 签名、zipalign 和版本/语言声明均已核验。

本次临时调试证书 SHA-256 为 `304747e1145ceffc1a5c78f5dbb657486b4c53f46c994e94f8a08120154fe605`，与此前调试包不同。保留记录升级需要原签名密钥；不要卸载原应用。实体蓝牙、实际系统权限回调、母语用词人工审阅与读屏仍按设备清单验收。

截图及原始记录：[Windows](https://github.com/GHOST-AKU/wozai/actions/runs/37014761654/artifacts/11230185005)、[Android API 26](https://github.com/GHOST-AKU/wozai/actions/runs/37014761654/artifacts/11229767335)、[Android API 34](https://github.com/GHOST-AKU/wozai/actions/runs/37014761654/artifacts/11229127615)。

## 历史版本 0.3.0 · 安卓与 Windows 多语言

两端使用同一份 299 键简体中文／英文目录、语言注册表和 ICU 格式契约；应用与测试源码提交为 `afaf6826bba8645f6e840f870013fecab79a5f63`。独立构建分支提交 `00ebdbde3e12cfc1a860d68935fd4499309e9410` 的[最终运行 37003827341](https://github.com/GHOST-AKU/wozai/actions/runs/37003827341)包含 Android 编译、Windows 打包、Android API 26 和 API 34 原生验证，四项任务全部通过。后续交付提交仅更新文档和恢复手动触发，不改变已经验证的应用源码。

| 检查 | 实际结果 | 证明范围 |
| --- | --- | --- |
| 共享目录与生成器 | 两种语言各 299 键；8 项生成器测试、23 项语言解析检查通过；339 处字面消息键引用、0 错误；生成产物无漂移 | 重复/漏译/额外键、ICU 参数、标签与脚本、别名、英文回退、Windows CRLF |
| 协议与认证 | 16 项核心/会话 + 12 项认证测试通过 | 真实 TCP、消息边界、有界窗口、许可、签名、篡改与重放拒绝 |
| 信任与数据库 | 21 项信任策略 + 11 项 SQLite 测试通过 | 旧版中文状态迁为稳定状态码，历史正文保留，信任与消息独立 |
| Android 编译与 Lint | 主 APK 与 test APK 编译成功；Lint 0 错误、4 警告 | JDK 17、AGP 8.13、SDK 36 / Build Tools 35.0.0；保留的警告为插件更新、API 26 资源限定及两处单色图标 |
| Android API 26 原生运行 | 120 项通过；原始结果 `INSTRUMENTATION_CODE: -1` | 系统 ICU、真实认证 TCP、许可等待时重建、草稿/光标/阅读位置、前台服务、收发及回执 |
| Android API 34 原生运行 | 120 项通过；原始结果 `INSTRUMENTATION_CODE: -1` | Android 13+ 系统应用语言选择与重建；后台已发布通知、通道名称随语言刷新；服务和身份保持 |
| Windows 桌面与数据 | 61 项桌面、104 项目录/迁移检查通过 | 稳定身份、TCP/回执、便携目录、旧文件保留、互斥与失败处理 |
| Windows 翻译 | 631 项通过 | 全部目录模式、ICU 复数/撇号/字面参数、逐键英文回退及语言偏好；启动错误按保存的语言显示 |
| Windows 蓝牙实现 | 原生生命周期测试、54 项接口/JNI、蓝牙路由字节流测试通过 | 编译与桥接、资源释放、共用身份/许可/保存后回执；没有验证无线硬件 |
| Windows 真实程序与 GUI | `NearbyIM.exe`、完整 JDK 和自带运行时均通过 | DPAPI、默认便携路径、退出；语言实时切换、已打开对话框、连接、草稿、搜索/选择/滚动位置保留；真实 mDNS |
| Windows 布局 | 真实窗口检查通过 | Noto 字体、简洁页头、圆角高亮、输入与发送等高、窄窗口设置、大字号和滚轮 |
| 安装包核验 | 最终安卓 artifact / APK SHA-256、ZIP CRC、v2 签名与 zipalign 校验通过 | 版本 0.3.0 / code 4，minSdk 26、targetSdk 36；原生资源含 en、zh-Hans，英文品牌 NearbyIM |
| 独立代码审查 | 重要问题已修复，复审未发现剩余严重或重要问题 | 修复旧默认昵称变更、Unicode/脚本区域解析与单键英文 ICU 回退；临时法语、阿拉伯语 RTL、塞尔维亚语拉丁脚本目录可扩展 |

原生语言检查使用安卓模拟器和真实 localhost TCP，不是实体蓝牙通信。用户此前已报告旧版 Windows 可与实体安卓通过局域网通信。0.3.0 的 Windows ↔ Android 蓝牙、实际系统权限/蓝牙启用弹窗在跨语言重建后的返回、不同 DPI 与读屏仍需按 [device-test.md](device-test.md)验收。

### 0.3.0 下载与完整性

| 产物 | 下载 | 字节数与 SHA-256 |
| --- | --- | --- |
| Windows 便携 ZIP | [NearbyIM-0.3.0-windows-x64](https://github.com/GHOST-AKU/wozai/actions/runs/37003827341/artifacts/11224653026) | 53,839,532；构建上传摘要 `e4880e5864cd1af698c59538cb015c672079ea1f6c8c3e948c7b8f288fbf0524` |
| Android artifact ZIP | [NearbyIM-0.3.0-android-debug](https://github.com/GHOST-AKU/wozai/actions/runs/37003827341/artifacts/11225396246) | 737,310；下载核验 `e896e58a43f4170f6ccdf5ef3b7143498811c5a17b9e17746999dd3da5952988` |
| ZIP 内主 APK `debug/app-debug.apk` | 从 Android ZIP 解压；测试 APK 不用于日常使用 | 775,900；`b52b3fcb11213403cb4283961fdd0965791d1bfdcad73a4c0e6c760353d60990` |

运行记录与截图：[Windows](https://github.com/GHOST-AKU/wozai/actions/runs/37003827341/artifacts/11224638245)、[Android API 26](https://github.com/GHOST-AKU/wozai/actions/runs/37003827341/artifacts/11224603631)、[Android API 34](https://github.com/GHOST-AKU/wozai/actions/runs/37003827341/artifacts/11225366094)。

### 安卓保留数据升级与签名

本次 CI 调试证书 SHA-256 为 `35a79cd68a10132d5cd94d0bb5d423beec22093e9f9b3eaaea3198f9333628c1`，与此前交付的 0.2.0 调试 APK 不同。因此该下载包不能覆盖安装那个旧 APK；代码中的数据库迁移需要同包名、同签名密钥的正常更新。CI 当前每次生成临时调试密钥，还未配置长期发布签名。

有历史记录的手机保留原应用，使用原签名密钥构建 0.3.0 升级包；**不要为安装本次调试包卸载旧应用**。卸载会删除消息、信任、草稿及 Keystore 身份。本次模拟器验证适用于新安装和同一密钥的会话测试，不能替代实体用户设备的保留数据更新。

## 历史版本 0.2.0 · 2026-10-01 交付记录

本轮实现浅色原生界面、独立设备信任与 NIM2 身份验证。用户恢复编译后，已在独立 GitHub Actions 构建分支生成 `WoZai-0.2.0-debug.apk`。本地仍缺少可运行的安卓设备，原生截图与双机测试待执行。

| 检查 | 实际结果 | 证明范围 |
| --- | --- | --- |
| `sh tools/test-core.sh` | 16 项核心/会话测试 + 12 项认证测试通过 | 真实本机 TCP、消息窗口、签名验证、篡改与重放拒绝 |
| `sh tools/test-trust.sh` | 21 项信任策略检查 + 6 项 SQLite 测试通过 | 记住/仅本次/撤销、异步授权、升级保留消息、摘要与信任独立存储 |
| `sh tools/check-source.sh` | Java 语法及 XML/资源引用检查通过 | 解析检查，不是 Android API 类型检查、AAPT 或编译 |
| 独立协议审查 | 未发现可操作的协议正确性或认证缺陷 | 不代表正式安全审计或 Android 签名提供者验证 |
| 独立 Android 集成审查 | 发现同会话历史重载丢失阅读位置、旧扫描停止失败回调干扰新重连，已修正 | 源码审查，真实生命周期/NSD/蓝牙运行仍需验收 |
| 启动图标 | 保留原图及白色底板 | 首页移除纸杯图标不影响启动图标 |
| Android `assembleDebug` / `lintDebug` | BUILD SUCCESSFUL；Lint 0 错误、6 警告 | Gradle 8.13、JDK 17、SDK 36 / Build Tools 35.0.0 |
| APK 签名与对齐 | apksigner v2 与 zipalign 4 字节校验通过 | 调试签名，不是发布签名 |
| 下载完整性与声明 | artifact SHA-256、APK SHA-256、ZIP CRC 全部通过；版本 0.2.0 / code 3，minSdk 26、targetSdk 36 | 已核验下载文件与构建校验文件一致 |
| 原生截图 / 双机 | 未执行 | 尚无设备运行记录 |

### 本版构建产物

- 构建：[GitHub Actions 36876158883](https://github.com/GHOST-AKU/wozai/actions/runs/36876158883)，构建提交 `8d800b118843fa776c7ea900f565dce59dffd833`；与功能分支 `54e4c788b7a41c05e76341bf6f3d26f17dd04e42` 的应用源码一致，工作流多了隔离分支触发与产物校验步骤。
- APK：695576 字节，SHA-256 `699c0f3f26a48ab24b6ca5b1d3bc72fba1461fbd2a29760094797a6d90b8c31b`。
- 调试证书 SHA-256：`3f8b315d08e8a0c9db9235ab82364af404e4924bcfe91c914a7f48e7b69e0f3b`。
- 与旧 0.1.0 调试证书不同，不能覆盖安装旧调试版；卸载旧版会删除本机消息与设备信任。
- 构建期间修正 SDK 安装步骤对已移除 `tools` 包的依赖、Material 矢量颜色的 framework 属性引用、剪贴板类的明确导入。资源检查已能发现未声明的 `?attr/` 引用，修正前 10 项失败、修正后通过。
- 6 个 Lint 警告：目标 SDK/Gradle 有更新、API 26 资源目录限定冗余、未使用昵称字符串、两处缺少单色启动图标；没有关闭错误检查或绕过 Lint。保留当前 SDK 与用户指定的启动图标。

NIM2 拒绝旧版无认证连接，两端需同为 0.2.0。旧历史不自动获得信任。首次认可保存公钥，后续匹配公钥才免应用确认；系统蓝牙权限和配对仍照常处理。消息仍未实现端到端加密。详见 `protocol-v2.md` 与 `device-test.md`。

## 历史版本 0.1.0 / 0.1.1

下列为早前版本记录，不能用于证明当前 0.2.0 的 Android 构建或运行结果。

已交付 `NearbyIM-0.1.0-debug.apk` 调试安装包。以下签名与完整性记录对应该 0.1.0 安装包。当时源码已改名为「我在」并更新版本为 0.1.1；改名构建已结束，用户要求暂停，未继续核验或交付新版 APK。硬件功能仍需真实双机验收。

| 检查 | 实际结果 | 证明范围 |
| --- | --- | --- |
| `sh tools/test-core.sh` | 15 项通过，0 项失败 | JDK 17 下的真实 TCP 与共享协议 |
| 许可时序回归 | 修正前失败，修正后通过 | 观察到 READY 的对方立即发消息不会被错误拒绝 |
| 消息窗口 / 回执变异检查 | 移除 3 项保护后对应 3 项测试失败；正式代码全通过 | 测试能够捕获无界收件、未知回执与无界待回执窗口 |
| Java 语法解析 | 应用 13 个 Java 文件，0 个语法错误 | 仅语法，不等于 Android API 编译成功 |
| XML | Manifest、主题与图标均解析成功 | XML 格式，不等于 AAPT 资源编译 |
| 官方 Gradle Wrapper | JAR 完整且 SHA-256 匹配官方值 | 构建启动器来源与完整性 |
| 独立代码审查 | 已执行，重要问题已按源码修正 | 未代替硬件测试 |
| Android `assembleDebug` / `lintDebug` | BUILD SUCCESSFUL；Lint 0 错误、0 警告 | AGP 8.13.0、Gradle 8.13、完整 JDK 17、SDK 36 / Build Tools 35.0.0 |
| APK 签名与对齐 | `apksigner verify`、`zipalign -c 4` 均通过；v2 调试签名 | 可验证安装包签名和对齐；不是发布版签名 |
| APK 完整性与声明 | ZIP CRC 全通过，包含 Manifest / DEX / 资源；包名 `dev.ghost.nearbyim`，版本 `0.1.0`，minSdk 26、targetSdk 36 | 未代替安装和运行验证 |
| 蓝牙双机、安卓生命周期 / 权限 UI | 未执行硬件验收 | 需按 `device-test.md` 使用真实手机完成 |

## 测试覆盖

1. UTF-8 文字、颜文字、Emoji、换行，经每次只读取一个字节的输入流仍完整。
2. 连续帧保持消息边界。
3. 拒绝空文字与超限文字；8192 字节边界可用。
4. 拒绝非法长度，分配内存前检查上限。
5. 拒绝截断帧。
6. 拒绝非法 UTF-8 与不支持的版本。
7. 拒绝未知消息类型、错误 UUID、无效昵称与异常 ACK 载荷。
8. 校验局域网数字 IP、IPv6 ULA 与端口；拒绝域名、公共地址和错误端口。
9. 真实 TCP 双向收发与明确触发的保存回执。
10. 单方同意不能发送；多次关闭只产生一次关闭事件。
11. 未经许可的文字注入被拒绝。
12. 对方刚看到本机 READY 字节就发送的文字仍被接受。
13. 不保存、不回执的接收突发最多进入 32 条，然后释放连接。
14. 未知 ACK 不交给消息存储层。
15. 最多 32 条待回执发送，收到合法回执后释放一个发送名额。

## 审查修正

- 本机同意状态先于 READY 可见，写线程仍保证后续 TEXT 排在 READY 后。
- 待处理权限 / 蓝牙启用操作用可序列化的动作描述保存，重新绑定后恢复。
- NSD 搜索使用独立代次，迟到的旧搜索回调不能修改新设备列表。
- 搜索失败与连接失败分别回调；搜索提示不能把尚在进行的连接标记为空闲。
- 当前历史会话在服务重新绑定后恢复，草稿按对方 UUID 分开。
- 输入框在本机保存成功后才清空，保存失败保留草稿。
- 元数据写入失败抛出数据库错误；收件持久化失败不 ACK。
- 收件、发件与输出队列有界；未知回执不进入存储任务。

## 编译修正与安装包

- Android 8.0 主题使用对应版本支持的属性，API 27+ 主题单独设置浅色导航栏。
- Android 13+ 注册系统返回回调；旧版本保留 `onBackPressed` 兼容路径。
- 动态颜色直接使用 API 31+ 系统资源，昵称文字通过格式化字符串资源显示。
- 心跳采用固定延迟调度，避免进程恢复时集中补跑。
- 新旧 Android 备份规则均排除本机身份与聊天数据，包括新版设备转移。

安装包 SHA-256：`c541a11786439efc8ed62c06fc638bdb51ebbb87beee0abfc1e7ee29ff5bb23c`。

Android 调试证书 SHA-256：`8bc863277274507c25c69b0860e60d6a10b78199063910e46dcd74f3bbaf0eff`。

保留的小限制：修改昵称后，新聊天握手立即使用新昵称；正在运行的局域网发现广播名称在停止并重新开启接收后更新。应用没有群聊、文件传输和互联网转发；这些未列入第一版范围。
