#!/usr/bin/env bash
set -euo pipefail
if [[ "$EUID" -eq 0 ]]; then echo 'Run as the service user, without sudo.' >&2; exit 1; fi
[[ $# -eq 0 || ( $# -eq 1 && "$1" == --units-only ) ]] || { echo 'Usage: install-user.sh [--units-only]' >&2; exit 1; }
data_dir="$HOME/.local/share/myserver"
unit_dir="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
systemctl --user show-environment >/dev/null
[[ -f "$data_dir/app/cli.py" && -f "$data_dir/app/ssh_gateway.py" ]] || { echo 'Stage the application first.' >&2; exit 1; }
python3 - "$data_dir" "$unit_dir" <<'PY'
import sys
from pathlib import Path
root, unit_dir = Path(sys.argv[1]), Path(sys.argv[2])
if any(ord(c)<32 for c in str(root)):
    raise SystemExit('Runtime path must not contain control characters')
unit_dir.mkdir(parents=True, exist_ok=True)
# systemd performs specifier expansion even inside quotes.
def quote(value): return '"'+value.replace('\\','\\\\').replace('"','\\"').replace('%','%%')+'"'
for name in ['myserver-backup.service','myserver-backup.timer','myserver-publish.service','myserver-publish.timer']:
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
python3 -S "$data_dir/app/cli.py" init
python3 -S "$data_dir/app/cli.py" check
systemctl --user start myserver-backup.service
systemctl --user enable myserver-backup.timer myserver-publish.timer
systemctl --user start myserver-backup.timer myserver-publish.timer
echo 'myserver is ready. Device requests run directly over SSH.'
