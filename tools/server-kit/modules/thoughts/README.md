# 想法模块

[返回 Server Kit](../../README.md) · [Android 构建](../../android/README.md)

想法是 Server Kit 的一个功能模块，负责公开想法的记录、离线草稿、标签、搜索、回收站、编辑历史、冲突保留与 Gist 发布。Android 实现在 `../../android/src/app/thoughts/mobile/modules/thoughts/`；此目录包含配套后端及部署工具。终端模块无需安装这个服务。

## 工作方式

手机 → SSH 受限通道 → 服务器本机 API → Gist。博客读者只读取配置的 Gist raw URL，不请求管理服务器。

App 不预置任何服务器、用户名、主机公钥或 Gist ID。首次在「服务 → 配置服务器」填写连接名称、地址、SSH 端口和用户名，读取并核对服务器指纹后保存。随后输入一次服务器密码自动登记设备，成功后再次验证受限密钥登录。密码不落盘，使用后清理缓冲；私钥留在 Android Keystore。服务器身份变化会拒绝连接，需要重新核验。

也可以手动登记手机公钥。设备条目使用 `restrict,command=...`，只允许 `thoughts-rpc-v1` 协议，不开放 shell、SFTP 或转发。目前权限包括想法管理与只读的 `system.read`。

所有想法同步后公开。自动同步可在设置或编辑页关闭：关闭后保存、重新打开应用、恢复网络均不会自动提交，只有点击同步才统一提交本机队列；开启时保存、打开应用或恢复网络会尝试同步。离线内容与草稿保留在本机，没有后台常驻。UUID 重试避免重复创建，版本检查避免覆盖其他编辑，未同步修改与草稿可导出。Gist 发布通过持久化队列重试，手机区分已保存到服务器和已发布到 Gist。

## 服务器部署

需要 Linux、Python 3.10+、pip、OpenSSH、systemd，以及能写入目标 Gist 的 GitHub token。API 仅监听 `127.0.0.1:8765`，设备沿用已有 SSH 端口，不需要公网 HTTP 或 HTTPS。

使用 SSH config 中自行配置的别名，或 `user@host`：

```sh
bash tools/server-kit/modules/thoughts/deploy/stage.sh my-server
ssh -t my-server 'bash ~/.local/share/thoughts/deploy/activate.sh'
```

上传脚本不含个人配置或凭据。激活脚本交互配置 Gist ID、文件名与 token，并通过 sudo 为当前非 root 用户生成 systemd 服务。运行账户、主目录和属组在安装时读取，不写死在仓库。App 的 SSH 用户应为部署服务的同一账户。

本地配置保存在服务器 `~/.local/share/thoughts/`：

- `gist.json`：目标 Gist ID 和文件名。
- `gist-token`：专用 token，权限仅为 gist；脚本验证 token 账户拥有该 Gist。
- `thoughts.sqlite`：记录、历史、会话与发布队列。
- `devices/`：已登记设备和权限。
- `backups/`：每日 SQLite online backup，保留 30 天。

配置文件权限为 `0600`，不进入 Git。也可通过 `THOUGHTS_GIST_ID` / `THOUGHTS_GIST_FILE` 指定发布目标，通过 `THOUGHTS_GIST_CONFIG` 指定配置文件。博客的 Gist raw URL 由博客自身配置，App 不持有 GitHub 凭据。

设备管理（在服务器运行）：

```sh
python3 ~/.local/share/thoughts/deploy/register-device.py
python3 ~/.local/share/thoughts/deploy/register-device.py list
python3 ~/.local/share/thoughts/deploy/register-device.py revoke --id DEVICE_ID
```

登记会保留其他管理员 SSH 公钥，撤销后拒绝设备的新请求。更换 Gist 目标或 token 时重新运行 `configure-gist.py`。备份与 Gist 都可能保留被删除记录的历史；发布单文件上限 900 KB，超过时保留队列并报告错误。

## 后端开发与验证

服务端采用 Flask、Gunicorn、SQLite。写入与发布队列同事务完成；发布器在一致快照后释放数据库锁，再写 Gist，上传过程中的新记录继续排队。

从仓库根目录运行：

```sh
python3 -m venv .venv
.venv/bin/pip install -r tools/server-kit/modules/thoughts/server/requirements.txt pytest==8.4.2
.venv/bin/python -m pytest tools/server-kit/modules/thoughts/server/tests -q
```

本地 Web 管理可用 `THOUGHTS_DEV=1 THOUGHTS_ORIGIN=http://127.0.0.1:8765` 启动 Gunicorn；开发时将 `THOUGHTS_DATABASE` 指向测试数据库。恢复备份前停止 API 和发布定时器，另存数据库及 WAL，恢复服务用户所有权和 `0600` 权限，再启动服务。

本模块保留 `thoughts.service`、`~/.local/share/thoughts/`、`thoughts-rpc-v1` 与 `THOUGHTS_*` 服务端环境变量，以兼容已有部署。仓库移动无需迁移线上数据库、设备授权或 Gist 配置。`deploy/stage.sh` 只上传本模块的服务端与部署文件，Android 安装包通过 App 构建产物分发。
