ROUTES = {}


def route(path):
    def register(handler):
        ROUTES[path] = handler
        return handler
    return register


def dispatch(path):
    handler = ROUTES.get(path)
    if handler is None:
        return 404, "not found"
    return 200, handler()


@route("/health")
def health():
    return "healthy"


@route("/version")
def version():
    return "1.4.2"
