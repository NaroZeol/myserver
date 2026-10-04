#!/usr/bin/env python3
"""Publication boundary tests; no signing keys, GitHub access or Android SDK required."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

SPEC = importlib.util.spec_from_file_location('android_release', Path(__file__).resolve().parents[1] / 'release.py')
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)
SHA = 'a' * 40
SOURCE = dict(schema=1, repository='example/project', channel='stable', branch='feature/inbox', commit=SHA)
META = dict(package_name='app.thoughts.mobile', version_code=100123, version_name='1.2.0+123', min_sdk=26, certificate_sha256='b' * 64)


class ReleaseChecks(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='myserver-release-check-')
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)

    def android(self):
        root = self.directory / 'android'
        (root / 'res/xml').mkdir(parents=True)
        (root / 'assets').mkdir()
        (root / 'AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.thoughts.mobile" android:versionCode="18" android:versionName="1.2.0"><application android:label="myserver"><provider android:authorities="app.thoughts.mobile.inbox.files"/><provider android:authorities="app.thoughts.mobile.updates"/></application></manifest>')
        (root / 'res/xml/shortcuts.xml').write_text('<shortcut android:targetPackage="app.thoughts.mobile"/>')
        (root / 'assets/existing.txt').write_text('unchanged')
        return root

    def apk(self, source=SOURCE):
        apk = self.directory / 'myserver.apk'
        with ZipFile(apk, 'w') as archive:
            archive.writestr('assets/update-source.json', json.dumps(source))
            archive.writestr('classes.dex', b'fixture only')
        return apk

    def environment(self, **changes):
        return dict(GITHUB_EVENT_NAME='push', GITHUB_REF_TYPE='branch', GITHUB_REPOSITORY='example/project', GITHUB_REF_NAME='feature/inbox', GITHUB_SHA=SHA, GITHUB_RUN_NUMBER='123', **changes)

    def test_local_build_has_no_hardcoded_update_repository(self):
        root = self.android()
        original = (root / 'AndroidManifest.xml').read_text()
        source = release.prepare_build(root, {})
        self.assertEqual(source['repository'], '')
        self.assertEqual((root / 'AndroidManifest.xml').read_text(), original)
        self.assertEqual((root / 'build/assets/existing.txt').read_text(), 'unchanged')
        self.assertFalse((root / 'assets/update-source.json').exists())

    def test_all_branches_keep_application_identity_and_refresh_build_provenance(self):
        root = self.android()
        for number, branch in enumerate(('main', 'feature/inbox', 'feature/文件传输'), 123):
            with self.subTest(branch=branch):
                # A stale environment from an old local build cannot select another package.
                environment = dict(MYSERVER_PREVIEW='1', MYSERVER_UPDATE_REPOSITORY=SOURCE['repository'], MYSERVER_UPDATE_BRANCH=branch, MYSERVER_UPDATE_COMMIT=SHA, MYSERVER_VERSION_CODE=str(100000 + number), MYSERVER_VERSION_NAME='1.2.0+' + str(number))
                self.assertEqual(release.prepare_build(root, environment), dict(SOURCE, branch=branch))
                manifest = (root / 'build/AndroidManifest.xml').read_text()
                self.assertIn('versionCode="' + str(100000 + number) + '"', manifest)
                self.assertIn('package="app.thoughts.mobile"', manifest)
                self.assertIn('app.thoughts.mobile.inbox.files', manifest)
                self.assertIn('app.thoughts.mobile.updates', manifest)
                self.assertIn('android:label="myserver"', manifest)
                self.assertNotIn('.preview', manifest)
                self.assertEqual((root / 'build/res/xml/shortcuts.xml').read_text(), (root / 'res/xml/shortcuts.xml').read_text())
        release.prepare_build(root, {})
        self.assertEqual(json.loads((root / 'build/assets/update-source.json').read_text())['repository'], '')

    def test_untrusted_build_metadata_is_rejected(self):
        root = self.android()
        for changes in (dict(MYSERVER_VERSION_CODE='0'), dict(MYSERVER_VERSION_CODE='2100000001'), dict(MYSERVER_VERSION_CODE='1" injected="true'), dict(MYSERVER_UPDATE_REPOSITORY='example/project/../private'), dict(MYSERVER_UPDATE_CHANNEL='preview'), dict(MYSERVER_UPDATE_REPOSITORY='example/project', MYSERVER_UPDATE_BRANCH='feature/work', MYSERVER_UPDATE_COMMIT='not-a-commit')):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                release.prepare_build(root, changes)

    def test_release_tags_follow_global_run_numbers(self):
        self.assertEqual(release.release_tag(100123), 'android-stable-100123')
        self.assertEqual(release.release_tag(100124), 'android-stable-100124')

    def test_real_build_tools_badging_formats_and_signer(self):
        for spelling in ('minSdkVersion', 'sdkVersion'):
            badging="package: name='app.thoughts.mobile' versionCode='100123' versionName='1.2.0+123' platformBuildVersionName='15'\n"+spelling+":'26'\n"
            signer='Signer #1 certificate SHA-256 digest: '+'B'*64+'\n'
            with patch.object(release, 'command', side_effect=[badging,signer]):
                self.assertEqual(release.apk_metadata('/fixture.apk','/tools'),META)

    def test_manifest_matches_apk_provenance_package_signature_and_digest(self):
        apk = self.apk()
        with patch.object(release, 'apk_metadata', return_value=META), patch.dict(os.environ, self.environment(), clear=True):
            result = release.create_manifest(apk, '/unused', SOURCE)
        self.assertEqual(result['certificate_sha256'], 'b' * 64)
        self.assertEqual(result['size'], apk.stat().st_size)
        self.assertEqual(result['sha256'], release.hashlib.sha256(apk.read_bytes()).hexdigest())
        self.assertTrue(result['apk_url'].endswith('/myserver.apk'))
        self.assertEqual(result['release_url'], 'https://github.com/example/project/releases/tag/android-stable-100123')
        for package in ('wrong.package', 'app.thoughts.mobile.preview'):
            with self.subTest(package=package), patch.object(release, 'apk_metadata', return_value=dict(META, package_name=package)), patch.dict(os.environ, self.environment(), clear=True), self.assertRaises(ValueError):
                release.create_manifest(apk, '/unused', SOURCE)
        with self.assertRaises(ValueError):
            release.create_manifest(apk, '/unused', dict(SOURCE, commit='c' * 40))

    def test_alternate_channel_cannot_be_published_with_matching_package(self):
        source = dict(SOURCE, channel='preview')
        with patch.object(release, 'apk_metadata', return_value=META), patch.dict(os.environ, self.environment(), clear=True), self.assertRaises(ValueError):
            release.create_manifest(self.apk(source), '/unused', source)

    def test_pull_request_cannot_reach_release_or_signing_operations(self):
        with patch.dict(os.environ, dict(self.environment(), GITHUB_EVENT_NAME='pull_request'), clear=True), patch.object(release, 'remote_head') as head, self.assertRaises(ValueError):
            release.publish(self.apk(), '/unused')
        head.assert_not_called()

    def test_stale_run_never_publishes(self):
        with patch.dict(os.environ, self.environment(), clear=True), patch.object(release, 'remote_head', return_value='d' * 40), patch.object(release, 'command') as command:
            release.publish(self.apk(), '/unused')
        command.assert_not_called()

    def test_complete_assets_precede_publication_and_manifest_is_machine_readable(self):
        apk = self.apk()
        with patch.dict(os.environ, self.environment(), clear=True), patch.object(release, 'remote_head', return_value=SHA), patch.object(release, 'apk_metadata', return_value=META), patch.object(release, 'existing_release', return_value=None), patch.object(release, 'command') as command:
            seen = []
            def record(*args, **kwargs):
                seen.append(args)
                if args[1:3] == ('release', 'create'):
                    self.assertIn('--draft', args)
                    self.assertNotIn('--prerelease', args)
                    body = Path(args[args.index('--notes-file') + 1]).read_text()
                    document = release.release_document(body)
                    self.assertEqual(document['commit'], SHA)
                    self.assertEqual(document['branch'], 'feature/inbox')
                    self.assertEqual(document['channel'], 'stable')
                if args[1:3] == ('release', 'upload'):
                    update = json.loads(Path(args[5]).read_text())
                    self.assertEqual(update['version_code'], 100123)
                return ''
            command.side_effect = record
            release.publish(apk, '/unused')
        self.assertEqual([args[2] for args in seen], ['create', 'upload', 'edit'])
        self.assertIn('--draft=false', seen[-1])
        self.assertIn('--latest=true', seen[-1])
        self.assertIn('--prerelease=false', seen[-1])

    def test_advanced_branch_during_upload_keeps_unpublished_draft(self):
        with patch.dict(os.environ, self.environment(), clear=True), patch.object(release, 'remote_head', side_effect=[SHA, 'd' * 40]), patch.object(release, 'apk_metadata', return_value=META), patch.object(release, 'existing_release', return_value=None), patch.object(release, 'command', return_value='') as command:
            release.publish(self.apk(), '/unused')
        self.assertFalse(any('--draft=false' in entry.args for entry in command.call_args_list))

    def test_rerun_preserves_already_published_bytes(self):
        old = dict(draft=False, body=release.MARKER + json.dumps(dict(SOURCE, **META)) + '\n-->')
        with patch.dict(os.environ, self.environment(), clear=True), patch.object(release, 'remote_head', return_value=SHA), patch.object(release, 'apk_metadata', return_value=META), patch.object(release, 'existing_release', return_value=old), patch.object(release, 'command') as command:
            release.publish(self.apk(), '/unused')
        command.assert_not_called()


if __name__ == '__main__':
    unittest.main()
