"""Exercise the shared service independently of any installed feature."""
import hashlib
import os
import sqlite3
import subprocess
import sys
import time
from pathlib import Path

from app import create_app
import modules
from paths import data_root, database_path
from test_api import app, owner, create


def test_service_without_thoughts_still_authenticates_and_reads_system(tmp_path, monkeypatch):
    monkeypatch.setattr(modules, 'ENABLED', ())
    application = create_app({'TESTING': True, 'DATABASE': str(tmp_path / 'service.sqlite')})
    with application.app_context(), application.db() as db:
        assert 'thoughts' not in {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        db.execute('INSERT INTO sessions VALUES(?,?)', (hashlib.sha256(b'test-session').hexdigest(), time.time() + 60))
    client = application.test_client()
    assert client.get('/api/health').json == {'status': 'ok'}
    assert client.get('/api/system').status_code == 401
    result = client.get('/api/system', headers={'Authorization': 'Bearer test-session'})
    assert result.status_code == 200
    assert result.json['service'] == 'myserver'
    assert result.json['modules'] == {}
    assert result.json['capabilities'] == ['system.read']
    assert client.get('/api/thoughts').status_code == 404
    assert modules.rpc_routes() == []


def test_module_status_and_web_assets_are_registered(app, owner):
    create(owner, content='module status test')
    result = owner.get('/api/system').json
    assert result['modules']['thoughts']['records'] == {'active': 1, 'trash': 0}
    assert result['modules']['thoughts']['publication']['generation'] == 1
    assert owner.get('/app/').status_code == 200
    assert owner.get('/app/assets/app.js').status_code == 200
    assert owner.get('/app/assets/../routes.py').status_code == 404


def test_database_reopens_without_losing_records_history_or_queue(app, owner):
    note = create(owner, content='before').json
    owner.patch('/api/thoughts/' + note['id'], json={**note, 'content': 'after'})
    def snapshot():
        with sqlite3.connect(app.config['DATABASE']) as connection:
            return {table: connection.execute('SELECT * FROM ' + table).fetchall()
                    for table in ('thoughts', 'revisions', 'publication', 'settings')}
    before = snapshot()
    create_app({'TESTING': True, 'DATABASE': app.config['DATABASE']})
    assert snapshot() == before


def test_runtime_database_override_and_blank_default(tmp_path, monkeypatch):
    monkeypatch.setattr(Path, 'home', lambda: tmp_path)
    monkeypatch.setenv('MYSERVER_DATABASE', '')
    assert data_root() == tmp_path / '.local/share/myserver'
    assert database_path() == data_root() / 'myserver.sqlite'
    monkeypatch.setenv('MYSERVER_DATABASE', str(tmp_path / 'test.sqlite'))
    assert database_path() == tmp_path / 'test.sqlite'


def test_gateway_rejects_arbitrary_commands_without_importing_flask(tmp_path):
    env = dict(os.environ, HOME=str(tmp_path), SSH_ORIGINAL_COMMAND='sh')
    result = subprocess.run([sys.executable, '-S', str(Path(__file__).parents[1] / 'ssh_gateway.py'), 'a' * 64],
                            input='{}\n', capture_output=True, text=True, env=env, check=True)
    import json
    assert json.loads(result.stdout)['status'] == 403
    assert not (tmp_path / '.local/share/myserver').exists()
