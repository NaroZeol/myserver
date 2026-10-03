"""Configure Gist interactively or from injected settings. Never prints credentials."""
import argparse
import getpass
import json
import os
import re
import sqlite3
import tempfile
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app"))
from paths import data_root
from urllib.request import Request, urlopen


def setting(name, prompt, fallback, non_interactive):
    value = os.environ.get(name, '').strip()
    if value:
        return value
    return fallback if non_interactive else input(prompt).strip() or fallback


def write_private(path, value):
    fd, temporary = tempfile.mkstemp(dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as output:
            output.write(value)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def configure(root, non_interactive=False):
    previous = json.loads((root / 'config/thoughts-gist.json').read_text()) if (root / 'config/thoughts-gist.json').exists() else {}
    ident = setting('THOUGHTS_GIST_ID', 'Gist ID: ', previous.get('id', ''), non_interactive)
    filename = setting('THOUGHTS_GIST_FILE', 'Gist filename [thoughts.json]: ', previous.get('file', 'thoughts.json'), non_interactive)
    if not re.fullmatch(r'[0-9a-fA-F]{5,64}', ident) or not re.fullmatch(r'[A-Za-z0-9_.-]{1,100}', filename):
        raise SystemExit('Invalid Gist ID or filename; nothing changed.')
    token_file = os.environ.get('THOUGHTS_GIST_TOKEN_FILE', '').strip()
    if token_file:
        try:
            token = Path(token_file).expanduser().read_text().strip()
        except (OSError, UnicodeError):
            raise SystemExit('Could not read THOUGHTS_GIST_TOKEN_FILE; nothing changed.') from None
    elif non_interactive:
        raise SystemExit('Set THOUGHTS_GIST_TOKEN_FILE for non-interactive configuration; nothing changed.')
    else:
        token = getpass.getpass('GitHub token (only gist permission): ').strip()
    if not token:
        raise SystemExit('No token supplied; nothing changed.')
    headers = {'Authorization': 'Bearer ' + token, 'User-Agent': 'myserver'}
    try:
        with urlopen(Request('https://api.github.com/user', headers=headers), timeout=20) as response:
            owner = json.load(response)['id']
        with urlopen(Request('https://api.github.com/gists/' + ident, headers=headers), timeout=20) as response:
            metadata = json.load(response)
            scopes = response.headers.get('X-OAuth-Scopes', '')
    except Exception:
        raise SystemExit('Could not verify Gist access; token was not saved.') from None
    if metadata.get('owner', {}).get('id') != owner:
        raise SystemExit('The Gist must belong to the token owner; nothing changed.')
    if scopes and set(part.strip() for part in scopes.split(',')) != {'gist'}:
        raise SystemExit('Use a dedicated token with only gist scope; nothing changed.')
    root.mkdir(parents=True, mode=0o700, exist_ok=True)
    target = {'id': ident, 'file': filename}
    for name, value in [('credentials/thoughts-gist-token', token), ('config/thoughts-gist.json', json.dumps(target) + '\n')]:
        (root / name).parent.mkdir(mode=0o700, exist_ok=True)
        write_private(root / name, value)
    database = root / 'myserver.sqlite'
    if database.exists() and previous != target:
        with sqlite3.connect(database, timeout=15) as connection:
            connection.execute('UPDATE publication SET generation=generation+1 WHERE id=1')
    print('Gist target and credentials saved on this server.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--non-interactive', action='store_true', help='Read configuration from environment variables without prompting')
    args = parser.parse_args()
    configure(data_root(), args.non_interactive)
