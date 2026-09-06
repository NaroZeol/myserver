#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
server_target="${1:?Usage: stage.sh SSH_TARGET (SSH config alias or user@host)}"
[[ "$server_target" != -* ]] || { echo 'Invalid SSH target.' >&2; exit 1; }
ssh "$server_target" 'mkdir -p ~/.local/share/thoughts/{app,deploy,artifacts,backups,devices}; chmod 700 ~/.local/share/thoughts'
tar -C tools/thoughts/server -cf - app.py ssh_gateway.py publisher.py requirements.txt static | ssh "$server_target" 'tar -xf - -C ~/.local/share/thoughts/app'
tar -C tools/thoughts/deploy -cf - thoughts.service thoughts-backup.service thoughts-backup.timer thoughts-publish.service thoughts-publish.timer backup.sh install-system.sh configure-gist.py activate.sh ssh-gateway.sh register-device.py | ssh "$server_target" 'tar -xf - -C ~/.local/share/thoughts/deploy'
ssh "$server_target" 'chmod 700 ~/.local/share/thoughts/deploy/ssh-gateway.sh; python3 -m pip install --target ~/.local/share/thoughts/python -r ~/.local/share/thoughts/app/requirements.txt'
if [[ -f tools/thoughts/android/build/thoughts.apk ]]; then scp tools/thoughts/android/build/thoughts.apk "$server_target":.local/share/thoughts/artifacts/thoughts.apk; fi
echo 'Staged. Run ~/.local/share/thoughts/deploy/activate.sh on the server.'
