"""Shared database lifecycle, session authorization and request helpers."""
import hashlib
import sqlite3
import time
from datetime import datetime, timezone
from functools import wraps
from flask import current_app as app, g, jsonify, request


def now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def db():
    if "db" not in g:
        g.db = sqlite3.connect(app.config["DATABASE"], timeout=15)
        g.db.row_factory = sqlite3.Row
        g.db.execute("PRAGMA foreign_keys=ON")
    return g.db

def close_db(_error):
    connection = g.pop("db", None)
    if connection:
        connection.close()


def error(message, status):
    return jsonify(error=message), status

def payload():
    value = request.get_json(silent=True)
    if not isinstance(value, dict):
        raise ValueError("请求必须是 JSON 对象")
    return value

def token_hash():
    authorization = request.headers.get("Authorization", "")
    token = authorization[7:] if authorization.startswith("Bearer ") else request.cookies.get("myserver_session", "")
    return hashlib.sha256(token.encode()).hexdigest()

def authenticated(fn):
    @wraps(fn)
    def wrapper(*args, **kwargs):
        if not db().execute("SELECT 1 FROM sessions WHERE token=? AND expires>?", (token_hash(), time.time())).fetchone():
            return error("请先登录", 401)
        return fn(*args, **kwargs)
    return wrapper
