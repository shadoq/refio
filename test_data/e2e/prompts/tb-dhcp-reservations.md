Produce the quarterly DHCP inventory for `etc/dhcp/dhcpd.conf`
exactly as `docs/network.md` specifies and write it to
`report/dhcp-inventory.txt` as `key = value` lines: host_count,
ipv6_prefix_len, dynamic_range_count, first_dynamic_ip, last_dynamic_ip and
largest_range_size. Commented-out blocks and pools are not in effect, and
range sizes count both endpoints. `python3 checks.py` must exit 0 - it
re-parses the configuration and compares exactly. Do not modify the
configuration, the docs or the gate.
