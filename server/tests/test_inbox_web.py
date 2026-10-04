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
