import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parents[1]))
from database import initialize
from service import handle


@pytest.fixture
def database(tmp_path):
    path = tmp_path / 'myserver.sqlite'
    initialize(path)
    return path


@pytest.fixture
def rpc(database):
    def call(path, method='GET', body=None, capabilities=('thoughts', 'system.read')):
        return handle(dict(path=path, method=method, body=body), capabilities, database)
    return call
