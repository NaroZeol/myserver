"""Deployment input must stay configurable without bypassing credential checks."""
import importlib.util
import io
import json
import os
import sqlite3
import subprocess
from pathlib import Path

import pytest

DEPLOY = Path(__file__).parents[2] / 'deploy'
spec = importlib.util.spec_from_file_location('gist_setup', DEPLOY / 'configure-gist.py')
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


@pytest.fixture
def injected(tmp_path, monkeypatch):
    for key in ('THOUGHTS_GIST_ID', 'THOUGHTS_GIST_FILE', 'THOUGHTS_GIST_TOKEN_FILE'):
        monkeypatch.delenv(key, raising=False)
    token_file = tmp_path / 'mounted secret'
    token_file.write_text('test-only-credential\n')
    token_file.chmod(0o600)
    monkeypatch.setenv('THOUGHTS_GIST_ID', 'abcde12345')
    monkeypatch.setenv('THOUGHTS_GIST_FILE', 'notes.json')
    monkeypatch.setenv('THOUGHTS_GIST_TOKEN_FILE', str(token_file))
    def prompt_forbidden(*args):
        raise AssertionError('Configuration unexpectedly prompted for input')
    monkeypatch.setattr('builtins.input', prompt_forbidden)
    monkeypatch.setattr(setup.getpass, 'getpass', prompt_forbidden)
    return tmp_path / 'data'


def github(monkeypatch, owner=42, scopes='gist', failure=False):
    calls = []
    def open_request(request, timeout):
        assert timeout == 20
        assert request.get_header('Authorization') == 'Bearer test-only-credential'
        calls.append(request.full_url)
        if failure:
            raise OSError('test-only-credential must not appear in errors')
        value = {'id': 42} if request.full_url.endswith('/user') else {'owner': {'id': owner}}
        response = io.StringIO(json.dumps(value))
        response.headers = {'X-OAuth-Scopes': scopes}
        return response
    monkeypatch.setattr(setup, 'urlopen', open_request)
    return calls


def test_injected_configuration_checks_gist_access_and_writes_private_files(injected, monkeypatch, capsys):
    calls = github(monkeypatch)
    setup.configure(injected, non_interactive=True)
    assert calls == ['https://api.github.com/user', 'https://api.github.com/gists/abcde12345']
    assert json.loads((injected / 'config/thoughts-gist.json').read_text()) == {'id': 'abcde12345', 'file': 'notes.json'}
    assert (injected / 'credentials/thoughts-gist-token').read_text() == 'test-only-credential'
    for name in ('config/thoughts-gist.json', 'credentials/thoughts-gist-token'):
        assert (injected / name).stat().st_mode & 0o777 == 0o600
    assert 'test-only-credential' not in capsys.readouterr().out


@pytest.mark.parametrize('options', [{'owner': 99}, {'scopes': 'gist,repo'}, {'failure': True}])
def test_unverified_credentials_never_replace_existing_configuration(injected, monkeypatch, options):
    (injected / "config").mkdir(parents=True)
    (injected / "credentials").mkdir()
    previous = {'id': 'fffff12345', 'file': 'existing.json'}
    (injected / 'config/thoughts-gist.json').write_text(json.dumps(previous))
    (injected / 'credentials/thoughts-gist-token').write_text('previous-token')
    github(monkeypatch, **options)
    with pytest.raises(SystemExit) as error:
        setup.configure(injected, non_interactive=True)
    assert 'test-only-credential' not in str(error.value)
    assert json.loads((injected / 'config/thoughts-gist.json').read_text()) == previous
    assert (injected / 'credentials/thoughts-gist-token').read_text() == 'previous-token'


@pytest.mark.parametrize('kind', ['unset', 'unreadable', 'empty'])
def test_batch_configuration_never_prompts_or_connects_without_a_token(injected, monkeypatch, tmp_path, kind):
    if kind == 'unset':
        monkeypatch.delenv('THOUGHTS_GIST_TOKEN_FILE')
    elif kind == 'unreadable':
        monkeypatch.setenv('THOUGHTS_GIST_TOKEN_FILE', str(tmp_path / 'missing secret'))
    else:
        Path(os.environ['THOUGHTS_GIST_TOKEN_FILE']).write_text('')
    calls = github(monkeypatch)
    with pytest.raises(SystemExit):
        setup.configure(injected, non_interactive=True)
    assert not calls and not injected.exists()


@pytest.mark.parametrize('key,value', [('THOUGHTS_GIST_ID', '../other'), ('THOUGHTS_GIST_FILE', '../notes.json')])
def test_invalid_injected_target_fails_before_reading_credentials(injected, monkeypatch, key, value):
    monkeypatch.setenv(key, value)
    calls = github(monkeypatch)
    with pytest.raises(SystemExit, match='Invalid Gist ID or filename'):
        setup.configure(injected, non_interactive=True)
    assert not calls and not injected.exists()


def test_existing_target_defaults_and_publication_queue_are_preserved(injected, monkeypatch):
    github(monkeypatch)
    setup.configure(injected, non_interactive=True)
    with sqlite3.connect(injected / 'myserver.sqlite') as db:
        db.execute('CREATE TABLE publication(id INTEGER, generation INTEGER)')
        db.execute('INSERT INTO publication VALUES(1,7)')
    monkeypatch.delenv('THOUGHTS_GIST_ID')
    monkeypatch.delenv('THOUGHTS_GIST_FILE')
    setup.configure(injected, non_interactive=True)
    with sqlite3.connect(injected / 'myserver.sqlite') as db:
        assert db.execute('SELECT generation FROM publication').fetchone()[0] == 7
    monkeypatch.setenv('THOUGHTS_GIST_ID', 'fffff12345')
    setup.configure(injected, non_interactive=True)
    with sqlite3.connect(injected / 'myserver.sqlite') as db:
        assert db.execute('SELECT generation FROM publication').fetchone()[0] == 8


@pytest.mark.parametrize('arguments,expected', [([], 'environment-host'), (['explicit-host'], 'explicit-host')])
def test_staging_uses_injected_target_with_argument_override(tmp_path, arguments, expected):
    mock = tmp_path / 'ssh'
    mock.write_text('#!/bin/sh\nprintf "%s\\n" "$1" >> "$STAGING_TARGET_LOG"\ncat >/dev/null\n')
    mock.chmod(0o700)
    log = tmp_path / 'targets'
    env = dict(os.environ, PATH=str(tmp_path) + ':' + os.environ['PATH'], MYSERVER_SSH_TARGET='environment-host', STAGING_TARGET_LOG=str(log))
    subprocess.run(['bash', str(DEPLOY / 'stage.sh'), *arguments], cwd=tmp_path, env=env, stdin=subprocess.DEVNULL, check=True, capture_output=True)
    assert log.read_text().splitlines() == [expected]
    result = subprocess.run(['bash', str(DEPLOY / 'stage.sh')], cwd=tmp_path, env=dict(env, MYSERVER_SSH_TARGET='-oProxyCommand=unexpected'), stdin=subprocess.DEVNULL, capture_output=True)
    assert result.returncode != 0 and log.read_text().splitlines() == [expected]


def test_interactive_setup_remains_available(injected, monkeypatch):
    for key in ('THOUGHTS_GIST_ID', 'THOUGHTS_GIST_FILE', 'THOUGHTS_GIST_TOKEN_FILE'):
        monkeypatch.delenv(key)
    responses = iter(['abcde12345', ''])
    monkeypatch.setattr('builtins.input', lambda _: next(responses))
    monkeypatch.setattr(setup.getpass, 'getpass', lambda _: 'test-only-credential')
    github(monkeypatch)
    setup.configure(injected)
    assert json.loads((injected / 'config/thoughts-gist.json').read_text()) == {'id': 'abcde12345', 'file': 'thoughts.json'}


def test_blank_environment_entries_do_not_override_saved_publication_settings(tmp_path, monkeypatch):
    from modules.thoughts import publisher
    root = tmp_path / '.local/share/myserver'
    (root / "config").mkdir(parents=True)
    (root / "credentials").mkdir()
    (root / 'config/thoughts-gist.json').write_text(json.dumps({'id': 'abcde12345', 'file': 'saved.json'}))
    (root / 'credentials/thoughts-gist-token').write_text('test-only-credential')
    monkeypatch.setattr(publisher.Path, 'home', lambda: tmp_path)
    for key in ('THOUGHTS_GIST_ID', 'THOUGHTS_GIST_FILE', 'THOUGHTS_GIST_CONFIG', 'THOUGHTS_GIST_TOKEN_FILE'):
        monkeypatch.setenv(key, '')
    calls = []
    def send(request, timeout):
        calls.append(request)
        assert request.get_header('Authorization') == 'Bearer test-only-credential'
        response = io.BytesIO(b'{}')
        response.status = 200
        return response
    monkeypatch.setattr(publisher, 'urlopen', send)
    publisher.github_write('[]')
    assert len(calls) == 1
    assert calls[0].full_url == 'https://api.github.com/gists/abcde12345'
    assert json.loads(calls[0].data) == {'files': {'saved.json': {'content': '[]'}}}


def test_staging_uploads_complete_bundle_without_touching_active_code(tmp_path):
    home = tmp_path / 'home'
    root = home / '.local/share/myserver'
    (root / 'app').mkdir(parents=True)
    (root / 'app/active-marker').write_text('active')
    commands = tmp_path / 'bin'
    commands.mkdir()
    ssh = commands / 'ssh'
    ssh.write_text('#!/bin/bash\nexec bash -c "$2"\n')
    ssh.chmod(0o700)
    env = dict(os.environ, HOME=str(home), PATH=str(commands) + ':' + os.environ['PATH'])
    subprocess.run(['bash', str(DEPLOY / 'stage.sh'), 'test-target'], env=env,
                   check=True, capture_output=True, timeout=10)
    assert (root / 'app/active-marker').read_text() == 'active'
    assert (root / 'staged/app/service.py').exists()
    assert (root / 'staged/deploy/activate.py').exists()
    assert not (root / 'staged/server').exists()
    assert not list(root.glob('.stage-*'))
    assert root.stat().st_mode & 0o777 == 0o700
    assert (root / 'staged/deploy/ssh-gateway.sh').stat().st_mode & 0o777 == 0o700
