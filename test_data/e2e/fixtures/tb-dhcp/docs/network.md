# DHCP inventory (authoritative)

`etc/dhcp/dhcpd.conf` is the live ISC dhcpd configuration for site
northgate. Lines starting with `#` are comments and carry no configuration;
commented-out blocks and pools are not in effect.

The quarterly inventory needs these six numbers, derived from the
configuration only:

- `host_count` - the number of `host` reservation blocks that are in effect.
- `ipv6_prefix_len` - the prefix length used by the `fixed-prefix6`
  declarations of the reservations.
- `dynamic_range_count` - the number of dynamic IPv4 `range` statements that
  are in effect, wherever they appear (inside a `group`, a `shared-network`
  or a plain `subnet`).
- `first_dynamic_ip` - the lowest start address among those dynamic ranges.
- `last_dynamic_ip` - the highest end address among those dynamic ranges.
- `largest_range_size` - the number of addresses covered by the largest
  dynamic range, counting both endpoints (a range
  `10.20.0.100 10.20.0.180` covers 81 addresses).

Write them to `report/dhcp-inventory.txt` as `key = value` lines, one per
line, exactly those six keys and nothing else. `python3 checks.py` must exit
0: it re-parses the configuration, does all address arithmetic with the
`ipaddress` module and compares every value exactly. Do not modify the
configuration or the gate.
