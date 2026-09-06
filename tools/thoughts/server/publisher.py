"""Durable SQLite outbox -> the one public Gist. Never runs in a reader's browser."""
import fcntl
import json
import os
import re
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

def gist_target():
    path = Path(os.environ.get("THOUGHTS_GIST_CONFIG", str(Path.home() / ".local/share/thoughts/gist.json")))
    config = json.loads(path.read_text()) if path.is_file() else {}
    ident = os.environ.get("THOUGHTS_GIST_ID", config.get("id", ""))
    filename = os.environ.get("THOUGHTS_GIST_FILE", config.get("file", "thoughts.json"))
    if not re.fullmatch(r"[0-9a-fA-F]{5,64}", ident) or not isinstance(filename, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,100}", filename):
        raise RuntimeError("请先配置服务器的 Gist ID 与文件名")
    return ident, filename


def github_write(content):
    token_file = Path(os.environ.get("THOUGHTS_GIST_TOKEN_FILE", str(Path.home() / ".local/share/thoughts/gist-token")))
    if not token_file.is_file():
        raise RuntimeError("尚未配置服务器的 Gist 写入凭据")
    token = token_file.read_text().strip()
    if not token:
        raise RuntimeError("Gist 写入凭据为空")
    gist_id, gist_file = gist_target()
    payload = json.dumps({"files": {gist_file: {"content": content}}}).encode()
    request = Request(f"https://api.github.com/gists/{gist_id}", data=payload, method="PATCH", headers={
        "Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "Thoughts",
    })
    try:
        with urlopen(request, timeout=20) as response:
            if response.status != 200:
                raise RuntimeError("Gist 暂时无法发布，请稍后重试")
            response.read()
    except HTTPError as error:
        # Do not persist response bodies or credentials in logs or status output.
        if error.code in (401, 403):
            raise RuntimeError("Gist 凭据无效、权限不足或请求被限流") from None
        raise RuntimeError(f"Gist 发布失败（HTTP {error.code}），服务器会自动重试") from None
    except (URLError, TimeoutError, OSError):
        raise RuntimeError("暂时无法连接 Gist，服务器会自动重试") from None


def publish_once(database, writer=github_write):
    """Serialize publishers, but never hold a SQLite write lock during HTTP."""
    lock_path = Path(str(database) + ".publish.lock")
    with lock_path.open("a") as lock:
        os.chmod(lock_path, 0o600)
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return False
        connection = sqlite3.connect(database, timeout=15)
        connection.row_factory = sqlite3.Row
        try:
            connection.execute("BEGIN")
            state = connection.execute("SELECT * FROM publication WHERE id=1").fetchone()
            if state["generation"] <= state["published_generation"]:
                connection.commit()
                return True
            generation = state["generation"]
            records = []
            for row in connection.execute("SELECT * FROM thoughts WHERE deleted_at IS NULL ORDER BY created_at DESC,id DESC"):
                record = dict(row)
                record["tags"] = json.loads(record["tags"])
                record["excerpt"] = ""
                records.append(record)
            connection.commit()
            content = json.dumps(records, ensure_ascii=False, indent=2)
            if len(content.encode()) > 900_000:
                raise RuntimeError("想法内容超过单文件发布限制，请先分卷；服务器记录已保留")
            writer(content)
            with connection:
                connection.execute("UPDATE publication SET published_generation=?,published_at=?,last_error=NULL WHERE id=1", (generation, datetime.now(timezone.utc).isoformat()))
            return True
        except Exception as error:
            connection.rollback()
            message = str(error) if isinstance(error, RuntimeError) else "Gist 发布暂时失败，服务器会自动重试"
            with connection:
                connection.execute("UPDATE publication SET last_error=? WHERE id=1", (message,))
            return False
        finally:
            connection.close()


if __name__ == "__main__":
    database = os.environ.get("THOUGHTS_DATABASE", str(Path.home() / ".local/share/thoughts/thoughts.sqlite"))
    raise SystemExit(0 if publish_once(database) else 1)
