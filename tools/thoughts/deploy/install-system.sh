#!/usr/bin/env bash
set -euo pipefail
if [[ "$EUID" -ne 0 ]]; then echo 'Run with sudo and a service username.' >&2; exit 1; fi
service_user="${1:-${SUDO_USER:-}}"
[[ -n "$service_user" && "$service_user" != root ]] || { echo 'Specify a non-root service account.' >&2; exit 1; }
service_home="$(getent passwd "$service_user" | cut -d: -f6)"
[[ -n "$service_home" ]] || { echo 'Unknown service account.' >&2; exit 1; }
data_dir="$service_home/.local/share/thoughts"
deploy_dir="$data_dir/deploy"
[[ -f "$data_dir/app/app.py" && -f "$data_dir/app/ssh_gateway.py" ]] || { echo 'Stage the application first.' >&2; exit 1; }
python3 - "$service_user" "$data_dir" <<'PY'
import grp, pwd, sys
from pathlib import Path
user, root = sys.argv[1], Path(sys.argv[2])
group = grp.getgrgid(pwd.getpwnam(user).pw_gid).gr_name
# systemd performs specifier expansion even inside quotes.
def quote(value): return '"'+value.replace('\\','\\\\').replace('"','\\"').replace('%','%%')+'"'
for name in ['thoughts.service','thoughts-backup.service','thoughts-backup.timer','thoughts-publish.service','thoughts-publish.timer']:
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
systemctl enable --now thoughts.service thoughts-backup.timer thoughts-publish.timer
systemctl restart thoughts.service
curl --fail --silent --retry 5 --retry-connrefused --max-time 10 http://127.0.0.1:8765/api/health
systemctl start thoughts-backup.service
echo
echo 'Thoughts API is loopback-only. Device access uses the existing SSH service.'
