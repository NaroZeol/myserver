# Android 发布签名

从 1.7.1（versionCode 12）开始，正式 APK 使用全新签名密钥与证书，替换原带有个人标签的证书。未保留旧签名的更新兼容链。

- 证书主题和签发者：`CN=myserver`。
- 算法：RSA 3072 位，SHA256withRSA。
- 证书 SHA-256：`89E23DD6AE45585ACEF5C9A37DED4F1BB852C8E6E6E83C87B315A690CE74556C`。

以上均为公开证书信息；私钥和密码仅存放在受保护的本机文件及 GitHub Actions secrets 中。

## 安装

已安装旧证书版本时，先同步或导出本机草稿和未同步记录，再卸载旧 App、安装新 APK。卸载会删除本机数据和 Android Keystore 中的设备密钥，安装后需重新配置服务器并登记想法或终端密钥。服务器已有记录与 Gist 不受签名更换影响。

此后以这套新证书签名的更新可直接覆盖 1.7.1，无需再次重装。预览版仍使用独立应用 ID 和临时测试证书。

## 构建

本机通过 `SERVER_KIT_KEYSTORE`、`SERVER_KIT_KEYSTORE_PASSWORD_FILE` 和 `SERVER_KIT_KEY_ALIAS` 注入签名配置；默认别名为 `myserver`，不在仓库中保存实际路径或密码。

GitHub Actions 使用 `SERVER_KIT_KEYSTORE_BASE64`、`SERVER_KIT_KEYSTORE_PASSWORD` secrets，以及 `SERVER_KIT_KEY_ALIAS` variable。更改变量不会重新生成密钥；后续发布需继续使用同一份 keystore。

下载后可用 Android build-tools 的 `apksigner verify --verbose --print-certs server-kit.apk` 查看证书，与上面的 SHA-256 核对。自行构建并使用自有 keystore 时，证书指纹应以自己的发布配置为准。
