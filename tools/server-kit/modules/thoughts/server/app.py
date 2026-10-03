"""Single-owner Thoughts service. SQLite is authoritative; clients may keep drafts."""
import hashlib
import json
import os
import secrets
import shutil
import sqlite3
import time
import uuid
from datetime import datetime, timezone
from functools import wraps
from pathlib import Path

from flask import Flask, g, jsonify, request, send_from_directory
from werkzeug.exceptions import HTTPException
from werkzeug.security import check_password_hash, generate_password_hash
from werkzeug.middleware.proxy_fix import ProxyFix


def now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def create_app(config=None):
    app = Flask(__name__, static_folder="static", static_url_path="/app/assets")
    app.config.update(
        DATABASE=os.environ.get("THOUGHTS_DATABASE", str(Path.home() / ".local/share/thoughts/thoughts.sqlite")),
        PUBLIC_ORIGIN=os.environ.get("THOUGHTS_ORIGIN", "http://127.0.0.1:8765"),
        COOKIE_SECURE=os.environ.get("THOUGHTS_DEV") != "1",
        MAX_CONTENT_LENGTH=128 * 1024,
    )
    if config:
        app.config.update(config)
    if os.environ.get("THOUGHTS_TRUST_PROXY") == "1":
        app.wsgi_app = ProxyFix(app.wsgi_app, x_for=1, x_proto=1, x_host=0)

    def db():
        if "db" not in g:
            g.db = sqlite3.connect(app.config["DATABASE"], timeout=15)
            g.db.row_factory = sqlite3.Row
            g.db.execute("PRAGMA foreign_keys=ON")
        return g.db

    @app.teardown_appcontext
    def close_db(_error):
        connection = g.pop("db", None)
        if connection:
            connection.close()

    Path(app.config["DATABASE"]).parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with app.app_context():
        db().executescript("""
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY, expires REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS login_attempts(ip TEXT PRIMARY KEY, attempts INTEGER NOT NULL, started REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS thoughts(
                id TEXT PRIMARY KEY, content TEXT NOT NULL, tags TEXT NOT NULL DEFAULT '[]',
                created_at TEXT NOT NULL, updated_at TEXT NOT NULL, deleted_at TEXT,
                version INTEGER NOT NULL DEFAULT 1);
            CREATE INDEX IF NOT EXISTS thoughts_date ON thoughts(created_at DESC);
            CREATE TABLE IF NOT EXISTS revisions(
                revision_id INTEGER PRIMARY KEY, thought_id TEXT NOT NULL REFERENCES thoughts(id),
                snapshot TEXT NOT NULL, saved_at TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS revisions_thought ON revisions(thought_id, revision_id DESC);
            CREATE TABLE IF NOT EXISTS publication(id INTEGER PRIMARY KEY CHECK(id=1), generation INTEGER NOT NULL DEFAULT 0, published_generation INTEGER NOT NULL DEFAULT 0, published_at TEXT, last_error TEXT);
            INSERT OR IGNORE INTO publication(id) VALUES(1);
        """)
        # Migrate the earlier, unpublished visibility design without losing records.
        with db():
            db().execute("BEGIN IMMEDIATE")
            if "visibility" in [row["name"] for row in db().execute("PRAGMA table_info(thoughts)")]:
                db().execute("DROP INDEX IF EXISTS thoughts_public_date")
                db().execute("ALTER TABLE thoughts DROP COLUMN visibility")
                db().execute("UPDATE publication SET generation=generation+1 WHERE id=1")
    os.chmod(app.config["DATABASE"], 0o600)

    def error(message, status):
        return jsonify(error=message), status

    def payload():
        value = request.get_json(silent=True)
        if not isinstance(value, dict):
            raise ValueError("请求必须是 JSON 对象")
        return value

    def serialize(row):
        result = dict(row)
        result["tags"] = json.loads(result["tags"])
        return result

    def token_hash():
        authorization = request.headers.get("Authorization", "")
        token = authorization[7:] if authorization.startswith("Bearer ") else request.cookies.get("thoughts_session", "")
        return hashlib.sha256(token.encode()).hexdigest()

    def authenticated(fn):
        @wraps(fn)
        def wrapper(*args, **kwargs):
            if not db().execute("SELECT 1 FROM sessions WHERE token=? AND expires>?", (token_hash(), time.time())).fetchone():
                return error("请先登录", 401)
            return fn(*args, **kwargs)
        return wrapper

    @app.before_request
    def protect_writes():
        if request.path.startswith("/api/") and request.method in ("POST", "PUT", "PATCH", "DELETE"):
            origin = request.headers.get("Origin")
            if origin and origin != app.config["PUBLIC_ORIGIN"]:
                return error("请求来源不被允许", 403)
            if request.cookies.get("thoughts_session") and not origin and not request.headers.get("Authorization"):
                return error("缺少请求来源", 403)
            if request.method != "DELETE" and not request.is_json:
                return error("请使用 JSON 请求", 415)

    @app.after_request
    def headers(response):
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["X-Frame-Options"] = "DENY"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["Content-Security-Policy"] = "default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
        if request.path.startswith("/api/"):
            response.headers["Cache-Control"] = "no-store"
        return response

    @app.errorhandler(ValueError)
    def invalid(exc):
        return error(str(exc), 400)

    @app.errorhandler(HTTPException)
    def http_error(exc):
        return error("请求无法处理" if exc.code != 404 else "记录或页面不存在", exc.code)

    @app.get("/api/health")
    def health():
        db().execute("SELECT 1").fetchone()
        return jsonify(status="ok")

    @app.post("/api/login")
    def login():
        password = payload().get("password")
        if not isinstance(password, str) or len(password) > 1024:
            return error("密码格式不正确", 400)
        ip = request.remote_addr or "unknown"
        with db():
            db().execute("BEGIN IMMEDIATE")
            db().execute("DELETE FROM login_attempts WHERE started<?", (time.time() - 900,))
            attempt = db().execute("SELECT * FROM login_attempts WHERE ip=?", (ip,)).fetchone()
            if attempt and attempt["attempts"] >= 8:
                return error("尝试过于频繁，请 15 分钟后重试", 429)
            db().execute("INSERT INTO login_attempts VALUES(?,1,?) ON CONFLICT(ip) DO UPDATE SET attempts=attempts+1", (ip, time.time()))
        owner = db().execute("SELECT value FROM settings WHERE key='password_hash'").fetchone()
        if not owner or not check_password_hash(owner["value"], password):
            return error("密码不正确", 401)
        token = secrets.token_urlsafe(32)
        with db():
            db().execute("DELETE FROM login_attempts WHERE ip=?", (ip,))
            db().execute("DELETE FROM sessions WHERE expires<?", (time.time(),))
            db().execute("INSERT INTO sessions VALUES(?,?)", (hashlib.sha256(token.encode()).hexdigest(), time.time() + 86400 * 30))
        response = jsonify(ok=True, token=token)
        response.set_cookie("thoughts_session", token, max_age=86400 * 30, secure=app.config["COOKIE_SECURE"], httponly=True, samesite="Strict", path="/api")
        return response

    @app.get("/api/session")
    @authenticated
    def session():
        return jsonify(ok=True)

    @app.get("/api/system")
    @authenticated
    def system_status():
        root = Path(app.config["DATABASE"]).parent
        backups = sorted((root / "backups").glob("thoughts-????????-??????.sqlite"))
        latest = backups[-1] if backups else None
        counts = db().execute("SELECT COUNT(*) AS total, SUM(deleted_at IS NOT NULL) AS trash FROM thoughts").fetchone()
        publication = dict(db().execute("SELECT * FROM publication WHERE id=1").fetchone())
        return jsonify(
            protocol_version=1, service="想法", version="1.2.0",
            capabilities=["thoughts", "system.read"],
            records={"active": counts["total"] - (counts["trash"] or 0), "trash": counts["trash"] or 0},
            publication=publication,
            storage={"database_bytes": Path(app.config["DATABASE"]).stat().st_size, "free_bytes": shutil.disk_usage(root).free},
            backup={"count": len(backups), "latest_at": datetime.fromtimestamp(latest.stat().st_mtime, timezone.utc).isoformat() if latest else None},
        )

    @app.post("/api/logout")
    @authenticated
    def logout():
        with db():
            db().execute("DELETE FROM sessions WHERE token=?", (token_hash(),))
        response = jsonify(ok=True)
        response.delete_cookie("thoughts_session", path="/api", secure=app.config["COOKIE_SECURE"], httponly=True, samesite="Strict")
        return response

    def listing():
        limit = max(1, min(int(request.args.get("limit", 50)), 200))
        offset = max(0, int(request.args.get("offset", 0)))
        query = request.args.get("q", "").strip()[:200]
        conditions, params = [], []
        if request.args.get("trash") == "1":
            conditions.append("deleted_at IS NOT NULL")
        elif request.args.get("all") != "1":
            conditions.append("deleted_at IS NULL")
        if query:
            conditions.append("(instr(lower(content),lower(?))>0 OR instr(lower(tags),lower(?))>0)")
            params += [query, query]
        where = " WHERE " + " AND ".join(conditions) if conditions else ""
        rows = db().execute("SELECT * FROM thoughts" + where + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", params + [limit + 1, offset]).fetchall()
        return jsonify(items=[serialize(row) for row in rows[:limit]], has_more=len(rows) > limit, next_offset=offset + limit)

    @app.get("/api/thoughts")
    @authenticated
    def thoughts():
        return listing()

    def validate(data):
        content = data.get("content")
        tags = data.get("tags", [])
        if "visibility" in data:
            raise ValueError("想法全部公开，请更新客户端")
        if not isinstance(content, str) or not content.strip() or len(content) > 20000:
            raise ValueError("请填写 1–20000 字的想法")
        if not isinstance(tags, list) or len(tags) > 12 or any(not isinstance(t, str) or not t.strip() or len(t) > 30 for t in tags):
            raise ValueError("最多 12 个标签，每个标签 1–30 字")
        return content.strip(), json.dumps(list(dict.fromkeys(t.strip() for t in tags)), ensure_ascii=False)

    @app.post("/api/thoughts")
    @authenticated
    def create():
        data = payload()
        content, tags = validate(data)
        ident = str(uuid.UUID(data.get("id", str(uuid.uuid4()))))
        timestamp = now()
        with db():
            db().execute("BEGIN IMMEDIATE")
            existing = db().execute("SELECT * FROM thoughts WHERE id=?", (ident,)).fetchone()
            if existing:
                if (existing["content"], existing["tags"]) != (content, tags) or existing["deleted_at"]:
                    return error("这条离线记录已在服务器修改，请保留本地内容后刷新", 409)
                return jsonify(serialize(existing))
            db().execute("INSERT INTO thoughts(id,content,tags,created_at,updated_at) VALUES(?,?,?,?,?)", (ident, content, tags, timestamp, timestamp))
            enqueue()
        return jsonify(serialize(db().execute("SELECT * FROM thoughts WHERE id=?", (ident,)).fetchone())), 201

    def enqueue():
        db().execute("UPDATE publication SET generation=generation+1 WHERE id=1")

    @app.get("/api/publication")
    @authenticated
    def publication():
        row = dict(db().execute("SELECT * FROM publication WHERE id=1").fetchone())
        row["pending"] = row["generation"] > row["published_generation"]
        return jsonify(row)

    @app.post("/api/publish")
    @authenticated
    def publish():
        from publisher import publish_once
        publish_once(app.config["DATABASE"])
        return publication()

    def snapshot(row):
        db().execute("INSERT INTO revisions(thought_id,snapshot,saved_at) VALUES(?,?,?)", (row["id"], json.dumps(serialize(row), ensure_ascii=False), now()))

    @app.patch("/api/thoughts/<ident>")
    @authenticated
    def update(ident):
        data = payload()
        with db():
            db().execute("BEGIN IMMEDIATE")
            row = db().execute("SELECT * FROM thoughts WHERE id=?", (ident,)).fetchone()
            if not row:
                return error("记录不存在", 404)
            if type(data.get("version")) is not int or data["version"] != row["version"]:
                return error("另一台设备已修改这条想法，请先保留草稿并刷新", 409)
            if "restore" in data:
                if data["restore"] is not True or not row["deleted_at"]:
                    raise ValueError("该记录不在回收站")
                content, tags, deleted = row["content"], row["tags"], None
            else:
                if row["deleted_at"]:
                    return error("请先从回收站恢复", 409)
                content, tags = validate(data)
                deleted = None
            snapshot(row)
            db().execute("UPDATE thoughts SET content=?,tags=?,deleted_at=?,updated_at=?,version=version+1 WHERE id=?", (content, tags, deleted, now(), ident))
            enqueue()
        return jsonify(serialize(db().execute("SELECT * FROM thoughts WHERE id=?", (ident,)).fetchone()))

    @app.delete("/api/thoughts/<ident>")
    @authenticated
    def delete(ident):
        data = payload()
        with db():
            db().execute("BEGIN IMMEDIATE")
            row = db().execute("SELECT * FROM thoughts WHERE id=?", (ident,)).fetchone()
            if not row:
                return error("记录不存在", 404)
            if type(data.get("version")) is not int or data["version"] != row["version"]:
                return error("记录已经变更，请刷新后重试", 409)
            snapshot(row)
            db().execute("UPDATE thoughts SET deleted_at=?,updated_at=?,version=version+1 WHERE id=?", (now(), now(), ident))
            enqueue()
        return jsonify(ok=True)

    @app.get("/api/thoughts/<ident>/history")
    @authenticated
    def history(ident):
        rows = db().execute("SELECT snapshot,saved_at FROM revisions WHERE thought_id=? ORDER BY revision_id DESC", (ident,)).fetchall()
        return jsonify(items=[dict(item=json.loads(row["snapshot"]), saved_at=row["saved_at"]) for row in rows])

    @app.get("/api/export")
    @authenticated
    def export():
        response = jsonify(schema_version=1, exported_at=now(), thoughts=[serialize(row) for row in db().execute("SELECT * FROM thoughts ORDER BY created_at,id")], revisions=[dict(row) for row in db().execute("SELECT * FROM revisions ORDER BY revision_id")])
        response.headers["Content-Disposition"] = 'attachment; filename="thoughts-export.json"'
        return response

    @app.get("/app/")
    def index():
        return send_from_directory(app.static_folder, "index.html")

    app.db = db
    return app


if __name__ == "__main__":
    import argparse
    import getpass
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["password", "import", "backup"])
    parser.add_argument("file", nargs="?")
    args = parser.parse_args()
    app = create_app()
    with app.app_context():
        connection = app.db()
        if args.command == "password":
            password = getpass.getpass("New password (at least 16 characters): ")
            if len(password) < 16:
                raise SystemExit("Password must be at least 16 characters")
            with connection:
                connection.execute("INSERT OR REPLACE INTO settings VALUES('password_hash',?)", (generate_password_hash(password),))
                connection.execute("DELETE FROM sessions")
            print("Password updated; all sessions revoked.")
        elif args.command == "backup":
            if not args.file:
                raise SystemExit("Backup destination required")
            with sqlite3.connect(args.file) as destination:
                connection.backup(destination)
            os.chmod(args.file, 0o600)
            print("Backup complete.")
        else:
            if not args.file:
                raise SystemExit("JSON file required")
            records = json.loads(Path(args.file).read_text())
            count = 0
            with connection:
                for record in records:
                    ident = str(uuid.uuid5(uuid.NAMESPACE_URL, "thoughts-legacy:" + str(record["id"])))
                    excerpt, content = record.get("excerpt", "").strip(), record.get("content", "").strip()
                    body = excerpt + ("\n\n" + content if content and content != excerpt else "")
                    body = body.strip() or content
                    if not body:
                        continue
                    count += connection.execute("INSERT OR IGNORE INTO thoughts VALUES(?,?,?,?,?,?,1)", (ident, body, '[]', record["created_at"], record["updated_at"], record.get("deleted_at"))).rowcount
                if count:
                    connection.execute("UPDATE publication SET generation=generation+1 WHERE id=1")
            print(f"Imported {count} existing public thoughts; repeated imports are safe.")
