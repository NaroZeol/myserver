"""Dispatch authorized RPC requests directly to installed business modules."""
from datetime import datetime, timezone
from pathlib import Path
import re
import shutil
from urllib.parse import urlsplit, parse_qs

from database import connect
import modules
from rpc import Reply, Request, Route, RpcError
from system_metrics import snapshot
from listeners import snapshot as listening_ports

VERSION = '1.12.0'


def session(connection, request):
    return Reply(dict(ok=True, capabilities=sorted(request.capabilities), transport='ssh', protocol_version=1))


def system_status(connection, request):
    root = request.database.parent
    backups = sorted([*(root / 'backups').glob('myserver-????????-??????.sqlite'),
                      *(root / 'backups').glob('myserver-????????-??????.tar')])
    latest = backups[-1] if backups else None
    return Reply(dict(
        protocol_version=1, service='myserver', version=VERSION,
        metrics=snapshot(root), capabilities=sorted(request.capabilities),
        modules=modules.status(connection, request.capabilities),
        storage=dict(database_bytes=request.database.stat().st_size, free_bytes=shutil.disk_usage(root).free),
        backup=dict(count=len(backups), latest_at=datetime.fromtimestamp(latest.stat().st_mtime, timezone.utc).isoformat() if latest else None),
    ))


def system_listeners(connection, request):
    return Reply(dict(items=listening_ports()))


def prepare(value, capabilities, database):
    if not isinstance(value, dict):
        raise ValueError('请求必须是 JSON 对象')
    path, method, body = value.get('path'), value.get('method'), value.get('body')
    if not isinstance(path, str) or len(path) > 2048 or not isinstance(method, str):
        raise ValueError('请求格式不正确')
    url = urlsplit(path)
    if url.scheme or url.netloc or url.fragment or '%' in url.path or '\\' in path or any(ord(c) < 32 for c in path):
        raise ValueError('请求路径不被允许')
    if body is not None and not isinstance(body, dict):
        raise ValueError('请求内容必须是 JSON 对象')
    granted = frozenset(capabilities) & modules.capabilities()
    routes = [Route(None, 'GET', r'/session', session), Route('system.read', 'GET', r'/system', system_status),
              Route('system.read', 'GET', r'/system/listeners', system_listeners)] + modules.routes()
    for route in routes:
        match = re.fullmatch(route.pattern, url.path)
        if route.method == method and match:
            if route.capability and route.capability not in granted:
                raise RpcError('这台设备没有此功能的权限', 403)
            query = {name: values[0] for name, values in parse_qs(url.query, keep_blank_values=True, errors='strict', max_num_fields=30).items()}
            return route.handler, Request(url.path, method, body, query, match.groupdict(), granted, Path(database))
    raise RpcError('设备密钥不能执行此操作', 403)


def handle(value, capabilities, database):
    try:
        handler, request = prepare(value, capabilities, database)
        with connect(database) as connection:
            result = handler(connection, request)
        return dict(status=result.status, body=result.body)
    except RpcError as error:
        return dict(status=error.status, body=dict(error=str(error)))
    except (ValueError, UnicodeError):
        return dict(status=400, body=dict(error='请求内容无效，请检查输入'))
