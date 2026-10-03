"""Installed server features and their restricted SSH capabilities."""
from importlib import import_module

ENABLED = ("thoughts",)


def features():
    return [import_module("modules." + name) for name in ENABLED]


def init_app(app):
    for feature in features():
        feature.init_app(app)


def status():
    return {name: import_module("modules." + name).status() for name in ENABLED}


def capabilities():
    return {"system.read"}.union(*(feature.CAPABILITIES for feature in features()))


def rpc_routes():
    return [route for feature in features() for route in feature.RPC_ROUTES]
