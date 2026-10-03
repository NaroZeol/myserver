#!/usr/bin/env bash
# Maintainer-only refresh; normal Android builds use the committed offline assets.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/terminal-vendor assets/terminal
python3 - <<'PY'
import base64, hashlib, io, tarfile, urllib.request
from pathlib import Path
root=Path('assets/terminal')
packages=[
 ('https://registry.npmjs.org/@xterm/xterm/-/xterm-6.0.0.tgz','TQwDdQGtwwDt+2cgKDLn0IRaSxYu1tSUjgKarSDkUM0ZNiSRXFpjxEsvc/Zgc5kq5omJ+V0a8/kIM2WD3sMOYg==',{'package/lib/xterm.js':'build/terminal-vendor/xterm.js','package/css/xterm.css':str(root/'xterm.css'),'package/LICENSE':str(root/'XTERM-LICENSE')}),
 ('https://registry.npmjs.org/@xterm/addon-fit/-/addon-fit-0.11.0.tgz','jYcgT6xtVYhnhgxh3QgYDnnNMYTcf8ElbxxFzX0IZo+vabQqSPAjC3c1wJrKB5E19VwQei89QCiZZP86DCPF7g==',{'package/lib/addon-fit.js':'build/terminal-vendor/addon-fit.js','package/LICENSE':str(root/'FIT-LICENSE')})]
for url,expected,files in packages:
 with urllib.request.urlopen(url,timeout=60) as response: payload=response.read()
 assert base64.b64encode(hashlib.sha512(payload).digest()).decode()==expected, 'Upstream integrity mismatch'
 with tarfile.open(fileobj=io.BytesIO(payload),mode='r:gz') as archive:
  for source,target in files.items(): Path(target).write_bytes(archive.extractfile(source).read())
PY
# Android 10's stock WebView predates optional chaining / nullish coalescing.
npx --yes --package esbuild@0.25.12 esbuild build/terminal-vendor/xterm.js --target=chrome74 --minify --outfile=assets/terminal/xterm.js
npx --yes --package esbuild@0.25.12 esbuild build/terminal-vendor/addon-fit.js --target=chrome74 --minify --outfile=assets/terminal/addon-fit.js
python3 - <<'PY'
from pathlib import Path
import hashlib,json
root=Path('assets/terminal')
files=['xterm.js','xterm.css','addon-fit.js','XTERM-LICENSE','FIT-LICENSE']
(root/'vendor.json').write_text(json.dumps({'source':'https://github.com/xtermjs/xterm.js','packages':{'@xterm/xterm':'6.0.0','@xterm/addon-fit':'0.11.0'},'transform':{'esbuild':'0.25.12','target':'chrome74'},'sha256':{f:hashlib.sha256((root/f).read_bytes()).hexdigest() for f in files}},indent=2)+'\n')
PY
