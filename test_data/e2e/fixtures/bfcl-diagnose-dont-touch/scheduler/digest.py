"""The weekly digest schedule."""

from scheduler.recurrence import next_run


def parse_time(text):
    hour, minute = text.split(":")
    return int(hour), int(minute)


def next_digest(after, config):
    """`config` looks like {"days": ["mon", "thu"], "at": "09:00"}."""
    hour, minute = parse_time(config["at"])
    return next_run(after, set(config["days"]), hour, minute)
