#!/usr/bin/env bash
# Ephemeral GitHub runner fixture. No production credentials or Gist writes.
set -euo pipefail
cd "$(dirname "$0")/.."
fixture_dir="$HOME/.local/share/thoughts"
mkdir -p "$fixture_dir"/{app,deploy,devices,backups} build/ssh-fixture
chmod 700 "$fixture_dir"
cp ../server/{app.py,publisher.py,ssh_gateway.py} "$fixture_dir/app/"
cp ../deploy/{register-device.py,ssh-gateway.sh} "$fixture_dir/deploy/"
chmod 700 "$fixture_dir/deploy/ssh-gateway.sh"
python3 -m pip install -q --target build/ssh-fixture/python -r ../server/requirements.txt
PYTHONPATH="$PWD/build/ssh-fixture/python" python3 -m gunicorn --chdir "$fixture_dir/app" --bind 127.0.0.1:8765 'app:create_app()' > build/ssh-fixture/api.log 2>&1 &
for name in host wrong-host; do ssh-keygen -q -t ecdsa -b 256 -N '' -f "build/ssh-fixture/$name"; done
# A random password exists only on this disposable runner for the enrollment test.
python3 - <<'PYTEST'
import getpass, os, secrets, subprocess
from pathlib import Path
password = secrets.token_hex(32)
path = Path('build/ssh-fixture/password')
path.write_text(password)
path.chmod(0o600)
subprocess.run(['sudo', 'chpasswd'], input=getpass.getuser()+':'+password+'\n', text=True, check=True)
print('::add-mask::'+password)
PYTEST
cat > build/ssh-fixture/sshd_config <<EOF
Port 2222
ListenAddress 127.0.0.1
HostKey $PWD/build/ssh-fixture/host
PidFile $PWD/build/ssh-fixture/sshd.pid
AuthorizedKeysFile .ssh/authorized_keys
PasswordAuthentication yes
KbdInteractiveAuthentication no
UsePAM yes
PermitRootLogin no
AllowUsers $(id -un)
LogLevel VERBOSE
EOF
sudo mkdir -p /run/sshd
sudo /usr/sbin/sshd -f "$PWD/build/ssh-fixture/sshd_config" -E "$PWD/build/ssh-fixture/sshd.log"
sudo chmod 644 "$PWD/build/ssh-fixture/sshd.log"
sudo passwd -S "$(id -un)"
curl --fail --silent --retry 5 --retry-connrefused http://127.0.0.1:8765/api/health
