"""Installed server modules and their explicit RPC permissions."""
from importlib import import_module

ENABLED = ("thoughts",)


def features():
    return [import_module("modules." + name) for name in ENABLED]


def initialize(connection):
    for feature in features():
        feature.initialize(connection)


def status(connection, capabilities):
    return {name: feature.status(connection) for name, feature in zip(ENABLED, features())
            if feature.CAPABILITIES & capabilities}


def capabilities():
    return {"system.read"}.union(*(feature.CAPABILITIES for feature in features()))


def routes():
    return [route for feature in features() for route in feature.routes()]
