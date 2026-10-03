#!/usr/bin/env bash
set -euo pipefail
data_dir="$HOME/.local/share/myserver"
bash "$data_dir/deploy/install-user.sh"
echo 'myserver is active. Configure Gist publication with deploy/configure-gist.py when using thoughts.'
