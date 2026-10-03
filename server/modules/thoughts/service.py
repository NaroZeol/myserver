"""Public thoughts, revision history and Gist publication over direct RPC."""
import json
import uuid
from rpc import Reply, Route, RpcError
from database import now

CAPABILITIES = {"thoughts"}


def routes():
    item = r'/thoughts/(?P<ident>[0-9a-f-]{36})'
    return [
        Route('thoughts', 'GET', r'/thoughts', listing),
        Route('thoughts', 'POST', r'/thoughts', create),
        Route('thoughts', 'PATCH', item, update),
        Route('thoughts', 'DELETE', item, delete),
        Route('thoughts', 'GET', item + r'/history', history),
        Route('thoughts', 'GET', r'/publication', publication),
        Route('thoughts', 'POST', r'/publish', publish),
        Route('thoughts', 'GET', r'/export', export),
    ]


def initialize(connection):
    connection.executescript("""
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
    with connection:
        connection.execute("BEGIN IMMEDIATE")
        if "visibility" in [row["name"] for row in connection.execute("PRAGMA table_info(thoughts)")]:
            connection.execute("DROP INDEX IF EXISTS thoughts_public_date")
            connection.execute("ALTER TABLE thoughts DROP COLUMN visibility")
            connection.execute("UPDATE publication SET generation=generation+1 WHERE id=1")


def status(connection):
    counts = connection.execute("SELECT COUNT(*) AS total, SUM(deleted_at IS NOT NULL) AS trash FROM thoughts").fetchone()
    return dict(records={"active": counts["total"] - (counts["trash"] or 0), "trash": counts["trash"] or 0},
                publication=dict(connection.execute("SELECT * FROM publication WHERE id=1").fetchone()))


def serialize(row):
    result = dict(row)
    result["tags"] = json.loads(result["tags"])
    return result


def listing(connection, request):
    limit = max(1, min(int(request.query.get("limit", 50)), 200))
    offset = max(0, min(int(request.query.get("offset", 0)), 2**63 - 1))
    query = request.query.get("q", "").strip()[:200]
    conditions, params = [], []
    if request.query.get("trash") == "1":
        conditions.append("deleted_at IS NOT NULL")
    elif request.query.get("all") != "1":
        conditions.append("deleted_at IS NULL")
    if query:
        conditions.append("(instr(lower(content),lower(?))>0 OR instr(lower(tags),lower(?))>0)")
        params += [query, query]
    where = " WHERE " + " AND ".join(conditions) if conditions else ""
    rows = connection.execute("SELECT * FROM thoughts" + where + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", params + [limit + 1, offset]).fetchall()
    return Reply(dict(items=[serialize(row) for row in rows[:limit]], has_more=len(rows) > limit, next_offset=offset + limit))

def validate(data):
    content = data.get("content")
    tags = data.get("tags", [])
    if "visibility" in data:
        raise RpcError("想法全部公开，请更新客户端")
    if not isinstance(content, str) or not content.strip() or len(content) > 20000:
        raise RpcError("请填写 1–20000 字的想法")
    if not isinstance(tags, list) or len(tags) > 12 or any(not isinstance(t, str) or not t.strip() or len(t) > 30 for t in tags):
        raise RpcError("最多 12 个标签，每个标签 1–30 字")
    return content.strip(), json.dumps(list(dict.fromkeys(t.strip() for t in tags)), ensure_ascii=False)

def create(connection, request):
    data = request.payload()
    content, tags = validate(data)
    raw_id = data.get("id", str(uuid.uuid4()))
    if not isinstance(raw_id, str):
        raise RpcError("记录 ID 无效")
    ident = str(uuid.UUID(raw_id))
    timestamp = now()
    with connection:
        connection.execute("BEGIN IMMEDIATE")
        existing = connection.execute("SELECT * FROM thoughts WHERE id=?", (ident,)).fetchone()
        if existing:
            if (existing["content"], existing["tags"]) != (content, tags) or existing["deleted_at"]:
                raise RpcError("这条离线记录已在服务器修改，请保留本地内容后刷新", 409)
            return Reply(serialize(existing))
        connection.execute("INSERT INTO thoughts(id,content,tags,created_at,updated_at) VALUES(?,?,?,?,?)", (ident, content, tags, timestamp, timestamp))
        enqueue(connection)
    return Reply(serialize(connection.execute("SELECT * FROM thoughts WHERE id=?", (ident,)).fetchone()), 201)

def enqueue(connection):
    connection.execute("UPDATE publication SET generation=generation+1 WHERE id=1")

def publication(connection, request):
    row = dict(connection.execute("SELECT * FROM publication WHERE id=1").fetchone())
    row["pending"] = row["generation"] > row["published_generation"]
    return Reply(row)

def publish(connection, request):
    from .publisher import publish_once
    publish_once(request.database)
    return publication(connection, request)

def snapshot(connection, row):
    connection.execute("INSERT INTO revisions(thought_id,snapshot,saved_at) VALUES(?,?,?)", (row["id"], json.dumps(serialize(row), ensure_ascii=False), now()))

def update(connection, request):
    ident = request.params["ident"]
    data = request.payload()
    with connection:
        connection.execute("BEGIN IMMEDIATE")
        row = connection.execute("SELECT * FROM thoughts WHERE id=?", (ident,)).fetchone()
        if not row:
            raise RpcError("记录不存在", 404)
        if type(data.get("version")) is not int or data["version"] != row["version"]:
            raise RpcError("另一台设备已修改这条想法，请先保留草稿并刷新", 409)
        if "restore" in data:
            if data["restore"] is not True or not row["deleted_at"]:
                raise RpcError("该记录不在回收站")
            content, tags, deleted = row["content"], row["tags"], None
        else:
            if row["deleted_at"]:
                raise RpcError("请先从回收站恢复", 409)
            content, tags = validate(data)
            deleted = None
        snapshot(connection, row)
        connection.execute("UPDATE thoughts SET content=?,tags=?,deleted_at=?,updated_at=?,version=version+1 WHERE id=?", (content, tags, deleted, now(), ident))
        enqueue(connection)
    return Reply(serialize(connection.execute("SELECT * FROM thoughts WHERE id=?", (ident,)).fetchone()))

def delete(connection, request):
    ident = request.params["ident"]
    data = request.payload()
    with connection:
        connection.execute("BEGIN IMMEDIATE")
        row = connection.execute("SELECT * FROM thoughts WHERE id=?", (ident,)).fetchone()
        if not row:
            raise RpcError("记录不存在", 404)
        if type(data.get("version")) is not int or data["version"] != row["version"]:
            raise RpcError("记录已经变更，请刷新后重试", 409)
        snapshot(connection, row)
        connection.execute("UPDATE thoughts SET deleted_at=?,updated_at=?,version=version+1 WHERE id=?", (now(), now(), ident))
        enqueue(connection)
    return Reply(dict(ok=True))

def history(connection, request):
    ident = request.params["ident"]
    rows = connection.execute("SELECT snapshot,saved_at FROM revisions WHERE thought_id=? ORDER BY revision_id DESC", (ident,)).fetchall()
    return Reply(dict(items=[dict(item=json.loads(row["snapshot"]), saved_at=row["saved_at"]) for row in rows]))

def export(connection, request):
    response = Reply(dict(schema_version=1, exported_at=now(), thoughts=[serialize(row) for row in connection.execute("SELECT * FROM thoughts ORDER BY created_at,id")], revisions=[dict(row) for row in connection.execute("SELECT * FROM revisions ORDER BY revision_id")]))
    return response
