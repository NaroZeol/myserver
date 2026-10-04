#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
server_target="${1:-${MYSERVER_SSH_TARGET:-}}"
: "${server_target:?Pass SSH_TARGET or set MYSERVER_SSH_TARGET (SSH config alias or user@host)}"
[[ "$server_target" != -* ]] || { echo 'Invalid SSH target.' >&2; exit 1; }
# Upload a complete bundle without changing the active application.
tar -C "$repo_dir" --exclude=__pycache__ --exclude=tests --exclude=README.md -cf - \
    server/cli.py server/backups.py server/database.py server/rpc.py server/service.py server/paths.py \
    server/ssh_gateway.py server/system_metrics.py server/modules \
    deploy/activate.sh deploy/activate.py deploy/install-user.sh deploy/backup.sh deploy/myserver \
    deploy/myserver-backup.service deploy/myserver-backup.timer \
    deploy/myserver-publish.service deploy/myserver-publish.timer \
    deploy/configure-gist.py deploy/ssh-gateway.sh deploy/register-device.py | ssh "$server_target" '
set -eu
umask 077
root="$HOME/.local/share/myserver"
mkdir -p "$root"
chmod 700 "$root"
exec 9>"$root/staging.lock"
flock -n 9
incoming=$(mktemp -d "$root/.stage-XXXXXX")
trap '\''rm -rf "$incoming"'\'' EXIT
tar -xf - -C "$incoming"
mv "$incoming/server" "$incoming/app"
chmod 700 "$incoming/deploy/ssh-gateway.sh"
rm -rf "$root/staged"
mv "$incoming" "$root/staged"
'
echo 'Staged. Run bash ~/.local/share/myserver/staged/deploy/activate.sh on the server.'
