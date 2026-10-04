# myserver · Android

[工具集与模块结构](../README.md) · [终端说明](TERMINAL.md) · [服务端](../server/README.md)

Android 8.0+。App 不预置服务器地址、用户名或凭据，安装后在服务器首页配置。application ID 为 `app.thoughts.mobile`。

[收件箱](../docs/inbox.md) 接收系统分享的文字、链接和多文件，使用独立 SSH 传输队列，支持中断恢复及 APK 系统安装。文件传输使用 `dataSync` 前台服务，终端会话使用自己的 `specialUse` 服务，二者生命周期独立。

## 构建与安装包

需要 JDK 17、Android platform 35 和 build-tools 35.0.0，不需要 Gradle。从仓库根目录运行：

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
export ANDROID_BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
export MYSERVER_KEYSTORE=/path/to/private/signing.jks
export MYSERVER_KEYSTORE_PASSWORD_FILE=/path/to/private/signing-password
export MYSERVER_KEY_ALIAS=myserver
bash android/build.sh
bash android/check-boundaries.sh
```

安装包：`android/build/myserver.apk`。签名别名由 `MYSERVER_KEY_ALIAS` 注入，默认 `myserver`。当前发布证书为 `CN=myserver`，不包含个人名称、邮箱或服务器信息。构建目录已被 Git 忽略，私钥和密码文件不进入仓库。

GitHub Actions 在 Android 10 / 15 测试全部通过后，使用现有发布证书重新签署已测试 APK，并发布到 GitHub Releases。`main` 与开发分支均使用 `myserver` 名称和 `app.thoughts.mobile` 包名，可在 App 设置中选择更新分支并覆盖安装。CI 版本号跨分支递增，版本名为 Manifest 基础版本加构建编号，如 `1.12.0+123`。PR 和测试任务不读取发布密钥；缺少签名 secrets 时停止发布。环境变量、更新来源和发布协议见 [App 更新](../docs/app-updates.md)。

本机参数可参考 [.env.example](.env.example)，复制到 `android/.env` 并填写后，显式加载：

```sh
set -a
. ./android/.env
set +a
bash android/build.sh
```

`.env` 不提交；脚本不会自动读取或执行它。私钥和密码仍使用受保护文件或 CI secrets。发布证书指纹与安装要求见 [签名说明](SIGNING.md)。

## 验证

`build.sh` 递归编译 `src/`，`test.sh` 递归编译 `test/src/`。终端测试与终端源码使用相同 Java 包，使会话、渲染器和授权实现继续保持包内可见。`check-boundaries.sh` 单独编译公共层、服务器、终端、收件箱与设置，不提供想法源码或已编译的想法类，验证模块独立性。

Android 10 / 15 模拟器覆盖离线草稿、同步、服务器身份、设备密钥登记、一次授权后的服务与终端连接、监控刷新调度、真实 SSH PTY、内置键盘/Fn/系统输入法切换、tmux 触摸滚动、锁屏/后台、页面重建、旋转、选区复制及布局边界。收件箱测试覆盖私有分享编辑、多附件、真实 SSH 分块与续传、前台服务队列、文件摘要、只读 Provider 与目标服务器变化。CI 使用临时服务器和随机密码，不访问生产环境或 Gist。测试产物保存在 `build/test/`。

终端渲染交互测试（开发时安装 Playwright 及 Chromium）：

```sh
node android/test/terminal-renderer.mjs
```

可通过 `PLAYWRIGHT_MODULE` 指向已有 Playwright 的 `index.mjs`。终端依赖随 APK 打包，维护更新使用 `vendor-terminal.sh`，正常构建不依赖 npm 或 CDN。
