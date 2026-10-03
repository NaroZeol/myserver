# myserver 服务端

[返回项目](../README.md) · [想法模块](modules/thoughts/README.md)

服务端统一提供会话认证、设备授权、系统状态、数据库与备份。功能模块注册业务路由和权限，SSH 网关只接受声明过的请求。

## 部署

需要 Linux、Python 3.10+、pip、OpenSSH 与 systemd。API 仅监听 `127.0.0.1:8765`；App 使用现有 SSH 端口，无需公网 HTTP、HTTPS 或反向代理。服务以普通用户运行，App 的 SSH 账户使用同一用户。

在开发电脑上，从仓库根目录运行：

```sh
export MYSERVER_SSH_TARGET=my-server
bash deploy/stage.sh
ssh -t "$MYSERVER_SSH_TARGET" 'bash ~/.local/share/myserver/deploy/activate.sh'
```

`stage.sh` 接受 SSH 别名或 `user@host`，命令行参数优先于环境变量。脚本上传服务源码、部署工具和 Python 依赖，不上传 `.env`、本机数据库或 APK。`activate.sh` 通过 sudo 安装 systemd 单元；账户、主目录和属组均在安装时读取。

本机配置可参照 [deploy/.env.example](../deploy/.env.example)，放入被 Git 忽略的 `.env` 后显式加载。环境变量不会自动传递到服务器或 systemd。使用想法发布时，按[模块说明](modules/thoughts/README.md)另行配置 Gist；系统状态和设备授权无需 Gist 凭据。

## 运行目录

```text
~/.local/share/myserver/
├── app/                        # 服务源码与模块
├── deploy/                     # 部署和维护命令
├── python/                     # Python 依赖
├── config/thoughts-gist.json    # 想法发布目标
├── credentials/thoughts-gist-token
├── devices/                    # 设备公钥、名称与权限
├── myserver.sqlite             # 公共会话与各模块业务数据
└── backups/                    # 每日 SQLite 备份
```

根目录为 `0700`，凭据、数据库和设备文件为 `0600`。GitHub token 只在服务端使用。公开配置示例不包含服务器地址、用户、Gist ID 或密钥。

## 设备与服务管理

在服务器上运行：

```sh
python3 ~/.local/share/myserver/deploy/register-device.py
python3 ~/.local/share/myserver/deploy/register-device.py list
python3 ~/.local/share/myserver/deploy/register-device.py revoke --id DEVICE_ID
systemctl status myserver.service
systemctl list-timers 'myserver-*'
journalctl -u myserver.service -n 50
```

设备登记默认授权已安装模块的能力；可用 `--capabilities system.read` 只授予系统状态读取权限。条目使用 `restrict` 和固定强制命令，只接受 `myserver-rpc-v1`。不允许 shell、SFTP、转发或客户端自行提升权限；登记与撤销保留其他 SSH 公钥。App 终端使用独立密钥，具有 SSH 账户本身的 shell 权限。

服务使用以下 systemd 单元：

- `myserver.service`：API。
- `myserver-publish.timer` / `.service`：每分钟重试想法 Gist 发布队列。
- `myserver-backup.timer` / `.service`：每日备份，成功后保留最近 30 天的备份文件。

恢复数据库前停止 API、发布和备份定时器及对应服务，另存当前数据库及 WAL，再恢复备份，确保账户所有权和 `0600` 权限。验证数据库后重新启动服务和定时器。

## 开发

```sh
python3 -m venv .venv
.venv/bin/pip install -r server/requirements.txt pytest==8.4.2
.venv/bin/python -m pytest server/tests -q
```

本地调试请设置 `MYSERVER_DATABASE` 指向测试数据库，并使用 `MYSERVER_DEV=1 MYSERVER_ORIGIN=http://127.0.0.1:8765`。从 `server/` 启动 Gunicorn：

```sh
../.venv/bin/gunicorn --bind 127.0.0.1:8765 'app:create_app()'
```

可用 `python app.py password` 设置 Web 管理密码；没有预置密码。Web 界面由想法模块提供，入口 `/app/`；需要远程访问时通过 SSH 本地转发连接回环端口。

`/api/system` 需要认证，返回 CPU 短时使用率、1/5/15 分钟负载、内存、磁盘与运行时间，以及模块状态。指标来自 Python 标准库与 Linux `/proc`，无采集代理、shell 命令或后台轮询。CPU 为约 200ms 的采样，负载是运行/不可中断任务的平均数量；内存使用量按 `MemTotal - MemAvailable` 计算。无法读取的指标返回空值。参见 [Linux /proc 文档](https://docs.kernel.org/filesystems/proc.html)。
