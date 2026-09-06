"""Interactive deployment configuration. Credentials stay on the server."""
import getpass
import json
import os
import re
import sqlite3
from pathlib import Path
from urllib.request import Request, urlopen

root = Path.home() / '.local/share/thoughts'
root.mkdir(parents=True, mode=0o700, exist_ok=True)
previous = json.loads((root/'gist.json').read_text()) if (root/'gist.json').exists() else {}
ident = input('Gist ID: ').strip() or previous.get('id', '')
filename = input('Gist filename [thoughts.json]: ').strip() or 'thoughts.json'
if not re.fullmatch(r'[0-9a-fA-F]{5,64}', ident) or not re.fullmatch(r'[A-Za-z0-9_.-]{1,100}', filename):
    raise SystemExit('Invalid Gist ID or filename; nothing changed.')
token = getpass.getpass('GitHub token (only gist permission): ').strip()
if not token: raise SystemExit('No token supplied; nothing changed.')
headers = {'Authorization': 'Bearer '+token, 'User-Agent': 'Thoughts'}
try:
    with urlopen(Request('https://api.github.com/user', headers=headers), timeout=20) as response:
        owner = json.load(response)['id']
    with urlopen(Request('https://api.github.com/gists/'+ident, headers=headers), timeout=20) as response:
        metadata = json.load(response)
        scopes = response.headers.get('X-OAuth-Scopes', '')
except Exception:
    raise SystemExit('Could not verify Gist access; token was not saved.')
if metadata.get('owner', {}).get('id') != owner:
    raise SystemExit('The Gist must belong to the token owner; nothing changed.')
if scopes and set(part.strip() for part in scopes.split(',')) != {'gist'}:
    raise SystemExit('Use a dedicated token with only gist scope; nothing changed.')
for name, value in [('gist-token',token),('gist.json',json.dumps({'id':ident,'file':filename})+'\n')]:
    path = root/name
    fd = os.open(path, os.O_WRONLY|os.O_CREAT|os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as output: output.write(value)
    os.chmod(path, 0o600)
database=root/'thoughts.sqlite'
if database.exists() and previous != {'id':ident,'file':filename}:
    with sqlite3.connect(database, timeout=15) as connection:
        connection.execute('UPDATE publication SET generation=generation+1 WHERE id=1')
print('Gist target and credentials saved on this server.')
