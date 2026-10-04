# Android 固定签名和覆盖升级

固定证书 SHA-256：`47a10fd5a882ab26c9a11fcc61df3b41f7c41017746fc6840cfbe581ca52c1dd`。
这是当前 0.3.1 安装包使用的原有证书（CN=Android Debug），有效期至 2056-09-24。将原私钥导入强密码 PKCS12 并改变别名／密钥库密码不会改变证书；没有创建新的发行身份。签名密钥与应用内 AndroidKeyStore 设备身份是两套独立密钥。

## 私钥保管

PKCS12 别名为 `nearbyim`，密钥密码与密钥库密码一致。用户应分别保存加密密钥库和恢复资料，并在独立的私人存储保留备份。当前执行环境不是长期备份。不得把密钥、密码、base64 内容提交到仓库或上传到 Release、Issues、Actions artifacts；`.p12`、`.pfx`、`.keystore`、`.jks` 及 `.signing/` 已忽略。

GitHub 仓库 Settings → Secrets and variables → Actions 中需要两个 Secrets：

- `WOZAI_ANDROID_KEYSTORE_BASE64`：加密 PKCS12 文件的 base64。
- `WOZAI_ANDROID_KEYSTORE_PASSWORD`：对应的强密码。

当前集成访问 Secrets 接口返回 `403 Resource not accessible by integration`，用户需通过自己的 GitHub 设置页添加。代码已经准备，未配置 Secrets 前不能宣称远端长期签名流程已经验收。能编辑仓库工作流的人员也能改变密钥使用代码，应限制仓库写权限；签名工作流仅允许手动触发。

## 本地和 CI 构建

本地在私人环境中提供 `WOZAI_ANDROID_KEYSTORE`（PKCS12 绝对路径）和 `WOZAI_ANDROID_KEYSTORE_PASSWORD`，然后执行：

```sh
python3 tools/build-signed-android.py
```

密钥库及密码只通过环境变量传入，密码不进入命令行或源码。脚本先核对别名和固定证书，再构建 `release`、相同证书的测试 APK 和用于拒绝检查的不同证书 debug APK，并执行 Lint、APK 签名、对齐、版本、不可调试及内嵌提交检查。临时密钥文件在退出时删除。

GitHub Actions 的 **Build Android with the persistent signing certificate** 使用相同脚本，仅签名步骤获得 Secrets。它不会退回临时调试证书；缺失、损坏或不同证书的密钥立即失败。未提供签名环境时，普通 `assembleRelease` 仍可构建 unsigned APK，普通开发预览维持临时调试签名；这些不是固定证书的发布候选。

发布候选为 `android-signed-release` artifact；升级检查包含发布候选、相同证书测试 APK、公开 0.3.1 基线与故意使用不同证书的 debug 包。任何 artifact 都不包含私钥。不要把测试 APK 或不同证书包作为正式客户端分发。此流程不会自动覆盖现有 GitHub Release。

## 覆盖升级验收

当前源码 versionCode 为 8，高于 0.3.1 已发布包的 7，versionName 保持 0.3.1。本轮是签名维护，既有 `v0.3.1` 标签和发布资产保持原状。后续发布继续单调递增 versionCode，并按发布版本调整 versionName。

API 26 / 34 自动检查安装的是公开 0.3.1 的确切字节，启动旧应用创建实际设备 UUID、SQLite 与 AndroidKeyStore 身份，然后在空白模拟器内写入测试消息、信任记录和设置。不同证书更新必须以 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 拒绝，拒绝后再次检查资料；同证书 code 8 通过 `adb install -r` 覆盖，重新启动应用，再检查 UID、聊天、会话、昵称、设备 UUID、公钥、旧签名证明、私钥继续签名、信任、界面设置及私有文件字节。测试只允许模拟器，发现已有消息或信任记录时拒绝写入测试资料。

旧 0.3.1 安卓草稿只在内存／Activity 保存状态中，未持久保存的草稿不属于本测试的验收范围；不能保证跨升级保留。不要以手工创建一个草稿偏好字段来宣称旧应用草稿升级通过。

对于不同证书的旧公共包，必须找到其原私钥才可能直接覆盖。当前这把密钥不能解决旧 CI 临时证书丢失的问题；原密钥缺失时仍需单独的数据迁移方案，不以卸载有记录的旧应用作为解决办法。真实用户手机的保留数据升级也不等于模拟器验收，证据和阻碍继续记录在 Issue #8。
