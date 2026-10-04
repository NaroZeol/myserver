# Android 发布签名

正式 APK 使用以下发布证书：

- 证书主题和签发者：`CN=myserver`。
- 算法：RSA 3072 位，SHA256withRSA。
- 证书 SHA-256：`89E23DD6AE45585ACEF5C9A37DED4F1BB852C8E6E6E83C87B315A690CE74556C`。

以上均为公开证书信息；私钥和密码仅存放在受保护的本机文件及 GitHub Actions secrets 中。

## 安装

相同 application ID 与签名证书的版本可以覆盖更新。证书不同时，需先同步或导出本机数据，再卸载重装；卸载会清除本机数据和设备密钥，之后需重新登记。预览包使用独立应用 ID 和长期预览证书，与正式版分别保留数据；所有开发分支使用同一份预览证书。Actions 的内部测试 APK 使用一次性测试证书，不作为更新源。

## 构建

本机通过 `MYSERVER_KEYSTORE`、`MYSERVER_KEYSTORE_PASSWORD_FILE` 和 `MYSERVER_KEY_ALIAS` 注入签名配置；默认别名为 `myserver`，不在仓库中保存实际路径或密码。

GitHub Actions 使用 `MYSERVER_KEYSTORE_BASE64`、`MYSERVER_KEYSTORE_PASSWORD` secrets，以及 `MYSERVER_KEY_ALIAS` variable。预览签名使用独立的 `MYSERVER_PREVIEW_KEYSTORE_BASE64`、`MYSERVER_PREVIEW_KEYSTORE_PASSWORD` secrets，以及 `MYSERVER_PREVIEW_KEY_ALIAS` variable（默认 `myserver-preview`）。更改变量不会重新生成密钥；同一渠道的后续发布需继续使用同一份 keystore。发布任务只有在两套模拟器测试通过后才能读取对应密钥，PR 不读取这些 secrets。

下载后可用 Android build-tools 的 `apksigner verify --verbose --print-certs myserver.apk` 查看证书，与上面的 SHA-256 核对。自行构建并使用自有 keystore 时，证书指纹应以自己的发布配置为准。

更新渠道、安装包验证和 CI 发布规则见 [App 更新](../docs/app-updates.md)。
