"""One bounded JSON request per restricted SSH exec channel; no HTTP bridge."""
import fcntl
import json
import os
import re
import signal
import sys
from contextlib import contextmanager
from pathlib import Path
from paths import data_root, database_path

MAX_REQUEST = 140 * 1024
MAX_RESPONSE = 16 * 1024 * 1024


def registered_device(root, device_id):
    if not re.fullmatch('[0-9a-f]{64}', device_id):
        raise PermissionError('设备标识无效')
    try:
        record = json.loads((root / 'devices' / (device_id + '.json')).read_text())
        command = f'restrict,command="{root}/deploy/ssh-gateway.sh {device_id}" '
        authorized = Path.home() / '.ssh/authorized_keys'
        if not any(line.startswith(command + record['public_key'] + ' ') for line in authorized.read_text().splitlines()):
            raise PermissionError('设备已被撤销')
        return record
    except FileNotFoundError:
        raise PermissionError('设备未登记或已被撤销') from None


def reject_constant(_value):
    raise ValueError('JSON 常量无效')


@contextmanager
def authorized_operation(device_id):
    root = data_root()
    if (root / 'maintenance').exists():
        raise RuntimeError('Service maintenance')
    with (root / 'operations.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_SH | fcntl.LOCK_NB)
        if (root / 'maintenance').exists():
            raise RuntimeError('Service maintenance')
        yield registered_device(root, device_id)


def process(device_id, stream):
    with authorized_operation(device_id) as record:
        raw = stream.readline(MAX_REQUEST + 1)
        if len(raw) > MAX_REQUEST or not raw.endswith(b'\n'):
            raise ValueError('请求过大或不完整')
        value = json.loads(raw, parse_constant=reject_constant)
        from service import handle
        return handle(value, record['capabilities'], database_path())


def process_transfer(device_id, source, output):
    from rpc import RpcError
    download_started = False
    try:
        with authorized_operation(device_id) as record:
            from database import connect
            from modules.inbox.service import transfer, transfer_header
            raw = source.readline(8193)
            if len(raw) > 8192 or not raw.endswith(b'\n'):
                raise RpcError('传输头过大或不完整')
            data = transfer_header(json.loads(raw, parse_constant=reject_constant))
            required = 'inbox.write' if data['op'] == 'upload' else 'inbox.read'
            if required not in record['capabilities']:
                raise RpcError('这台设备没有此功能的权限', 403)
            def reply(body):
                nonlocal download_started
                output.write(encode(dict(status=200, body=body)))
                output.flush()
                if data['op'] == 'download':
                    download_started = True
            with connect(database_path()) as connection:
                transfer(connection, database_path(), data, source, output, reply)
        return
    except PermissionError as error:
        result = dict(status=403, body=dict(error=str(error)))
    except RpcError as error:
        result = dict(status=error.status, body=dict(error=str(error)))
    except (ValueError, UnicodeError, RecursionError):
        result = dict(status=400, body=dict(error='传输请求无效'))
    except Exception:
        result = dict(status=503, body=dict(error='文件传输暂时不可用，请重试'))
    # A framed download already promises raw bytes. Closing a truncated stream
    # lets the client detect its length; appending JSON would corrupt the file.
    if not download_started:
        output.write(encode(result))
        output.flush()


def encode(result):
    raw = (json.dumps(result, ensure_ascii=False, separators=(',', ':'), allow_nan=False) + '\n').encode('utf-8')
    if len(raw) > MAX_RESPONSE:
        return encode(dict(status=413, body=dict(error='响应过大，请缩小查询范围或在服务器导出备份')))
    return raw


def main():
    os.umask(0o077)
    command = os.environ.get('SSH_ORIGINAL_COMMAND')
    signal.alarm(90 if command == 'myserver-transfer-v1' else 45)
    if command == 'myserver-transfer-v1' and len(sys.argv) == 2:
        process_transfer(sys.argv[1], sys.stdin.buffer, sys.stdout.buffer)
        return
    try:
        if command != 'myserver-rpc-v1' or len(sys.argv) != 2:
            raise PermissionError('此密钥仅能用于 myserver App')
        result = process(sys.argv[1], sys.stdin.buffer)
        output = encode(result)
    except PermissionError as error:
        output = encode(dict(status=403, body=dict(error=str(error))))
    except (ValueError, UnicodeError, RecursionError):
        output = encode(dict(status=400, body=dict(error='请求无效')))
    except Exception:
        output = encode(dict(status=503, body=dict(error='服务暂时不可用，本地记录已保留')))
    sys.stdout.buffer.write(output)
    sys.stdout.buffer.flush()


if __name__ == '__main__':
    main()
