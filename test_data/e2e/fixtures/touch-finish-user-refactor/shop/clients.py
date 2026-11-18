"""Client records."""

from collections import namedtuple

ClientRecord = namedtuple("ClientRecord", "client_id name email tier")

_CLIENTS = {}


def register_client(client_id, name, email, tier="standard"):
    record = ClientRecord(client_id, name, email, tier)
    _CLIENTS[client_id] = record
    return record


def find_client(client_id):
    try:
        return _CLIENTS[client_id]
    except KeyError:
        raise KeyError("unknown client: %s" % client_id)


def all_clients():
    return sorted(_CLIENTS.values(), key=lambda record: record.client_id)


def clear_clients():
    _CLIENTS.clear()
