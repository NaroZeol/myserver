"""Local initialization, consistency checks and SQLite online backups."""
import argparse
import os
from pathlib import Path
import sqlite3

from database import connect, initialize, SCHEMA_VERSION
from paths import database_path


def check(path):
    from service import handle
    import modules
    with connect(path, read_only=True) as connection:
        if connection.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
            raise RuntimeError('数据库完整性检查失败')
        if connection.execute('PRAGMA user_version').fetchone()[0] != SCHEMA_VERSION:
            raise RuntimeError('请先初始化数据库')
    if handle(dict(path='/system', method='GET'), modules.capabilities(), path)['status'] != 200:
        raise RuntimeError('RPC 检查失败')


def backup(path, destination):
    destination = Path(destination)
    if destination.resolve() == Path(path).resolve():
        raise ValueError('备份文件不能覆盖数据库')
    descriptor = os.open(destination, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    os.close(descriptor)
    try:
        with connect(path, read_only=True) as source, sqlite3.connect(destination) as target:
            source.backup(target)
            if target.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
                raise RuntimeError('备份完整性检查失败')
    except BaseException:
        destination.unlink(missing_ok=True)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['init', 'check', 'backup'])
    parser.add_argument('file', nargs='?', type=Path)
    args = parser.parse_args()
    path = database_path()
    if args.command == 'init':
        initialize(path)
    elif args.command == 'check':
        check(path)
    else:
        if not args.file: parser.error('backup requires a destination')
        backup(path, args.file)
    print('myserver ' + args.command + ': OK')


if __name__ == '__main__':
    main()
