"""JSON request/response types for myserver-rpc-v1; no HTTP transport."""
from dataclasses import dataclass
from pathlib import Path
from typing import Callable


class RpcError(Exception):
    def __init__(self, message, status=400):
        super().__init__(message)
        self.status = status


@dataclass(frozen=True)
class Reply:
    body: dict
    status: int = 200


@dataclass(frozen=True)
class Route:
    capability: str | None
    method: str
    pattern: str
    handler: Callable


@dataclass
class Request:
    path: str
    method: str
    body: dict | None
    query: dict
    params: dict
    capabilities: frozenset
    database: Path

    def payload(self):
        if not isinstance(self.body, dict):
            raise ValueError("请求内容必须是 JSON 对象")
        return self.body
