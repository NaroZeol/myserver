# 想法模块

[服务端](../../README.md) · [Android](../../../android/README.md)

想法提供公开记录、离线草稿、标签、搜索、回收站、编辑历史、冲突保留与 Gist 发布。

手机 → SSH 受限网关 → myserver API → Gist。博客读者只读取 Gist raw URL，不请求管理服务器。

## 记录与同步

想法同步后公开。关闭自动同步后，保存、打开应用和恢复网络均保留本机队列，点击同步才提交；开启后这些操作会尝试同步。离线内容与草稿可导出，没有后台常驻。

UUID 重试避免重复创建，版本检查防止覆盖其他编辑。写入与发布队列在同一数据库事务中完成；发布器取得一致快照后释放数据库锁，上传期间的新记录继续排队。App 区分已保存到服务器和已发布到 Gist。

## Gist 配置

部署服务后，在服务器运行 `python3 ~/.local/share/myserver/deploy/configure-gist.py` 交互配置，或使用环境变量：

```sh
export THOUGHTS_GIST_ID=your_gist_id
export THOUGHTS_GIST_FILE=thoughts.json
export THOUGHTS_GIST_TOKEN_FILE=/path/to/private/github-token
python3 ~/.local/share/myserver/deploy/configure-gist.py --non-interactive
```

Token 通过受保护文件提供，只授予 gist 权限。工具验证账户拥有目标 Gist；失败时不替换配置。Gist 目标写入 `config/thoughts-gist.json`，token 写入 `credentials/thoughts-gist-token`，均位于 myserver 运行根目录。

`THOUGHTS_GIST_ID` / `THOUGHTS_GIST_FILE` 覆盖发布目标，`THOUGHTS_GIST_CONFIG` 指定目标配置文件，`THOUGHTS_GIST_TOKEN_FILE` 指定凭据文件。未提供或为空的环境变量使用已保存配置。环境变量由运行发布器的进程读取，不自动从开发电脑传递到服务端。

备份与 Gist 可能保留删除记录的历史。单次发布上限为 900 KB，超过时保留队列并报告错误。读取端自行配置 Gist URL，App 不持有 GitHub 写入凭据。
