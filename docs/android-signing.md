# Android 固定签名和覆盖升级

固定证书 SHA-256：`47a10fd5a882ab26c9a11fcc61df3b41f7c41017746fc6840cfbe581ca52c1dd`。
这是 0.3.1、正式 0.3.2/code12 沿用的原有证书（CN=Android Debug），有效期至 2056-09-24。将原私钥导入强密码 PKCS12 并改变别名／密钥库密码不会改变证书；没有创建新的发行身份。签名密钥与应用内 AndroidKeyStore 设备身份是两套独立密钥。

## 私钥保管

PKCS12 别名为 `nearbyim`，密钥密码与密钥库密码一致。用户应分别保存加密密钥库和恢复资料，并在独立的私人存储保留备份。当前执行环境不是长期备份。不得把密钥、密码、base64 内容提交到仓库或上传到 Release、Issues、Actions artifacts；`.p12`、`.pfx`、`.keystore`、`.jks` 及 `.signing/` 已忽略。

GitHub 仓库 Settings → Secrets and variables → Actions 中需要两个 Secrets：

- `WOZAI_ANDROID_KEYSTORE_BASE64`：加密 PKCS12 文件的 base64。
- `WOZAI_ANDROID_KEYSTORE_PASSWORD`：对应的强密码。

正式 0.3.2 已使用仓库 Secrets 完成固定签名，历史证据见 Issue #26。连接器不读取或导出 Secrets，凭据只注入签名步骤。能编辑工作流的人员也能改变密钥使用代码，应限制仓库写权限；签名只允许手动主线任务或明确限定的维护者实现分支，普通 PR 不继承签名 Secrets。

## 本地和 CI 构建

本地在私人环境中提供 `WOZAI_ANDROID_KEYSTORE`（PKCS12 绝对路径）和 `WOZAI_ANDROID_KEYSTORE_PASSWORD`，然后执行：

```sh
python3 tools/build-signed-android.py
```

密钥库及密码只通过环境变量传入，密码不进入命令行或源码。脚本先核对别名和固定证书，再构建 `release`、相同证书的测试 APK 和用于拒绝检查的不同证书 debug APK，并执行 Lint、APK 签名、对齐、版本、不可调试及内嵌提交检查。临时密钥文件在退出时删除。

GitHub Actions 的 **Build Android with the persistent signing certificate** 使用相同脚本，仅签名步骤获得 Secrets。它不会退回临时调试证书；缺失、损坏或不同证书的密钥立即失败。未提供签名环境时，普通 `assembleRelease` 仍可构建 unsigned APK，普通开发预览维持临时调试签名；这些不是固定证书的发布候选。

向维护者提供的任何安装包（包括测试候选）都必须通过固定证书校验。临时签名的普通预览只用于内部隔离测试，不得作为覆盖升级包交付，也不以要求卸载／清数据绕过签名不符。

本轮原生 UI 候选使用 `WOZAI_ANDROID_CANDIDATE_ONLY=true python3 tools/build-signed-android.py --preview`，构建不可调试的固定签名 preview 及同证书测试 APK。脚本继续校验同一证书、版本、来源、对齐及 Lint。`android-fixed-signature-preview` artifact 只含客户端 APK 和公开签名信息；带 `INTERNAL` 前缀的测试／错误签名夹具不能分发给用户。受限分支工作流同时检查公开 0.3.2/code12 的确切字节覆盖升级、错误证书拒绝与新界面，均不发布 Release。

发布候选为 `android-signed-release` artifact；升级检查包含发布候选、相同证书测试 APK、公开 0.3.1 基线与故意使用不同证书的 debug 包。任何 artifact 都不包含私钥。不要把测试 APK 或不同证书包作为正式客户端分发。此流程不会自动覆盖现有 GitHub Release。

## 覆盖升级验收

当前源码为 `0.3.3-preview.2`、code13，版本源为 `i18n/config.json`。历史签名维护使用 code7→code8；该历史基线和已发布标签／附件保持原状。当前候选另核对正式 0.3.2/code12→code13，公开基线哈希和实际 schema7 记入 `tools/android-signing.json`，不重新编译旧发行包。两条路径分别严格检查其预期 schema（code7/8 为4），不将旧夹具的版本假设用于新基线。

API 26 / 34 自动检查安装的是公开 0.3.1 的确切字节，启动旧应用创建实际设备 UUID、SQLite 与 AndroidKeyStore 身份，然后在空白模拟器内写入测试消息、信任记录和设置。不同证书更新必须以 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 拒绝，拒绝后再次检查资料；同证书 code 8 通过 `adb install -r` 覆盖，重新启动应用，再检查 UID、聊天、会话、昵称、设备 UUID、公钥、旧签名证明、私钥继续签名、信任、界面设置及私有文件字节。测试只允许模拟器，发现已有消息或信任记录时拒绝写入测试资料。

旧 0.3.1 安卓草稿只在内存／Activity 保存状态中，未持久保存的草稿不属于本测试的验收范围；不能保证跨升级保留。不要以手工创建一个草稿偏好字段来宣称旧应用草稿升级通过。

对于不同证书的旧公共包，必须找到其原私钥才可能直接覆盖。当前这把密钥不能解决旧 CI 临时证书丢失的问题；原密钥缺失时仍需单独的数据迁移方案，不以卸载有记录的旧应用作为解决办法。真实用户手机的保留数据升级也不等于模拟器验收，证据和阻碍继续记录在 Issue #8。

## English: candidate delivery and upgrade boundaries

Every APK offered for installation, including an unpublished preview, must match the persistent SHA-256 certificate above. The original certificate was used by published 0.3.2/code12. Debug/UI fixtures with temporary signers are internal inputs, not acceptable upgrade candidates. Never ask a user to uninstall or clear data to bypass a mismatch.

The fixed-signature native candidate uses `WOZAI_ANDROID_CANDIDATE_ONLY=true python3 tools/build-signed-android.py --preview`. Only the signing step receives existing repository Secrets; missing or different keys fail closed, and the private temporary file is removed. A restricted owner-controlled implementation branch or a manual main run may invoke this workflow; arbitrary PRs cannot. The user artifact contains only the client and public signature metadata. Test APKs and wrong-signer fixtures remain explicitly internal.

The current source is preview.2/code13. Verification reuses the exact published code12 APK pinned by digest and schema7, and exercises in-place upgrades, preserved identity/trust/history/settings and rejection of the withdrawn incorrect signer. Historical code7/code8 uses schema4; each path checks its exact expected schema. Historical code7/code8 tests remain available. None of these workflows publishes a release. A device that already installed another certificate cannot be overwritten by the project certificate without the corresponding original signing authority; retain its data and resolve that case separately.

测试代码修正而客户端输入未变时，可以通过 `--preview --fixtures-for <完整提交SHA>` 重建测试 APK；必须先放入已校验的 `candidate.apk`。脚本强制核对祖先关系、应用／资源／构建输入无差异、客户端来源与固定证书，以及重新生成的优化 DEX 与原 APK 完全一致；不重新交付另一份客户端。升级输入清单分别记录测试提交与客户端提交。

For test-only corrections, `--preview --fixtures-for <full-commit-SHA>` can rebuild fixtures for an already verified `candidate.apk`. Reuse requires an ancestor commit, identical application/resource/build inputs, pinned provenance/certificate, and an exact match between regenerated optimized DEX and the original APK. The client bytes remain unchanged; the upgrade manifest records both fixture and client revisions.
