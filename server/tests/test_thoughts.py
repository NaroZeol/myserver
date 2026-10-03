import json
import uuid

import pytest
from database import initialize
from modules.thoughts import publisher


def create(rpc, **fields):
    payload = {"id": str(uuid.uuid4()), "content": "一条想法 <script>alert(1)</script>", "tags": ["学习"], **fields}
    return rpc("/thoughts", "POST", payload)


def test_idempotent_offline_retries_do_not_duplicate_or_overwrite(rpc):
    ident = str(uuid.uuid4())
    first = create(rpc, id=ident)
    again = create(rpc, id=ident)
    assert first["status"] == 201 and again["status"] == 200
    assert first["body"] == again["body"]
    assert create(rpc, id=ident, content="different")["status"] == 409
    assert len(rpc("/thoughts")["body"]["items"]) == 1


def test_optimistic_edit_conflict_and_history(database, rpc):
    note = create(rpc)["body"]
    updated = rpc(f'/thoughts/{note["id"]}', "PATCH", {**note, "content": "edited"})
    assert updated["status"] == 200 and updated["body"]["version"] == 2
    assert rpc(f'/thoughts/{note["id"]}', "PATCH", {**note, "content": "stale"})["status"] == 409
    history = rpc(f'/thoughts/{note["id"]}/history')["body"]["items"]
    assert len(history) == 1 and history[0]["item"]["content"] == note["content"]


def test_trash_restore_enqueues_publication_and_retains_history(database, rpc):
    note = create(rpc)["body"]
    path = f'/thoughts/{note["id"]}'
    assert rpc(path, "DELETE", {"version": 0})["status"] == 409
    assert rpc(path, "DELETE", {"version": 1})["status"] == 200
    assert rpc("/thoughts")["body"]["items"] == []
    trash = rpc("/thoughts?trash=1")["body"]["items"]
    assert len(trash) == 1 and trash[0]["deleted_at"]
    restored = rpc(path, "PATCH", {"version": 2, "restore": True})["body"]
    assert "visibility" not in restored and restored["deleted_at"] is None
    assert rpc("/publication")["body"]["generation"] == 3
    assert len(rpc(path + "/history")["body"]["items"]) == 2


@pytest.mark.parametrize("payload", [{"content": ""}, {"content": "x", "tags": "oops"}, {"content": "x", "visibility": "unknown"}, {"content": "x", "id": "not-a-uuid"}, {"content": "x" * 20001}, {"content": "x", "tags": [1]}])
def test_invalid_input_is_rejected(rpc, payload):
    assert rpc("/thoughts", "POST", payload)["status"] == 400


def test_search_pagination_export_and_restart(database, rpc):
    create(rpc, content="alpha", tags=["系统"])
    create(rpc, content="beta", tags=["生活"])
    page = rpc("/thoughts?limit=1")["body"]
    assert len(page["items"]) == 1 and page["has_more"]
    assert len(rpc("/thoughts?limit=1&offset=1")["body"]["items"]) == 1
    assert len(rpc("/thoughts?q=系统")["body"]["items"]) == 1
    assert rpc("/thoughts?q=%27%20OR%201=1--")["body"]["items"] == []
    export = rpc("/export")
    assert len(export["body"]["thoughts"]) == 2 and "password_hash" not in json.dumps(export)
    initialize(database)
    assert len(rpc("/thoughts")["body"]["items"]) == 2


def test_publisher_fail_retry_and_deleted_records(database, rpc):
    note = create(rpc)["body"]
    assert rpc("/publication")["body"]["pending"]
    def fail(_content):
        raise RuntimeError("network unavailable")
    assert not publisher.publish_once(database, writer=fail)
    assert rpc("/publication")["body"]["last_error"] == "network unavailable"
    snapshots = []
    assert publisher.publish_once(database, writer=snapshots.append)
    state = rpc("/publication")["body"]
    assert not state["pending"] and state["last_error"] is None
    exported = json.loads(snapshots[0])
    assert exported[0]["content"] == note["content"] and "visibility" not in exported[0]
    assert "password" not in snapshots[0] and "token" not in snapshots[0]
    assert publisher.publish_once(database, writer=snapshots.append)
    assert len(snapshots) == 1  # No duplicate GitHub revision when nothing changed.
    rpc(f'/thoughts/{note["id"]}', "DELETE", {"version": 1})
    assert publisher.publish_once(database, writer=snapshots.append)
    assert json.loads(snapshots[-1]) == []


def test_write_during_gist_upload_stays_pending_and_publishers_are_serialized(database, rpc):
    create(rpc, content="first")
    def concurrent_write(_content):
        assert not publisher.publish_once(database, writer=lambda _data: None)
        create(rpc, content="during upload")
    assert publisher.publish_once(database, writer=concurrent_write)
    assert rpc("/publication")["body"]["pending"]
    snapshots = []
    assert publisher.publish_once(database, writer=snapshots.append)
    assert len(json.loads(snapshots[0])) == 2
    assert not rpc("/publication")["body"]["pending"]
