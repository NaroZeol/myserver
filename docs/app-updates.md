# App 更新与发布

App 从构建时指定的公开 GitHub 仓库检查和下载更新，不通过自己的服务器，也不需要在手机或服务端保存 GitHub token。安装始终由 Android 系统确认。

在「设置 → 应用更新」选择更新分支，检查后下载并安装。默认跟随构建时的源码分支。默认在打开 App 时检查更新，距上次成功检查至少十二小时；可以关闭自动检查或随时手动检查。下载交由 Android 管理，退出页面后继续，返回可查看进度或取消。安装成功会清理旧安装包；取消系统安装时保留文件以便重试。

## 更新分支

`main` 与开发分支统一使用 `myserver` 名称、`app.thoughts.mobile` 包名和现有发布证书，覆盖安装时保留本机数据与设备密钥。App 按选定分支查找最新构建，不会自动切换到其他分支。开发分支可直接发布，无需先合并到 `main`。

安装包来自 GitHub Releases。Actions 的临时测试 APK 使用一次性测试证书，仅供自动测试，不能用于日常覆盖更新。

覆盖安装要求包名和签名兼容，且不能降低 Android `versionCode`。CI 使用 `100000 + github.run_number`，跨分支依次递增；版本名为 Manifest 基础版本加 `+` 和构建编号，如 `1.12.0+123`。重新运行同一次工作流保持原编号。若目标分支的构建早于当前已安装版本，需要先为该分支生成新构建。后续发布继续使用现有签名证书。参见 [Android 版本规则](https://developer.android.com/studio/publish/versioning) 与 [签名说明](../android/SIGNING.md)。

## CI 权限与发布顺序

`android.yml` 只在 Android 源码、资源、构建脚本和原生 SSH 测试依赖变化时触发；仅网页或说明文档的修改不会生成 APK。标签推送不触发 Android 工作流。

1. Android 10 / 15 测试使用独立的临时签名，无权读取发布密钥。
2. 两个平台全部通过后，单独的发布任务获取已测试的 API 35 APK；只重新签名，不重新编译。
3. 发布任务验证包名、版本、源码仓库、分支、提交、签名和 SHA-256，然后在草稿 Release 上传 APK 与校验清单。
4. 上传前后均检查远端分支仍指向本次提交；分支已前进则不公开旧构建。上传全部成功后才公开 Release。

发布只接受仓库分支的 `push` 或 `workflow_dispatch`，PR 不会接触长期签名或发布权限。发布任务单独获得 `contents: write`；其余任务保持 `contents: read`。同一构建重新运行时不会覆盖已发布 APK。GitHub 的公开 Release API 不要求客户端持有凭据，参见 [Release API](https://docs.github.com/en/rest/releases/releases)。

发布前需在仓库 Actions 配置：

| 用途 | Secrets | 可选 variable |
|---|---|---|
| 所有分支的发布签名 | `MYSERVER_KEYSTORE_BASE64`、`MYSERVER_KEYSTORE_PASSWORD` | `MYSERVER_KEY_ALIAS`，默认 `myserver` |

缺少密钥时发布失败，不会退回测试密钥或生成替代证书。私钥只在发布任务的临时目录展开，并在任务结束时删除；应另行保留受保护的离线备份。

## 构建注入

仓库源码不保存个人仓库地址。CI 通过以下环境变量生成 APK 内的 `assets/update-source.json`：

```sh
MYSERVER_UPDATE_REPOSITORY=OWNER/REPOSITORY
MYSERVER_UPDATE_BRANCH=feature/example
MYSERVER_UPDATE_COMMIT=<完整的40位提交SHA>
MYSERVER_VERSION_CODE=<正整数>
MYSERVER_VERSION_NAME=1.2.0+123
```

本机构建未指定更新仓库时，更新来源为空，不会默认连接其他人的仓库。构建注入只修改被忽略的 `android/build/`，不修改提交中的 Manifest、资源或原始 assets。

## 发布协议

所有分支的发布标签为 `android-stable-<version_code>`，原始分支名称保留在清单中。Release 设为非 prerelease，并标记为 latest；App 仍按清单中的分支筛选更新。schema 1 的 `channel` 字段固定为 `stable`。

每个 Release 包含 `myserver.apk`、`update.json` 两个附件。`update.json` 使用 schema 1：

```json
{
  "schema": 1,
  "repository": "OWNER/REPOSITORY",
  "channel": "stable",
  "branch": "feature/example",
  "commit": "完整的40位提交SHA",
  "version_code": 100123,
  "version_name": "1.2.0+123",
  "min_sdk": 26,
  "package_name": "app.thoughts.mobile",
  "sha256": "APK的SHA256",
  "size": 500000,
  "certificate_sha256": "签名证书的SHA256",
  "apk_url": "https://github.com/OWNER/REPOSITORY/releases/download/TAG/myserver.apk",
  "release_url": "https://github.com/OWNER/REPOSITORY/releases/tag/TAG",
  "published_at": "2026-01-01T00:00:00+00:00"
}
```

Release 描述同时包含 `<!-- myserver-update` 与 `-->` 包围的同一份 JSON，便于一次列表请求按分支和版本筛选候选，避免逐个读取附件。客户端下载后仍需核对文件大小、摘要、包名、版本和签名，不以 Release 标题或下载文件名代替校验。

可在本地运行 `python3 android/test/release_checks.py` 验证跨分支应用身份、源码注入、过期构建、发布顺序和重跑保护；这些测试不访问 GitHub，也不需要私钥。
