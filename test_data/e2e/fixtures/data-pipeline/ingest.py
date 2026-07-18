"""Stage 1: ingest raw sales rows. Amounts arrive as integer cents."""


def read_raw():
    # Amounts are in cents (integers), as delivered by the upstream system.
    return [
        {"id": 1, "amount_cents": 1050},
        {"id": 2, "amount_cents": 2999},
        {"id": 3, "amount_cents": 500},
    ]
