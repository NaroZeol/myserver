#!/usr/bin/env python3
"""Build-time update provenance and trusted, tested GitHub release publication."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from urllib.parse import quote
import xml.etree.ElementTree as ET
from zipfile import ZipFile

ANDROID = '{http://schemas.android.com/apk/res/android}'
MARKER = '<!-- myserver-update\n'
CODE_OFFSET = 100000


def repository(value):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}', value) or value.split('/')[-1] in ('.', '..'):
        raise ValueError('Invalid public GitHub repository')
    return value


def prepare_build(directory, environment):
    """Keep committed resources generic; inject provenance into the built APK only."""
    directory = Path(directory)
    manifest = (directory / 'AndroidManifest.xml').read_text()
    root = ET.fromstring(manifest)
    code = environment.get('MYSERVER_VERSION_CODE', root.attrib[ANDROID + 'versionCode'])
    if not re.fullmatch('[0-9]{1,10}', code) or not 1 <= int(code) <= 2100000000:
        raise ValueError('Android versionCode must be between 1 and 2100000000')
    version = environment.get('MYSERVER_VERSION_NAME', root.attrib[ANDROID + 'versionName'])
    if not re.fullmatch('[A-Za-z0-9][A-Za-z0-9.+_-]{0,79}', version):
        raise ValueError('Invalid Android version name')
    manifest = re.sub(r'android:versionCode="[^"]+"', 'android:versionCode="' + code + '"', manifest, count=1)
    manifest = re.sub(r'android:versionName="[^"]+"', 'android:versionName="' + version + '"', manifest, count=1)
    build = directory / 'build'
    build.mkdir(exist_ok=True)
    shutil.rmtree(build / 'res', ignore_errors=True)
    shutil.copytree(directory / 'res', build / 'res')
    shutil.rmtree(build / 'assets', ignore_errors=True)
    shutil.copytree(directory / 'assets', build / 'assets')
    repo = environment.get('MYSERVER_UPDATE_REPOSITORY', '')
    channel = environment.get('MYSERVER_UPDATE_CHANNEL', 'stable')
    branch = environment.get('MYSERVER_UPDATE_BRANCH', '')
    commit = environment.get('MYSERVER_UPDATE_COMMIT', '')
    if channel != 'stable':
        raise ValueError('All branches use the stable application identity')
    if repo:
        repository(repo)
        if not branch or len(branch.encode()) > 255 or any(ord(c) < 32 for c in branch):
            raise ValueError('A published build needs its source branch')
        if not re.fullmatch('[0-9a-f]{40}', commit):
            raise ValueError('A published build needs its full source commit')
    source = dict(schema=1, repository=repo, channel=channel, branch=branch, commit=commit)
    (build / 'assets/update-source.json').write_text(json.dumps(source, ensure_ascii=False, separators=(',', ':')) + '\n')
    (build / 'AndroidManifest.xml').write_text(manifest)
    return source


def release_tag(code):
    return 'android-stable-' + str(code)


def command(*args, **kwargs):
    return subprocess.run(args, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=120, **kwargs).stdout


def apk_metadata(apk, build_tools):
    badging = command(str(Path(build_tools) / 'aapt2'), 'dump', 'badging', str(apk))
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.MULTILINE)
    minimum = re.search(r"^(?:minSdkVersion|sdkVersion):'([0-9]+)'", badging, re.MULTILINE)
    if not package or not minimum:
        raise ValueError('Cannot read APK package/version/minSdk')
    signatures = command(str(Path(build_tools) / 'apksigner'), 'verify', '--print-certs', str(apk))
    certificates = re.findall(r'^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$', signatures, re.MULTILINE)
    if len(certificates) != 1:
        raise ValueError('Release APK must have exactly one verified signer')
    return dict(package_name=package[1], version_code=int(package[2]), version_name=package[3], min_sdk=int(minimum[1]), certificate_sha256=certificates[0].lower())


def create_manifest(apk, build_tools, expected):
    apk = Path(apk)
    with ZipFile(apk) as archive:
        info = archive.getinfo('assets/update-source.json')
        if info.file_size > 4096:
            raise ValueError('APK update provenance is too large')
        source = json.loads(archive.read(info))
    if source != expected:
        raise ValueError('Tested APK provenance differs from the publishing run')
    metadata = apk_metadata(apk, build_tools)
    if source['channel'] != 'stable' or metadata['package_name'] != 'app.thoughts.mobile':
        raise ValueError('APK must use the stable application identity')
    code = CODE_OFFSET + int(os.environ['GITHUB_RUN_NUMBER'])
    if metadata['version_code'] != code:
        raise ValueError('APK versionCode differs from the tested workflow run')
    tag = release_tag(code)
    base = 'https://github.com/' + repository(source['repository'])
    digest = hashlib.sha256()
    with apk.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return dict(**source, **metadata, sha256=digest.hexdigest(), size=apk.stat().st_size,
                apk_url=base + '/releases/download/' + tag + '/myserver.apk',
                release_url=base + '/releases/tag/' + tag,
                published_at=datetime.now(timezone.utc).isoformat(timespec='seconds'))


def gh_api(path, payload=None):
    args = ['gh', 'api', '-H', 'Accept: application/vnd.github+json', path]
    if payload is not None:
        args.extend(['--method', 'POST', '--input', '-'])
    return json.loads(command(*args, input=None if payload is None else json.dumps(payload)))


def remote_head(repo, branch):
    reference = gh_api('repos/' + repo + '/git/ref/heads/' + quote(branch, safe=''))
    return reference['object']['sha']


def existing_release(repo, tag):
    result = subprocess.run(['gh', 'api', 'repos/' + repo + '/releases/tags/' + tag], capture_output=True, text=True, timeout=120)
    if result.returncode == 0:
        return json.loads(result.stdout)
    if 'HTTP 404' in result.stderr:
        return None
    raise RuntimeError('Could not inspect the existing release: ' + result.stderr.strip())


def release_document(body):
    start = body.find(MARKER)
    if start < 0:
        raise ValueError('Existing release is not a myserver update')
    end = body.find('\n-->', start + len(MARKER))
    if end < 0:
        raise ValueError('Existing update marker is incomplete')
    return json.loads(body[start + len(MARKER):end])


def publish(apk, build_tools):
    env = os.environ
    if env.get('GITHUB_EVENT_NAME') not in ('push', 'workflow_dispatch') or env.get('GITHUB_REF_TYPE') != 'branch':
        raise ValueError('Only a trusted branch push or dispatch may publish an update')
    repo = repository(env['GITHUB_REPOSITORY'])
    branch, commit = env['GITHUB_REF_NAME'], env['GITHUB_SHA']
    if not re.fullmatch('[0-9a-f]{40}', commit):
        raise ValueError('Invalid publishing commit')
    expected = dict(schema=1, repository=repo, channel='stable', branch=branch, commit=commit)
    if remote_head(repo, branch) != commit:
        print('Source branch has advanced; this older run will not publish.')
        return
    manifest = create_manifest(apk, build_tools, expected)
    tag = release_tag(manifest['version_code'])
    old = existing_release(repo, tag)
    if old:
        previous = release_document(old.get('body') or '')
        if any(previous.get(key) != manifest[key] for key in ('repository', 'branch', 'channel', 'commit', 'version_code', 'package_name')):
            raise ValueError('Refusing to overwrite an unrelated release')
        if not old['draft']:
            print('This successful run is already published; keeping its original APK and manifest.')
            return
    with tempfile.TemporaryDirectory(prefix='myserver-release-') as temporary:
        directory = Path(temporary)
        (directory / 'update.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
        source_url = 'https://github.com/' + repo + '/commit/' + commit
        body = ('myserver ' + manifest['version_name'] + '\n\n'
                + 'Source branch: `' + branch.replace('`', '') + '`\n\n'
                + 'Source commit: [' + commit[:12] + '](' + source_url + ')\n\n'
                + 'Validated on Android 10 and 15. Download `myserver.apk` to install; `update.json` contains package, checksum and signing-certificate metadata.\n\n'
                + MARKER + json.dumps(manifest, ensure_ascii=False, separators=(',', ':')) + '\n-->\n')
        notes = directory / 'notes.md'
        notes.write_text(body)
        title = 'myserver ' + manifest['version_name'] + ' · ' + branch + ' · ' + str(manifest['version_code'])
        if old:
            command('gh', 'release', 'edit', tag, '--repo', repo, '--title', title, '--notes-file', str(notes))
        else:
            args = ['gh', 'release', 'create', tag, '--repo', repo, '--target', commit, '--draft', '--title', title, '--notes-file', str(notes)]
            command(*args)
        command('gh', 'release', 'upload', tag, str(apk), str(directory / 'update.json'), '--repo', repo, '--clobber')
        # A force-push/new push during upload must not make a stale build public.
        if remote_head(repo, branch) != commit:
            print('Source branch advanced during upload; leaving the release as an unpublished draft.')
            return
        command('gh', 'release', 'edit', tag, '--repo', repo, '--draft=false', '--prerelease=false', '--latest=true')
        print('Published ' + manifest['release_url'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    prepare = sub.add_parser('prepare-build')
    prepare.add_argument('--android-dir', type=Path, default=Path(__file__).resolve().parent)
    release = sub.add_parser('publish')
    release.add_argument('--apk', type=Path, required=True)
    release.add_argument('--build-tools', required=True)
    args = parser.parse_args()
    if args.command == 'prepare-build':
        prepare_build(args.android_dir, os.environ)
    else:
        publish(args.apk, args.build_tools)


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as error:
        # gh/apksigner output contains public metadata only; never dump process environment.
        raise SystemExit(error.stderr.strip() or 'Release command failed') from None
