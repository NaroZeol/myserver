# Server Kit · Android

[工具集与模块结构](../README.md) · [终端说明](TERMINAL.md) · [想法服务](../modules/thoughts/README.md)

Android 8.0+，当前版本 1.6.1。App 不预置服务器地址、用户名或凭据，安装后在服务页配置。application ID 为 `app.thoughts.mobile`，保留以兼容已有安装、数据库和 Android Keystore。

## 构建与安装包

需要 JDK 17、Android platform 35 和 build-tools 35.0.0，不需要 Gradle。从仓库根目录运行：

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
export ANDROID_BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
export SERVER_KIT_KEYSTORE=/path/to/private/signing.jks
export SERVER_KIT_KEYSTORE_PASSWORD_FILE=/path/to/private/signing-password
bash tools/server-kit/android/build.sh
bash tools/server-kit/android/check-boundaries.sh
```

安装包：`tools/server-kit/android/build/server-kit.apk`。签名别名仍为 `thoughts`；使用原签名即可覆盖更新。构建目录已被 Git 忽略，私钥和密码文件不进入仓库。构建仍接受旧的 `THOUGHTS_KEYSTORE` / `THOUGHTS_KEYSTORE_PASSWORD_FILE` 环境变量。

GitHub Actions 推荐 secrets `SERVER_KIT_KEYSTORE_BASE64` 和 `SERVER_KIT_KEYSTORE_PASSWORD`，也兼容已有 `THOUGHTS_KEYSTORE_BASE64` / `THOUGHTS_KEYSTORE_PASSWORD`。未配置时生成 `.preview` 包，不能覆盖正式版；artifact 名为 `server-kit-android-<release|preview>-api<29|35>`。

## 验证

`build.sh` 递归编译 `src/`，`test.sh` 递归编译 `test/src/`。终端测试与终端源码使用相同 Java 包，使会话、渲染器和授权实现继续保持包内可见。`check-boundaries.sh` 单独编译公共层和终端，不提供想法源码或已编译的想法类，验证模块独立性。

Android 10 / 15 模拟器覆盖离线草稿、同步、服务器身份、设备密钥登记、真实 SSH PTY、输入、旋转、键盘、选区复制及布局边界。CI 使用临时服务器和随机密码，不访问生产环境或 Gist。测试产物保存在 `build/test/`。

终端渲染交互测试（开发时安装 Playwright 及 Chromium）：

```sh
node tools/server-kit/android/test/terminal-renderer.mjs
```

可通过 `PLAYWRIGHT_MODULE` 指向已有 Playwright 的 `index.mjs`。终端依赖随 APK 打包，维护更新使用 `vendor-terminal.sh`，正常构建不依赖 npm 或 CDN。
