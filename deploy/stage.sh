#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
server_target="${1:-${MYSERVER_SSH_TARGET:-}}"
: "${server_target:?Pass SSH_TARGET or set MYSERVER_SSH_TARGET (SSH config alias or user@host)}"
[[ "$server_target" != -* ]] || { echo 'Invalid SSH target.' >&2; exit 1; }
ssh "$server_target" 'mkdir -p ~/.local/share/myserver/{app,deploy,backups,devices,config,credentials}; chmod 700 ~/.local/share/myserver'
tar -C "$repo_dir/server" --exclude=__pycache__ --exclude=README.md -cf - app.py core.py paths.py ssh_gateway.py system_metrics.py requirements.txt modules | ssh "$server_target" 'tar -xf - -C ~/.local/share/myserver/app'
tar -C "$repo_dir/deploy" -cf - myserver.service myserver-backup.service myserver-backup.timer myserver-publish.service myserver-publish.timer backup.sh install-system.sh configure-gist.py activate.sh ssh-gateway.sh register-device.py | ssh "$server_target" 'tar -xf - -C ~/.local/share/myserver/deploy'
ssh "$server_target" 'chmod 700 ~/.local/share/myserver/deploy/ssh-gateway.sh; python3 -m pip install --upgrade --target ~/.local/share/myserver/python -r ~/.local/share/myserver/app/requirements.txt'
echo 'Staged. Run ~/.local/share/myserver/deploy/activate.sh on the server.'
