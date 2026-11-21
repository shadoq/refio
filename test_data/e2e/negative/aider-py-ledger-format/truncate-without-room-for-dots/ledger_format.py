"""Ledger formatting: print multi-currency entries as fixed-width text."""

CURRENCIES = {"USD": "$", "EUR": "€"}
LOCALES = {
    "en": (",", ".", True),
    "de": (".", ",", False),
}


def _amount_text(currency, locale, cents):
    symbol = CURRENCIES[currency]
    group, decimal, symbol_first = LOCALES[locale]
    whole, frac = divmod(abs(cents), 100)
    digits = f"{whole:,}".replace(",", group)
    number = f"{digits}{decimal}{frac:02d}"
    text = f"{symbol}{number}" if symbol_first else f"{number} {symbol}"
    return f"({text})" if cents < 0 else text + " "


def _line(date, description, amount):
    return f"{date:<10} | {description:<24} | {amount:>14}"


def render_ledger(entries, locale="en"):
    """Return the formatted ledger for the given entries."""
    if locale not in LOCALES:
        raise ValueError(f"unknown locale: {locale}")
    for _, _, currency, _ in entries:
        if currency not in CURRENCIES:
            raise ValueError(f"unknown currency: {currency}")
    ordered = sorted(entries, key=lambda e: (e[0], e[1], e[3]))
    lines = [_line("Date", "Description", "Amount")]
    for date, description, currency, cents in ordered:
        if len(description) > 24:
            description = description[:24] + "..."
        lines.append(_line(date, description, _amount_text(currency, locale, cents)))
    return "\n".join(lines)
