from pathlib import Path
from collections import namedtuple
import pytest
import system_metrics as metrics


def test_linux_snapshot_counts_reclaimable_memory_and_cpu_once(tmp_path, monkeypatch):
    (tmp_path / 'stat').write_text('cpu 100 0 50 800 50 0 0 0 20 0\n')
    (tmp_path / 'meminfo').write_text('MemTotal: 1000 kB\nMemFree: 10 kB\nMemAvailable: 600 kB\nCached: 590 kB\n')
    (tmp_path / 'uptime').write_text('90061.75 1000.0\n')
    monkeypatch.setattr(metrics.os, 'cpu_count', lambda: 4)
    monkeypatch.setattr(metrics.os, 'getloadavg', lambda: (1.0, 0.5, 0.25))
    disk = namedtuple('usage', 'total used free')
    monkeypatch.setattr(metrics.shutil, 'disk_usage', lambda _: disk(10000, 7500, 2500))
    def sample(_):
        (tmp_path / 'stat').write_text('cpu 150 0 50 850 50 0 0 0 60 0\n')
    result = metrics.snapshot(tmp_path, tmp_path, sample)
    assert result['cpu'] == dict(cores=4, usage_percent=50.0, load_average=[1.0, 0.5, 0.25])
    assert result['memory']['used_bytes'] == 400 * 1024
    assert result['memory']['usage_percent'] == 40
    assert result['disk']['usage_percent'] == 75
    assert result['uptime_seconds'] == 90061


def test_missing_metrics_do_not_hide_the_remaining_status(tmp_path, monkeypatch):
    def missing(*args): raise OSError('unavailable')
    monkeypatch.setattr(metrics.os, 'getloadavg', missing)
    monkeypatch.setattr(metrics.shutil, 'disk_usage', missing)
    result = metrics.snapshot(tmp_path, tmp_path, lambda _: None)
    assert result['cpu']['usage_percent'] is None
    assert result['cpu']['load_average'] is None
    assert result['memory'] is None and result['disk'] is None and result['uptime_seconds'] is None


@pytest.mark.parametrize('before,after', [((10, 5), (10, 5)), ((10, 5), (9, 5)), ((10, 5), (15, 4)), ((10, 5), (11, 10))])
def test_invalid_counter_intervals_are_not_reported_as_utilization(before, after):
    assert metrics.cpu_percent(before, after) is None


def test_status_adds_metrics_without_changing_auth_or_existing_fields(rpc):
    assert rpc('/system', capabilities=[])['status'] == 403
    status = rpc('/system')['body']
    assert {'metrics', 'modules', 'storage', 'backup'} <= status.keys()
    assert {'cpu', 'memory', 'disk', 'uptime_seconds'} == status['metrics'].keys()
    assert status['metrics']['disk']['total_bytes'] > 0
