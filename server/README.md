# myserver 服务端

[返回项目](../README.md) · [想法模块](modules/thoughts/README.md)

App 通过 SSH 执行受限命令，网关验证设备与权限后直接调用业务模块；文件经独立二进制通道分块传输。常规访问不需要常驻 API 进程。电脑端可用命令临时开启仅监听本机的收件箱管理页，见[收件箱使用说明](../docs/inbox.md)。系统状态、公共数据库与备份独立于想法模块。

## 部署

需要 Linux、Python 3.10+、OpenSSH、systemd 与 `flock`。运行时只使用 Python 标准库，不需要 pip。App 使用现有 SSH 端口；SSH 账户与部署账户相同。

在开发电脑上，从仓库根目录运行：

```sh
export MYSERVER_SSH_TARGET=my-server
bash deploy/stage.sh
ssh "$MYSERVER_SSH_TARGET" 'bash ~/.local/share/myserver/staged/deploy/activate.sh'
```

`stage.sh` 接受 SSH 别名或 `user@host`，命令行参数优先于环境变量。脚本上传完整待启用包，不修改正在运行的源码，也不上传 `.env`、数据库或 APK。

激活时暂停新请求，等待在途请求完成，停止定时任务并备份数据库，再替换源码、初始化并检查数据库。成功后启动定时任务；失败时恢复源码、数据库及原定时任务状态。回滚快照保存在私有的 `archives/release-*` 目录，确认新版本正常后可自行清理。维护期间 App 保留本地记录，稍后可重试同步。

单元安装到 `${XDG_CONFIG_HOME:-~/.config}/systemd/user/`，后续部署不需要 sudo。配置示例见 [deploy/.env.example](../deploy/.env.example)。环境变量不会自动传递到服务器或 systemd。想法发布需要另行[配置 Gist](modules/thoughts/README.md)；系统状态和设备授权无需 GitHub 凭据。

用户管理器需可用，可运行 `systemctl --user show-environment` 检查。需要服务器重启后启动定时任务、退出 SSH 后继续运行时，检查：

```sh
loginctl show-user "$USER" -p Linger
```

若结果为 `Linger=no`，由管理员执行一次 `sudo loginctl enable-linger 用户名`。不要以 sudo 运行激活脚本。安装系统软件及修改防火墙仍属于系统管理操作。

## 运行目录

```text
~/.local/share/myserver/
├── app/                        # 标准库实现的 SSH RPC 与业务模块
├── deploy/                     # 部署和维护命令
├── config/thoughts-gist.json    # 想法发布目标
├── credentials/thoughts-gist-token
├── devices/                    # 设备公钥、名称与权限
├── myserver.sqlite             # 模块业务数据
├── inbox/                      # 私有附件、未完成上传与文件锁
├── backups/                    # 每日数据库和附件的完整备份
├── archives/                   # 部署回滚快照
└── staged/                     # 待启用包（启用后移除）
```

根目录为 `0700`，凭据、数据库和设备文件为 `0600`。GitHub token 只在服务端使用。公开配置示例不包含具体服务器、用户、Gist ID 或密钥。

## 设备与定时任务

在服务器上运行：

```sh
python3 ~/.local/share/myserver/deploy/register-device.py
python3 ~/.local/share/myserver/deploy/register-device.py list
python3 ~/.local/share/myserver/deploy/register-device.py revoke --id DEVICE_ID
python3 -S ~/.local/share/myserver/app/cli.py check
systemctl --user list-timers 'myserver-*'
journalctl --user -u myserver-publish.service -u myserver-backup.service -n 50
```

设备登记默认授权已安装模块的能力；可用 `--capabilities system.read` 只授予系统状态读取权限，或 `--add-capabilities inbox.read,inbox.write` 为已有设备明确追加收件箱权限。条目使用 `restrict` 和固定强制命令，只接受 `myserver-rpc-v1` 与 `myserver-transfer-v1`。网关不执行客户端 shell 命令，不提供 SFTP 或转发，客户端不能自行提升权限；登记与撤销保留其他 SSH 公钥。App 终端使用独立密钥，具有 SSH 账户本身的 shell 权限。

用户级 systemd 仅管理两个定时任务：

- `myserver-publish.timer` / `.service`：每分钟重试想法 Gist 发布队列。
- `myserver-backup.timer` / `.service`：每日备份数据库和已完成收件箱附件，成功后清理超过 30 天的日常备份文件。每份归档包含完整附件，请为备份预留容量。

手动完整备份使用 `~/.local/bin/myserver backup-full /path/to/new-backup.tar`，目标必须不存在。`backup-unpack /path/to/backup.tar /path/to/new-directory` 将备份解包到新目录，并验证路径、数据库和附件摘要；它不会覆盖运行中的服务。原 `backup /path/to/new-backup.sqlite` 命令只导出数据库，不能单独恢复收件箱附件。

恢复前创建运行根目录下的 `maintenance` 文件，停止两个定时器及对应服务、关闭临时 Web 入口，并取得 `operations.lock` 的独占锁，确保在途操作退出。另存当前数据库、WAL 和 `inbox`，将已校验解包的数据库与 `inbox/objects` 一起恢复；未完成上传不在备份中。保持目录 `0700`、文件 `0600` 和正确所有者，运行 `cli.py check` 后启动定时器，最后移除 `maintenance`。备份不含设备授权、凭据和服务器配置，需要另行妥善保存。

## 协议与开发

每个 SSH exec 通道只收发一行 UTF-8 JSON。请求上限 140 KiB，响应上限 16 MiB，请求最长 45 秒。设备权限来自服务端登记文件，每次请求还会检查 `authorized_keys`，不创建登录会话。

上述限制用于元数据 RPC。收件箱文件通道先交换有界 JSON 头，再流式传输最多 4 MiB 原始字节，块级超时 90 秒；同一 SSH 连接可打开多个通道，重连后查询已确认偏移继续。文件不编码进 RPC JSON。

```json
{"path":"/system","method":"GET"}
```

响应格式为 `{"status":200,"body":{...}}`。`path`、`method` 和数字状态保留为 RPC 协议字段，不代表 HTTP 传输。业务模块声明路由、所需能力和处理函数；授权通过后直接处理数据库操作。

`/system` 需要 `system.read` 权限，返回 CPU 短时使用率、1/5/15 分钟负载、内存、磁盘与运行时间，以及设备获准读取的模块状态。指标来自 Python 标准库与 Linux `/proc`，无采集代理、shell 命令或后台轮询。CPU 为约 200ms 的采样，内存使用量按 `MemTotal - MemAvailable` 计算；无法读取的指标返回空值。参见 [Linux /proc 文档](https://docs.kernel.org/filesystems/proc.html)。

```sh
python3 -m venv .venv
.venv/bin/pip install pytest==8.4.2
.venv/bin/python -m pytest server/tests -q
```

本地开发设置 `MYSERVER_DATABASE` 指向测试数据库，运行 `python3 -S server/cli.py init` 和 `check`。测试包含不加载第三方包且禁止网络连接的网关子进程，以及部署失败回滚；Android CI 在临时 SSH 服务器上验证实际 App 协议。
