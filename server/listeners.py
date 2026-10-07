"""Discover local TCP listeners without reading process arguments or invoking commands."""
import ipaddress
import os
import unicodedata
from pathlib import Path


def _sockets(proc):
    found = {}
    for family, name in ((4, 'tcp'), (6, 'tcp6')):
        try:
            lines = (proc / 'net' / name).read_text().splitlines()[1:]
        except OSError:
            continue
        for line in lines:
            fields = line.split()
            if len(fields) < 10 or fields[3] != '0A':
                continue
            try:
                encoded, port_hex = fields[1].split(':')
                raw = bytes.fromhex(encoded)
                if family == 4:
                    address = ipaddress.IPv4Address(raw[::-1])
                    if str(address) not in ('0.0.0.0', '127.0.0.1'):
                        continue
                    target = '127.0.0.1'
                else:
                    address = ipaddress.IPv6Address(b''.join(raw[i:i + 4][::-1] for i in range(0, 16, 4)))
                    if str(address) not in ('::', '::1'):
                        continue
                    target = '::1'
                port = int(port_hex, 16)
                inode = fields[9]
                if not 1 <= port <= 65535 or not inode.isdecimal():
                    continue
                found[inode] = dict(port=port, bind=str(address), target=target, program=None)
            except (ValueError, IndexError):
                continue
    return found


def snapshot(proc=Path('/proc')):
    sockets = _sockets(proc)
    if not sockets:
        return []
    remaining = set(sockets)
    try:
        processes = list(proc.iterdir())
    except OSError:
        processes = []
    for process in processes:
        if not process.name.isdecimal() or not remaining:
            continue
        try:
            name = ''.join(ch for ch in (process / 'comm').read_text() if
                           unicodedata.category(ch)[0] != 'C')[:64]
            for fd in (process / 'fd').iterdir():
                try:
                    link = os.readlink(fd)
                except OSError:
                    continue
                if link.startswith('socket:[') and link.endswith(']'):
                    inode = link[8:-1]
                    if inode in remaining:
                        sockets[inode]['program'] = name or None
                        remaining.remove(inode)
        except OSError:
            continue
    return sorted(sockets.values(), key=lambda item: (item['port'], item['target']))
