"""SQLite lifecycle shared by RPC, initialization and backups."""
from contextlib import contextmanager
from datetime import datetime, timezone
import os
from pathlib import Path
import sqlite3

SCHEMA_VERSION = 1


def now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


@contextmanager
def connect(path, read_only=False):
    path = Path(path).resolve()
    connection = sqlite3.connect(path.as_uri() + ('?mode=ro' if read_only else '?mode=rw'), uri=True, timeout=15)
    connection.row_factory = sqlite3.Row
    connection.execute('PRAGMA foreign_keys=ON')
    try:
        yield connection
    finally:
        connection.close()


def initialize(path):
    import modules
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor = os.open(path, os.O_CREAT | os.O_RDWR, 0o600)
    os.close(descriptor)
    path.chmod(0o600)
    with connect(path) as connection:
        version = connection.execute('PRAGMA user_version').fetchone()[0]
        if version > SCHEMA_VERSION:
            raise RuntimeError('数据库版本高于当前程序，请更新服务端')
        connection.execute('PRAGMA journal_mode=WAL')
        modules.initialize(connection)
        if version == 0:
            with connection:
                # SSH authenticates devices; obsolete web credentials have no role in this schema.
                for table in ('sessions', 'login_attempts', 'settings'):
                    connection.execute('DROP TABLE IF EXISTS ' + table)
                connection.execute('PRAGMA user_version=1')
