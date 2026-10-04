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
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('init')
    commands.add_parser('check')
    for name in ('backup', 'backup-full'):
        commands.add_parser(name).add_argument('file', type=Path)
    unpack = commands.add_parser('backup-unpack')
    unpack.add_argument('archive', type=Path)
    unpack.add_argument('directory', type=Path)
    from modules.inbox.cli import configure, run
    configure(commands.add_parser('inbox', help='Private cross-device inbox'))
    args = parser.parse_args()
    path = database_path()
    os.umask(0o077)
    if args.command == 'init':
        initialize(path)
    elif args.command == 'check':
        check(path)
    elif args.command == 'backup':
        backup(path, args.file)
    elif args.command == 'backup-full':
        from backups import full_backup
        full_backup(path, args.file)
    elif args.command == 'backup-unpack':
        from backups import unpack_backup
        unpack_backup(args.archive, args.directory)
    else:
        from rpc import RpcError
        try:
            run(args, path)
        except (RpcError, OSError, ValueError) as error:
            parser.exit(1, str(error) + '\n')
        return
    print('myserver ' + args.command + ': OK')


if __name__ == '__main__':
    main()
