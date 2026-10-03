# myserver

轻量 Android 服务器工具集，提供 SSH 终端、服务器状态和公开想法管理。App 与配套服务端在同一仓库维护，服务器地址、账户与凭据由使用者配置。

```text
myserver/
├── android/                    # 原生 App、公共能力与功能模块
│   └── src/app/thoughts/mobile/
│       ├── core/               # UI、连接配置、主机核验与设备密钥
│       ├── shell/              # 应用设置
│       └── modules/            # terminal、server、thoughts
├── server/
│   ├── app.py                  # HTTP 入口、登录会话与服务器状态
│   ├── core.py                 # 数据库与请求授权
│   ├── ssh_gateway.py          # 受限 SSH RPC 网关
│   ├── system_metrics.py       # CPU、内存、磁盘与运行时间
│   ├── modules/thoughts/       # 想法记录、历史与 Gist 发布
│   └── tests/
└── deploy/                     # 部署、设备登记、备份与 systemd
```

- [App 构建与安装](android/README.md)
- [服务端部署与维护](server/README.md)
- [SSH 终端](android/TERMINAL.md)
- [想法功能](server/modules/thoughts/README.md)
- [安全边界](SECURITY.md)

## 扩展功能

Android 公共层 `core/Feature.Host` 提供界面、导航、执行器和连接配置。模块在 `modules/<name>/` 实现 `Feature`，由 `MainActivity` 注册。终端独立使用 SSH shell；编译检查验证公共层和终端不依赖想法源码。当前服务页与想法同步共享受限 RPC 的设备身份，应用设置通过 `ThoughtsHost` 管理同步选项。

服务端统一运行在 `~/.local/share/myserver/`，使用用户级 `myserver.service`、公共数据库和设备权限。功能在 `server/modules/<name>/` 实现，在 `server/modules/__init__.py` 注册，声明自己的 RPC 路由与权限。公共认证、服务器指标与备份不依赖想法表；系统状态在 `modules` 字段下汇总模块数据。

部署参数通过 App 设置、环境变量和 CI secrets 注入，示例见 [Android 配置](android/.env.example) 与 [部署配置](deploy/.env.example)。

## 验证与发布

`android.yml` 按 Android 文件和原生 SSH 测试依赖触发，生成 `myserver.apk`，在 Android 10 / 15 上验证。`server.yml` 按服务端和部署文件触发，验证 API、授权、发布和配置。说明文档不会触发 APK 构建。CI 不连接生产服务器，不自动部署。

博客等读取端只消费 Gist 中已发布的想法，不请求管理服务。
