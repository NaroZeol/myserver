from listeners import snapshot


def test_local_listeners_and_process_labels(tmp_path):
    proc = tmp_path / 'proc'
    (proc / 'net').mkdir(parents=True)
    (proc / 'net/tcp').write_text(
        'sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n'
        '0: 0100007F:1F90 00000000:0000 0A 0 0 0 0 0 111\n'
        '1: 00000000:22B8 00000000:0000 0A 0 0 0 0 0 222\n'
        '2: 0200007F:270F 00000000:0000 0A 0 0 0 0 0 333\n'
        '3: 0100007F:0001 00000000:0000 01 0 0 0 0 0 444\n'
    )
    (proc / 'net/tcp6').write_text('sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n')
    (proc / '123/fd').mkdir(parents=True)
    (proc / '123/comm').write_text('myserver\n')
    (proc / '123/fd/4').symlink_to('socket:[111]')
    assert snapshot(proc) == [
        dict(port=8080, bind='127.0.0.1', target='127.0.0.1', program='myserver'),
        dict(port=8888, bind='0.0.0.0', target='127.0.0.1', program=None),
    ]


def test_listeners_require_system_permission(rpc):
    assert rpc('/system/listeners', capabilities=[])['status'] == 403
    assert isinstance(rpc('/system/listeners', capabilities=['system.read'])['body']['items'], list)
