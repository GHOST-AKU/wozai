# iPhone 和 iPad 的我在

适用范围为 0.4.0-preview.12 的 Apple 移动候选。用户反馈中的截图显示，联系人使用大标题、路径和 UTF-8 计数挤在键盘上方，「最新消息」覆盖气泡。旧代码也把所有文件固定在文字之后，并在文件状态更新时请求滚到底部。相关反馈见 [#102](https://github.com/GHOST-AKU/wozai/issues/102) 和 [#31](https://github.com/GHOST-AKU/wozai/issues/31)。

| 页面 | iPhone | iPad |
| --- | --- | --- |
| 聊天列表 | 系统标签、搜索和消息摘要。空列表用「查找附近设备」进入私聊设备页。 | 同一会话列表成为系统分栏的 leading 栏。选择会话显示右侧详情，窄窗折叠，栏宽继续按窗口保存。 |
| 附近 | 进入附近即看公共聊天。私聊设备有独立入口，局域网和蓝牙分别开启接收，地址及诊断下沉。 | 沿用系统可变形标签栏和侧栏。公开聊天的消息和输入区限制阅读宽度。 |
| 私聊 | 小标题、统一的文字和文件时间线、原生键盘和附件选择。安全码和详细路径在菜单内。 | 详情在分栏中工作，编辑时保留时间线和键盘的系统安全区。连接和草稿由同一模型持有。 |
| 首次私聊请求 | 页面顶部显示设备名以及拒绝、仅本次和接受并记住。 | 请求保留正在浏览的页面，已有可信设备接入时也继续显示当前选择。 |
| 设置 | 系统 Form，外观和强调色、读回执及接收目录有独立位置。 | 使用同一系统控件，适应侧栏或窄窗。 |

正文使用系统文字样式，背景和分隔使用系统语义样式，强调色用于发出气泡和主要控件。自带表情目录复用统一的 Unicode 数据，原生长按菜单显示当前语言名称及变体。

滚动回归先保存真实 NIM4 文件，再由 Java 测试对端发送新文字。验收检查文字排在文件后方，以及离开尾部后可通过滑动回到新消息。其余边界包括接收权限、正在传输和已保存状态、键盘、读取位置、显式跳到最新和 iPad 栏宽。真实设备上的厂商行为和无线链路以设备证据为准。

[NavigationSplitView](https://developer.apple.com/documentation/swiftui/navigationsplitview) · [Tab bars](https://developer.apple.com/design/human-interface-guidelines/tab-bars) · [Split views](https://developer.apple.com/design/human-interface-guidelines/split-views) · [ScrollViewReader](https://developer.apple.com/documentation/swiftui/scrollviewreader) · [safeAreaInset](https://developer.apple.com/documentation/swiftui/view/safeareainset(edge:alignment:spacing:content:)) · [scrollDismissesKeyboard](https://developer.apple.com/documentation/swiftui/view/scrolldismisseskeyboard(_:)) · [PhotosPicker](https://developer.apple.com/documentation/photosui/photospicker)
