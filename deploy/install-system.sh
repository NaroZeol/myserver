#!/usr/bin/env bash
set -euo pipefail
if [[ "$EUID" -ne 0 ]]; then echo 'Run with sudo and a service username.' >&2; exit 1; fi
service_user="${1:-${SUDO_USER:-}}"
[[ -n "$service_user" && "$service_user" != root ]] || { echo 'Specify a non-root service account.' >&2; exit 1; }
service_home="$(getent passwd "$service_user" | cut -d: -f6)"
[[ -n "$service_home" ]] || { echo 'Unknown service account.' >&2; exit 1; }
data_dir="$service_home/.local/share/myserver"
[[ -f "$data_dir/app/app.py" && -f "$data_dir/app/ssh_gateway.py" ]] || { echo 'Stage the application first.' >&2; exit 1; }
if [[ "${2:-}" != --units-only ]] && ! systemctl is-active --quiet myserver.service; then
    python3 - <<'CHECK'
import socket
with socket.socket() as sock:
    try: sock.bind(("127.0.0.1", 8765))
    except OSError: raise SystemExit("Port 8765 is in use by another service; stop it before activation.")
CHECK
fi
python3 - "$service_user" "$data_dir" <<'PY'
import grp, pwd, sys
from pathlib import Path
user, root = sys.argv[1], Path(sys.argv[2])
group = grp.getgrgid(pwd.getpwnam(user).pw_gid).gr_name
# systemd performs specifier expansion even inside quotes.
def quote(value): return '"'+value.replace('\\','\\\\').replace('"','\\"').replace('%','%%')+'"'
for name in ['myserver.service','myserver-backup.service','myserver-backup.timer','myserver-publish.service','myserver-publish.timer']:
    text=(root/'deploy'/name).read_text().replace('@USER@',user).replace('@GROUP@',group)
    lines=[]
    for line in text.splitlines():
        if '@DATA_DIR@' in line:
            key, value=line.split('=',1)
            if key=='ExecStart': value=value.replace('@DATA_DIR@/deploy/backup.sh',quote(str(root/'deploy/backup.sh')))
            else: value=quote(value.replace('@DATA_DIR@',str(root)))
            line=key+'='+value
        lines.append(line)
    path=Path('/etc/systemd/system')/name
    path.write_text('\n'.join(lines)+'\n');path.chmod(0o644)
PY
systemctl daemon-reload
[[ "${2:-}" == --units-only ]] && exit 0
systemctl enable myserver.service myserver-backup.timer myserver-publish.timer
systemctl restart myserver.service
curl --fail --silent --retry 5 --retry-connrefused --max-time 10 http://127.0.0.1:8765/api/health
systemctl start myserver-backup.service
systemctl start myserver-backup.timer myserver-publish.timer
echo
echo 'myserver API is loopback-only. Device access uses the existing SSH service.'
