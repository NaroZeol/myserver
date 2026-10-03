"""Public thoughts, revision history, offline synchronization and Gist publication."""
import json
import uuid
from pathlib import Path
from flask import jsonify, request, send_from_directory
from core import db, error, payload, authenticated, now



def status():
    counts = db().execute("SELECT COUNT(*) AS total, SUM(deleted_at IS NOT NULL) AS trash FROM thoughts").fetchone()
    return dict(records={"active": counts["total"] - (counts["trash"] or 0), "trash": counts["trash"] or 0},
                publication=dict(db().execute("SELECT * FROM publication WHERE id=1").fetchone()))


def init_app(app):
    with app.app_context():
        db().executescript("""
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
        # Normalize the public-only schema.
        with db():
            db().execute("BEGIN IMMEDIATE")
            if "visibility" in [row["name"] for row in db().execute("PRAGMA table_info(thoughts)")]:
                db().execute("DROP INDEX IF EXISTS thoughts_public_date")
                db().execute("ALTER TABLE thoughts DROP COLUMN visibility")
                db().execute("UPDATE publication SET generation=generation+1 WHERE id=1")

    def serialize(row):
        result = dict(row)
        result["tags"] = json.loads(result["tags"])
        return result


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
        from .publisher import publish_once
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
        return send_from_directory(Path(__file__).parent / "static", "index.html")

    @app.get("/app/assets/<path:name>")
    def assets(name):
        return send_from_directory(Path(__file__).parent / "static", name)
