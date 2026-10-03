#!/usr/bin/env bash
set -euo pipefail
data_dir="$HOME/.local/share/myserver"
sudo bash "$data_dir/deploy/install-system.sh" "$(id -un)"
echo 'myserver is active. Configure Gist publication with deploy/configure-gist.py when using thoughts.'
