#!/usr/bin/env python3
"""Activate a staged SSH service bundle with a private rollback snapshot."""
from contextlib import ExitStack
from datetime import datetime, timezone
import fcntl
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import uuid

UNITS = ('myserver-backup.service', 'myserver-backup.timer', 'myserver-publish.service', 'myserver-publish.timer')
TIMERS = tuple(name for name in UNITS if name.endswith('.timer'))


def manager(*args, check=True):
    return subprocess.run(['systemctl', '--user', *args], check=check, capture_output=True, text=True)


def state(name, verb):
    return manager(verb, '--quiet', name, check=False).returncode == 0


def stop_jobs():
    manager('stop', *TIMERS, check=False)
    manager('stop', *(name for name in UNITS if name.endswith('.service')), check=False)
    if any(state(name, 'is-active') for name in UNITS):
        raise RuntimeError('Could not stop scheduled jobs')


def snapshot(source, target):
    with sqlite3.connect(source.as_uri() + '?mode=ro', uri=True) as old, sqlite3.connect(target) as new:
        old.backup(new)
        if new.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
            raise RuntimeError('Database backup failed integrity check')
    target.chmod(0o600)


def activate(root, units):
    staged = root / 'staged'
    if not (staged / 'app/cli.py').is_file() or not (staged / 'deploy/install-user.sh').is_file():
        raise RuntimeError('Upload a complete bundle with stage.sh first')
    manager('show-environment')
    os.umask(0o077)
    (root / 'devices').mkdir(mode=0o700, exist_ok=True)
    (root / 'backups').mkdir(mode=0o700, exist_ok=True)
    with ExitStack() as stack:
        staging = stack.enter_context((root / 'staging.lock').open('a'))
        fcntl.flock(staging, fcntl.LOCK_EX | fcntl.LOCK_NB)
        registration = stack.enter_context((root / 'devices/register.lock').open('a'))
        fcntl.flock(registration, fcntl.LOCK_EX)
        marker = root / 'maintenance'
        marker.touch(mode=0o600, exist_ok=False)
        # New RPCs fail promptly. Existing requests finish before code or data changes.
        operations = stack.enter_context((root / 'operations.lock').open('a'))
        fcntl.flock(operations, fcntl.LOCK_EX)
        archive = root / 'archives' / ('release-' + datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S-') + uuid.uuid4().hex[:8])
        archive.mkdir(parents=True, mode=0o700)
        active = [name for name in UNITS if state(name, 'is-active')]
        enabled = [name for name in TIMERS if state(name, 'is-enabled')]
        previous_units = {name: (units / name).read_bytes() if (units / name).is_file() else None for name in UNITS}
        for name, value in previous_units.items():
            if value is not None:
                (archive / name).write_bytes(value)
        database = root / 'myserver.sqlite'
        existed = database.exists()
        saved = False
        moved = []
        try:
            stop_jobs()
            if existed:
                snapshot(database, archive / 'myserver.sqlite')
                saved = True
            for name in ('app', 'deploy'):
                if (root / name).exists():
                    (root / name).rename(archive / name)
                moved.append(name)
                (staged / name).rename(root / name)
            env = dict(os.environ, MYSERVER_DATABASE=str(database))
            subprocess.run(['bash', str(root / 'deploy/install-user.sh')], check=True, env=env)
        except BaseException:
            # Keep the gate closed if rollback itself fails; never expose mixed state.
            stop_jobs()
            for name in reversed(moved):
                if (root / name).exists():
                    (root / name).rename(archive / ('failed-' + name))
                if (archive / name).exists():
                    (archive / name).rename(root / name)
            if saved or not existed:
                for suffix in ('', '-wal', '-shm'):
                    Path(str(database) + suffix).unlink(missing_ok=True)
                if saved:
                    shutil.copy2(archive / 'myserver.sqlite', database)
                    database.chmod(0o600)
            newly_enabled = [name for name in TIMERS if name not in enabled]
            if newly_enabled:
                manager('disable', *newly_enabled, check=False)
            units.mkdir(parents=True, exist_ok=True)
            for name, value in previous_units.items():
                if value is None:
                    (units / name).unlink(missing_ok=True)
                else:
                    (units / name).write_bytes(value)
            manager('daemon-reload')
            if enabled:
                manager('enable', *enabled)
            if active:
                manager('start', *active)
            marker.unlink()
            raise
        marker.unlink()
        staged.rmdir()
    print('myserver activated. SSH RPC is ready; publication and backup timers are enabled.')


if __name__ == '__main__':
    if os.geteuid() == 0:
        raise SystemExit('Run as the service user, without sudo.')
    root = Path.home() / '.local/share/myserver'
    units = Path(os.environ.get('XDG_CONFIG_HOME') or Path.home() / '.config') / 'systemd/user'
    activate(root, units)
