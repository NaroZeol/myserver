import hashlib
import io
import json
from pathlib import Path
import sqlite3
import tarfile
import uuid

import pytest

from backups import full_backup, unpack_backup
from database import initialize
from rpc import RpcError


@pytest.fixture
def stored(tmp_path):
    database = tmp_path / 'myserver.sqlite'
    initialize(database)
    item, file = str(uuid.uuid4()), str(uuid.uuid4())
    content = b'private fixture bytes\x00\xff' * 100
    blob = tmp_path / 'inbox/objects' / item / file
    blob.parent.mkdir(parents=True)
    blob.write_bytes(content)
    with sqlite3.connect(database) as connection:
        connection.execute('INSERT INTO inbox_items VALUES(?,?,?,?,?,?,?,?,?)',
                           (item, 'fixture', '', '', 'test', '2026-01-01', 0, 'ready', '{}'))
        connection.execute('INSERT INTO inbox_files VALUES(?,?,?,?,?,?,?)',
                           (item, file, 'file.bin', 'application/octet-stream', len(content), hashlib.sha256(content).hexdigest(), 0))
    return database, blob, content


def test_full_archive_restores_real_bytes_and_private_modes(stored, tmp_path):
    database, blob, content = stored
    archive = tmp_path / 'backup.tar'
    full_backup(database, archive)
    assert archive.stat().st_mode & 0o777 == 0o600
    with tarfile.open(archive) as source:
        assert all(not member.issym() and not member.islnk() and not member.uname for member in source)
    restored = unpack_backup(archive, tmp_path / 'restored')
    copied = restored / blob.relative_to(tmp_path)
    assert copied.read_bytes() == content
    assert copied.stat().st_mode & 0o777 == 0o600
    assert (restored / 'myserver.sqlite').stat().st_mode & 0o777 == 0o600


def test_snapshot_survives_concurrent_deletion_after_pinning(stored, tmp_path, monkeypatch):
    database, blob, content = stored
    original = tarfile.open
    def open_archive(*args, **kwargs):
        if len(args) > 1 and args[1] == 'w':
            blob.unlink()
            with sqlite3.connect(database) as connection:
                connection.execute("UPDATE inbox_items SET state='deleted'")
        return original(*args, **kwargs)
    monkeypatch.setattr(tarfile, 'open', open_archive)
    full_backup(database, tmp_path / 'backup.tar')
    restored = unpack_backup(tmp_path / 'backup.tar', tmp_path / 'restored')
    assert (restored / blob.relative_to(tmp_path)).read_bytes() == content
    with sqlite3.connect(restored / 'myserver.sqlite') as connection:
        assert connection.execute('SELECT state FROM inbox_items').fetchone()[0] == 'ready'


def test_backup_does_not_claim_missing_objects_or_overwrite_destination(stored, tmp_path):
    database, blob, _ = stored
    destination = tmp_path / 'existing.tar'
    destination.write_bytes(b'keep')
    with pytest.raises(FileExistsError):
        full_backup(database, destination)
    assert destination.read_bytes() == b'keep'
    blob.unlink()
    with pytest.raises(RuntimeError):
        full_backup(database, tmp_path / 'missing.tar')
    assert not (tmp_path / 'missing.tar').exists()
    assert not list(tmp_path.glob('.backup-snapshot-*'))
    assert not list(tmp_path.glob('.myserver-backup-*'))


def test_partial_transfers_are_not_restored_as_resumable(stored, tmp_path):
    database, _, _ = stored
    with sqlite3.connect(database) as connection:
        connection.execute('INSERT INTO inbox_items VALUES(?,?,?,?,?,?,?,?,?)',
                           (str(uuid.uuid4()), 'partial', 'private unfinished text', '', 'test', '2026-01-01', 0, 'uploading', '{}'))
    full_backup(database, tmp_path / 'backup.tar')
    restored = unpack_backup(tmp_path / 'backup.tar', tmp_path / 'restored')
    with sqlite3.connect(restored / 'myserver.sqlite') as connection:
        assert connection.execute("SELECT count(*) FROM inbox_items WHERE state='uploading'").fetchone()[0] == 0
        assert connection.execute("SELECT count(*) FROM inbox_items WHERE text='private unfinished text'").fetchone()[0] == 0
    with sqlite3.connect(database) as connection:
        assert connection.execute("SELECT count(*) FROM inbox_items WHERE state='uploading' AND text='private unfinished text'").fetchone()[0] == 1


@pytest.mark.parametrize('name,kind', [('../escaped', 'file'), ('/absolute', 'file'), ('inbox/objects/link', 'symlink')])
def test_unpack_rejects_unsafe_members_without_writing_outside_destination(tmp_path, name, kind):
    archive = tmp_path / 'unsafe.tar'
    with tarfile.open(archive, 'w') as output:
        entry = tarfile.TarInfo(name)
        if kind == 'symlink':
            entry.type, entry.linkname = tarfile.SYMTYPE, '/tmp'
            output.addfile(entry)
        else:
            entry.size = 3
            output.addfile(entry, io.BytesIO(b'bad'))
    with pytest.raises((ValueError, RpcError)):
        unpack_backup(archive, tmp_path / 'restored')
    assert not (tmp_path / 'restored').exists()
    assert not (tmp_path / 'escaped').exists()


def test_unpack_verifies_digests_and_never_replaces_existing_directory(stored, tmp_path):
    database, blob, content = stored
    blob.write_bytes(b'x' * len(content))
    full_backup(database, tmp_path / 'bad.tar')
    with pytest.raises(ValueError, match='checksum'):
        unpack_backup(tmp_path / 'bad.tar', tmp_path / 'restore')
    assert not (tmp_path / 'restore').exists()
    existing = tmp_path / 'existing'
    existing.mkdir()
    (existing / 'keep').write_text('keep')
    with pytest.raises(FileExistsError):
        unpack_backup(tmp_path / 'bad.tar', existing)
    assert (existing / 'keep').read_text() == 'keep'
