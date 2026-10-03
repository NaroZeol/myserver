# SSH 终端模块

「终端」页打开独立的全屏 SSH 会话，使用连接配置中已核验的服务器身份。支持 PTY、ANSI/全屏程序、UTF-8、方向键、Tab、Ctrl 组合键、粘贴、字号调整和横屏。终端不依赖想法 API，不需要额外开放端口。

- 默认输入密码登录本次会话，密码不保存。可以明确勾选「记住此设备」：通过密码登录在 `authorized_keys` 追加独立终端密钥，然后验证密钥登录。自动登记需要服务器 Python 3。
- 终端密钥按服务器地址、账户、端口和主机公钥隔离，私钥留在 Android Keystore；不会复用或放宽想法 RPC 密钥。终端拥有登录账户的命令执行权限，禁用 agent、端口与 X11 转发。撤销终端授权需删除服务器 `authorized_keys` 中对应 `thoughts-terminal` 条目；想法设备撤销不影响独立的终端授权。
- 扩展键在竖屏采用固定两行布局，方向键呈倒 T 排列；横屏收为一行，保留全部按键。Ctrl / Alt 点击作用于下一个键，长按锁定（下划线提示）、再点解除；方向键与翻页键长按连发，长按减号输入管道符。按住音量下键可作为 Ctrl，可在终端菜单关闭。
- 双指缩放调整字号（10–24），长按文字选词、拖动选区手柄、顶部复制；菜单提供选择当前屏幕与全选。键盘按钮可打开或收起，浏览历史输出时保持滚动位置，点击「回到底部」返回提示符。粘贴保留 bracketed paste，多行或控制字符仍需确认。
- 返回时先取消选区或收起键盘，再确认关闭会话；旋转屏幕保持连接。当前为单会话，不提供后台保活或进程恢复。长任务建议在服务器的 tmux/screen 中运行，Android 终止 App 或网络断开后需重连。
- 渲染使用随 APK 打包的 xterm.js 6.0.0 与 fit 0.11.0，没有运行时 CDN、网页服务器或 WebSocket。WebView 只读取白名单内置资源，禁用文件访问、外部页面、网络请求和远程剪贴板控制；输入输出只通过本地桥接与 SSH 传输。终端内容不写日志，不保存到磁盘，最近任务缩略图受保护。
- 输出采用有界缓冲与渲染确认，输入队列有上限，避免大量输出阻塞想法同步。依赖来源与文件摘要在 `assets/terminal/vendor.json`，许可证在同目录和 App「开源许可」。内置脚本已转换为 Chrome 74 兼容语法；维护时使用 `vendor-terminal.sh` 重新生成并验证摘要，日常构建不需要 Node.js。


终端源码：`src/app/thoughts/mobile/modules/terminal/`。仅依赖公共 `core/`，使用独立的 SSH shell 会话和终端密钥。

终端交互参考 [Termux 扩展键布局](https://github.com/termux/termux-app/blob/master/termux-shared/src/main/java/com/termux/shared/termux/settings/properties/TermuxPropertyConstants.java) 和 [触摸/按键交互](https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java)，结合现有 SSH 与 xterm 渲染实现，不包含 Termux 本地运行环境。
