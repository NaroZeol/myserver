"""myserver HTTP service: authenticated shared capabilities and installed modules."""
import hashlib
import os
import secrets
import shutil
import sqlite3
import time
from datetime import datetime, timezone
from pathlib import Path

from system_metrics import snapshot as host_snapshot
from core import db, close_db, error, payload, token_hash, authenticated
from paths import database_path
import modules

from flask import Flask, jsonify, request
from werkzeug.exceptions import HTTPException
from werkzeug.security import check_password_hash, generate_password_hash
from werkzeug.middleware.proxy_fix import ProxyFix


def create_app(config=None):
    app = Flask(__name__, static_folder=None)
    app.config.update(
        DATABASE=str(database_path()),
        PUBLIC_ORIGIN=os.environ.get("MYSERVER_ORIGIN", "http://127.0.0.1:8765"),
        COOKIE_SECURE=os.environ.get("MYSERVER_DEV") != "1",
        MAX_CONTENT_LENGTH=128 * 1024,
    )
    if config:
        app.config.update(config)
    if os.environ.get("MYSERVER_TRUST_PROXY") == "1":
        app.wsgi_app = ProxyFix(app.wsgi_app, x_for=1, x_proto=1, x_host=0)

    app.teardown_appcontext(close_db)
    Path(app.config["DATABASE"]).parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with app.app_context():
        db().executescript("""
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY, expires REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS login_attempts(ip TEXT PRIMARY KEY, attempts INTEGER NOT NULL, started REAL NOT NULL);
        """)
    modules.init_app(app)
    os.chmod(app.config["DATABASE"], 0o600)

    @app.before_request
    def protect_writes():
        if request.path.startswith("/api/") and request.method in ("POST", "PUT", "PATCH", "DELETE"):
            origin = request.headers.get("Origin")
            if origin and origin != app.config["PUBLIC_ORIGIN"]:
                return error("请求来源不被允许", 403)
            if request.cookies.get("myserver_session") and not origin and not request.headers.get("Authorization"):
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
        response.set_cookie("myserver_session", token, max_age=86400 * 30, secure=app.config["COOKIE_SECURE"], httponly=True, samesite="Strict", path="/api")
        return response

    @app.get("/api/session")
    @authenticated
    def session():
        return jsonify(ok=True)

    @app.get("/api/system")
    @authenticated
    def system_status():
        root = Path(app.config["DATABASE"]).parent
        backups = sorted((root / "backups").glob("myserver-????????-??????.sqlite"))
        latest = backups[-1] if backups else None
        return jsonify(
            protocol_version=1, service="myserver", version="1.8.0",
            metrics=host_snapshot(root),
            capabilities=sorted(modules.capabilities()),
            modules=modules.status(),
            storage={"database_bytes": Path(app.config["DATABASE"]).stat().st_size, "free_bytes": shutil.disk_usage(root).free},
            backup={"count": len(backups), "latest_at": datetime.fromtimestamp(latest.stat().st_mtime, timezone.utc).isoformat() if latest else None},
        )

    @app.post("/api/logout")
    @authenticated
    def logout():
        with db():
            db().execute("DELETE FROM sessions WHERE token=?", (token_hash(),))
        response = jsonify(ok=True)
        response.delete_cookie("myserver_session", path="/api", secure=app.config["COOKIE_SECURE"], httponly=True, samesite="Strict")
        return response

    app.db = db
    return app


if __name__ == "__main__":
    import argparse
    import getpass
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["password", "backup"])
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
