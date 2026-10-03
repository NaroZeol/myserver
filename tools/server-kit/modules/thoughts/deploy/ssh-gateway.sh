#!/usr/bin/env bash
set -euo pipefail
exec /usr/bin/python3 "$HOME/.local/share/thoughts/app/ssh_gateway.py" "$@"
