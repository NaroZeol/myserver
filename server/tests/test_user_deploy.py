"""Exercise deployment in an isolated home with a simulated user manager."""
import json
import os
from pathlib import Path
import shutil
import subprocess

import pytest

DEPLOY = Path(__file__).parents[2] / 'deploy'


@pytest.fixture
def deployment(tmp_path):
    home = tmp_path / 'user home%'
    root = home / '.local/share/myserver'
    (root / 'app').mkdir(parents=True)
    for name in ('app.py', 'ssh_gateway.py'):
        (root / 'app' / name).touch()
    shutil.copytree(DEPLOY, root / 'deploy')
    commands = tmp_path / 'bin'
    commands.mkdir()
    script = '''#!/usr/bin/python3
import json,os,sys
from pathlib import Path
with open(os.environ['COMMAND_LOG'],'a') as out: out.write(json.dumps([Path(sys.argv[0]).name,*sys.argv[1:]])+'\\n')
if sys.argv[1:] == ['--user','show-environment'] and os.environ.get('MISSING_BUS'): sys.exit(1)
if Path(sys.argv[0]).name == 'curl' and os.environ.get('HEALTH_FAIL'): sys.exit(22)
'''
    for name in ('systemctl', 'curl'):
        (commands / name).write_text(script)
        (commands / name).chmod(0o700)
    log = tmp_path / 'commands.jsonl'
    env = dict(os.environ, HOME=str(home), XDG_CONFIG_HOME=str(tmp_path / 'config'),
               PATH=str(commands) + ':' + os.environ['PATH'], COMMAND_LOG=str(log))
    return root, env, log


def run(deployment, *arguments, **settings):
    root, env, log = deployment
    result = subprocess.run(['bash', str(root / 'deploy/install-user.sh'), *arguments],
                            env=dict(env, **settings), text=True, capture_output=True)
    return result, [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []


def test_user_units_install_without_starting_or_changing_system_units(deployment):
    result, calls = run(deployment, '--units-only')
    assert result.returncode == 0, result.stderr
    root, env, _ = deployment
    units = Path(env['XDG_CONFIG_HOME']) / 'systemd/user'
    assert len(list(units.glob('myserver*'))) == 5
    unit = (units / 'myserver.service').read_text()
    assert 'WantedBy=default.target' in unit
    assert '\nUser=' not in unit and '\nGroup=' not in unit
    assert 'NoNewPrivileges=true' in unit and 'UMask=0077' in unit
    assert 'WorkingDirectory=' + str(root).replace('%','%%') + '/app\n' in unit
    assert '@DATA_DIR@' not in unit
    assert calls == [['systemctl', '--user', 'show-environment'], ['systemctl', '--user', 'daemon-reload']]
    assert not (root / 'myserver.sqlite').exists()


def test_activation_uses_user_manager_and_checks_api_before_starting_timers(deployment):
    result, calls = run(deployment)
    assert result.returncode == 0, result.stderr
    assert all(call[1] == '--user' for call in calls if call[0] == 'systemctl')
    health = next(i for i, call in enumerate(calls) if call[0] == 'curl')
    assert calls[health + 1] == ['systemctl', '--user', 'start', 'myserver-backup.service']
    assert calls[health + 2] == ['systemctl', '--user', 'start', 'myserver-backup.timer', 'myserver-publish.timer']


def test_missing_user_bus_does_not_install_units(deployment):
    result, calls = run(deployment, '--units-only', MISSING_BUS='1')
    assert result.returncode != 0
    assert not (Path(deployment[1]['XDG_CONFIG_HOME']) / 'systemd/user').exists()
    assert len(calls) == 1


def test_failed_health_check_does_not_start_scheduled_jobs(deployment):
    result, calls = run(deployment, HEALTH_FAIL='1')
    assert result.returncode != 0
    assert not any(call[:3] == ['systemctl', '--user', 'start'] for call in calls)
