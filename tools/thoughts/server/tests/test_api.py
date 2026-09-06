import sys
import hashlib
import importlib.util
import json
import sqlite3
import uuid
from pathlib import Path

import pytest
from werkzeug.security import generate_password_hash

sys.path.insert(0, str(Path(__file__).parents[1]))
import publisher

spec = importlib.util.spec_from_file_location("thoughts", Path(__file__).parents[1] / "app.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


@pytest.fixture
def app(tmp_path):
    application = module.create_app({"TESTING": True, "DATABASE": str(tmp_path / "test.sqlite"), "COOKIE_SECURE": False, "PUBLIC_ORIGIN": "https://notes.test"})
    with application.app_context(), application.db() as db:
        db.execute("INSERT INTO settings VALUES('password_hash',?)", (generate_password_hash("a-test-password-at-least-16-characters"),))
    return application


@pytest.fixture
def owner(app):
    client = app.test_client()
    response = client.post("/api/login", json={"password": "a-test-password-at-least-16-characters"})
    assert response.status_code == 200
    client.environ_base["HTTP_AUTHORIZATION"] = "Bearer " + response.json["token"]
    return client


def create(owner, **fields):
    payload = {"id": str(uuid.uuid4()), "content": "一条想法 <script>alert(1)</script>", "tags": ["学习"], **fields}
    return owner.post("/api/thoughts", json=payload)


def test_management_requires_login_and_no_public_api_exists(app, owner):
    note = create(owner).json
    assert "visibility" not in note
    anonymous = app.test_client()
    for path in ["/api/thoughts", "/api/thoughts?all=1", "/api/export", "/api/publication", f'/api/thoughts/{note["id"]}/history']:
        assert anonymous.get(path).status_code == 401
    assert anonymous.get("/api/public/thoughts").status_code == 404
    assert anonymous.post("/api/publish", json={}).status_code == 401
    assert anonymous.post("/api/thoughts", json={"content": "attack"}).status_code == 401
    assert anonymous.patch(f'/api/thoughts/{note["id"]}', json={"content": "attack"}).status_code == 401
    result = owner.get("/api/export")
    assert result.headers["Cache-Control"] == "no-store"
    assert "Access-Control-Allow-Origin" not in result.headers


def test_idempotent_offline_retries_do_not_duplicate_or_overwrite(owner):
    ident = str(uuid.uuid4())
    first = create(owner, id=ident)
    again = create(owner, id=ident)
    assert first.status_code == 201 and again.status_code == 200
    assert first.json == again.json
    assert create(owner, id=ident, content="different").status_code == 409
    assert len(owner.get("/api/thoughts").json["items"]) == 1


def test_optimistic_edit_conflict_and_history(app, owner):
    note = create(owner).json
    updated = owner.patch(f'/api/thoughts/{note["id"]}', json={**note, "content": "edited"})
    assert updated.status_code == 200 and updated.json["version"] == 2
    assert owner.patch(f'/api/thoughts/{note["id"]}', json={**note, "content": "stale"}).status_code == 409
    history = owner.get(f'/api/thoughts/{note["id"]}/history').json["items"]
    assert len(history) == 1 and history[0]["item"]["content"] == note["content"]


def test_trash_restore_enqueues_publication_and_retains_history(app, owner):
    note = create(owner).json
    path = f'/api/thoughts/{note["id"]}'
    assert owner.delete(path, json={"version": 0}).status_code == 409
    assert owner.delete(path, json={"version": 1}).status_code == 200
    assert owner.get("/api/thoughts").json["items"] == []
    trash = owner.get("/api/thoughts?trash=1").json["items"]
    assert len(trash) == 1 and trash[0]["deleted_at"]
    restored = owner.patch(path, json={"version": 2, "restore": True}).json
    assert "visibility" not in restored and restored["deleted_at"] is None
    assert owner.get("/api/publication").json["generation"] == 3
    assert len(owner.get(path + "/history").json["items"]) == 2


def test_cookie_flags_csrf_json_and_logout(app):
    client = app.test_client()
    login = client.post("/api/login", json={"password": "a-test-password-at-least-16-characters"}, headers={"Origin": "https://notes.test"})
    cookie = login.headers["Set-Cookie"]
    assert "HttpOnly" in cookie and "SameSite=Strict" in cookie
    assert client.post("/api/thoughts", json={"content": "attack"}, headers={"Origin": "https://evil.test"}).status_code == 403
    assert client.post("/api/thoughts", json={"content": "no origin"}).status_code == 403
    assert client.post("/api/thoughts", data="text", headers={"Origin": "https://notes.test"}).status_code == 415
    token = login.json["token"]
    with app.app_context():
        stored = app.db().execute("SELECT token FROM sessions").fetchone()[0]
        assert stored != token and stored == hashlib.sha256(token.encode()).hexdigest()
    assert client.post("/api/logout", json={}, headers={"Origin": "https://notes.test"}).status_code == 200
    assert client.get("/api/session", headers={"Authorization": "Bearer " + token}).status_code == 401


def test_login_rate_limit_persists_across_clients(app):
    for _ in range(8):
        assert app.test_client().post("/api/login", json={"password": "wrong"}).status_code == 401
    assert app.test_client().post("/api/login", json={"password": "wrong"}).status_code == 429


@pytest.mark.parametrize("payload", [{"content": ""}, {"content": "x", "tags": "oops"}, {"content": "x", "visibility": "unknown"}, {"content": "x", "id": "not-a-uuid"}, {"content": "x" * 20001}, {"content": "x", "tags": [1]}])
def test_invalid_input_is_rejected(owner, payload):
    assert owner.post("/api/thoughts", json=payload).status_code == 400


def test_search_pagination_export_and_restart(app, owner):
    create(owner, content="alpha", tags=["系统"])
    create(owner, content="beta", tags=["生活"])
    page = owner.get("/api/thoughts?limit=1").json
    assert len(page["items"]) == 1 and page["has_more"]
    assert len(owner.get("/api/thoughts?limit=1&offset=1").json["items"]) == 1
    assert len(owner.get("/api/thoughts?q=系统").json["items"]) == 1
    assert owner.get("/api/thoughts?q=%27%20OR%201=1--").json["items"] == []
    export = owner.get("/api/export")
    assert len(export.json["thoughts"]) == 2 and "password_hash" not in export.text
    restarted = module.create_app(dict(app.config))
    with restarted.app_context():
        assert restarted.db().execute("SELECT count(*) FROM thoughts").fetchone()[0] == 2


def test_publisher_fail_retry_and_deleted_records(app, owner):
    note = create(owner).json
    assert owner.get("/api/publication").json["pending"]
    def fail(_content):
        raise RuntimeError("network unavailable")
    assert not publisher.publish_once(app.config["DATABASE"], writer=fail)
    assert owner.get("/api/publication").json["last_error"] == "network unavailable"
    snapshots = []
    assert publisher.publish_once(app.config["DATABASE"], writer=snapshots.append)
    state = owner.get("/api/publication").json
    assert not state["pending"] and state["last_error"] is None
    exported = json.loads(snapshots[0])
    assert exported[0]["content"] == note["content"] and "visibility" not in exported[0]
    assert "password" not in snapshots[0] and "token" not in snapshots[0]
    assert publisher.publish_once(app.config["DATABASE"], writer=snapshots.append)
    assert len(snapshots) == 1  # No duplicate GitHub revision when nothing changed.
    owner.delete(f'/api/thoughts/{note["id"]}', json={"version": 1})
    assert publisher.publish_once(app.config["DATABASE"], writer=snapshots.append)
    assert json.loads(snapshots[-1]) == []


def test_write_during_gist_upload_stays_pending_and_publishers_are_serialized(app, owner):
    create(owner, content="first")
    def concurrent_write(_content):
        assert not publisher.publish_once(app.config["DATABASE"], writer=lambda _data: None)
        create(owner, content="during upload")
    assert publisher.publish_once(app.config["DATABASE"], writer=concurrent_write)
    assert owner.get("/api/publication").json["pending"]
    snapshots = []
    assert publisher.publish_once(app.config["DATABASE"], writer=snapshots.append)
    assert len(json.loads(snapshots[0])) == 2
    assert not owner.get("/api/publication").json["pending"]


def test_reader_uses_only_gist():
    root = Path(__file__).parents[4]
    source = (root / "assets/js/thoughts.js").read_text()
    assert "feed.dataset.gist" in source
    assert "/api/" not in source and "fetch(" in source
    assert "thoughts_gist_url" in (root / "_pages/thoughts.md").read_text()
