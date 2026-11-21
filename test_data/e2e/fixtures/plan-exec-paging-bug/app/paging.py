def page(items, number, size):
    start = (number - 1) * size
    return items[start:start + size - (1 if number > 1 else 0)]
