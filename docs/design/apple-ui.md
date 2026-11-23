# iPhone 和 iPad 的我在

适用范围为 Apple 移动客户端 0.4.0-preview.12 及 .13。早期截图显示聊天大标题、连接路径、UTF-8 计数和跳转按钮占用阅读空间。旧代码还把所有文件固定在文字之后，并在文件状态更新时请求滚到底部。[#102](https://github.com/GHOST-AKU/wozai/issues/102) · [#31](https://github.com/GHOST-AKU/wozai/issues/31)

| 页面 | iPhone | iPad |
| --- | --- | --- |
| 聊天列表 | 系统标签、搜索和消息摘要。空列表用「查找附近设备」进入私聊设备页。 | 同一会话列表成为系统分栏的 leading 栏。选择会话显示右侧详情，窄窗折叠，栏宽继续按窗口保存。 |
| 附近 | 进入附近即看公共聊天。私聊设备有独立入口，局域网和蓝牙分别开启接收，地址及诊断下沉。 | 沿用系统可变形标签栏和侧栏。公开聊天的消息和输入区限制阅读宽度。 |
| 私聊 | 小标题、统一的文字和文件时间线、原生键盘和附件选择。详情隐藏标签栏，安全码和详细路径在菜单内。 | 详情在分栏中工作，编辑时保留时间线和键盘的系统安全区。连接和草稿由同一模型持有。 |
| 首次私聊请求 | 页面顶部显示设备名以及拒绝、仅本次和接受并记住。 | 请求保留正在浏览的页面，已有可信设备接入时也继续显示当前选择。 |
| 设置 | 系统 Form。主要设置优先显示，完整隐私说明、设备身份、版本和诊断由导航行进入。 | 使用同一系统控件，适应侧栏或窄窗。 |

正文使用系统文字样式，背景和分隔使用系统语义样式，强调色用于发出气泡和主要控件。自带表情目录复用统一的 Unicode 数据，原生长按菜单显示当前语言名称及变体。

| 强调色预设 | 浅色主色 | 深色主色 |
| --- | --- | --- |
| 紫色（默认） | `#624398` | `#D8BFFF` |
| 蓝色 | `#185BAA` | `#AAC9FF` |
| 青绿色 | `#006B61` | `#85D6C8` |
| 橙色 | `#984500` | `#FFB783` |

预设与 Android Next 同名同色，保留已选值。Android 12+ 的系统色来自壁纸，Apple 的系统色来自原生 accent。送达、失败和核验同时提供文字或符号。[#117](https://github.com/GHOST-AKU/wozai/issues/117)

| 场景 | 实际显示与操作 |
| --- | --- |
| 附近未开启或蓝牙受限 | 显示接收状态或权限提示，保留输入，发送要求公共接收可用。 |
| 附近搜索或已有直连 | 显示正在查找或经过认证的直连数。中继开关在附近设置，默认关闭。 |
| 附近消息排队 / 转交 / 终止 | 等待附近设备；转交仍是送达未确认；关闭接收与过期分开显示。 |
| 私聊尚未连接 | 草稿留在本机。有可用自动路由时可选择连接后发送，最长等待一分钟。 |
| 私聊已发送 / 已保存 / 已读 | 本机提交、对端持久回执与已读逐级区分。旧消息的回执描述对应消息。 |
| 图片 | 仅对已完成文件做有尺寸限制的本机缩略图。点击原生预览，通用文件保留名称、大小和分享。 |

用户确认 Apple 客户端的 Mesh 组网成功。这条实机反馈目前没有附带参与设备、跳数、型号或系统版本。回归测试使用隔离的 NIM4 测试对端。[#91](https://github.com/GHOST-AKU/wozai/issues/91)

滚动回归先保存真实 NIM4 文件，再由 Java 测试对端发送新文字。验收检查文字排在文件后方，以及离开尾部后可通过滑动回到新消息。其余边界包括接收权限、正在传输和已保存状态、键盘、读取位置、显式跳到最新和 iPad 栏宽。真实设备上的厂商行为和无线链路以设备证据为准。

[NavigationSplitView](https://developer.apple.com/documentation/swiftui/navigationsplitview) · [Tab bars](https://developer.apple.com/design/human-interface-guidelines/tab-bars) · [Split views](https://developer.apple.com/design/human-interface-guidelines/split-views) · [ScrollViewReader](https://developer.apple.com/documentation/swiftui/scrollviewreader) · [safeAreaInset](https://developer.apple.com/documentation/swiftui/view/safeareainset(edge:alignment:spacing:content:)) · [scrollDismissesKeyboard](https://developer.apple.com/documentation/swiftui/view/scrolldismisseskeyboard(_:)) · [PhotosPicker](https://developer.apple.com/documentation/photosui/photospicker)
