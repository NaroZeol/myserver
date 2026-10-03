#!/usr/bin/env python3
"""Explicit device enrollment. Preserve every unrelated authorized_keys entry."""
import argparse
import base64
import datetime
import fcntl
import hashlib
import json
import os
import re
import struct
import tempfile
from pathlib import Path


def public_key(value):
    parts = value.strip().split()
    if len(parts) < 2 or parts[0] != 'ssh-rsa':
        raise ValueError('请粘贴 App 生成的 ssh-rsa 公钥')
    blob = base64.b64decode(parts[1], validate=True)
    position, fields = 0, []
    while position + 4 <= len(blob):
        length = struct.unpack('>I', blob[position:position+4])[0]
        position += 4
        if position + length > len(blob): raise ValueError('公钥格式无效')
        fields.append(blob[position:position+length]); position += length
    if position != len(blob) or len(fields) != 3 or fields[0] != b'ssh-rsa': raise ValueError('公钥格式无效')
    if int.from_bytes(fields[2], 'big').bit_length() < 2048: raise ValueError('RSA 公钥至少需要 2048 位')
    if int.from_bytes(fields[1], 'big') < 65537: raise ValueError('RSA 公钥参数不正确')
    return 'ssh-rsa ' + base64.b64encode(blob).decode(), hashlib.sha256(blob).hexdigest()


def atomic_write(path, text):
    fd, temporary = tempfile.mkstemp(dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as output: output.write(text)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)


def enroll(root, authorized, key, name, capabilities):
    normalized, ident = public_key(key)
    if not capabilities or not set(capabilities) <= {'thoughts', 'system.read'}:
        raise ValueError('未知设备权限')
    devices = root / 'devices'
    devices.mkdir(parents=True, mode=0o700, exist_ok=True)
    authorized.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
    with (devices / 'register.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        original = authorized.read_text() if authorized.exists() else ''
        command = f'restrict,command="{root}/deploy/ssh-gateway.sh {ident}" '
        line = command + normalized + ' thoughts-device-' + ident[:12]
        matches = [value for value in original.splitlines() if normalized.split()[1] in value.split()]
        if matches and matches != [line]:
            raise ValueError('此公钥已有其他 SSH 权限，请使用专属设备密钥')
        record = dict(id=ident, name=name[:80], public_key=normalized, capabilities=sorted(set(capabilities)),
                      registered_at=datetime.datetime.now(datetime.timezone.utc).isoformat())
        atomic_write(devices / (ident + '.json'), json.dumps(record, ensure_ascii=False, indent=2)+'\n')
        if not matches:
            atomic_write(authorized, original + ('\n' if original and not original.endswith('\n') else '') + line + '\n')
    return ident


def revoke(root, authorized, ident):
    if not re.fullmatch('[0-9a-f]{64}', ident): raise ValueError('设备 ID 无效')
    devices = root / 'devices'
    with (devices / 'register.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        prefix = f'restrict,command="{root}/deploy/ssh-gateway.sh {ident}" '
        original = authorized.read_text()
        atomic_write(authorized, ''.join(line for line in original.splitlines(keepends=True) if not line.startswith(prefix)))
        (devices / (ident + '.json')).unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['add', 'list', 'revoke'], nargs='?', default='add')
    parser.add_argument('--name', default='我的手机')
    parser.add_argument('--key-file', type=Path)
    parser.add_argument('--id')
    parser.add_argument('--capabilities', default='thoughts,system.read')
    args = parser.parse_args()
    root = Path.home() / '.local/share/thoughts'
    authorized = Path.home() / '.ssh/authorized_keys'
    if args.action == 'list':
        for path in sorted((root / 'devices').glob('*.json')):
            record = json.loads(path.read_text())
            print(record['id'], record['name'], ','.join(record['capabilities']))
    elif args.action == 'revoke':
        revoke(root, authorized, args.id or '')
        print('设备已撤销；其他 SSH 公钥保持不变。')
    else:
        key = args.key_file.read_text() if args.key_file else input('粘贴 App 公钥：\n')
        ident = enroll(root, authorized, key, args.name, args.capabilities.split(','))
        print('设备已登记：' + ident)
        print('现在回到 App，点击「验证连接」。不需要重启 SSH。')


if __name__ == '__main__':
    try: main()
    except (ValueError, OSError) as error: raise SystemExit(str(error))
