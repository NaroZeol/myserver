#!/usr/bin/env bash
set -euo pipefail
if [[ "$EUID" -eq 0 ]]; then echo 'Run as the service user, without sudo.' >&2; exit 1; fi
[[ $# -eq 0 || ( $# -eq 1 && "$1" == --units-only ) ]] || { echo 'Usage: install-user.sh [--units-only]' >&2; exit 1; }
data_dir="$HOME/.local/share/myserver"
unit_dir="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
systemctl --user show-environment >/dev/null
[[ -f "$data_dir/app/app.py" && -f "$data_dir/app/ssh_gateway.py" ]] || { echo 'Stage the application first.' >&2; exit 1; }
if [[ "${1:-}" != --units-only ]] && ! systemctl --user is-active --quiet myserver.service; then
    python3 - <<'CHECK'
import socket
with socket.socket() as sock:
    try: sock.bind(("127.0.0.1", 8765))
    except OSError: raise SystemExit("Port 8765 is in use by another service; stop it before activation.")
CHECK
fi
python3 - "$data_dir" "$unit_dir" <<'PY'
import sys
from pathlib import Path
root, unit_dir = Path(sys.argv[1]), Path(sys.argv[2])
if any(ord(c)<32 for c in str(root)):
    raise SystemExit('Runtime path must not contain control characters')
unit_dir.mkdir(parents=True, exist_ok=True)
# systemd performs specifier expansion even inside quotes.
def quote(value): return '"'+value.replace('\\','\\\\').replace('"','\\"').replace('%','%%')+'"'
for name in ['myserver.service','myserver-backup.service','myserver-backup.timer','myserver-publish.service','myserver-publish.timer']:
    text=(root/'deploy'/name).read_text()
    lines=[]
    for line in text.splitlines():
        if '@DATA_DIR@' in line:
            key, value=line.split('=',1)
            if key=='WorkingDirectory': value=value.replace('@DATA_DIR@',str(root).replace('%','%%'))
            elif key=='ExecStart': value=value.replace('@DATA_DIR@/deploy/backup.sh',quote(str(root/'deploy/backup.sh')))
            else: value=quote(value.replace('@DATA_DIR@',str(root)))
            line=key+'='+value
        lines.append(line)
    path=unit_dir/name
    path.write_text('\n'.join(lines)+'\n');path.chmod(0o644)
PY
systemctl --user daemon-reload
[[ "${1:-}" == --units-only ]] && exit 0
systemctl --user enable myserver.service myserver-backup.timer myserver-publish.timer
systemctl --user restart myserver.service
curl --fail --silent --retry 5 --retry-connrefused --max-time 10 http://127.0.0.1:8765/api/health
systemctl --user start myserver-backup.service
systemctl --user start myserver-backup.timer myserver-publish.timer
echo
echo 'myserver API is loopback-only. Device access uses the existing SSH service.'
