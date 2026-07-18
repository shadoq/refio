from src.calc import compute_total


def render_report(items):
    return "total: %.2f" % compute_total(items)
