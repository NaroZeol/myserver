import base64
import importlib.util
import json
import sqlite3
import struct
from pathlib import Path

import pytest
from test_api import app
from ssh_gateway import handle, validate_request

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
    with pytest.raises((PermissionError,ValueError)):
        validate_request(dict(path=path,method=method),['thoughts','system.read'])


def test_permissions_are_server_side():
    with pytest.raises(PermissionError):validate_request(dict(path='/system',method='GET',capabilities=['system.read']),['thoughts'])
    assert validate_request(dict(path='/session',method='GET'),['system.read'])[0]=='/session'


def test_gateway_reuses_api_auth_and_cleans_internal_session(app):
    client=app.test_client()
    class Response:
        def __init__(self,result):self.result=result;self.code=result.status_code
        def __enter__(self):return self
        def __exit__(self,*args):pass
        def read(self,limit):return self.result.data
    class Opener:
        def open(self,request,timeout):
            assert request.full_url=='http://127.0.0.1:8765/api/session'
            assert request.get_header('Authorization').startswith('Bearer ')
            result=client.get('/api/session',headers={'Authorization':request.get_header('Authorization')})
            return Response(result)
    response=handle(dict(path='/session',method='GET'),['thoughts'],app.config['DATABASE'],Opener())
    assert response==dict(status=200,body=dict(ok=True,capabilities=['thoughts'],transport='ssh',protocol_version=1))
    with sqlite3.connect(app.config['DATABASE']) as db:assert db.execute('SELECT COUNT(*) FROM sessions').fetchone()[0]==0
    class Broken:
        def open(self,*args,**kwargs):raise TimeoutError()
    with pytest.raises(TimeoutError):handle(dict(path='/session',method='GET'),['thoughts'],app.config['DATABASE'],Broken())
    with sqlite3.connect(app.config['DATABASE']) as db:assert db.execute('SELECT COUNT(*) FROM sessions').fetchone()[0]==0


def test_system_overview_requires_auth_and_omits_secrets(app):
    client=app.test_client()
    assert client.get('/api/system').status_code==401
    result=client.post('/api/login',json={'password':'a-test-password-at-least-16-characters'})
    response=client.get('/api/system',headers={'Authorization':'Bearer '+result.json['token']})
    assert response.status_code==200
    assert response.json['records']==dict(active=0,trash=0)
    assert response.json['backup']['latest_at'] is None
    assert 'password' not in response.get_data(as_text=True) and 'token' not in response.get_data(as_text=True)


def test_gist_target_is_deployment_configuration(tmp_path, monkeypatch):
    from publisher import gist_target
    config = tmp_path / 'gist.json'
    monkeypatch.setenv('THOUGHTS_GIST_CONFIG', str(config))
    monkeypatch.delenv('THOUGHTS_GIST_ID', raising=False)
    monkeypatch.delenv('THOUGHTS_GIST_FILE', raising=False)
    with pytest.raises(RuntimeError): gist_target()
    config.write_text(json.dumps({'id': 'abcde12345', 'file': 'notes.json'}))
    assert gist_target() == ('abcde12345', 'notes.json')
    monkeypatch.setenv('THOUGHTS_GIST_ID', '12345fffff')
    assert gist_target() == ('12345fffff', 'notes.json')
