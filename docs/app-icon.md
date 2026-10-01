# 我在 · 纸杯电话图标

桌面图标来自用户提供的「手绘纸杯电话图标(1).png」。白色圆角底板属于图标，保留白色底板、黑色纸杯电话、连接线与三条声纹，只移除外围灰蓝背景。

最终 PNG 位于 `app/src/main/res/drawable-nodpi/ic_launcher_artwork.png`。它保留透明外缘；应用的自适应图标使用纯白背景，确保白色底板完整呈现。标准和圆形桌面图标分别由 `mipmap-anydpi-v26/ic_launcher.xml` 与 `ic_launcher_round.xml` 引用，适用于项目最低支持的 Android 8.0。

前景层四边各留出 15dp，让黑色图案落在自适应图标的中央安全区域内。不同桌面仍会使用各自的图标裁切形状。图标资源不参与聊天界面布局；通知栏小图标保持独立。

背景提取使用 ImageGen；接入项目时直接复制 PNG，没有再缩放或重新绘制。最终提取提示的要点是：保留整个白色底板和原有黑色图案，只清除外层灰蓝背景，透明区域不得有残留，不生成新图案。

本次仅检查 PNG 完整性、白色底板与透明外缘、黑色图案安全范围、XML 与 Manifest 引用。未执行 APK 编译，也未进行真实安卓桌面验收。

官方规范：https://developer.android.com/develop/ui/views/launch/icon_design_adaptive
