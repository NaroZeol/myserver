import hashlib
import io
import json
from pathlib import Path
import time
import uuid

import pytest
from database import connect
from rpc import RpcError
from service import handle
from modules.inbox import service as inbox


def manifest(*contents, text=''):
    return dict(id=str(uuid.uuid4()), text=text, note='', source='test', files=[dict(id=str(uuid.uuid4()), name=f'file-{i}.bin', mime='application/octet-stream', size=len(content), sha256=hashlib.sha256(content).hexdigest()) for i, content in enumerate(contents)])


def call(database, path, method='GET', body=None, capabilities=('inbox.read', 'inbox.write')):
    return handle(dict(path=path, method=method, body=body), capabilities, database)


def upload(database, data, index, content, offset=0):
    output = io.BytesIO()
    replies = []
    with connect(database) as connection:
        inbox.transfer(connection, database, dict(op='upload', item=data['id'], file=data['files'][index]['id'], offset=offset, length=len(content)), io.BytesIO(content), output, replies.append)
    return replies


def commit(database, data):
    return call(database, '/inbox/uploads/' + data['id'] + '/commit', 'POST')


def test_private_permission_and_text_commit(database):
    assert call(database, '/inbox', capabilities=['thoughts'])['status'] == 403
    data = manifest(text='private note https://example.test/')
    assert call(database, '/inbox/uploads', 'POST', data, ['inbox.read'])['status'] == 403
    assert call(database, '/inbox/uploads', 'POST', data)['status'] == 201
    assert call(database, '/inbox')['body']['items'] == []
    item = commit(database, data)['body']['item']
    assert item['title'] == data['text'] and item['files'] == []
    assert call(database, '/inbox')['body']['items'] == [item]
    assert call(database, '/thoughts', capabilities=['thoughts'])['body']['items'] == []


def test_group_upload_resume_verify_and_chunked_download(database):
    data = manifest(b'hello world', b'other file')
    created = call(database, '/inbox/uploads', 'POST', data)
    assert created['body']['files'][0]['offset'] == 0
    assert upload(database, data, 0, b'hello ') == [dict(offset=0), dict(offset=6)]
    assert call(database, '/inbox/uploads/' + data['id'])['body']['files'][0]['offset'] == 6
    assert commit(database, data)['status'] == 409
    assert call(database, '/inbox')['body']['items'] == []
    upload(database, data, 0, b'world', 6)
    upload(database, data, 1, b'other file')
    item = commit(database, data)['body']['item']
    assert len(item['files']) == 2
    assert call(database, '/inbox/uploads', 'POST', data)['body']['state'] == 'ready'
    assert commit(database, data)['body']['item'] == item
    output, replies = io.BytesIO(), []
    with connect(database) as connection:
        inbox.transfer(connection, database, dict(op='download', item=data['id'], file=data['files'][0]['id'], offset=6, length=5), io.BytesIO(), output, replies.append)
    assert output.getvalue() == b'world'
    assert replies == [dict(offset=6, length=5, size=11, sha256=data['files'][0]['sha256'])]
    assert (database.parent/'inbox/objects'/data['id']/data['files'][0]['id']).stat().st_mode & 0o077 == 0


def test_partial_block_rolls_back_and_hash_failure_never_publishes(database):
    data = manifest(b'original')
    call(database, '/inbox/uploads', 'POST', data)
    with connect(database) as connection, pytest.raises(RpcError, match='不完整'):
        inbox.transfer(connection, database, dict(op='upload', item=data['id'], file=data['files'][0]['id'], offset=0, length=8), io.BytesIO(b'short'), io.BytesIO(), lambda body: None)
    assert call(database, '/inbox/uploads/' + data['id'])['body']['files'][0]['offset'] == 0
    upload(database, data, 0, b'incorrec')
    assert commit(database, data)['status'] == 422
    assert call(database, '/inbox')['body']['items'] == []
    assert call(database, '/inbox/uploads/' + data['id'], 'DELETE')['status'] == 200
    assert not (database.parent/'inbox/uploads'/data['id']).exists()
    assert call(database, '/inbox/uploads', 'POST', data)['status'] == 409


def test_browser_hash_is_computed_and_metadata_idempotency(database):
    data = manifest(b'file')
    del data['files'][0]['sha256']
    call(database, '/inbox/uploads', 'POST', data)
    different = dict(data, text='changed')
    assert call(database, '/inbox/uploads', 'POST', different)['status'] == 409
    upload(database, data, 0, b'file')
    result = commit(database, data)['body']['item']
    assert result['files'][0]['sha256'] == hashlib.sha256(b'file').hexdigest()
    assert call(database, '/inbox/uploads', 'POST', data)['body']['state'] == 'ready'
    updated = call(database, '/inbox/items/' + data['id'], 'PATCH', dict(title='renamed', note='note'))
    assert updated['body']['item']['title'] == 'renamed'
    assert call(database, '/inbox?q=renamed')['body']['items'][0]['note'] == 'note'
    assert call(database, '/inbox/uploads/' + data['id'], 'DELETE')['status'] == 409
    assert call(database, '/inbox/items/' + data['id'], 'DELETE')['status'] == 200
    assert call(database, '/inbox/items/' + data['id'])['status'] == 404
    assert not (database.parent/'inbox/objects'/data['id']).exists()


@pytest.mark.parametrize('field,value', [('name','../secret'),('name','path\\file'),('name','bad\r\nheader'),('size',True),('size',-1),('sha256','invalid'),('id','../not-a-uuid')])
def test_untrusted_file_metadata_cannot_address_paths(database, field, value):
    data = manifest(b'x')
    data['files'][0][field] = value
    assert call(database, '/inbox/uploads', 'POST', data)['status'] == 400
    assert call(database, '/inbox')['body']['items'] == []


def test_quota_includes_pending_and_expired_pending_is_cleaned(database, monkeypatch):
    first, second = manifest(b'12345678'), manifest(b'abcdefgh')
    assert call(database, '/inbox/uploads', 'POST', first)['status'] == 201
    with connect(database) as connection:
        monkeypatch.setenv('MYSERVER_INBOX_QUOTA_BYTES', str(inbox.usage(connection)*2-1))
    assert call(database, '/inbox/uploads', 'POST', second)['status'] == 507
    with connect(database) as connection:
        with connection:
            connection.execute('UPDATE inbox_items SET touched=? WHERE id=?', (time.time() - 8*86400, first['id']))
    assert call(database, '/inbox/uploads', 'POST', second)['status'] == 201
    assert call(database, '/inbox/uploads/' + first['id'])['status'] == 404
    assert not (database.parent/'inbox/uploads'/first['id']).exists()


def test_disk_reserve_and_range_validation(database, monkeypatch):
    monkeypatch.setenv('MYSERVER_INBOX_MIN_FREE_BYTES', str(2**63-1))
    assert call(database, '/inbox/uploads', 'POST', manifest(b'file'))['status'] == 507
    monkeypatch.setenv('MYSERVER_INBOX_MIN_FREE_BYTES', '0')
    data = manifest(b'file')
    call(database, '/inbox/uploads', 'POST', data)
    with pytest.raises(RpcError, match='范围'):
        upload(database, data, 0, b'too large')
    with pytest.raises(RpcError, match='位置'):
        upload(database, data, 0, b'ile', 1)
    with pytest.raises(RpcError):
        inbox.transfer_header(dict(op='download', item=data['id'], file=data['files'][0]['id'], offset=True, length=1))


def test_commit_recovers_directory_rename_before_database_commit(database):
    data = manifest(b'crash recovery')
    call(database, '/inbox/uploads', 'POST', data)
    upload(database, data, 0, b'crash recovery')
    directory = database.parent/'inbox'
    (directory/'uploads'/data['id']).rename(directory/'objects'/data['id'])
    assert call(database, '/inbox/uploads/' + data['id'])['body']['files'][0]['offset'] == 14
    assert commit(database, data)['status'] == 200


def test_chunk_gateway_is_authorized_bounded_and_binary(database, tmp_path, monkeypatch):
    import ssh_gateway
    root = database.parent
    monkeypatch.setattr(ssh_gateway, 'data_root', lambda: root)
    monkeypatch.setattr(ssh_gateway, 'database_path', lambda: database)
    monkeypatch.setattr(ssh_gateway, 'registered_device', lambda root, ident: dict(capabilities=['inbox.read', 'inbox.write']))
    data = manifest(b'\x00\xff\nfile')
    call(database, '/inbox/uploads', 'POST', data)
    header = dict(op='upload', item=data['id'], file=data['files'][0]['id'], offset=0, length=7)
    output = io.BytesIO()
    ssh_gateway.process_transfer('device', io.BytesIO(json.dumps(header).encode()+b'\n\x00\xff\nfile'), output)
    assert [json.loads(line)['body']['offset'] for line in output.getvalue().splitlines()] == [0,7]
    commit(database, data)
    header['op'] = 'download'; output = io.BytesIO()
    ssh_gateway.process_transfer('device', io.BytesIO(json.dumps(header).encode()+b'\n'), output)
    first, binary = output.getvalue().split(b'\n',1)
    assert json.loads(first)['body']['length'] == 7 and binary == b'\x00\xff\nfile'
    monkeypatch.setattr(ssh_gateway, 'registered_device', lambda root, ident: dict(capabilities=['system.read']))
    output = io.BytesIO(); ssh_gateway.process_transfer('device', io.BytesIO(json.dumps(header).encode()+b'\n'), output)
    assert json.loads(output.getvalue())['status'] == 403
    (root/'maintenance').touch(); output = io.BytesIO()
    ssh_gateway.process_transfer('device', io.BytesIO(json.dumps(header).encode()+b'\n'), output)
    assert json.loads(output.getvalue())['status'] == 503


def test_persistent_limits_apply_to_next_rpc_and_env_can_override(database, monkeypatch):
    from argparse import Namespace
    from modules.inbox.cli import configure_limits
    configure_limits(Namespace(quota_bytes=10, min_free_bytes=0, upload_ttl_seconds=60), database)
    assert call(database, '/inbox')['body']['storage']['quota_bytes'] == 10
    assert call(database, '/inbox/uploads', 'POST', manifest(b'too much content'))['status'] == 507
    path = database.parent/'config/inbox.json'
    assert path.stat().st_mode & 0o077 == 0
    monkeypatch.setenv('MYSERVER_INBOX_QUOTA_BYTES', '20')
    assert call(database, '/inbox')['body']['storage']['quota_bytes'] == 20
    assert json.loads(path.read_text())['quota_bytes'] == 10


def test_real_forced_command_binary_protocol_and_revocation(tmp_path):
    import os
    import subprocess
    import sys
    from database import initialize
    from test_ssh_gateway import key, registration
    root=tmp_path/'.local/share/myserver'
    device=registration.enroll(root,tmp_path/'.ssh/authorized_keys',key(),'test',['inbox.read','inbox.write'])
    db=root/'myserver.sqlite';initialize(db)
    data=manifest(b'raw\x00bytes\xff')
    call(db,'/inbox/uploads','POST',data)
    script=Path(__file__).parents[1]/'ssh_gateway.py'
    env=dict(os.environ,HOME=str(tmp_path),MYSERVER_DATABASE='',SSH_ORIGINAL_COMMAND='myserver-transfer-v1')
    header=dict(op='upload',item=data['id'],file=data['files'][0]['id'],offset=0,length=10)
    result=subprocess.run([sys.executable,'-S',str(script),device],input=json.dumps(header).encode()+b'\nraw\x00bytes\xff',capture_output=True,env=env,check=True,timeout=10)
    assert result.stderr==b''
    assert [json.loads(line)['body']['offset'] for line in result.stdout.splitlines()]==[0,10]
    assert commit(db,data)['status']==200
    registration.revoke(root,tmp_path/'.ssh/authorized_keys',device)
    header['op']='download'
    result=subprocess.run([sys.executable,'-S',str(script),device],input=json.dumps(header).encode()+b'\n',capture_output=True,env=env,check=True,timeout=10)
    assert json.loads(result.stdout)['status']==403


def test_empty_files_and_title_metadata_consume_quota(database, monkeypatch):
    data = manifest(b'')
    assert call(database, '/inbox/uploads', 'POST', data)['status'] == 201
    commit(database, data)
    with connect(database) as connection:
        used = inbox.usage(connection)
    assert used > 1024
    monkeypatch.setenv('MYSERVER_INBOX_QUOTA_BYTES', str(used))
    assert call(database, '/inbox/uploads', 'POST', manifest(b''))['status'] == 507
    assert call(database, '/inbox/items/'+data['id'], 'PATCH', dict(title='longer than the original title'))['status'] == 507
    call(database, '/inbox/items/'+data['id'], 'DELETE')
    with connect(database) as connection:
        assert inbox.usage(connection) == 128


def test_deletion_and_expiration_remove_private_metadata(database):
    data = manifest(b'private content', text='private body')
    data['note']='private note';data['source']='private source';data['files'][0]['name']='private-name.txt'
    call(database, '/inbox/uploads', 'POST', data)
    upload(database, data, 0, b'private content');commit(database,data)
    call(database,'/inbox/items/'+data['id'],'DELETE')
    with connect(database) as connection:
        row=dict(connection.execute('SELECT * FROM inbox_items WHERE id=?',(data['id'],)).fetchone())
        assert row==dict(id=data['id'],title='',text='',note='',source='',created_at='',touched=0,state='deleted',manifest='')
        assert not connection.execute('SELECT 1 FROM inbox_files WHERE item_id=?',(data['id'],)).fetchone()
    assert call(database,'/inbox/uploads','POST',data)['status']==409
    pending=manifest(b'secret',text='expired secret');call(database,'/inbox/uploads','POST',pending)
    with connect(database) as connection:
        with connection:connection.execute('UPDATE inbox_items SET touched=0 WHERE id=?',(pending['id'],))
    call(database,'/inbox/uploads','POST',manifest(text='new'))
    with connect(database) as connection:
        assert connection.execute('SELECT manifest FROM inbox_items WHERE id=?',(pending['id'],)).fetchone()[0]==''
        assert not connection.execute('SELECT 1 FROM inbox_files WHERE item_id=?',(pending['id'],)).fetchone()


def test_category_filter_happens_before_pagination(database):
    mixed=manifest(b'',text='hello');text=manifest(text='hello again');file_only=manifest(b'')
    for data in (mixed,text,file_only):call(database,'/inbox/uploads','POST',data);commit(database,data)
    files=call(database,'/inbox?type=files&limit=1')['body']
    assert len(files['items'])==1 and files['has_more']
    assert {row['id'] for row in call(database,'/inbox?type=text')['body']['items']}=={mixed['id'],text['id']}
    assert call(database,'/inbox?type=unknown')['status']==400


def test_cli_put_validates_title_before_creation_and_never_overwrites_download(database,tmp_path):
    import os
    import subprocess
    import sys
    source=tmp_path/'source.bin';source.write_bytes(b'cli file')
    cli=Path(__file__).parents[1]/'cli.py'
    env=dict(os.environ,MYSERVER_DATABASE=str(database))
    def run(*args):return subprocess.run([sys.executable,'-S',str(cli),'inbox',*map(str,args)],env=env,capture_output=True,text=True,timeout=10)
    assert run('put',source,'--title','  ').returncode==1
    assert call(database,'/inbox')['body']['items']==[]
    result=run('put',source,'--title','custom title','--note','note')
    assert result.returncode==0,result.stderr
    item=json.loads(result.stdout);assert item['title']=='custom title'
    destination=tmp_path/'received.bin'
    assert run('get',item['id'],'--output',destination).returncode==0
    assert destination.read_bytes()==source.read_bytes()
    destination.write_bytes(b'keep this file')
    assert run('get',item['id'],'--output',destination).returncode==1
    assert destination.read_bytes()==b'keep this file'
    listing=run('list','--json');assert json.loads(listing.stdout)['items']==[item]


def test_filtering_source_time_type_and_sort_precedes_pagination(database):
    from datetime import datetime,timezone
    fixtures=[
        ('z-photo.png','image/png',b'123','Android','2026-09-30T08:00:00.000+00:00'),
        ('a-photo.jpg','application/octet-stream',b'12345','app','2026-09-29T08:00:00.000+00:00'),
        ('b-photo.webp','image/webp',b'1234','browser','2026-09-30T08:00:00.000+00:00'),
        ('old.png','image/png',b'12345678','Android','2026-08-01T08:00:00.000+00:00'),
        ('report.pdf','application/pdf',b'12','server','2026-09-29T08:00:00.000+00:00'),
        ('package.apk','application/zip',b'1','Android','2026-09-30T08:00:00.000+00:00'),
    ]
    identifiers=[]
    for name,mime,content,source,created in fixtures:
        data=manifest(content);data['source']=source;data['files'][0].update(name=name,mime=mime)
        call(database,'/inbox/uploads','POST',data);upload(database,data,0,content);commit(database,data)
        with connect(database) as connection:
            with connection:connection.execute('UPDATE inbox_items SET created_at=? WHERE id=?',(created,data['id']))
        identifiers.append(data['id'])
    since=int(datetime(2026,9,1,tzinfo=timezone.utc).timestamp())
    query='/inbox?type=image&source=phone&since='+str(since)+'&sort=name&limit=1'
    first=call(database,query)['body'];second=call(database,query+'&offset=1')['body']
    assert [first['items'][0]['id'],second['items'][0]['id']]==[identifiers[1],identifiers[0]]
    assert first['has_more'] and not second['has_more']
    assert first['items'][0]['files'][0]['kind']=='image'
    assert first['items'][0]['source_kind']=='phone'
    assert first['items'][0]['total_size']==5
    size=call(database,'/inbox?type=image&sort=size')['body']['items']
    assert [item['total_size'] for item in size]==[8,5,4,3]
    assert call(database,'/inbox?type=apk')['body']['items'][0]['id']==identifiers[5]
    assert call(database,'/inbox?source=computer')['body']['items'][0]['id']==identifiers[2]
    oldest=call(database,'/inbox?sort=oldest')['body']['items']
    assert oldest[0]['id']==identifiers[3]
    newest=call(database,'/inbox?sort=newest')['body']['items']
    assert [item['id'] for item in newest]==[item['id'] for item in reversed(oldest)]


@pytest.mark.parametrize('query',['source=unknown','sort=unknown','since=-1','since=253402300800','since=not-a-number','type=unknown'])
def test_invalid_filter_values_are_rejected(database,query):
    assert call(database,'/inbox?'+query)['status']==400


def test_metadata_classifies_common_files_and_bounds_preview_candidates():
    assert inbox.file_kind('application/octet-stream','photo.HEIC')=='image'
    assert inbox.file_kind('application/zip','report.docx')=='document'
    assert inbox.file_kind('application/zip','app.apk')=='apk'
    assert inbox.file_kind('application/octet-stream','backup.tar.gz')=='archive'
    assert inbox.file_kind('application/octet-stream','movie.mp4')=='video'
    assert inbox.file_kind('audio/flac','recording')=='audio'
    assert inbox.file_kind('application/octet-stream','opaque.bin')=='other'
    assert inbox.preview_kind('image/svg+xml','image.svg',100)=='text'
    assert inbox.preview_kind('text/html','page.html',100)=='text'
    assert inbox.preview_kind('application/pdf','document.pdf',100) is None
    assert inbox.preview_kind('image/png','image.png',21*1024**2) is None
    assert inbox.preview_kind('text/plain','large.txt',1024**2+1) is None
