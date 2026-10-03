# Android 发布签名

正式 APK 使用以下发布证书：

- 证书主题和签发者：`CN=myserver`。
- 算法：RSA 3072 位，SHA256withRSA。
- 证书 SHA-256：`89E23DD6AE45585ACEF5C9A37DED4F1BB852C8E6E6E83C87B315A690CE74556C`。

以上均为公开证书信息；私钥和密码仅存放在受保护的本机文件及 GitHub Actions secrets 中。

## 安装

相同 application ID 与签名证书的版本可以覆盖更新。证书不同时，需先同步或导出本机数据，再卸载重装；卸载会清除本机数据和设备密钥，之后需重新登记。预览包使用独立应用 ID 和临时测试证书。

## 构建

本机通过 `MYSERVER_KEYSTORE`、`MYSERVER_KEYSTORE_PASSWORD_FILE` 和 `MYSERVER_KEY_ALIAS` 注入签名配置；默认别名为 `myserver`，不在仓库中保存实际路径或密码。

GitHub Actions 使用 `MYSERVER_KEYSTORE_BASE64`、`MYSERVER_KEYSTORE_PASSWORD` secrets，以及 `MYSERVER_KEY_ALIAS` variable。更改变量不会重新生成密钥；后续发布需继续使用同一份 keystore。

下载后可用 Android build-tools 的 `apksigner verify --verbose --print-certs myserver.apk` 查看证书，与上面的 SHA-256 核对。自行构建并使用自有 keystore 时，证书指纹应以自己的发布配置为准。
