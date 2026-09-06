"""One bounded JSON request per restricted SSH exec channel. Never runs client commands."""
import hashlib
import json
import os
import re
import secrets
import signal
import sqlite3
import sys
import time
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, ProxyHandler

MAX_REQUEST = 140 * 1024
MAX_RESPONSE = 16 * 1024 * 1024
# Capabilities are assigned during device registration, never by the phone.
ROUTES = [
    (None, {'GET'}, r'/session'),
    ('thoughts', {'GET', 'POST'}, r'/thoughts'),
    ('thoughts', {'PATCH', 'DELETE'}, r'/thoughts/[0-9a-f-]{36}'),
    ('thoughts', {'GET'}, r'/thoughts/[0-9a-f-]{36}/history'),
    ('thoughts', {'GET'}, r'/publication'),
    ('thoughts', {'POST'}, r'/publish'),
    ('thoughts', {'GET'}, r'/export'),
    ('system.read', {'GET'}, r'/system'),
]


def validate_request(value, capabilities):
    if not isinstance(value, dict):
        raise ValueError('请求必须是 JSON 对象')
    path, method = value.get('path'), value.get('method')
    if not isinstance(path, str) or len(path) > 2048 or not isinstance(method, str):
        raise ValueError('请求格式不正确')
    url = urlsplit(path)
    if url.scheme or url.netloc or url.fragment or '%' in url.path or '\\' in path or any(ord(c) < 32 for c in path):
        raise ValueError('请求路径不被允许')
    for capability, methods, pattern in ROUTES:
        if method in methods and re.fullmatch(pattern, url.path):
            if capability and capability not in capabilities:
                raise PermissionError('这台设备没有此功能的权限')
            body = value.get('body')
            if body is not None and not isinstance(body, dict):
                raise ValueError('请求内容必须是 JSON 对象')
            return path, method, body
    raise PermissionError('设备密钥不能执行此操作')


def handle(value, capabilities, database, opener=None):
    path, method, body = validate_request(value, capabilities)
    token = secrets.token_urlsafe(32)
    hashed = hashlib.sha256(token.encode()).hexdigest()
    connection = sqlite3.connect(database, timeout=10)
    try:
        with connection:
            connection.execute('DELETE FROM sessions WHERE expires<?', (time.time(),))
            connection.execute('INSERT INTO sessions VALUES(?,?)', (hashed, time.time() + 60))
        data = None if body is None else json.dumps(body).encode()
        req = Request('http://127.0.0.1:8765/api' + path, data=data, method=method, headers={
            'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token})
        opener = opener or build_opener(ProxyHandler({}))
        try:
            response = opener.open(req, timeout=30)
        except HTTPError as error:
            response = error
        with response:
            raw = response.read(MAX_RESPONSE + 1)
            if len(raw) > MAX_RESPONSE:
                raise ValueError('响应过大，请在服务器导出备份')
            result = json.loads(raw)
            if path == '/session' and response.code == 200:
                result.update(capabilities=sorted(capabilities), transport='ssh', protocol_version=1)
            return dict(status=response.code, body=result)
    finally:
        with connection:
            connection.execute('DELETE FROM sessions WHERE token=?', (hashed,))
        connection.close()


def registered_device(root, device_id):
    if not re.fullmatch('[0-9a-f]{64}', device_id):
        raise PermissionError('设备标识无效')
    record = json.loads((root / 'devices' / (device_id + '.json')).read_text())
    command = f'restrict,command="{root}/deploy/ssh-gateway.sh {device_id}" '
    authorized = Path.home() / '.ssh/authorized_keys'
    if not any(line.startswith(command + record['public_key'] + ' ') for line in authorized.read_text().splitlines()):
        raise PermissionError('设备已被撤销')
    return record


def main():
    signal.alarm(45)
    try:
        if os.environ.get('SSH_ORIGINAL_COMMAND') != 'thoughts-rpc-v1' or len(sys.argv) != 2:
            raise PermissionError('此密钥仅能用于想法 App')
        root = Path.home() / '.local/share/thoughts'
        record = registered_device(root, sys.argv[1])
        raw = sys.stdin.buffer.readline(MAX_REQUEST + 1)
        if len(raw) > MAX_REQUEST or not raw.endswith(b'\n'):
            raise ValueError('请求过大或不完整')
        result = handle(json.loads(raw), record['capabilities'], root / 'thoughts.sqlite')
    except PermissionError as error:
        result = dict(status=403, body=dict(error=str(error)))
    except (ValueError, json.JSONDecodeError):
        result = dict(status=400, body=dict(error='请求无效'))
    except Exception:
        result = dict(status=503, body=dict(error='服务暂时不可用，本地记录已保留'))
    sys.stdout.write(json.dumps(result, ensure_ascii=False, separators=(',', ':')) + '\n')
    sys.stdout.flush()


if __name__ == '__main__':
    main()
