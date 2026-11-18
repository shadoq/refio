"""Order life cycle as a small state machine.

States (plain strings): NEW, PAID, PACKED, SHIPPED, DELIVERED, CANCELLED, REFUNDED.
Events (plain strings): pay, pack, ship, deliver, cancel, refund.

Allowed transitions:

    NEW        --pay-->      PAID        (guard: the order has at least one item)
    NEW        --cancel-->   CANCELLED
    PAID       --pack-->     PACKED
    PAID       --cancel-->   REFUNDED    (money was taken, so cancelling refunds it)
    PACKED     --ship-->     SHIPPED     (guard: a shipping address has been set)
    PACKED     --cancel-->   REFUNDED
    SHIPPED    --deliver-->  DELIVERED
    DELIVERED  --refund-->   REFUNDED    (guard: at most 14 days after delivery, inclusive)

CANCELLED and REFUNDED are final: every event is illegal there. Any other
combination of state and event is illegal too.

Failure rules:

* An event that is not in the table for the current state raises IllegalTransition.
* An event whose guard is not satisfied raises GuardFailed.
* An event name that is not one of the six events above raises ValueError.
* A failed event never changes the state and never adds a history entry.
"""

REFUND_WINDOW_SECONDS = 14 * 24 * 60 * 60


class OrderError(Exception):
    """Base class of the errors raised by Order."""


class IllegalTransition(OrderError):
    """The event is not allowed in the current state."""


class GuardFailed(OrderError):
    """The event is allowed in this state but its guard is not satisfied."""


class Order:
    def __init__(self, clock):
        """clock is a callable returning the current time in seconds (a number)."""
        raise NotImplementedError

    @property
    def state(self):
        """Current state, starts as "NEW"."""
        raise NotImplementedError

    @property
    def total_cents(self):
        """Sum of the prices of all items."""
        raise NotImplementedError

    @property
    def history(self):
        """A new list of (from_state, event, to_state, time) tuples, oldest first.

        time is clock() at the moment of the transition. Changing the returned list
        must not change the order.
        """
        raise NotImplementedError

    def add_item(self, name, price_cents):
        """Add an item. Only possible while the state is NEW, otherwise raises
        OrderError. price_cents must be a positive int, otherwise ValueError."""
        raise NotImplementedError

    def set_address(self, address):
        """Set the shipping address (any non-blank string, otherwise ValueError).
        Allowed in every state that is not final; in a final state raises OrderError."""
        raise NotImplementedError

    def can(self, event):
        """True when apply(event) would succeed right now (guards included).
        Unknown event names give False, never an exception."""
        raise NotImplementedError

    def apply(self, event):
        """Perform the event, record it in the history and return the new state."""
        raise NotImplementedError
