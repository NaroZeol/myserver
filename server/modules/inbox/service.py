"""Inbox metadata and bounded file storage; filenames never become disk paths."""
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import time
import uuid

from database import now
from rpc import Reply, Route, RpcError

CAPABILITIES = {'inbox.read', 'inbox.write'}
MAX_CHUNK = 4 * 1024 * 1024


def initialize(connection):
    connection.executescript('''
        CREATE TABLE IF NOT EXISTS inbox_items(
            id TEXT PRIMARY KEY, title TEXT NOT NULL, text TEXT NOT NULL, note TEXT NOT NULL,
            source TEXT NOT NULL, created_at TEXT NOT NULL, touched REAL NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('uploading','ready','deleted')), manifest TEXT NOT NULL);
        CREATE INDEX IF NOT EXISTS inbox_date ON inbox_items(state,created_at DESC);
        CREATE TABLE IF NOT EXISTS inbox_files(
            item_id TEXT NOT NULL REFERENCES inbox_items(id), id TEXT NOT NULL,
            name TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL,
            position INTEGER NOT NULL, PRIMARY KEY(item_id,id));
    ''')


def status(connection):
    row = connection.execute("SELECT count(*) AS items FROM inbox_items WHERE state='ready'").fetchone()
    return dict(items=row['items'])


def routes():
    ident = r'(?P<ident>[0-9a-f-]{36})'
    return [
        Route('inbox.read', 'GET', '/inbox', listing),
        Route('inbox.read', 'GET', '/inbox/items/' + ident, detail),
        Route('inbox.write', 'PATCH', '/inbox/items/' + ident, update),
        Route('inbox.write', 'DELETE', '/inbox/items/' + ident, delete),
        Route('inbox.write', 'POST', '/inbox/uploads', create),
        Route('inbox.write', 'GET', '/inbox/uploads/' + ident, progress),
        Route('inbox.write', 'POST', '/inbox/uploads/' + ident + '/commit', commit),
        Route('inbox.write', 'DELETE', '/inbox/uploads/' + ident, cancel),
    ]


def ident(value):
    if not isinstance(value, str):
        raise RpcError('项目标识无效')
    try:
        parsed = str(uuid.UUID(value))
    except ValueError:
        raise RpcError('项目标识无效') from None
    if value != parsed:
        raise RpcError('项目标识必须为标准小写 UUID')
    return value


CONFIG_DEFAULTS = dict(quota_bytes=5 * 1024**3, min_free_bytes=64 * 1024**2, upload_ttl_seconds=7 * 86400)
CONFIG_MINIMUM = dict(quota_bytes=1, min_free_bytes=0, upload_ttl_seconds=60)


def settings(database, environment=True):
    path = Path(database).resolve().parent / 'config/inbox.json'
    values = dict(CONFIG_DEFAULTS)
    if path.exists():
        if path.stat().st_size > 8192:
            raise RuntimeError('Inbox configuration too large')
        configured = json.loads(path.read_text(encoding='utf-8'))
        if not isinstance(configured, dict) or set(configured) - set(values):
            raise RuntimeError('Invalid inbox configuration')
        values.update(configured)
    if environment:
        for key in values:
            name = 'MYSERVER_INBOX_' + key.upper()
            if name in os.environ:
                try:
                    values[key] = int(os.environ[name])
                except ValueError:
                    raise RuntimeError('Invalid inbox storage configuration') from None
    if any(type(value) is not int or not CONFIG_MINIMUM[key] <= value <= 2**63 - 1 for key, value in values.items()):
        raise RuntimeError('Invalid inbox storage configuration')
    return values


def quota(database):
    return settings(database)['quota_bytes']


def root(database):
    directory = Path(database).resolve().parent / 'inbox'
    directory.mkdir(mode=0o700, exist_ok=True)
    for name in ('objects', 'uploads'):
        (directory / name).mkdir(mode=0o700, exist_ok=True)
    return directory


@contextmanager
def locked(database, exclusive=True):
    directory = root(database)
    descriptor = os.open(directory / 'storage.lock', os.O_CREAT | os.O_RDWR, 0o600)
    with os.fdopen(descriptor, 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH)
        yield directory


def metadata_cost(title, text, note, source, created_at, manifest, entries):
    # Reserve SQLite/page overhead and a final digest even when a browser asks
    # the server to calculate SHA-256. Empty files still consume real storage.
    return 1024 + sum(len(value.encode('utf-8')) for value in (title, text, note, source, created_at, manifest)) + sum(256 + 64 + len(entry['name'].encode('utf-8')) + len(entry['mime'].encode('utf-8')) for entry in entries)


def usage(connection):
    metadata = connection.execute("""SELECT coalesce(sum(CASE WHEN state='deleted' THEN 128 ELSE
        1024+length(CAST(title AS BLOB))+length(CAST(text AS BLOB))+length(CAST(note AS BLOB))+
        length(CAST(source AS BLOB))+length(CAST(created_at AS BLOB))+length(CAST(manifest AS BLOB)) END),0) FROM inbox_items""").fetchone()[0]
    blobs = connection.execute("""SELECT coalesce(sum(f.size+256+64+length(CAST(f.name AS BLOB))+length(CAST(f.mime AS BLOB))),0)
        FROM inbox_files f JOIN inbox_items i ON i.id=f.item_id WHERE i.state!='deleted'""").fetchone()[0]
    return metadata + blobs


def tombstone(connection, item_id):
    connection.execute('PRAGMA secure_delete=ON')
    connection.execute('DELETE FROM inbox_files WHERE item_id=?', (item_id,))
    connection.execute("UPDATE inbox_items SET title='',text='',note='',source='',created_at='',touched=0,manifest='',state='deleted' WHERE id=?", (item_id,))


def check_space(database, amount):
    reserve = settings(database)['min_free_bytes']
    if shutil.disk_usage(Path(database).parent).free < amount + reserve:
        raise RpcError('服务器空间不足，请释放空间后重试', 507)


def item_row(connection, item_id, ready=False):
    ident(item_id)
    row = connection.execute('SELECT * FROM inbox_items WHERE id=?', (item_id,)).fetchone()
    if not row or row['state'] == 'deleted' or (ready and row['state'] != 'ready'):
        raise RpcError('收件不存在或尚未上传完成', 404)
    return row


def files(connection, item_id):
    return [dict(row) for row in connection.execute('SELECT id,name,mime,size,sha256 FROM inbox_files WHERE item_id=? ORDER BY position', (item_id,))]


def serialize(connection, row):
    return {**{key: row[key] for key in ('id', 'title', 'text', 'note', 'source', 'created_at')}, 'files': files(connection, row['id'])}


def listing(connection, request):
    limit = max(1, min(200, int(request.query.get('limit', 100))))
    offset = max(0, min(2**63 - 1, int(request.query.get('offset', 0))))
    query = request.query.get('q', '')[:200].strip()
    kind = request.query.get('type', 'all')
    if kind not in ('all', 'files', 'text'):
        raise RpcError('收件类型无效')
    condition = {'all': '1', 'files': 'EXISTS(SELECT 1 FROM inbox_files f WHERE f.item_id=inbox_items.id)', 'text': "length(trim(text,' '||char(9)||char(10)||char(13)))>0"}[kind]
    rows = connection.execute("SELECT * FROM inbox_items WHERE state='ready' AND " + condition + " AND (?='' OR instr(lower(title||' '||text||' '||note),lower(?))>0 OR EXISTS(SELECT 1 FROM inbox_files f WHERE f.item_id=inbox_items.id AND instr(lower(f.name),lower(?))>0)) ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", (query, query, query, limit + 1, offset)).fetchall()
    return Reply(dict(items=[serialize(connection, row) for row in rows[:limit]], has_more=len(rows) > limit, next_offset=offset + limit, storage=dict(used_bytes=usage(connection), quota_bytes=quota(request.database))))


def detail(connection, request):
    return Reply(dict(item=serialize(connection, item_row(connection, request.params['ident'], True))))


def string(data, name, maximum, default=''):
    value = data.get(name, default)
    if not isinstance(value, str) or len(value) > maximum or '\x00' in value:
        raise RpcError(name + ' 内容无效或过长')
    return value


def validate(data):
    item_id = ident(data.get('id'))
    text, note = string(data, 'text', 20000), string(data, 'note', 2000)
    source = string(data, 'source', 80, 'app').strip() or 'app'
    entries = data.get('files', [])
    if not isinstance(entries, list) or len(entries) > 32 or (not entries and not text.strip()):
        raise RpcError('请选择文件或输入文字；每次最多 32 个文件')
    normalized, seen = [], set()
    for entry in entries:
        if not isinstance(entry, dict):
            raise RpcError('文件信息无效')
        file_id = ident(entry.get('id'))
        name = string(entry, 'name', 255).strip()
        mime = string(entry, 'mime', 150, 'application/octet-stream')
        size, digest = entry.get('size'), entry.get('sha256', '')
        if file_id in seen or not name or name in ('.', '..') or any(c in name for c in '/\\') or any(ord(c) < 32 or ord(c) == 127 for c in name):
            raise RpcError('文件名或标识无效')
        if type(size) is not int or size < 0 or size > 2**63 - 1 or not isinstance(digest, str) or (digest != '' and not re.fullmatch('[0-9a-f]{64}', digest)):
            raise RpcError('文件大小或校验值无效')
        if not re.fullmatch(r'[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+', mime):
            mime = 'application/octet-stream'
        seen.add(file_id)
        normalized.append(dict(id=file_id, name=name, mime=mime, size=size, sha256=digest))
    default_title = (normalized[0]['name'] if normalized else text.strip().splitlines()[0])[:240]
    title = string(data, 'title', 240, default_title).strip()
    if not title:
        raise RpcError('标题不能为空')
    return dict(id=item_id, title=title, text=text, note=note, source=source, files=normalized)


def cleanup(connection, directory, database):
    deadline = time.time() - settings(database)['upload_ttl_seconds']
    expired = [row[0] for row in connection.execute("SELECT id FROM inbox_items WHERE state='uploading' AND touched<?", (deadline,))]
    with connection:
        for item_id in expired:
            tombstone(connection, item_id)
    active = {row[0] for row in connection.execute("SELECT id FROM inbox_items WHERE state!='deleted'")}
    for part in ('uploads', 'objects'):
        for path in (directory / part).iterdir():
            if path.name not in active:
                shutil.rmtree(path, ignore_errors=True)


def upload_status(connection, directory, item_id):
    row = item_row(connection, item_id)
    area = directory / ('objects' if row['state'] == 'ready' else 'uploads') / item_id
    if not area.exists() and (directory / 'objects' / item_id).exists():
        area = directory / 'objects' / item_id
    return dict(id=item_id, state=row['state'], files=[dict(id=f['id'], offset=(area / f['id']).stat().st_size if (area / f['id']).is_file() else 0) for f in files(connection, item_id)])


def create(connection, request):
    data = validate(request.payload())
    manifest = json.dumps(data, sort_keys=True, ensure_ascii=False, separators=(',', ':'))
    with locked(request.database) as directory:
        cleanup(connection, directory, request.database)
        old = connection.execute('SELECT * FROM inbox_items WHERE id=?', (data['id'],)).fetchone()
        if old:
            if old['state'] == 'deleted' or old['manifest'] != manifest:
                raise RpcError('此上传标识已有其他内容，请重新创建', 409)
            return Reply(upload_status(connection, directory, data['id']))
        title = data['title']
        timestamp = now()
        size = sum(entry['size'] for entry in data['files']) + metadata_cost(title, data['text'], data['note'], data['source'], timestamp, manifest, data['files'])
        if connection.execute('SELECT count(*) FROM inbox_items').fetchone()[0] >= 100000:
            raise RpcError('收件箱条目数量已达上限，请联系服务器管理员', 507)
        if size + usage(connection) > quota(request.database):
            raise RpcError('收件箱配额不足，请清理文件后重试', 507)
        check_space(request.database, size)
        folder = directory / 'uploads' / data['id']
        if folder.exists():
            shutil.rmtree(folder)  # A crash before metadata insertion left no committed item.
        folder.mkdir(mode=0o700, exist_ok=False)
        try:
            for entry in data['files']:
                fd = os.open(folder / entry['id'], os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                os.close(fd)
            with connection:
                connection.execute('INSERT INTO inbox_items VALUES(?,?,?,?,?,?,?,?,?)', (data['id'], title, data['text'], data['note'], data['source'], timestamp, time.time(), 'uploading', manifest))
                for pos, entry in enumerate(data['files']):
                    connection.execute('INSERT INTO inbox_files VALUES(?,?,?,?,?,?,?)', (data['id'], entry['id'], entry['name'], entry['mime'], entry['size'], entry['sha256'], pos))
        except BaseException:
            shutil.rmtree(folder, ignore_errors=True)
            raise
        return Reply(upload_status(connection, directory, data['id']), 201)


def progress(connection, request):
    with locked(request.database, False) as directory:
        return Reply(upload_status(connection, directory, request.params['ident']))


def file_hash(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def commit(connection, request):
    item_id = request.params['ident']
    with locked(request.database) as directory:
        row = item_row(connection, item_id)
        if row['state'] == 'ready':
            return Reply(dict(item=serialize(connection, row)))
        pending, final = directory / 'uploads' / item_id, directory / 'objects' / item_id
        folder = pending if pending.exists() else final
        digests = []
        for entry in files(connection, item_id):
            path = folder / entry['id']
            if not path.is_file() or path.stat().st_size != entry['size']:
                raise RpcError('文件尚未传输完成', 409)
            digest = file_hash(path)
            if entry['sha256'] and digest != entry['sha256']:
                raise RpcError('文件校验失败，请取消后重新上传', 422)
            digests.append((digest, item_id, entry['id']))
        if folder == pending:
            pending.rename(final)
        with connection:
            connection.executemany('UPDATE inbox_files SET sha256=? WHERE item_id=? AND id=?', digests)
            connection.execute("UPDATE inbox_items SET state='ready',touched=? WHERE id=?", (time.time(), item_id))
        return Reply(dict(item=serialize(connection, item_row(connection, item_id, True))))


def cancel(connection, request):
    return remove(connection, request, False)


def delete(connection, request):
    return remove(connection, request, True)


def remove(connection, request, ready):
    item_id = ident(request.params['ident'])
    with locked(request.database) as directory:
        row = connection.execute('SELECT state FROM inbox_items WHERE id=?', (item_id,)).fetchone()
        if not row:
            return Reply(dict(ok=True))
        if row['state'] != 'deleted':
            if (row['state'] == 'ready') != ready:
                raise RpcError('项目状态已变化，请刷新后重试', 409)
            with connection:
                tombstone(connection, item_id)
        for part in ('uploads', 'objects'):
            path = directory / part / item_id
            if path.exists():
                shutil.rmtree(path)
    return Reply(dict(ok=True))


def update(connection, request):
    data = request.payload()
    if not data or set(data) - {'title', 'note'}:
        raise RpcError('只支持修改标题与备注')
    with locked(request.database):
        row = item_row(connection, request.params['ident'], True)
        title = string(data, 'title', 240, row['title']).strip()
        note = string(data, 'note', 2000, row['note'])
        if not title:
            raise RpcError('标题不能为空')
        if usage(connection) - len((row['note'] + row['title']).encode()) + len((note + title).encode()) > quota(request.database):
            raise RpcError('收件箱配额不足', 507)
        with connection:
            connection.execute('UPDATE inbox_items SET title=?,note=? WHERE id=?', (title, note, row['id']))
        return Reply(dict(item=serialize(connection, item_row(connection, row['id'], True))))


def transfer_header(data):
    if not isinstance(data, dict) or set(data) != {'op', 'item', 'file', 'offset', 'length'}:
        raise RpcError('传输头无效')
    if data['op'] not in ('upload', 'download'):
        raise RpcError('传输操作无效')
    ident(data['item']); ident(data['file'])
    if type(data['offset']) is not int or type(data['length']) is not int or data['offset'] < 0 or not 0 <= data['length'] <= MAX_CHUNK:
        raise RpcError('文件传输范围无效')
    return data


def transfer(connection, database, header, source, output, reply):
    data = transfer_header(header)
    with locked(database, data['op'] == 'upload') as directory:
        row = item_row(connection, data['item'], data['op'] == 'download')
        entry = next((f for f in files(connection, row['id']) if f['id'] == data['file']), None)
        if not entry:
            raise RpcError('文件不存在', 404)
        if data['offset'] + data['length'] > entry['size']:
            raise RpcError('文件传输范围超出大小', 416)
        if data['op'] == 'upload':
            if row['state'] != 'uploading':
                raise RpcError('项目已提交', 409)
            path = directory / 'uploads' / row['id'] / entry['id']
            if not path.exists() or path.stat().st_size != data['offset']:
                raise RpcError('上传位置已变化，请读取进度后重试', 409)
            check_space(database, data['length'])
            reply(dict(offset=data['offset']))
            with path.open('r+b') as target:
                target.seek(data['offset'])
                try:
                    remaining = data['length']
                    while remaining:
                        block = source.read(min(65536, remaining))
                        if not block:
                            raise RpcError('文件块不完整，请重试', 400)
                        target.write(block)
                        remaining -= len(block)
                    target.flush(); os.fsync(target.fileno())
                except BaseException:
                    target.truncate(data['offset'])
                    raise
            with connection:
                connection.execute('UPDATE inbox_items SET touched=? WHERE id=?', (time.time(), row['id']))
            reply(dict(offset=data['offset'] + data['length']))
        else:
            path = directory / 'objects' / row['id'] / entry['id']
            with path.open('rb') as handle:
                if os.fstat(handle.fileno()).st_size != entry['size']:
                    raise RpcError('服务器文件不完整，请从备份恢复', 409)
                reply(dict(length=data['length'], size=entry['size'], offset=data['offset'], sha256=entry['sha256']))
                handle.seek(data['offset'])
                remaining = data['length']
                while remaining:
                    block = handle.read(min(65536, remaining))
                    if not block:
                        raise OSError('Unexpected EOF')
                    output.write(block); remaining -= len(block)
                output.flush()
