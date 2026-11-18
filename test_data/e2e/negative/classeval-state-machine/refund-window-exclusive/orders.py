"""Order life cycle as a small state machine. See the table in the original skeleton."""

REFUND_WINDOW_SECONDS = 14 * 24 * 60 * 60

EVENTS = ("pay", "pack", "ship", "deliver", "cancel", "refund")

TABLE = {
    "NEW": {"pay": "PAID", "cancel": "CANCELLED"},
    "PAID": {"pack": "PACKED", "cancel": "REFUNDED"},
    "PACKED": {"ship": "SHIPPED", "cancel": "REFUNDED"},
    "SHIPPED": {"deliver": "DELIVERED"},
    "DELIVERED": {"refund": "REFUNDED"},
    "CANCELLED": {},
    "REFUNDED": {},
}


class OrderError(Exception):
    """Base class of the errors raised by Order."""


class IllegalTransition(OrderError):
    """The event is not allowed in the current state."""


class GuardFailed(OrderError):
    """The event is allowed in this state but its guard is not satisfied."""


class Order:
    def __init__(self, clock):
        self._clock = clock
        self._state = "NEW"
        self._items = []
        self._address = None
        self._history = []
        self._delivered_at = None

    @property
    def state(self):
        return self._state

    @property
    def total_cents(self):
        return sum(p for _, p in self._items)

    @property
    def history(self):
        return list(self._history)

    def add_item(self, name, price_cents):
        if self._state != "NEW":
            raise OrderError("items can only be added to a new order")
        if type(price_cents) is not int or price_cents <= 0:
            raise ValueError("price must be a positive int")
        self._items.append((name, price_cents))

    def set_address(self, address):
        if not isinstance(address, str) or not address.strip():
            raise ValueError("address must not be blank")
        if not TABLE[self._state]:
            raise OrderError("order is final")
        self._address = address

    def _guard(self, event):
        if event == "pay":
            return bool(self._items)
        if event == "ship":
            return self._address is not None
        if event == "refund":
            return self._clock() - self._delivered_at < REFUND_WINDOW_SECONDS
        return True

    def can(self, event):
        target = TABLE[self._state].get(event) if event in EVENTS else None
        return target is not None and self._guard(event)

    def apply(self, event):
        if event not in EVENTS:
            raise ValueError("unknown event: %r" % (event,))
        target = TABLE[self._state].get(event)
        if target is None:
            raise IllegalTransition("%s not allowed in %s" % (event, self._state))
        if not self._guard(event):
            raise GuardFailed("guard of %s not satisfied" % event)
        now = self._clock()
        self._history.append((self._state, event, target, now))
        self._state = target
        if target == "DELIVERED":
            self._delivered_at = now
        return target
