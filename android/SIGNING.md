# Android 发布签名

所有分支的 APK 使用相同的包名 `app.thoughts.mobile` 和以下发布证书：

- 证书主题和签发者：`CN=myserver`。
- 算法：RSA 3072 位，SHA256withRSA。
- 证书 SHA-256：`89E23DD6AE45585ACEF5C9A37DED4F1BB852C8E6E6E83C87B315A690CE74556C`。

以上均为公开证书信息；私钥和密码仅存放在受保护的本机文件及 GitHub Actions secrets 中。

## 安装

相同 application ID 与签名证书的版本可以覆盖更新。开发分支与主线共用应用身份，切换分支后继续保留本机数据和设备密钥；目标版本的 `versionCode` 必须高于已安装版本。证书不同时，需先同步或导出本机数据，再卸载重装；卸载会清除本机数据和设备密钥，之后需重新登记。Actions 的内部测试 APK 使用一次性测试证书，不作为更新源。

## 构建

本机通过 `MYSERVER_KEYSTORE`、`MYSERVER_KEYSTORE_PASSWORD_FILE` 和 `MYSERVER_KEY_ALIAS` 注入签名配置；默认别名为 `myserver`，不在仓库中保存实际路径或密码。

GitHub Actions 使用 `MYSERVER_KEYSTORE_BASE64`、`MYSERVER_KEYSTORE_PASSWORD` secrets，以及 `MYSERVER_KEY_ALIAS` variable（默认 `myserver`）。后续发布必须继续使用现有 keystore，不能为了切换分支重新生成密钥。发布任务只有在两套模拟器测试通过后才能读取发布密钥，PR 不读取这些 secrets。

下载后可用 Android build-tools 的 `apksigner verify --verbose --print-certs myserver.apk` 查看证书，与上面的 SHA-256 核对。自行构建并使用自有 keystore 时，证书指纹应以自己的发布配置为准。

更新分支、安装包验证和 CI 发布规则见 [App 更新](../docs/app-updates.md)。
