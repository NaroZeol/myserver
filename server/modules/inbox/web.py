"""Explicitly started, temporary loopback management UI; no public listener."""
from contextlib import ExitStack, suppress
from http import cookies
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hmac
import io
import json
import os
import re
from pathlib import Path
import secrets
import socket
import threading
import time
import unicodedata
import zipfile
from urllib.parse import parse_qs, quote, urlsplit

from database import connect
from rpc import RpcError
from .cli import call, operation
from .service import MAX_CHUNK, PREVIEW_LIMITS, files, ident, item_row, locked, transfer, preview_kind

STATIC = Path(__file__).with_name('web')
REQUEST_SECONDS = 90
BODY_IDLE_SECONDS = 5
PREVIEW_CSP = "default-src 'none'; sandbox; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
MAX_IMAGE_PIXELS = 40_000_000


def raster_dimensions(head):
    if head.startswith(b'\x89PNG\r\n\x1a\n') and len(head) >= 24 and head[12:16] == b'IHDR':
        return 'image/png', int.from_bytes(head[16:20], 'big'), int.from_bytes(head[20:24], 'big')
    if head[:6] in (b'GIF87a', b'GIF89a') and len(head) >= 10:
        return 'image/gif', int.from_bytes(head[6:8], 'little'), int.from_bytes(head[8:10], 'little')
    if head.startswith(b'RIFF') and head[8:12] == b'WEBP' and len(head) >= 30:
        if head[12:16] == b'VP8X':
            return 'image/webp', 1 + int.from_bytes(head[24:27], 'little'), 1 + int.from_bytes(head[27:30], 'little')
        if head[12:16] == b'VP8L' and head[20] == 0x2f:
            bits = int.from_bytes(head[21:25], 'little')
            return 'image/webp', 1 + (bits & 0x3fff), 1 + ((bits >> 14) & 0x3fff)
        if head[12:16] == b'VP8 ' and head[23:26] == b'\x9d\x01\x2a':
            return 'image/webp', int.from_bytes(head[26:28], 'little') & 0x3fff, int.from_bytes(head[28:30], 'little') & 0x3fff
    if head.startswith(b'\xff\xd8\xff'):
        position = 2
        while position < len(head) and head[position] == 0xff:
            while position < len(head) and head[position] == 0xff:
                position += 1
            if position >= len(head):
                break
            marker = head[position]; position += 1
            if marker in (0xd9, 0xda):
                break
            if marker == 1 or 0xd0 <= marker <= 0xd8:
                continue
            if position + 2 > len(head):
                break
            length = int.from_bytes(head[position:position + 2], 'big')
            if length < 2 or position + length > len(head):
                break
            if marker in (0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf) and length >= 8:
                return 'image/jpeg', int.from_bytes(head[position + 5:position + 7], 'big'), int.from_bytes(head[position + 3:position + 5], 'big')
            position += length
    return None


def safe_preview(source, entry):
    kind = preview_kind(entry['mime'], entry['name'], entry['size'])
    if kind is None:
        if preview_kind(entry['mime'], entry['name'], 0):
            raise RpcError('文件较大，请下载后查看', 413)
        raise RpcError('此格式暂不支持预览，请下载后查看', 415)
    if kind == 'text':
        raw = source.read(PREVIEW_LIMITS['text'] + 1)
        if len(raw) > PREVIEW_LIMITS['text']:
            raise RpcError('文本较大，请下载后查看', 413)
        if len(raw) != entry['size']:
            raise RpcError('文件不完整，请重新下载', 409)
        try:
            text = raw.decode('utf-8-sig')
        except UnicodeError:
            raise RpcError('文本编码暂不支持，请下载后查看', 415) from None
        if any(ord(char) < 32 and char not in '\r\n\t' for char in text):
            raise RpcError('文件包含二进制内容，请下载后查看', 415)
        return 'text/plain; charset=utf-8', text.encode('utf-8')
    head = source.read(min(entry['size'], 256 * 1024))
    if kind == 'image':
        dimensions = raster_dimensions(head)
        if dimensions:
            mime, width, height = dimensions
            if 0 < width <= 32768 and 0 < height <= 32768 and width * height <= MAX_IMAGE_PIXELS:
                return mime, None
            raise RpcError('图片尺寸较大，请下载后查看', 413)
    elif kind in ('audio', 'video'):
        if len(head) >= 16 and head[4:8] == b'ftyp' and 16 <= int.from_bytes(head[:4], 'big') <= entry['size']:
            return ('video/mp4' if kind == 'video' else 'audio/mp4'), None
        if head.startswith(b'\x1a\x45\xdf\xa3') and b'webm' in head[:4096]:
            return kind + '/webm', None
        if head.startswith(b'OggS\x00'):
            return kind + '/ogg', None
        if kind == 'audio':
            if head.startswith(b'RIFF') and head[8:12] == b'WAVE':
                return 'audio/wav', None
            if head.startswith(b'fLaC'):
                return 'audio/flac', None
            if head.startswith(b'ID3') and len(head) >= 10 or len(head) >= 2 and head[0] == 0xff and head[1] & 0xe0 == 0xe0:
                return 'audio/mpeg', None
    raise RpcError('文件内容与可预览格式不符，请下载后查看', 415)


class InboxServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = False

    def __init__(self, database, port, lifetime, idle_timeout):
        if not 0 <= port <= 65535 or not 60 <= lifetime <= 86400 or not 60 <= idle_timeout <= lifetime:
            raise ValueError('端口须为 0–65535；有效期为 60–86400 秒，空闲超时不超过有效期')
        self.database = Path(database)
        self.token = secrets.token_urlsafe(32)
        self.cookie = 'myserver_inbox_' + secrets.token_hex(8)
        self.sessions = {}
        self.failures = []
        self.deadline = time.monotonic() + lifetime
        self.last_active = time.monotonic()
        self.idle_timeout = idle_timeout
        self.busy = 0
        self.guard = threading.Lock()
        self.slots = threading.BoundedSemaphore(8)
        self.requests = set()
        super().__init__(('127.0.0.1', port), Handler)
        self.timeout = 1

    def active(self):
        current = time.monotonic()
        return current < self.deadline and (self.busy > 0 or current - self.last_active < self.idle_timeout) and not (self.database.parent / 'maintenance').exists()


    @staticmethod
    def close_socket(request):
        with suppress(OSError):
            request.shutdown(socket.SHUT_RDWR)
        with suppress(OSError):
            request.close()

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            with suppress(OSError):
                request.sendall(b'HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\nContent-Length: 0\r\n\r\n')
            self.close_socket(request)
            return
        with self.guard:
            self.requests.add(request)
        try:
            super().process_request(request, address)
        except BaseException:
            with self.guard:
                self.requests.discard(request)
            self.slots.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            with self.guard:
                self.requests.discard(request)
            self.slots.release()

    def handle_error(self, request, address):
        pass  # Closed/deadline sockets must not print private request diagnostics.

    def server_close(self):
        with self.guard:
            requests = tuple(self.requests)
        for request in requests:
            self.close_socket(request)
        super().server_close()


def zip_component(value):
    cleaned = ''.join('_' if c in '/\\:<>"|?*' or unicodedata.category(c).startswith('C') else c for c in value)
    cleaned = cleaned.strip(' .').encode('utf-8')[:120].decode('utf-8', errors='ignore').rstrip(' .')
    return cleaned or '收件'


class ZipStream:
    """Non-seekable ZIP sink; zipfile keeps only its bounded central directory."""
    def __init__(self, handler):
        self.handler = handler
        self.position = 0
        self.aborted = False

    def abort(self):
        self.aborted = True

    def tell(self):
        return self.position

    def seek(self, *_args):
        raise io.UnsupportedOperation('stream')

    def write(self, value):
        if self.aborted:
            raise OSError('ZIP stream aborted')
        try:
            if not self.handler.server.active():
                raise TimeoutError('Inbox download expired or maintenance started')
            self.handler.wfile.write(value)
            self.position += len(value)
            return len(value)
        except BaseException:
            self.abort()
            raise

    def flush(self):
        if self.aborted:
            raise OSError('ZIP stream aborted')
        self.handler.wfile.flush()


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    server_version = 'myserver'
    sys_version = ''

    def log_message(self, *_args):
        pass  # Tokens, filenames and private text must not enter access logs.

    def setup(self):
        super().setup()
        self.connection.settimeout(30)
        self.started = False
        self.busy = False
        self.watchdog = None
        self.set_deadline(min(time.monotonic() + REQUEST_SECONDS, self.server.deadline))

    def set_deadline(self, deadline):
        self.request_deadline = deadline
        if self.watchdog is not None:
            self.watchdog.cancel()
        self.watchdog = threading.Timer(max(0, deadline - time.monotonic()), self.server.close_socket, (self.connection,))
        self.watchdog.daemon = True
        self.watchdog.start()

    def finish(self):
        if self.watchdog is not None:
            self.watchdog.cancel()
        with suppress(OSError):
            super().finish()

    def read_body(self, length):
        # Network input is received before any deployment/storage locks. A
        # sliding socket timeout alone cannot bound a slow trickling client.
        result = bytearray()
        while len(result) < length:
            remaining = self.request_deadline - time.monotonic()
            if remaining <= 0:
                raise RpcError('上传超时，请重新尝试', 408)
            if not self.server.active():
                raise RpcError('管理入口已结束或服务正在维护', 503)
            self.connection.settimeout(min(BODY_IDLE_SECONDS, remaining))
            block = self.rfile.read1(min(65536, length - len(result)))
            if not block:
                raise RpcError('请求内容不完整')
            result.extend(block)
        return bytes(result)

    def reply(self, status, value, extra=None):
        raw = json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode()
        self.bytes(status, raw, 'application/json; charset=utf-8', extra)

    def bytes(self, status, raw, mime, extra=None):
        self.headers_out(status, mime, len(raw), extra)
        self.wfile.write(raw)

    def headers_out(self, status, mime, length, extra=None):
        self.started = True
        self.send_response(status)
        self.send_header('Content-Type', mime)
        if length is not None:
            self.send_header('Content-Length', str(length))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Referrer-Policy', 'no-referrer')
        self.send_header('Content-Security-Policy', (extra or {}).get('Content-Security-Policy', "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' blob:; media-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"))
        self.send_header('Connection', 'close')
        for name, value in (extra or {}).items():
            if name.lower() != 'content-security-policy':
                self.send_header(name, value)
        self.end_headers()
        self.close_connection = True

    def length(self, maximum):
        if self.headers.get('Transfer-Encoding') or len(self.headers.get_all('Content-Length', [])) != 1:
            raise RpcError('请求长度无效', 400)
        try:
            length = int(self.headers['Content-Length'])
        except ValueError:
            raise RpcError('请求长度无效', 400) from None
        if not 0 <= length <= maximum:
            raise RpcError('请求过大', 413)
        return length

    def json_body(self):
        if self.headers.get_content_type() != 'application/json':
            raise RpcError('需要 JSON 请求', 415)
        raw = self.read_body(self.length(140 * 1024))
        def reject(_value):
            raise ValueError('Nonfinite JSON')
        value = json.loads(raw, parse_constant=reject)
        if not isinstance(value, dict):
            raise RpcError('请求格式无效')
        return value

    def authenticate(self, mutation=False):
        jar = cookies.SimpleCookie()
        with suppress(cookies.CookieError):
            jar.load(self.headers.get('Cookie', ''))
        value = jar.get(self.server.cookie)
        session = self.server.sessions.get(value.value if value else '')
        if session is None:
            raise RpcError('请先输入本次访问令牌', 401)
        if mutation and not hmac.compare_digest(self.headers.get('X-CSRF-Token', '').encode('utf-8'), session.encode('ascii')):
            raise RpcError('页面验证失效，请刷新', 403)
        with self.server.guard:
            self.server.last_active = time.monotonic()
            self.server.busy += 1
            self.busy = True
        return session

    def handle_method(self):
        try:
            host = self.headers.get('Host', '')
            if len(self.path) > 2048 or sum(len(k) + len(v) for k, v in self.headers.items()) > 16384:
                raise RpcError('请求头过大', 431)
            # SSH -L preserves the browser's Host/Origin, including its local port.
            # Validate an exact loopback authority, not the server's listening port.
            authority = re.fullmatch(r'(?:127\.0\.0\.1|localhost|\[::1\])(?::([1-9][0-9]{0,4}))?', host)
            if len(self.headers.get_all('Host', [])) != 1 or authority is None or int(authority.group(1) or 80) > 65535:
                raise RpcError('仅支持本机访问；请使用 SSH 端口转发', 403)
            if not self.server.active():
                raise RpcError('本次管理入口已结束', 503)
            mutation = self.command != 'GET'
            origin = self.headers.get('Origin')
            if len(self.headers.get_all('Origin', [])) > 1 or ((mutation or origin is not None) and origin != 'http://' + host):
                raise RpcError('跨来源请求被拒绝', 403)
            url = urlsplit(self.path)
            if url.scheme or url.netloc or url.fragment or '%' in url.path:
                raise RpcError('请求路径无效')
            path = url.path
            if self.command == 'GET' and path in ('/', '/app.js', '/style.css'):
                name, mime = {'/': ('index.html', 'text/html; charset=utf-8'), '/app.js': ('app.js', 'text/javascript; charset=utf-8'), '/style.css': ('style.css', 'text/css; charset=utf-8')}[path]
                self.bytes(200, (STATIC / name).read_bytes(), mime)
                return
            if path == '/api/login' and self.command == 'POST':
                current = time.monotonic()
                self.server.failures = [stamp for stamp in self.server.failures if current - stamp < 60]
                if len(self.server.failures) >= 10:
                    raise RpcError('尝试次数较多，请一分钟后重试', 429)
                value = self.json_body().get('token', '')
                if not isinstance(value, str) or len(value) > 200 or not hmac.compare_digest(value.encode('utf-8'), self.server.token.encode('ascii')):
                    self.server.failures.append(current)
                    raise RpcError('访问令牌不正确', 401)
                if len(self.server.sessions) >= 16:
                    self.server.sessions.pop(next(iter(self.server.sessions)))
                sid, csrf = secrets.token_urlsafe(32), secrets.token_urlsafe(24)
                self.server.sessions[sid] = csrf
                self.server.last_active = current
                self.reply(200, dict(csrf=csrf), {'Set-Cookie': f'{self.server.cookie}={sid}; HttpOnly; SameSite=Strict; Path=/'})
                return
            csrf = self.authenticate(mutation)
            if path == '/api/session' and self.command == 'GET':
                self.reply(200, dict(csrf=csrf))
                return
            if path == '/api/logout' and self.command == 'POST':
                self.server.sessions = {sid: value for sid, value in self.server.sessions.items() if value != csrf}
                self.reply(200, dict(ok=True), {'Set-Cookie': f'{self.server.cookie}=; Max-Age=0; HttpOnly; SameSite=Strict; Path=/'})
                return
            if path == '/api/transfer' and self.command == 'PUT':
                query = parse_qs(url.query, max_num_fields=5, strict_parsing=True)
                if set(query) != {'item', 'file', 'offset'} or any(len(v) != 1 for v in query.values()):
                    raise RpcError('文件传输参数无效')
                header = dict(op='upload', item=query['item'][0], file=query['file'][0], offset=int(query['offset'][0]), length=self.length(MAX_CHUNK))
                content = self.read_body(header['length'])
                replies = []
                with operation(self.server.database), connect(self.server.database) as connection:
                    transfer(connection, self.server.database, header, io.BytesIO(content), io.BytesIO(), replies.append)
                self.reply(200, replies[-1])
                return
            if path == '/api/bundle' and self.command == 'GET':
                self.set_deadline(self.server.deadline)
                self.bundle(url.query)
                return
            if path.startswith('/api/preview/') and self.command == 'GET':
                self.set_deadline(self.server.deadline)
                self.download(path, preview=True)
                return
            if path.startswith('/api/download/') and self.command == 'GET':
                self.set_deadline(self.server.deadline)
                self.download(path)
                return
            if path.startswith('/api/inbox'):
                body = self.json_body() if self.command in ('POST', 'PATCH') else None
                with operation(self.server.database):
                    result = call(self.server.database, self.path[4:], self.command, body)
                self.reply(200, result)
                return
            raise RpcError('操作不存在', 404)
        except RpcError as error:
            if not self.started:
                self.reply(error.status, dict(error=str(error)))
        except (ValueError, UnicodeError, RecursionError):
            if not self.started:
                self.reply(400, dict(error='请求无效'))
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            self.close_connection = True
        except Exception:
            if not self.started:
                self.reply(503, dict(error='服务暂时不可用，请稍后重试'))
            self.close_connection = True
        finally:
            if self.busy:
                with self.server.guard:
                    self.server.busy -= 1
                    self.server.last_active = time.monotonic()
                self.busy = False

    def bundle(self, query_string):
        query = parse_qs(query_string, max_num_fields=2, strict_parsing=True)
        if set(query) != {'items'} or len(query['items']) != 1:
            raise RpcError('请选择需要下载的收件')
        item_ids = query['items'][0].split(',')
        if not 1 <= len(item_ids) <= 32 or len(set(item_ids)) != len(item_ids):
            raise RpcError('每次最多下载 32 条不同收件')
        for item_id in item_ids:
            ident(item_id)
        with ExitStack() as handles:
            entries = []
            with operation(self.server.database), locked(self.server.database, False) as directory, connect(self.server.database) as connection:
                groups = [(item_row(connection, item_id, True), files(connection, item_id)) for item_id in item_ids]
                if sum(len(group_files) for _row, group_files in groups) > 64:
                    raise RpcError('每次打包最多包含 64 个附件，请减少选择', 413)
                for number, (row, group_files) in enumerate(groups, 1):
                    folder = f'{number:02d}-' + zip_component(row['title'])
                    if row['text']:
                        entries.append((folder + '/文字.txt', row['text'].encode('utf-8'), None))
                    if row['note']:
                        entries.append((folder + '/备注.txt', row['note'].encode('utf-8'), None))
                    for index, entry in enumerate(group_files, 1):
                        source = handles.enter_context((directory / 'objects' / row['id'] / entry['id']).open('rb'))
                        if os.fstat(source.fileno()).st_size != entry['size']:
                            raise RpcError('有附件不完整，请取消选择后重试', 409)
                        name = folder + f'/{index:02d}-' + zip_component(entry['name'])
                        entries.append((name, source, entry['size']))
            # All descriptors point at immutable inodes; deleting an item or
            # deploying while a reader downloads does not require holding locks.
            self.headers_out(200, 'application/zip', None, {'Content-Disposition': 'attachment; filename="myserver-inbox.zip"'})
            writer = ZipStream(self)
            with zipfile.ZipFile(writer, mode='w', compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
                try:
                    for name, content, size in entries:
                        if size is None:
                            archive.writestr(name, content)
                        else:
                            with archive.open(name, mode='w', force_zip64=True) as target:
                                try:
                                    remaining = size
                                    while remaining:
                                        block = content.read(min(65536, remaining))
                                        if not block:
                                            raise OSError('Unexpected EOF in immutable inbox object')
                                        target.write(block)
                                        remaining -= len(block)
                                except BaseException:
                                    # Abort before the member context can write a
                                    # descriptor for partial content. ZIP close
                                    # must also be unable to emit its success
                                    # directory after a storage read failure.
                                    writer.abort()
                                    raise
                except BaseException:
                    writer.abort()
                    raise

    def download(self, path, preview=False):
        parts = path.split('/')
        if len(parts) != 5:
            raise RpcError('下载路径无效')
        item_id, file_id = ident(parts[3]), ident(parts[4])
        with operation(self.server.database), locked(self.server.database, False) as directory, connect(self.server.database) as connection:
            item_row(connection, item_id, True)
            entry = next((f for f in files(connection, item_id) if f['id'] == file_id), None)
            if not entry:
                raise RpcError('文件不存在', 404)
            source = (directory / 'objects' / item_id / file_id).open('rb')
            if os.fstat(source.fileno()).st_size != entry['size']:
                source.close()
                raise RpcError('文件不完整', 409)
        # An open immutable inode remains safe if another request deletes its item.
        with source:
            mime = 'application/octet-stream'
            preview_headers = {'Content-Security-Policy': PREVIEW_CSP, 'Cross-Origin-Resource-Policy': 'same-origin'} if preview else {}
            if preview:
                mime, text = safe_preview(source, entry)
                if text is not None:
                    self.bytes(200, text, mime, {**preview_headers, 'Content-Disposition': 'inline'})
                    return
            size = entry['size']
            start, end, status = 0, size - 1, 200
            etag = '"' + entry['sha256'] + '"'
            range_value = self.headers.get('Range')
            if range_value and self.headers.get('If-Range', etag) == etag:
                match = re.fullmatch(r'bytes=([0-9]{0,20})-([0-9]{0,20})', range_value.strip())
                valid = len(self.headers.get_all('Range', [])) == 1 and match and any(match.groups()) and size > 0
                if valid:
                    left, right = match.groups()
                    if left:
                        start = int(left)
                        end = min(size - 1, int(right)) if right else size - 1
                        valid = start < size and start <= end
                    else:
                        suffix = int(right)
                        valid = suffix > 0
                        start = max(0, size - suffix)
                if not valid:
                    self.reply(416, dict(error='下载范围无效'), {'Content-Range': f'bytes */{size}', 'Accept-Ranges': 'bytes'})
                    return
                status = 206
            length = max(0, end - start + 1)
            headers = {**preview_headers, 'Content-Disposition': ("inline" if preview else "attachment") + "; filename=download; filename*=UTF-8''" + quote(entry['name'], safe=''), 'Accept-Ranges': 'bytes', 'ETag': etag}
            if status == 206:
                headers['Content-Range'] = f'bytes {start}-{end}/{size}'
            self.headers_out(status, mime, length, headers)
            source.seek(start)
            remaining = length
            while remaining:
                if not self.server.active():
                    self.close_connection = True
                    return
                block = source.read(min(65536, remaining))
                if not block:
                    self.close_connection = True
                    return
                self.wfile.write(block)
                remaining -= len(block)

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = handle_method


def serve(database, port=8787, lifetime=7200, idle_timeout=1800):
    with operation(database):
        call(database, '/inbox?limit=1')
    with InboxServer(database, port, lifetime, idle_timeout) as server:
        actual = server.server_address[1]
        print(f'管理地址：http://127.0.0.1:{actual}\n访问令牌：{server.token}\n有效期：{lifetime // 60} 分钟；空闲 {idle_timeout // 60} 分钟后结束\n电脑 SSH 转发：ssh -L {actual}:127.0.0.1:{actual} <服务器别名>\n按 Ctrl+C 关闭。', flush=True)
        try:
            while server.active():
                server.handle_request()
        except KeyboardInterrupt:
            pass
        finally:
            server.sessions.clear()
            server.token = ''
    print('管理入口已关闭。')
