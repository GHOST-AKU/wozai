# 我在 / NearbyIM 0.3.3

本发行直接复用0.3.3-preview.4/build15，不重新编译，内部版本及文件名保留preview。Already running identical preview.4/build15 files? No reinstall is needed.

- Android: install the APK over the existing app; persistent signer/code15. Do not uninstall or clear user data to bypass signature mismatches. 原签名APK可覆盖安装；不要卸载或清空数据来绕过签名问题。
- Windows GUI: extract the portable ZIP, then run NearbyIM.exe; runtime included. 解压后运行NearbyIM.exe，无需额外安装Java。
- Linux GUI: use the AppImage (mark executable), deb, or extracted tar.gz. No separate Java required. 根据系统选择AppImage/deb/tar.gz，自带Java运行时。
- macOS: universal Intel/Apple Silicon, macOS14+. Ad-hoc only, no Developer ID/notarization. 仅临时签名，无公证。
- iPhone/iPad: iOS/iPadOS17+, unsigned IPA; re-sign and sideload. 未签名，不能直接点击安装。
- Terminal: extract the standalone archive, run nearbyim / nearbyim.exe. LAN listening starts off; use a separate profile from GUI. 独立解压运行，不共用GUI数据目录，LAN监听默认关闭。

Apple installation/signing / Apple安装与签名:
https://github.com/GHOST-AKU/wozai/blob/v0.3.3/docs/releases/native-preview-install.md

Full release notes and known platform limits / 完整说明与限制:
https://github.com/GHOST-AKU/wozai/releases/tag/v0.3.3

Verify downloads using SHA256SUMS.txt. RELEASE_PROVENANCE.json records the original build commits, sizes, hashes, signing states and CI runs. 使用SHA256SUMS.txt核对附件；来源和签名状态见RELEASE_PROVENANCE.json。
