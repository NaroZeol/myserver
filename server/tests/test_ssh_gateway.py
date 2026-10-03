import base64
import importlib.util
import json
import fcntl
import os
import subprocess
import sys
import struct
from pathlib import Path

import pytest
from database import initialize
from rpc import RpcError
from service import handle, prepare
import ssh_gateway

spec=importlib.util.spec_from_file_location('registration',Path(__file__).parents[2]/'deploy/register-device.py')
registration=importlib.util.module_from_spec(spec);spec.loader.exec_module(registration)


def key(seed=1):
    fields=[b'ssh-rsa',(65537).to_bytes(3,'big'),b'\x00\x80'+bytes([seed])*255]
    blob=b''.join(struct.pack('>I',len(value))+value for value in fields)
    return 'ssh-rsa '+base64.b64encode(blob).decode()+' phone'


def test_device_registration_is_restricted_idempotent_and_revocable(tmp_path):
    root=tmp_path/'data';authorized=tmp_path/'.ssh/authorized_keys'
    authorized.parent.mkdir();authorized.write_text('existing administrator key\n\n')
    original=authorized.read_text()
    ident=registration.enroll(root,authorized,key(),'手机',['thoughts','system.read'])
    line=authorized.read_text().splitlines()[-1]
    assert line.startswith(f'restrict,command="{root}/deploy/ssh-gateway.sh {ident}" ssh-rsa ')
    assert authorized.read_text().startswith(original)
    assert authorized.stat().st_mode & 0o077 == 0
    before=authorized.read_text()
    assert registration.enroll(root,authorized,key(),'手机',['thoughts'])==ident
    assert authorized.read_text()==before
    registration.revoke(root,authorized,ident)
    assert authorized.read_text()==original
    assert not (root/'devices'/f'{ident}.json').exists()


def test_do_not_convert_existing_unrestricted_key(tmp_path):
    authorized=tmp_path/'authorized_keys';authorized.write_text(key()+'\n')
    with pytest.raises(ValueError):registration.enroll(tmp_path/'data',authorized,key(),'手机',['thoughts'])
    assert authorized.read_text()==key()+'\n'


@pytest.mark.parametrize('path,method', [('/login','POST'),('/logout','POST'),('/system','POST'),('http://attacker.test/','GET'),('//attacker.test','GET'),('/thoughts/%2e%2e/system','GET'),('/thoughts\n','GET'),('/app/','GET'),('/../system','GET')])
def test_gateway_rejects_non_capability_routes(path,method):
    with pytest.raises((RpcError,ValueError)):
        prepare(dict(path=path,method=method),['thoughts','system.read'],"unused.sqlite")


def test_permissions_are_server_side(rpc):
    assert rpc('/system', capabilities=['thoughts'])['status'] == 403
    assert rpc('/session', capabilities=['system.read'])['body']['capabilities'] == ['system.read']


def test_gist_target_is_deployment_configuration(tmp_path, monkeypatch):
    from modules.thoughts.publisher import gist_target
    config = tmp_path / 'config/thoughts-gist.json'
    monkeypatch.setenv('THOUGHTS_GIST_CONFIG', str(config))
    monkeypatch.delenv('THOUGHTS_GIST_ID', raising=False)
    monkeypatch.delenv('THOUGHTS_GIST_FILE', raising=False)
    with pytest.raises(RuntimeError): gist_target()
    config.parent.mkdir(exist_ok=True)
    config.write_text(json.dumps({'id': 'abcde12345', 'file': 'notes.json'}))
    assert gist_target() == ('abcde12345', 'notes.json')
    monkeypatch.setenv('THOUGHTS_GIST_ID', '12345fffff')
    assert gist_target() == ('12345fffff', 'notes.json')


def test_maintenance_blocks_authority_changes(tmp_path):
    root=tmp_path/'data';authorized=tmp_path/'.ssh/authorized_keys'
    ident=registration.enroll(root,authorized,key(),'手机',['system.read'])
    original=authorized.read_bytes()
    (root/'maintenance').touch()
    with pytest.raises(ValueError,match='维护'):
        registration.enroll(root,authorized,key(2),'另一台',['thoughts'])
    with pytest.raises(ValueError,match='维护'):
        registration.revoke(root,authorized,ident)
    assert authorized.read_bytes()==original
    assert len(list((root/'devices').glob('*.json')))==1


@pytest.fixture
def gateway(tmp_path):
    root = tmp_path / '.local/share/myserver'
    ident = registration.enroll(root, tmp_path / '.ssh/authorized_keys', key(), 'test device', ['thoughts', 'system.read'])
    initialize(root / 'myserver.sqlite')
    script = Path(__file__).parents[1] / 'ssh_gateway.py'
    # Every successful operation must work with no site packages and no sockets.
    runner = '''import sys,runpy
sys.path.insert(0,sys.argv[1].rsplit('/',1)[0])
def deny_network(event,args):
    if event in ('socket.connect','socket.bind'): raise RuntimeError('network forbidden')
sys.addaudithook(deny_network)
sys.argv=sys.argv[1:]
runpy.run_path(sys.argv[0],run_name='__main__')
'''
    def call(value=None, raw=None, device=ident, command='myserver-rpc-v1'):
        env = dict(os.environ, HOME=str(tmp_path), MYSERVER_DATABASE='', SSH_ORIGINAL_COMMAND=command)
        result = subprocess.run([sys.executable, '-S', '-c', runner, str(script), device],
                                input=raw if raw is not None else json.dumps(value) + '\n',
                                text=True, capture_output=True, env=env, check=True, timeout=10)
        assert result.stderr == ''
        assert len(result.stdout.splitlines()) == 1
        return json.loads(result.stdout)
    return call, root, ident


def test_direct_gateway_reads_writes_and_preserves_wire_protocol(gateway):
    call, root, _ = gateway
    assert call(dict(path='/session', method='GET')) == dict(status=200, body=dict(ok=True, capabilities=['system.read', 'thoughts'], transport='ssh', protocol_version=1))
    created = call(dict(path='/thoughts', method='POST', body={'content': '离线重试', 'tags': ['test']}))
    assert created['status'] == 201
    assert call(dict(path='/thoughts', method='GET'))['body']['items'] == [created['body']]
    status = call(dict(path='/system', method='GET'))
    assert status['status'] == 200
    assert status['body']['modules']['thoughts']['records'] == dict(active=1, trash=0)
    assert status['body']['backup']['latest_at'] is None
    assert not {'password', 'token', 'hostname', 'username'} & status['body'].keys()


def test_unregistered_revoked_or_repurposed_device_is_denied(gateway):
    call, root, ident = gateway
    request = dict(path='/session', method='GET')
    assert call(request, device='f' * 64)['status'] == 403
    assert call(request, device='../invalid')['status'] == 403
    authorized = root.parents[2] / '.ssh/authorized_keys'
    original = authorized.read_bytes()
    authorized.write_text(key() + '\n')
    assert call(request)['status'] == 403
    authorized.write_bytes(original)
    registration.revoke(root, authorized, ident)
    assert call(request)['status'] == 403


def test_device_cannot_inject_capabilities_or_invoke_ungranted_routes(gateway):
    call, root, ident = gateway
    path = root / 'devices' / (ident + '.json')
    record = json.loads(path.read_text())
    record['capabilities'] = ['system.read']
    path.write_text(json.dumps(record))
    for route, method in [('/thoughts','GET'),('/thoughts','POST'),('/export','GET'),('/publish','POST'),('/publication','GET')]:
        assert call(dict(path=route, method=method, capabilities=['thoughts'], body={'content': 'injection'}))['status'] == 403
    assert call(dict(path='/session', method='GET'))['body']['capabilities'] == ['system.read']


@pytest.mark.parametrize('raw', ['{}', '{}\n', '[]\n', '{broken}\n', '{"path":"/thoughts","method":"POST","body":{"content":NaN}}\n', '{"path":"/thoughts","method":"POST","body":[]}\n', '[' * 1100 + '\n', 'x' * (140 * 1024) + '\n'], ids=['incomplete','empty','array','syntax','constant','body','deep','oversized'])
def test_malformed_or_oversized_input_cannot_mutate_database(gateway, raw):
    call, _, _ = gateway
    assert call(raw=raw)['status'] == 400
    assert call(dict(path='/thoughts', method='GET'))['body']['items'] == []


def test_maintenance_and_exclusive_update_lock_fail_promptly(gateway):
    call, root, _ = gateway
    request = dict(path='/session', method='GET')
    (root / 'maintenance').touch()
    assert call(request)['status'] == 503
    (root / 'maintenance').unlink()
    with (root / 'operations.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        assert call(request)['status'] == 503
    assert call(request)['status'] == 200


def test_missing_database_returns_sanitized_error_without_creating_empty_file(gateway):
    call, root, _ = gateway
    (root / 'myserver.sqlite').unlink()
    result = call(dict(path='/session', method='GET'))
    assert result['status'] == 503
    assert str(root) not in json.dumps(result)
    assert not (root / 'myserver.sqlite').exists()


def test_response_bound_counts_encoded_bytes_including_frame(monkeypatch):
    monkeypatch.setattr(ssh_gateway, 'MAX_RESPONSE', 512)
    raw = ssh_gateway.encode(dict(status=200, body={'content': '字' * 200}))
    assert len(raw) <= 512 and raw.endswith(b'\n')
    assert json.loads(raw)['status'] == 413
