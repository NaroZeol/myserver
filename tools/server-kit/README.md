# Server Kit

轻量 Android 服务器工具集。公共层提供服务器配置、主机身份核验、SSH 与设备密钥；想法、终端和服务概览是独立的功能入口。

```text
tools/server-kit/
├── android/                  # App、公共能力、各功能模块、测试和构建
│   ├── src/app/thoughts/mobile/
│   │   ├── MainActivity.java # 应用组装、导航与生命周期入口
│   │   ├── core/             # 通用界面、Feature 契约、SSH 与连接配置
│   │   ├── shell/            # 应用级设置
│   │   └── modules/
│   │       ├── thoughts/     # 记录、草稿、数据库、同步与受限 RPC
│   │       ├── terminal/     # SSH 终端、输入、选区与渲染
│   │       └── server/       # 服务概览及当前服务的连接入口
│   ├── assets/terminal/      # 随 App 离线打包的终端资源
│   └── test/                # 按 Java 包组织的原生测试与渲染测试
└── modules/
    └── thoughts/
        ├── server/          # 想法 API、SQLite、Gist 发布与 Web 管理
        └── deploy/          # 此模块的部署、授权、备份与 systemd 文件
```

- [App 构建、安装包与开发](android/README.md)
- [想法模块及服务器部署](modules/thoughts/README.md)
- [终端操作与实现](android/TERMINAL.md)

App 的默认安装包为 `android/build/server-kit.apk`。博客仍位于仓库根目录，工具集位于 `tools/`，由 Jekyll 排除；博客正文和前端不属于 App 的构建输入。

## 模块边界

`core/Feature.Host` 只提供界面、导航、执行器和服务器配置，公共 UI 与 SSH 不依赖想法数据库。想法专用接口放在 `modules/thoughts/ThoughtsHost`，受限 RPC 密钥由想法模块显式提供；终端只依赖公共能力，独立编译检查会防止它反向依赖想法。

`MainActivity` 负责组装已安装模块，并协调现有想法的同步、导出和本机数据操作。服务概览目前复用想法后端的 `system.read` 能力，应用设置也集成想法同步设置；这两处依赖是显式的，不代表已有通用服务器管理 API。

新增工具通常在 `android/src/app/thoughts/mobile/modules/<name>/` 实现 `Feature`，在应用入口注册。需要配套服务时，再在 `modules/<name>/` 放该模块的后端和部署工具。模块使用自己的数据与授权标识，共享连接核验和非导出设备密钥能力。原生项目保持直接编译，不增加框架或 Gradle 模块依赖。

## 兼容与 CI

Android application ID `app.thoughts.mobile`、主入口组件、数据库名、偏好设置名和已登记密钥别名保持兼容，因此新目录下的 APK 可以覆盖安装。Java 功能包的移动不迁移用户数据。服务器的想法服务运行目录、协议和 systemd 单元属于该模块，也保持兼容。

`.github/workflows/server-kit-android.yml` 仅响应 Android 源码、资源、构建/测试脚本和 SSH 集成测试依赖；说明文档、博客和无关服务文件不会触发 App 构建。想法后端由 `thoughts-server.yml` 单独验证，博客由 `blog.yml` 验证。
