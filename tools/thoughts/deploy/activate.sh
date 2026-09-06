#!/usr/bin/env bash
set -euo pipefail
data_dir="$HOME/.local/share/thoughts"
if [[ ! -s "$data_dir/gist-token" || ! -s "$data_dir/gist.json" ]]; then
    python3 "$data_dir/deploy/configure-gist.py"
fi
sudo bash "$data_dir/deploy/install-system.sh" "$(id -un)"
cd "$data_dir/app"
PYTHONPATH="$data_dir/python" python3 publisher.py
echo 'Initial Gist publication completed.'
