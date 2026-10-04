"""Consistent database and immutable inbox objects in a private, portable archive."""
from contextlib import closing
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tarfile
import tempfile

from database import connect
from modules.inbox.service import locked, ident, file_hash


def private_member(member):
    if not member.isfile() and not member.isdir():
        raise ValueError('Backup may contain only regular files and directories')
    member.uid = member.gid = 0
    member.uname = member.gname = ''
    member.mode = 0o700 if member.isdir() else 0o600
    return member


def full_backup(database, destination):
    database, destination = Path(database).resolve(), Path(destination).absolute()
    if os.path.lexists(destination):
        raise FileExistsError('Backup destination already exists')
    if destination.resolve() == database or (database.parent / 'inbox') in destination.resolve().parents:
        raise ValueError('Backup destination cannot replace application data')
    descriptor, temporary = tempfile.mkstemp(prefix='.myserver-backup-', dir=destination.parent)
    os.close(descriptor)
    try:
        with tempfile.TemporaryDirectory(prefix='.backup-snapshot-', dir=database.parent) as stage_name:
            stage = Path(stage_name)
            snapshot = stage / 'myserver.sqlite'
            # Files become immutable at commit. Pin their inodes while holding the same
            # lock as commit/delete, then let normal uploads proceed during tar writing.
            with locked(database) as inbox:
                with connect(database, read_only=True) as source, closing(sqlite3.connect(snapshot)) as target:
                    source.backup(target)
                    target.row_factory = sqlite3.Row
                    if target.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
                        raise RuntimeError('Database backup failed integrity check')
                    rows = target.execute("SELECT f.* FROM inbox_files f JOIN inbox_items i ON i.id=f.item_id WHERE i.state='ready'").fetchall()
                    for row in rows:
                        item_id, file_id = ident(row['item_id']), ident(row['id'])
                        source_file = inbox / 'objects' / item_id / file_id
                        if source_file.is_symlink() or not source_file.is_file() or source_file.stat().st_size != row['size']:
                            raise RuntimeError('Inbox object is missing or invalid; backup aborted')
                        folder = stage / 'inbox/objects' / item_id
                        folder.mkdir(parents=True, mode=0o700, exist_ok=True)
                        os.link(source_file, folder / file_id, follow_symlinks=False)
                    # Incomplete transfers are deliberately excluded and cannot be
                    # resumed from an archive that does not contain their partial bytes.
                    with target:
                        target.execute("DELETE FROM inbox_files WHERE item_id IN (SELECT id FROM inbox_items WHERE state='uploading')")
                        target.execute("UPDATE inbox_items SET state='deleted',title='',text='',note='',source='',manifest='{}' WHERE state='uploading'")
                    target.execute('PRAGMA wal_checkpoint(TRUNCATE)')
                snapshot.chmod(0o600)
            (stage / 'manifest.json').write_text(json.dumps(dict(
                format='myserver-backup', version=1,
                created_at=datetime.now(timezone.utc).isoformat(),
                contents=['database', 'inbox-ready-files'],
            )) + '\n')
            with tarfile.open(temporary, 'w', dereference=True) as archive:
                archive.add(snapshot, arcname='myserver.sqlite', filter=private_member)
                archive.add(stage / 'manifest.json', arcname='manifest.json', filter=private_member)
                if (stage / 'inbox').exists():
                    archive.add(stage / 'inbox', arcname='inbox', filter=private_member)
            with open(temporary, 'rb') as stream:
                os.fsync(stream.fileno())
            # Atomic publication, with no overwrite even if another backup won a race.
            os.link(temporary, destination)
    finally:
        Path(temporary).unlink(missing_ok=True)


def unpack_backup(archive_path, destination):
    """Validate and unpack into a NEW directory. Never alter a running installation."""
    destination = Path(destination).absolute()
    destination.mkdir(mode=0o700, exist_ok=False)
    try:
        names = set()
        with tarfile.open(archive_path, 'r:') as archive:
            for member in archive:
                parts = Path(member.name).parts
                allowed = member.name in ('myserver.sqlite', 'manifest.json', 'inbox', 'inbox/objects')
                if len(parts) in (3, 4) and parts[:2] == ('inbox', 'objects'):
                    ident(parts[2])
                    if len(parts) == 4:
                        ident(parts[3])
                    allowed = True
                if not allowed or member.name in names or not (member.isfile() or member.isdir()):
                    raise ValueError('Invalid backup member')
                if member.isdir() and member.name in ('myserver.sqlite', 'manifest.json'):
                    raise ValueError('Invalid backup member type')
                if member.isfile() and member.name not in ('myserver.sqlite', 'manifest.json') and len(parts) != 4:
                    raise ValueError('Invalid backup object path')
                names.add(member.name)
                output = destination.joinpath(*parts)
                if member.isdir():
                    output.mkdir(parents=True, mode=0o700, exist_ok=True)
                else:
                    output.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
                    fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                    with os.fdopen(fd, 'wb') as target, archive.extractfile(member) as source:
                        shutil.copyfileobj(source, target, 1024 * 1024)
        manifest = json.loads((destination / 'manifest.json').read_text())
        if manifest.get('format') != 'myserver-backup' or manifest.get('version') != 1:
            raise ValueError('Unsupported backup format')
        with connect(destination / 'myserver.sqlite', read_only=True) as connection:
            if connection.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
                raise ValueError('Backup database failed integrity check')
            expected = set()
            for row in connection.execute("SELECT f.* FROM inbox_files f JOIN inbox_items i ON i.id=f.item_id WHERE i.state='ready'"):
                item_id, file_id = ident(row['item_id']), ident(row['id'])
                relative = 'inbox/objects/' + item_id + '/' + file_id
                file = destination / relative
                if not file.is_file() or file.stat().st_size != row['size'] or file_hash(file) != row['sha256']:
                    raise ValueError('Backup file failed checksum verification')
                expected.add(relative)
            actual = {name for name in names if len(Path(name).parts) == 4}
            if expected != actual:
                raise ValueError('Backup contains unreferenced objects')
        return destination
    except BaseException:
        shutil.rmtree(destination)
        raise
