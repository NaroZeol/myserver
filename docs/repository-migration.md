# 独立仓库迁移

Android App 与配套服务端从博客仓库拆出，在同一仓库维护、验证与发布。原仓库的文章、主题、页面、Gist 读取逻辑不属于工具集。

## 历史来源

来源为 `NaroZeol/narozeol.github.io` 的工具开发分支，截至 `4529ab2d82f005d784f3e048f7a3e5167d02f606`。仅提取 `tools/thoughts/`、`tools/server-kit/` 及两项工具 CI 的相关历史；未带入博客内容或已废弃分支。提取保留 14 个相关提交的作者身份、日期与说明；作者与提交者邮箱统一使用 GitHub 隐藏邮箱。因父历史、文件集合和邮箱变化，提交 SHA 会改变。

旧历史中的目录结构保持原样；迁移提交将工具提升到仓库根目录。可用 `git log --follow -- <文件>` 追踪文件。主线已有的 Actions 依赖更新与 Dependabot 分组设置也已带入。

## 兼容边界

- App 保持 `app.thoughts.mobile`、原签名、数据库、偏好设置和设备密钥别名；可覆盖安装，已有数据不需要导出迁移。
- 服务端保留 `~/.local/share/thoughts/`、systemd 单元、受限 SSH 协议和 Gist 配置；仓库迁移无需重新部署或登记设备。
- 服务器地址、用户名和凭据继续由使用者配置。GitHub 写入凭据仅保存在部署服务器。
- Android 与服务端各自按文件路径触发 CI；文档改动不会触发 APK 构建。
- 正式构建使用 GitHub Actions secrets `SERVER_KIT_KEYSTORE_BASE64` 与 `SERVER_KIT_KEYSTORE_PASSWORD`，签名材料不进入 Git；没有 secrets 时构建独立预览包。
- 博客读取约束测试留在博客仓库；工具的服务端测试无需检出博客。

仓库沿用来源代码的 MIT 许可，第三方依赖声明保留在 `android/assets/THIRD_PARTY_NOTICES.txt` 及终端资源目录。
