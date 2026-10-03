"""Small, read-only Linux host snapshot. No agent, shell commands or extra dependencies."""
import os
import shutil
import time
from pathlib import Path


def cpu_ticks(proc):
    fields = (proc / "stat").read_text().splitlines()[0].split()
    if fields[0] != "cpu" or len(fields) < 5:
        raise ValueError("No aggregate CPU counters")
    # guest/guest_nice are already included in user/nice; do not count them twice.
    values = [int(v) for v in fields[1:9]]
    return sum(values), values[3] + (values[4] if len(values) > 4 else 0)


def cpu_percent(before, after):
    total, idle = after[0] - before[0], after[1] - before[1]
    if total <= 0 or idle < 0 or idle > total:
        return None
    return round(100 * (total - idle) / total, 1)


def snapshot(disk_path, proc=Path("/proc"), pause=time.sleep):
    result = {"cpu": {"cores": os.cpu_count(), "usage_percent": None, "load_average": None},
              "memory": None, "disk": None, "uptime_seconds": None}
    try:
        first = cpu_ticks(proc)
        pause(0.2)
        result["cpu"]["usage_percent"] = cpu_percent(first, cpu_ticks(proc))
    except (OSError, ValueError, IndexError):
        pass
    try:
        result["cpu"]["load_average"] = [round(value, 2) for value in os.getloadavg()]
    except (OSError, AttributeError):
        pass
    try:
        fields = {}
        for line in (proc / "meminfo").read_text().splitlines():
            name, value = line.split(":", 1)
            fields[name] = int(value.split()[0]) * 1024
        total = fields["MemTotal"]
        available = fields.get("MemAvailable")
        if available is None:
            available = (fields["MemFree"] + fields.get("Buffers", 0) + fields.get("Cached", 0)
                         + fields.get("SReclaimable", 0) - fields.get("Shmem", 0))
        available = max(0, min(total, available))
        if total > 0:
            result["memory"] = {"total_bytes": total, "used_bytes": total - available,
                                "available_bytes": available, "usage_percent": round((total - available) * 100 / total, 1)}
    except (OSError, ValueError, KeyError, IndexError):
        pass
    try:
        disk = shutil.disk_usage(disk_path)
        if disk.total > 0:
            result["disk"] = {"total_bytes": disk.total, "used_bytes": disk.used, "free_bytes": disk.free,
                              "usage_percent": round(disk.used * 100 / disk.total, 1)}
    except OSError:
        pass
    try:
        result["uptime_seconds"] = max(0, int(float((proc / "uptime").read_text().split()[0])))
    except (OSError, ValueError, IndexError, OverflowError):
        pass
    return result
