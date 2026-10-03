"""Exercise staging, checks and rollback using an isolated user manager."""
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess

import pytest
from database import initialize

REPO = Path(__file__).parents[2]
DEPLOY = REPO / 'deploy'


@pytest.fixture
def deployment(tmp_path):
    home = tmp_path / 'user home%'
    root = home / '.local/share/myserver'
    shutil.copytree(REPO / 'server', root / 'app', ignore=shutil.ignore_patterns('__pycache__', 'tests'))
    shutil.copytree(DEPLOY, root / 'deploy')
    commands = tmp_path / 'bin'
    commands.mkdir()
    script = '''#!/usr/bin/python3
import json,os,sys
from pathlib import Path
with open(os.environ['COMMAND_LOG'],'a') as out: out.write(json.dumps(['systemctl',*sys.argv[1:]])+'\\n')
assert sys.argv[1]=='--user'
verb,*names=sys.argv[2:]
if verb=='show-environment': sys.exit(1 if os.environ.get('MISSING_BUS') else 0)
path=Path(os.environ['MANAGER_STATE'])
state=json.loads(path.read_text()) if path.exists() else {'active':[],'enabled':[]}
if verb in ('is-active','is-enabled'): sys.exit(0 if names[-1] in state['active' if verb=='is-active' else 'enabled'] else 3)
if verb in ('start','stop','enable','disable'):
    field='active' if verb in ('start','stop') else 'enabled'
    for name in names:
        if verb in ('start','enable'):
            if name not in state[field]: state[field].append(name)
        elif name in state[field]: state[field].remove(name)
    path.write_text(json.dumps(state))
'''
    (commands / 'systemctl').write_text(script)
    (commands / 'systemctl').chmod(0o700)
    log = tmp_path / 'commands.jsonl'
    env = dict(os.environ, HOME=str(home), MYSERVER_DATABASE=str(root / 'myserver.sqlite'), XDG_CONFIG_HOME=str(tmp_path / 'config'),
               PATH=str(commands) + ':' + os.environ['PATH'], COMMAND_LOG=str(log), MANAGER_STATE=str(tmp_path / 'state.json'))
    return root, env, log


def run(deployment, *arguments, script='install-user.sh', **settings):
    root, env, log = deployment
    result = subprocess.run(['bash', str(root / 'deploy' / script), *arguments],
                            env=dict(env, **settings), text=True, capture_output=True, timeout=20)
    return result, [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []


def test_user_units_install_without_starting_or_changing_system_units(deployment):
    result, calls = run(deployment, '--units-only')
    assert result.returncode == 0, result.stderr
    root, env, _ = deployment
    units = Path(env['XDG_CONFIG_HOME']) / 'systemd/user'
    assert len(list(units.glob('myserver*'))) == 4
    assert not (units / 'myserver.service').exists()
    for name in ('myserver-backup.service', 'myserver-publish.service'):
        unit = (units / name).read_text()
        assert '\nUser=' not in unit and '\nGroup=' not in unit
        assert 'NoNewPrivileges=true' in unit and 'UMask=0077' in unit
        assert 'WorkingDirectory=' + str(root).replace('%','%%') + '/app\n' in unit
        assert '@DATA_DIR@' not in unit
    assert calls == [['systemctl', '--user', 'show-environment'], ['systemctl', '--user', 'daemon-reload']]
    assert not (root / 'myserver.sqlite').exists()


def test_installation_initializes_and_checks_before_starting_timers(deployment):
    result, calls = run(deployment)
    assert result.returncode == 0, result.stderr
    assert 'myserver check: OK' in result.stdout
    assert all(call[1] == '--user' for call in calls)
    assert calls[-3:] == [
        ['systemctl', '--user', 'start', 'myserver-backup.service'],
        ['systemctl', '--user', 'enable', 'myserver-backup.timer', 'myserver-publish.timer'],
        ['systemctl', '--user', 'start', 'myserver-backup.timer', 'myserver-publish.timer']]
    assert (deployment[0] / 'myserver.sqlite').exists()


def test_missing_user_bus_does_not_install_units(deployment):
    result, calls = run(deployment, '--units-only', MISSING_BUS='1')
    assert result.returncode != 0
    assert not (Path(deployment[1]['XDG_CONFIG_HOME']) / 'systemd/user').exists()
    assert len(calls) == 1


def test_invalid_database_does_not_start_scheduled_jobs(deployment):
    (deployment[0] / 'myserver.sqlite').write_text('broken')
    result, calls = run(deployment)
    assert result.returncode != 0
    assert not any(call[:3] == ['systemctl', '--user', 'start'] for call in calls)


def stage(root):
    shutil.copytree(root / 'app', root / 'staged/app')
    shutil.copytree(root / 'deploy', root / 'staged/deploy')


def test_activation_preserves_data_and_keeps_private_rollback_snapshot(deployment):
    root, env, _ = deployment
    initialize(root / 'myserver.sqlite')
    (root / 'app/bundle-marker').write_text('old')
    stage(root)
    (root / 'staged/app/bundle-marker').write_text('new')
    result, _ = run(deployment, script='activate.sh')
    assert result.returncode == 0, result.stderr
    assert (root / 'app/bundle-marker').read_text() == 'new'
    assert not (root / 'maintenance').exists() and not (root / 'staged').exists()
    archive, = (root / 'archives').iterdir()
    assert (archive / 'app/bundle-marker').read_text() == 'old'
    assert (archive / 'myserver.sqlite').stat().st_mode & 0o777 == 0o600
    assert set(json.loads(Path(env['MANAGER_STATE']).read_text())['enabled']) == {'myserver-publish.timer','myserver-backup.timer'}


def test_failed_activation_restores_code_database_units_and_timer_states(deployment):
    root, env, _ = deployment
    assert run(deployment)[0].returncode == 0
    (root / 'app/bundle-marker').write_text('old')
    units = Path(env['XDG_CONFIG_HOME']) / 'systemd/user'
    old_units = {p.name: p.read_bytes() for p in units.iterdir()}
    old_state = json.loads(Path(env['MANAGER_STATE']).read_text())
    with sqlite3.connect(root / 'myserver.sqlite') as db:
        # Verify rollback even if initialization has changed the schema.
        db.executescript('PRAGMA user_version=0; CREATE TABLE sessions(token); INSERT INTO sessions VALUES("old");')
    stage(root)
    (root / 'staged/app/service.py').write_text('raise RuntimeError("failed health check")\n')
    result, _ = run(deployment, script='activate.sh')
    assert result.returncode != 0
    assert (root / 'app/bundle-marker').read_text() == 'old'
    assert not (root / 'maintenance').exists()
    with sqlite3.connect(root / 'myserver.sqlite') as db:
        assert db.execute('SELECT token FROM sessions').fetchone() == ('old',)
        assert db.execute('PRAGMA user_version').fetchone()[0] == 0
    assert {p.name: p.read_bytes() for p in units.iterdir()} == old_units
    assert json.loads(Path(env['MANAGER_STATE']).read_text()) == old_state


def test_existing_maintenance_is_not_silently_bypassed(deployment):
    root, _, _ = deployment
    stage(root)
    (root / 'maintenance').touch()
    result, _ = run(deployment, script='activate.sh')
    assert result.returncode != 0
    assert (root / 'maintenance').exists() and (root / 'staged/app').exists()
