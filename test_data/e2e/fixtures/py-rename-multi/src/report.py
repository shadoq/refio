from src.calc import calc_total


def render_report(items):
    return "total: %.2f" % calc_total(items)
