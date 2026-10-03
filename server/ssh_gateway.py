"""One bounded JSON request per restricted SSH exec channel; no HTTP bridge."""
import fcntl
import json
import os
import re
import signal
import sys
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


def process(device_id, stream):
    root = data_root()
    if (root / 'maintenance').exists():
        raise RuntimeError('Service maintenance')
    with (root / 'operations.lock').open('a') as lock:
        # Updates take the exclusive lock; do not keep a phone waiting during deployment.
        fcntl.flock(lock, fcntl.LOCK_SH | fcntl.LOCK_NB)
        if (root / 'maintenance').exists():
            raise RuntimeError('Service maintenance')
        record = registered_device(root, device_id)
        raw = stream.readline(MAX_REQUEST + 1)
        if len(raw) > MAX_REQUEST or not raw.endswith(b'\n'):
            raise ValueError('请求过大或不完整')
        value = json.loads(raw, parse_constant=reject_constant)
        # Import the business code only while the running bundle is protected.
        from service import handle
        return handle(value, record['capabilities'], database_path())


def encode(result):
    raw = (json.dumps(result, ensure_ascii=False, separators=(',', ':'), allow_nan=False) + '\n').encode('utf-8')
    if len(raw) > MAX_RESPONSE:
        return encode(dict(status=413, body=dict(error='响应过大，请缩小查询范围或在服务器导出备份')))
    return raw


def main():
    os.umask(0o077)
    signal.alarm(45)
    try:
        if os.environ.get('SSH_ORIGINAL_COMMAND') != 'myserver-rpc-v1' or len(sys.argv) != 2:
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
