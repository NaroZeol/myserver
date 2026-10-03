"""Server-side permission declarations. Device payloads cannot grant capabilities."""
CAPABILITIES = {"thoughts"}
RPC_ROUTES = [
    ('thoughts', {'GET', 'POST'}, r'/thoughts'),
    ('thoughts', {'PATCH', 'DELETE'}, r'/thoughts/[0-9a-f-]{36}'),
    ('thoughts', {'GET'}, r'/thoughts/[0-9a-f-]{36}/history'),
    ('thoughts', {'GET'}, r'/publication'),
    ('thoughts', {'POST'}, r'/publish'),
    ('thoughts', {'GET'}, r'/export'),
]


def init_app(app):
    from .routes import init_app as register
    register(app)


def status():
    from .routes import status as read
    return read()
