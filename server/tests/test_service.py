"""Test the shared service independently of installed features and HTTP libraries."""
import json
import os
import sqlite3
import subprocess
import sys
from pathlib import Path

import pytest
import modules
from cli import backup, check
from database import initialize, connect
from paths import data_root, database_path
from service import handle
from test_thoughts import create


def test_service_without_thoughts_still_authenticates_and_reads_system(tmp_path, monkeypatch):
    monkeypatch.setattr(modules, 'ENABLED', ())
    path = tmp_path / 'service.sqlite'
    initialize(path)
    with connect(path) as db:
        assert 'thoughts' not in {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    result = handle(dict(path='/system', method='GET'), ['system.read'], path)
    assert result['status'] == 200
    assert result['body']['service'] == 'myserver'
    assert result['body']['modules'] == {}
    assert result['body']['capabilities'] == ['system.read']
    assert handle(dict(path='/thoughts', method='GET'), ['thoughts'], path)['status'] == 403
    assert modules.routes() == []
    check(path)


def test_module_status_respects_granted_permissions(rpc):
    create(rpc, content='module status test')
    result = rpc('/system')['body']
    assert result['modules']['thoughts']['records'] == {'active': 1, 'trash': 0}
    assert result['modules']['thoughts']['publication']['generation'] == 1
    assert rpc('/system', capabilities=['system.read'])['body']['modules'] == {}
    assert rpc('/session', capabilities=['thoughts', 'unknown'])['body']['capabilities'] == ['thoughts']


def test_initialization_preserves_business_data_and_removes_web_credentials(database, rpc):
    note = create(rpc, content='before')['body']
    rpc('/thoughts/' + note['id'], 'PATCH', {**note, 'content': 'after'})
    def snapshot():
        with connect(database) as connection:
            return {table: [tuple(row) for row in connection.execute('SELECT * FROM ' + table)]
                    for table in ('thoughts', 'revisions', 'publication')}
    before = snapshot()
    with connect(database) as connection:
        connection.executescript('PRAGMA user_version=0; CREATE TABLE sessions(token); CREATE TABLE login_attempts(ip); CREATE TABLE settings(key, value); INSERT INTO settings VALUES("password_hash", "obsolete");')
    initialize(database)
    initialize(database)
    assert snapshot() == before
    with connect(database) as connection:
        tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        assert not {'sessions', 'login_attempts', 'settings'} & tables
        assert connection.execute('PRAGMA user_version').fetchone()[0] == 1
    assert database.stat().st_mode & 0o777 == 0o600


def test_future_schema_fails_without_changing_data(database):
    with connect(database) as db:
        db.execute('PRAGMA user_version=999')
    before = database.read_bytes()
    with pytest.raises(RuntimeError): initialize(database)
    assert database.read_bytes() == before


def test_missing_database_is_not_created_by_rpc(tmp_path):
    path = tmp_path / 'missing.sqlite'
    with pytest.raises(sqlite3.OperationalError):
        handle(dict(path='/session', method='GET'), [], path)
    assert not path.exists()


def test_runtime_database_override_and_blank_default(tmp_path, monkeypatch):
    monkeypatch.setattr(Path, 'home', lambda: tmp_path)
    monkeypatch.setenv('MYSERVER_DATABASE', '')
    assert data_root() == tmp_path / '.local/share/myserver'
    assert database_path() == data_root() / 'myserver.sqlite'
    monkeypatch.setenv('MYSERVER_DATABASE', str(tmp_path / 'test.sqlite'))
    assert database_path() == tmp_path / 'test.sqlite'


def test_gateway_rejects_arbitrary_commands_without_dependencies(tmp_path):
    env = dict(os.environ, HOME=str(tmp_path), SSH_ORIGINAL_COMMAND='sh')
    result = subprocess.run([sys.executable, '-S', str(Path(__file__).parents[1] / 'ssh_gateway.py'), 'a' * 64],
                            input='{}\n', capture_output=True, text=True, env=env, check=True)
    assert json.loads(result.stdout)['status'] == 403
    assert not (tmp_path / '.local/share/myserver').exists()


def test_cli_and_online_backup_work_without_site_packages(tmp_path):
    path = tmp_path / 'data # with spaces.sqlite'
    target = tmp_path / 'backup.sqlite'
    env = dict(os.environ, MYSERVER_DATABASE=str(path))
    cli = Path(__file__).parents[1] / 'cli.py'
    for arguments in [('init',), ('check',), ('backup', str(target))]:
        subprocess.run([sys.executable, '-S', str(cli), *arguments], env=env, check=True, capture_output=True)
    check(target)
    assert target.stat().st_mode & 0o777 == 0o600
    with pytest.raises(FileExistsError): backup(path, target)
    with pytest.raises(ValueError): backup(path, path)


def test_backup_preserves_records_revisions_and_pending_publication(database, rpc, tmp_path):
    note = create(rpc)['body']
    rpc('/thoughts/' + note['id'], 'PATCH', {**note, 'content': 'new'})
    target = tmp_path / 'backup.sqlite'
    backup(database, target)
    with connect(database) as source, connect(target) as saved:
        for table in ('thoughts', 'revisions', 'publication'):
            assert source.execute('SELECT * FROM ' + table).fetchall() == saved.execute('SELECT * FROM ' + table).fetchall()
