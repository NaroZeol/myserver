# myserver · Android

[工具集与模块结构](../README.md) · [终端说明](TERMINAL.md) · [服务端](../server/README.md)

Android 8.0+，当前版本 1.9.0。App 不预置服务器地址、用户名或凭据，安装后在服务器首页配置。application ID 为 `app.thoughts.mobile`。

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

GitHub Actions 推荐 secrets `MYSERVER_KEYSTORE_BASE64` 和 `MYSERVER_KEYSTORE_PASSWORD`。自有 keystore 使用其他别名时，设置仓库 Actions variable `MYSERVER_KEY_ALIAS`，无需修改工作流。仅主线构建可以读取正式签名；其他分支、PR 或未配置 secrets 时生成 `.preview` 包，不能覆盖正式版；artifact 名为 `myserver-android-<release|preview>-api<29|35>`。

本机参数可参考 [.env.example](.env.example)，复制到 `android/.env` 并填写后，显式加载：

```sh
set -a
. ./android/.env
set +a
bash android/build.sh
```

`.env` 不提交；脚本不会自动读取或执行它。私钥和密码仍使用受保护文件或 CI secrets。发布证书指纹与安装要求见 [签名说明](SIGNING.md)。

## 验证

`build.sh` 递归编译 `src/`，`test.sh` 递归编译 `test/src/`。终端测试与终端源码使用相同 Java 包，使会话、渲染器和授权实现继续保持包内可见。`check-boundaries.sh` 单独编译公共层、服务器、终端与设置，不提供想法源码或已编译的想法类，验证模块独立性。

Android 10 / 15 模拟器覆盖离线草稿、同步、服务器身份、设备密钥登记、一次授权后的服务与终端连接、监控刷新调度、真实 SSH PTY、内置键盘/系统输入法切换、旋转、选区复制及布局边界。CI 使用临时服务器和随机密码，不访问生产环境或 Gist。测试产物保存在 `build/test/`。

终端渲染交互测试（开发时安装 Playwright 及 Chromium）：

```sh
node android/test/terminal-renderer.mjs
```

可通过 `PLAYWRIGHT_MODULE` 指向已有 Playwright 的 `index.mjs`。终端依赖随 APK 打包，维护更新使用 `vendor-terminal.sh`，正常构建不依赖 npm 或 CDN。
