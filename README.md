# myserver

myserver 是一款连接自有 Linux 服务器的 Android App。终端、端口转发、监控、收件箱和想法都以 SSH 为连接通道，无需额外开放公网 HTTP API。

```text
     +-----------------------+               +---------------------------------+
    /      ANDROID APP      /|    SSH       /           LINUX SERVER          /|
   +-----------------------+ |============>+---------------------------------+ |
   | terminal / monitor    | | shell / RPC | shell / tmux                    | |
   | inbox / thoughts      | | file stream | metrics / inbox / thoughts      | |
   | app updates           |/              | local-only inbox web            |/
   +-----------------------+               +------+-------------------+------+
                                                  ^                   |
  Desktop browser -- SSH tunnel + token ----------+                   +-- publish --> Gist --> Blog
```

首次可用密码登记设备公钥，后续使用密钥连接。电脑端收件箱网页按需启动，凭本次令牌进入。

「服务器 → 端口转发」可选择服务器正在监听的本机 TCP 端口，或手动输入端口，在手机本机临时打开 SSH 转发；切到浏览器时连接仍会保持。转发需先启用终端免密连接，使用后可从页面或通知停止。

代码分为 `android/`（App）、`server/`（服务端）和 `deploy/`（部署脚本）。参见 [Android 安装](android/README.md)、[服务端部署](server/README.md)、[收件箱使用](docs/inbox.md)和[安全说明](SECURITY.md)。
