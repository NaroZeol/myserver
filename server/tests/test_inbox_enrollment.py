from test_ssh_gateway import registration, key


def test_explicit_capability_upgrade_preserves_existing_grants_and_other_keys(tmp_path):
    root = tmp_path / 'data'
    authorized = tmp_path / '.ssh/authorized_keys'
    ident = registration.enroll(root, authorized, key(), 'Phone', ['system.read'])
    before = authorized.read_text()
    registration.enroll(root, authorized, key(), 'Android', ['inbox.read', 'inbox.write'], add_capabilities=True)
    import json
    record = json.loads((root / 'devices' / (ident + '.json')).read_text())
    assert record['capabilities'] == ['inbox.read', 'inbox.write', 'system.read']
    assert record['name'] == 'Phone'
    assert authorized.read_text() == before
    assert 'thoughts' not in record['capabilities']


def test_new_device_can_be_granted_only_inbox(tmp_path):
    import json
    root = tmp_path / 'data'
    ident = registration.enroll(root, tmp_path / '.ssh/authorized_keys', key(), 'Phone',
                               ['inbox.read', 'inbox.write'], add_capabilities=True)
    assert json.loads((root / 'devices' / (ident + '.json')).read_text())['capabilities'] == ['inbox.read', 'inbox.write']
