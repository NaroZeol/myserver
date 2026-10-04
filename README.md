# myserver

轻量 Android 服务器工具集，以服务器连接、监控和 SSH 终端为核心，想法是其中的一个可扩展模块。App 与配套服务端在同一仓库维护，服务器地址、账户与凭据由使用者配置。

```text
myserver/
├── android/                    # 原生 App、公共能力与功能模块
│   └── src/app/thoughts/mobile/
│       ├── core/               # UI、连接配置、主机核验与设备密钥
│       ├── shell/              # 应用设置
│       └── modules/            # terminal、server、inbox、thoughts
├── server/
│   ├── service.py              # RPC 分发、权限与服务器状态
│   ├── database.py             # 数据库生命周期
│   ├── cli.py                  # 初始化、检查与备份
│   ├── ssh_gateway.py          # 受限 SSH RPC 网关
│   ├── system_metrics.py       # CPU、内存、磁盘与运行时间
│   ├── modules/thoughts/       # 想法记录、历史与 Gist 发布
│   ├── modules/inbox/          # 私有收件箱、文件传输与临时管理页
│   ├── backups.py              # 数据库与文件的一致备份
│   └── tests/
└── deploy/                     # 部署、设备登记、备份与 systemd
```

- [App 构建与安装](android/README.md)
- [服务端部署与维护](server/README.md)
- [SSH 终端](android/TERMINAL.md)
- [想法功能](server/modules/thoughts/README.md)
- [私有收件箱](docs/inbox.md)
- [安全边界](SECURITY.md)

## 使用

- **服务器**：默认首页，集中管理连接、监控与终端。监控支持手动或 2 / 5 / 10 / 30 / 60 秒刷新，只在页面可见时自动采样。
- **收件箱**：手机系统分享、电脑浏览器与服务器命令共用的私有空间。支持文字、链接、多附件、可恢复传输，以及下载、另存、转发和 APK 系统安装。
- **想法**：列表和编辑属于同一栏目，保存后回到列表；编辑中退出会保留草稿。自动同步与手动同步均可选。
- **设置**：监控偏好、模块设置、数据操作和应用信息。

首次授权可同时登记服务与终端的独立密钥，后续使用密钥连接，密码不落盘。终端支持内置模拟键盘与系统输入法切换。

## 扩展功能

Android 公共层 `core/Feature.Host` 提供界面、导航、执行器和连接配置。模块在 `modules/<name>/` 实现 `Feature`，由 `MainActivity` 注册。终端独立使用 SSH shell；编译检查验证公共层和终端不依赖想法源码。连接配置、设备授权和 RPC 位于公共层；服务器、终端和设置均不依赖想法实现。模块通过 `Feature.renderSettings` 提供自己的设置，想法的数据与同步操作使用独立的 `ThoughtsHost` 合约。

服务端统一运行在 `~/.local/share/myserver/`，App 通过 SSH 按需访问，使用公共数据库和设备权限；用户级 systemd 负责发布与备份定时任务。电脑端可用命令临时启动仅监听本机的收件箱 HTTP 管理页，经 SSH 转发后凭本次令牌登录。功能在 `server/modules/<name>/` 实现，在 `server/modules/__init__.py` 注册，声明自己的 RPC 路由与权限。公共认证、服务器指标与备份不依赖想法表；系统状态在 `modules` 字段下汇总模块数据。

部署参数通过 App 设置、环境变量和 CI secrets 注入，示例见 [Android 配置](android/.env.example) 与 [部署配置](deploy/.env.example)。

## 验证与发布

`android.yml` 按 Android 文件和原生 SSH 测试依赖触发，生成 `myserver.apk`，在 Android 10 / 15 上验证。`server.yml` 按服务端和部署文件触发，验证 RPC、授权、发布、配置与部署回滚。说明文档和仅网页的改动不会触发 APK 构建。两套模拟器测试通过后，CI 将已验证的 APK 发布到 GitHub Releases；主线与开发分支共用包名和发布签名，App 可选择更新分支并覆盖安装，详见 [App 更新](docs/app-updates.md)。CI 不连接生产服务器，不自动部署。

博客等读取端只消费 Gist 中已发布的想法，不请求管理服务。
