"""Owner-operated inbox commands. The command never executes received content."""
from contextlib import contextmanager, suppress
import fcntl
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import uuid

from database import connect
from rpc import RpcError
from service import handle
from .service import CAPABILITIES, MAX_CHUNK, file_hash, transfer, settings, CONFIG_MINIMUM, locked


@contextmanager
def operation(database):
    directory = Path(database).resolve().parent
    if (directory / 'maintenance').exists():
        raise RpcError('服务正在维护，请稍后重试', 503)
    fd = os.open(directory / 'operations.lock', os.O_CREAT | os.O_RDWR, 0o600)
    with os.fdopen(fd, 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_SH | fcntl.LOCK_NB)
        if (directory / 'maintenance').exists():
            raise RpcError('服务正在维护，请稍后重试', 503)
        yield


def call(database, path, method='GET', body=None):
    result = handle(dict(path=path, method=method, body=body), CAPABILITIES, database)
    if not 200 <= result['status'] < 300:
        raise RpcError(result['body'].get('error', '操作失败'), result['status'])
    return result['body']


def configure(parser):
    commands = parser.add_subparsers(dest='inbox_command', required=True)
    config = commands.add_parser('configure', help='Persist inbox limits for CLI, browser and SSH devices')
    config.add_argument('--quota-bytes', type=int)
    config.add_argument('--min-free-bytes', type=int)
    config.add_argument('--upload-ttl-seconds', type=int)
    put = commands.add_parser('put', help='Copy files or text into the private inbox')
    put.add_argument('files', nargs='*', type=Path)
    put.add_argument('--text', default='', help='Text, or - to read UTF-8 from stdin')
    put.add_argument('--note', default='')
    put.add_argument('--title')
    put.add_argument('--source', default='server')
    listing = commands.add_parser('list', help='List ready items')
    listing.add_argument('--json', action='store_true')
    listing.add_argument('--search', default='')
    get = commands.add_parser('get', help='Download one item file without overwriting')
    get.add_argument('id')
    get.add_argument('--file', help='File ID (required for a multi-file item)')
    get.add_argument('--output', required=True, type=Path)
    web = commands.add_parser('web', help='Open a temporary loopback HTTP management interface')
    web.add_argument('--port', type=int, default=8787)
    web.add_argument('--timeout', type=int, default=7200, help='Maximum lifetime, seconds')
    web.add_argument('--idle-timeout', type=int, default=1800, help='Idle timeout, seconds')


def run(args, database):
    if args.inbox_command == 'web':
        from .web import serve
        serve(database, args.port, args.timeout, args.idle_timeout)
        return
    with operation(database):
        if args.inbox_command == 'configure':
            configure_limits(args, database)
        elif args.inbox_command == 'put':
            put(args, database)
        elif args.inbox_command == 'get':
            get(args, database)
        else:
            from urllib.parse import urlencode
            result = call(database, '/inbox?' + urlencode(dict(q=args.search)))
            if args.json:
                print(json.dumps(result, ensure_ascii=False, indent=2))
            else:
                for item in result['items']:
                    print(item['id'], json.dumps(item['title'], ensure_ascii=False), item['created_at'])
                if result['has_more']:
                    print('仅显示最近 100 条；使用 --search 缩小范围。', file=sys.stderr)


def put(args, database):
    import mimetypes
    text = sys.stdin.read(20001) if args.text == '-' else args.text
    data = dict(id=str(uuid.uuid4()), text=text, note=args.note, source=args.source, files=[])
    if args.title is not None:
        if not args.title.strip() or len(args.title) > 240 or '\x00' in args.title:
            raise RpcError('标题须为 1–240 字')
        data['title'] = args.title
    for path in args.files:
        if not path.is_file():
            raise RpcError('输入不是普通文件：' + str(path))
        data['files'].append(dict(id=str(uuid.uuid4()), name=path.name, mime=mimetypes.guess_type(path.name)[0] or 'application/octet-stream', size=path.stat().st_size, sha256=file_hash(path)))
    call(database, '/inbox/uploads', 'POST', data)
    try:
        for path, entry in zip(args.files, data['files']):
            with path.open('rb') as source, connect(database) as connection:
                offset = 0
                while offset < entry['size']:
                    length = min(MAX_CHUNK, entry['size'] - offset)
                    transfer(connection, database, dict(op='upload', item=data['id'], file=entry['id'], offset=offset, length=length), source, io.BytesIO(), lambda body: None)
                    offset += length
        result = call(database, '/inbox/uploads/' + data['id'] + '/commit', 'POST')
        print(json.dumps(result['item'], ensure_ascii=False, indent=2))
    except BaseException:
        with suppress(RpcError):
            call(database, '/inbox/uploads/' + data['id'], 'DELETE')
        raise


def get(args, database):
    item = call(database, '/inbox/items/' + args.id)['item']
    if not item['files']:
        with args.output.open('x', encoding='utf-8') as target:
            os.chmod(args.output, 0o600)
            target.write(item['text'])
        return
    entry = next((entry for entry in item['files'] if entry['id'] == args.file), None) if args.file else (item['files'][0] if len(item['files']) == 1 else None)
    if not entry:
        raise RpcError('多文件项目请使用 --file 指定文件 ID')
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, 'wb') as output, connect(database) as connection:
            offset = 0
            while offset < entry['size']:
                length = min(MAX_CHUNK, entry['size'] - offset)
                transfer(connection, database, dict(op='download', item=item['id'], file=entry['id'], offset=offset, length=length), io.BytesIO(), output, lambda body: None)
                offset += length
            output.flush(); os.fsync(output.fileno())
        if file_hash(args.output) != entry['sha256']:
            raise RpcError('下载校验失败')
    except BaseException:
        args.output.unlink(missing_ok=True)
        raise
    print(str(args.output))


def configure_limits(args, database):
    import tempfile
    with locked(database):
        values = settings(database, environment=False)
        for key in values:
            value = getattr(args, key)
            if value is not None:
                if not CONFIG_MINIMUM[key] <= value <= 2**63 - 1:
                    raise RpcError('配置数值超出允许范围：' + key)
                values[key] = value
        directory = Path(database).resolve().parent / 'config'
        directory.mkdir(mode=0o700, exist_ok=True)
        descriptor, temporary = tempfile.mkstemp(dir=directory, prefix='.inbox-')
        try:
            with os.fdopen(descriptor, 'w', encoding='utf-8') as output:
                json.dump(values, output, indent=2)
                output.write('\n'); output.flush(); os.fsync(output.fileno())
            os.replace(temporary, directory / 'inbox.json')
        finally:
            Path(temporary).unlink(missing_ok=True)
    print(json.dumps(values, indent=2))
