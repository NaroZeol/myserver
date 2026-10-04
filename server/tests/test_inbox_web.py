import hashlib
import http.client
import json
import threading
from urllib.parse import urlencode

import pytest
from modules.inbox.web import InboxServer
from test_inbox import manifest


@pytest.fixture
def web(database):
    server = InboxServer(database, 0, 120, 60)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    port = server.server_address[1]
    def request(path, method='GET', body=None, headers=None, raw=None):
        connection = http.client.HTTPConnection('127.0.0.1', port, timeout=5)
        actual = dict(Origin=f'http://127.0.0.1:{port}')
        actual.update(headers or {})
        if body is not None:
            raw = json.dumps(body).encode(); actual['Content-Type'] = 'application/json'
        connection.request(method, path, raw, actual)
        response = connection.getresponse()
        result = response.status, dict(response.getheaders()), response.read()
        connection.close()
        return result
    yield server, request
    server.shutdown(); server.server_close(); thread.join(5)


def login(server, request):
    status, headers, raw = request('/api/login', 'POST', dict(token=server.token))
    assert status == 200
    assert 'HttpOnly' in headers['Set-Cookie'] and 'SameSite=Strict' in headers['Set-Cookie']
    return {'Cookie':headers['Set-Cookie'].split(';',1)[0], 'X-CSRF-Token':json.loads(raw)['csrf']}


def test_web_token_host_origin_csrf_and_no_public_content(web):
    server, request = web
    assert request('/api/inbox')[0] == 401
    assert request('/')[0] == 200
    assert request('/app.js')[0] == 200
    assert request('/api/login', 'POST', dict(token='wrong'))[0] == 401
    assert request('/api/login?token='+server.token, 'POST', {})[0] == 401
    assert request('/api/login', 'POST', dict(token=server.token), {'Host':'attacker.test'})[0] == 403
    assert request('/api/login', 'POST', dict(token=server.token), {'Origin':'http://attacker.test'})[0] == 403
    auth = login(server, request)
    assert request('/api/inbox', headers=auth)[0] == 200
    assert request('/api/inbox/uploads', 'POST', manifest(text='private'), {'Cookie':auth['Cookie']})[0] == 403
    assert request('/api/inbox/uploads', 'POST', manifest(text='private'), auth)[0] == 200
    assert request('/api/system', headers=auth)[0] == 404
    assert request('/api/logout', 'POST', {}, auth)[0] == 200
    assert request('/api/inbox', headers=auth)[0] == 401


def test_web_upload_download_is_attachment_and_stream_hash_checked(web):
    server, request = web; auth=login(server,request)
    content=b'<script>location="https://attacker.test/"</script>'
    data=manifest(content); data['files'][0]['name']='page.html'; data['files'][0]['mime']='text/html'
    del data['files'][0]['sha256']
    assert request('/api/inbox/uploads','POST',data,auth)[0]==200
    query=urlencode(dict(item=data['id'],file=data['files'][0]['id'],offset=0))
    status,_,raw=request('/api/transfer?'+query,'PUT',headers=auth,raw=content)
    assert status==200 and json.loads(raw)['offset']==len(content)
    status,_,raw=request('/api/inbox/uploads/'+data['id']+'/commit','POST',{},auth)
    assert status==200 and json.loads(raw)['item']['files'][0]['sha256']==hashlib.sha256(content).hexdigest()
    path='/api/download/'+data['id']+'/'+data['files'][0]['id']
    assert request(path)[0]==401
    status,headers,raw=request(path,headers=auth)
    assert status==200 and raw==content
    assert headers['Content-Type']=='application/octet-stream'
    assert headers['Content-Disposition'].startswith('attachment;')
    assert headers['X-Content-Type-Options']=='nosniff'
    assert headers['Cache-Control']=='no-store'


def test_web_token_ends_with_server_and_maintenance_rejects(web, database):
    server, request = web; auth=login(server,request)
    (database.parent/'maintenance').touch()
    assert request('/api/inbox',headers=auth)[0]==503
    assert not server.active()


def test_slow_upload_has_absolute_deadline_and_never_holds_storage_or_deploy_lock(web, database, monkeypatch):
    import fcntl
    import socket
    import time
    import modules.inbox.web as web_module
    from test_inbox import call
    server, request = web;auth=login(server,request)
    data=manifest(b'12345678');call(database,'/inbox/uploads','POST',data)
    monkeypatch.setattr(web_module,'REQUEST_SECONDS',.6)
    monkeypatch.setattr(web_module,'BODY_IDLE_SECONDS',.4)
    port=server.server_address[1]
    client=socket.create_connection(('127.0.0.1',port),timeout=2)
    query=urlencode(dict(item=data['id'],file=data['files'][0]['id'],offset=0))
    header=f'PUT /api/transfer?{query} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nOrigin: http://127.0.0.1:{port}\r\nCookie: {auth["Cookie"]}\r\nX-CSRF-Token: {auth["X-CSRF-Token"]}\r\nContent-Length: 8\r\n\r\n'
    client.sendall(header.encode()+b'1')
    start=time.monotonic()
    while not server.busy and time.monotonic()-start<.3:time.sleep(.005)
    assert server.busy==1
    # Previously both locks were held during network reads, blocking deployment.
    for path in (database.parent/'operations.lock',database.parent/'inbox/storage.lock'):
        with path.open('a') as lock:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    server.last_active-=1000
    assert server.active()  # An authenticated in-flight transfer is not idle.
    for byte in b'2345':
        time.sleep(.13)
        try:client.send(bytes([byte]))
        except OSError:break
    time.sleep(.2)
    try:
        result=client.recv(4096)
    except ConnectionResetError:
        result=b''
    finally:client.close()
    assert time.monotonic()-start<1.4
    assert result==b'' or b'408' in result or b'400' in result
    assert call(database,'/inbox/uploads/'+data['id'])['body']['files'][0]['offset']==0


def test_maintenance_interrupts_body_before_any_file_write(web,database):
    import socket
    import time
    from test_inbox import call
    server,request=web;auth=login(server,request)
    data=manifest(b'abcd');call(database,'/inbox/uploads','POST',data)
    port=server.server_address[1];client=socket.create_connection(('127.0.0.1',port),timeout=3)
    query=urlencode(dict(item=data['id'],file=data['files'][0]['id'],offset=0))
    headers=f'PUT /api/transfer?{query} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nOrigin: http://127.0.0.1:{port}\r\nCookie: {auth["Cookie"]}\r\nX-CSRF-Token: {auth["X-CSRF-Token"]}\r\nContent-Length: 4\r\n\r\n'
    client.sendall(headers.encode()+b'a');time.sleep(.03)
    (database.parent/'maintenance').touch();client.sendall(b'b')
    result=client.recv(4096);client.close()
    assert b'503' in result
    assert call(database,'/inbox/uploads/'+data['id'])['body']['files'][0]['offset']==0


def test_browser_download_supports_single_range_and_immutable_etag(web,database):
    from test_inbox import call,upload,commit
    server,request=web;auth=login(server,request)
    data=manifest(b'0123456789');call(database,'/inbox/uploads','POST',data);upload(database,data,0,b'0123456789');commit(database,data)
    path='/api/download/'+data['id']+'/'+data['files'][0]['id']
    status,headers,body=request(path,headers={**auth,'Range':'bytes=3-6'})
    assert status==206 and body==b'3456' and headers['Content-Range']=='bytes 3-6/10'
    assert headers['Accept-Ranges']=='bytes' and headers['ETag']=='"'+data['files'][0]['sha256']+'"'
    assert request(path,headers={**auth,'Range':'bytes=7-'})[2]==b'789'
    assert request(path,headers={**auth,'Range':'bytes=-2'})[2]==b'89'
    assert request(path,headers={**auth,'Range':'bytes=0-1','If-Range':'"other"'})[0]==200
    for invalid in ('bytes=10-','bytes=3-1','bytes=0-1,4-5','bytes=-0','bytes=-'):
        status,headers,_=request(path,headers={**auth,'Range':invalid})
        assert status==416 and headers['Content-Range']=='bytes */10'


def test_http_connection_capacity_is_bounded(web):
    import socket
    import time
    server, _request = web
    clients=[]
    try:
        for _ in range(8):
            clients.append(socket.create_connection(server.server_address, timeout=2))
        deadline=time.monotonic()+2
        while len(server.requests)<8 and time.monotonic()<deadline:time.sleep(.005)
        assert len(server.requests)==8
        with socket.create_connection(server.server_address,timeout=2) as excess:
            assert b'503 Service Unavailable' in excess.recv(1024)
        assert len(server.requests)==8
    finally:
        for client in clients:client.close()


def test_authenticated_download_uses_server_lifetime_instead_of_upload_deadline(web,database,monkeypatch):
    import time
    import modules.inbox.web as web_module
    from test_inbox import call,upload,commit
    server,request=web;auth=login(server,request)
    data=manifest(b'download');call(database,'/inbox/uploads','POST',data);upload(database,data,0,b'download');commit(database,data)
    monkeypatch.setattr(web_module,'REQUEST_SECONDS',.25)
    original=web_module.Handler.headers_out
    def slow_download(self,status,mime,length,extra=None):
        if mime=='application/octet-stream':time.sleep(.4)
        return original(self,status,mime,length,extra)
    monkeypatch.setattr(web_module.Handler,'headers_out',slow_download)
    status,_,body=request('/api/download/'+data['id']+'/'+data['files'][0]['id'],headers=auth)
    assert status==200 and body==b'download'


def test_bundle_streams_selected_files_text_notes_with_unique_safe_names(web,database):
    import io
    from pathlib import PurePosixPath
    import zipfile
    from test_inbox import call,upload,commit
    server,request=web;auth=login(server,request)
    first=manifest(b'first file',b'second file',text='private text')
    first['title']='../shared\\unsafe:<>title';first['note']='private note'
    for entry in first['files']:entry['name']='same.txt'
    second=manifest(text='second private text');second['title']=first['title']
    for data in (first,second):call(database,'/inbox/uploads','POST',data)
    upload(database,first,0,b'first file');upload(database,first,1,b'second file')
    commit(database,first);commit(database,second)
    path='/api/bundle?'+urlencode(dict(items=first['id']+','+second['id']))
    assert request(path)[0]==401
    status,headers,raw=request(path,headers=auth)
    assert status==200 and headers['Content-Type']=='application/zip'
    assert headers['Content-Disposition']=='attachment; filename="myserver-inbox.zip"'
    assert 'Content-Length' not in headers and headers['Connection']=='close'
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        names=archive.namelist()
        assert len(names)==len(set(names))==5
        assert all(not PurePosixPath(name).is_absolute() and '..' not in PurePosixPath(name).parts and not any(c in name for c in '\\:<>'+chr(0)) for name in names)
        assert all(entry.compress_type==zipfile.ZIP_STORED for entry in archive.infolist())
        assert {archive.read(name) for name in names}=={b'first file',b'second file',b'private text',b'private note',b'second private text'}
        assert archive.testzip() is None


def test_bundle_limits_and_missing_selection_never_start_zip(web,database):
    import uuid
    from test_inbox import call,commit
    server,request=web;auth=login(server,request)
    one=manifest(text='item');call(database,'/inbox/uploads','POST',one);commit(database,one)
    for query in ('', 'items=', 'items='+one['id']+','+one['id'], 'items='+','.join(str(uuid.uuid4()) for _ in range(33))):
        assert request('/api/bundle?'+query,headers=auth)[0]==400
    assert request('/api/bundle?items='+str(uuid.uuid4()),headers=auth)[0]==404
    groups=[manifest(*([b'']*22)) for _ in range(3)]
    for group in groups:call(database,'/inbox/uploads','POST',group);commit(database,group)
    status,headers,_=request('/api/bundle?items='+','.join(g['id'] for g in groups),headers=auth)
    assert status==413 and headers['Content-Type'].startswith('application/json')


def test_bundle_releases_locks_before_streaming_and_keeps_open_deleted_files(web,database,monkeypatch):
    import fcntl
    import io
    import zipfile
    import modules.inbox.web as web_module
    from test_inbox import call,upload,commit
    server,request=web;auth=login(server,request)
    data=manifest(b'immutable file');call(database,'/inbox/uploads','POST',data);upload(database,data,0,b'immutable file');commit(database,data)
    original=web_module.ZipStream.write
    checked=[]
    def write(self,value):
        if not checked:
            for path in (database.parent/'operations.lock',database.parent/'inbox/storage.lock'):
                with path.open('a') as lock:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            assert call(database,'/inbox/items/'+data['id'],'DELETE')['status']==200
            checked.append(True)
        return original(self,value)
    monkeypatch.setattr(web_module.ZipStream,'write',write)
    status,_,raw=request('/api/bundle?items='+data['id'],headers=auth)
    assert status==200 and checked
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        assert archive.read(archive.namelist()[0])==b'immutable file'


def test_interrupted_bundle_closes_every_open_attachment(web,database,monkeypatch):
    from pathlib import Path
    import modules.inbox.web as web_module
    from test_inbox import call,upload,commit
    server,request=web;auth=login(server,request)
    data=manifest(b'one',b'two');call(database,'/inbox/uploads','POST',data)
    upload(database,data,0,b'one');upload(database,data,1,b'two');commit(database,data)
    opened=[];original_open=Path.open
    def track_open(path,*args,**kwargs):
        handle=original_open(path,*args,**kwargs)
        if path.parent.name==data['id'] and path.parent.parent.name=='objects':opened.append(handle)
        return handle
    monkeypatch.setattr(Path,'open',track_open)
    original_write=web_module.ZipStream.write
    def interrupt(self,value):
        (database.parent/'maintenance').touch()
        return original_write(self,value)
    monkeypatch.setattr(web_module.ZipStream,'write',interrupt)
    status,_,_raw=request('/api/bundle?items='+data['id'],headers=auth)
    assert status==200
    assert len(opened)==2 and all(handle.closed for handle in opened)


@pytest.mark.parametrize('failure', ['eof', 'error'])
def test_bundle_source_failure_cannot_finalize_a_valid_truncated_archive(web,database,monkeypatch,failure):
    import io
    from pathlib import Path
    import zipfile
    from test_inbox import call,upload,commit
    server,request=web;auth=login(server,request)
    content=b'second complete file'*10000
    data=manifest(b'first complete file',content)
    call(database,'/inbox/uploads','POST',data)
    upload(database,data,0,b'first complete file');upload(database,data,1,content);commit(database,data)
    original_open=Path.open;opened=[]
    class FailingReader:
        def __init__(self,source):self.source=source;self.reads=0
        def __enter__(self):return self
        def __exit__(self,*_args):self.source.close()
        def fileno(self):return self.source.fileno()
        def read(self,_size):
            self.reads+=1
            if self.reads==1:return self.source.read(7)
            if failure=='eof':return b''
            raise OSError('Injected storage read failure')
    def failing_open(path,*args,**kwargs):
        handle=original_open(path,*args,**kwargs)
        if path.parent.name==data['id'] and path.parent.parent.name=='objects':
            opened.append(handle)
            if path.name==data['files'][1]['id']:return FailingReader(handle)
        return handle
    monkeypatch.setattr(Path,'open',failing_open)
    status,_,raw=request('/api/bundle?items='+data['id'],headers=auth)
    assert status==200 and raw.startswith(b'PK')
    assert len(opened)==2 and all(handle.closed for handle in opened)
    with pytest.raises(zipfile.BadZipFile):zipfile.ZipFile(io.BytesIO(raw))
    assert b'PK\x05\x06' not in raw  # No end-of-central-directory success marker.


def preview_fixture(database,name,mime,content):
    from test_inbox import call,upload,commit
    data=manifest(content);data['files'][0].update(name=name,mime=mime)
    assert call(database,'/inbox/uploads','POST',data)['status']==201
    upload(database,data,0,content);commit(database,data)
    return '/api/preview/'+data['id']+'/'+data['files'][0]['id']


def small_png(width=1,height=1):
    import struct
    import zlib
    def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',width,height,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(b'\x00\xff\x88\x22\xff'))+chunk(b'IEND',b'')


def test_authenticated_raster_preview_uses_signature_and_is_sandboxed(web,database):
    server,request=web;auth=login(server,request)
    png=small_png();path=preview_fixture(database,'photo.png','image/png',png)
    assert request(path)[0]==401
    status,headers,raw=request(path,headers=auth)
    assert status==200 and raw==png
    assert headers['Content-Type']=='image/png'
    assert headers['Content-Disposition'].startswith('inline;')
    assert headers['X-Content-Type-Options']=='nosniff'
    assert 'sandbox' in headers['Content-Security-Policy']
    assert "default-src 'none'" in headers['Content-Security-Policy']
    assert headers['Cross-Origin-Resource-Policy']=='same-origin'
    assert headers['Cache-Control']=='no-store'
    assert request(path,headers={**auth,'Range':'bytes=0-7'})[2]==png[:8]


def test_spoofed_images_pdf_and_excessive_dimensions_are_not_rendered(web,database):
    server,request=web;auth=login(server,request)
    html=b'<html><script>window.pwned=1</script></html>'
    path=preview_fixture(database,'attack.png','image/png',html)
    assert request(path,headers=auth)[0]==415
    pdf=preview_fixture(database,'document.pdf','application/pdf',b'%PDF-1.4')
    assert request(pdf,headers=auth)[0]==415
    giant=preview_fixture(database,'giant.png','image/png',small_png(100000,100000))
    assert request(giant,headers=auth)[0]==413


@pytest.mark.parametrize('name,mime,content',[
    ('page.html','text/html',b'<script>window.pwned=1</script>'),
    ('image.svg','image/svg+xml',b'<svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)"></svg>'),
    ('script.js','application/javascript',b'fetch("/api/inbox").then(alert)'),
])
def test_active_document_previews_are_only_utf8_plain_text(web,database,name,mime,content):
    server,request=web;auth=login(server,request)
    status,headers,body=request(preview_fixture(database,name,mime,content),headers=auth)
    assert status==200 and body==content
    assert headers['Content-Type']=='text/plain; charset=utf-8'
    assert headers['Content-Disposition']=='inline'
    assert 'sandbox' in headers['Content-Security-Policy']
    assert 'script-src' not in headers['Content-Security-Policy']


def test_text_size_encoding_and_binary_content_limits(web,database):
    server,request=web;auth=login(server,request)
    large=preview_fixture(database,'large.txt','text/plain',b'x'*(1024**2+1))
    assert request(large,headers=auth)[0]==413
    binary=preview_fixture(database,'binary.txt','text/plain',b'hello\x00world')
    assert request(binary,headers=auth)[0]==415
    invalid=preview_fixture(database,'invalid.txt','text/plain',b'hello\xff')
    assert request(invalid,headers=auth)[0]==415


def test_audio_preview_supports_range_and_media_is_allowed_by_page_csp(web,database):
    import io
    import wave
    server,request=web;auth=login(server,request)
    source=io.BytesIO()
    with wave.open(source,'wb') as output:
        output.setnchannels(1);output.setsampwidth(1);output.setframerate(8000);output.writeframes(b'\x80'*32)
    content=source.getvalue();path=preview_fixture(database,'recording.wav','audio/wav',content)
    status,headers,raw=request(path,headers={**auth,'Range':'bytes=10-19'})
    assert status==206 and raw==content[10:20]
    assert headers['Content-Type']=='audio/wav' and headers['Content-Range']==f'bytes 10-19/{len(content)}'
    page=request('/')[1]
    assert "media-src 'self'" in page['Content-Security-Policy']
    bad=preview_fixture(database,'fake.mp4','video/mp4',b'<script>alert(1)</script>')
    assert request(bad,headers=auth)[0]==415
