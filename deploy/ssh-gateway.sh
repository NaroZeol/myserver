#!/usr/bin/env bash
set -euo pipefail
exec /usr/bin/python3 "$HOME/.local/share/myserver/app/ssh_gateway.py" "$@"
