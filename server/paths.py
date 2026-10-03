"""Runtime files shared by the service, gateway and deployment tools."""
import os
from pathlib import Path


def data_root():
    return Path.home() / ".local/share/myserver"


def database_path():
    return Path(os.environ.get("MYSERVER_DATABASE", "").strip() or data_root() / "myserver.sqlite")
