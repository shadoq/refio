# Changelog

All notable changes to the `shop` package are documented here.

## 0.4.2 - 2026-03-06

### Security

- SEC-ADV-2026-031: `shop/offers.py:find_offer` built its SQL by string formatting, so the
  offer code reached the statement unescaped. It now uses a bound parameter.

## 0.4.1 - 2026-02-17

### Added

- `shop/reports.py` with `revenue_by_category` and `top_customers_by_region` for the weekly
  e-mail job.

### Changed

- `search_titles` now also matches on punctuation in product titles.

## 0.4.0 - 2026-01-28

### Added

- Offers table and `shop/offers.py`.
- Order history in the customer view (`orders_between`).

### Changed

- `connect()` seeds an empty database automatically; `seed.py` stays for file databases.

## 0.3.3 - 2026-01-09

### Fixed

- `customers_by_ids` returned rows in insertion order; it now orders by id.
