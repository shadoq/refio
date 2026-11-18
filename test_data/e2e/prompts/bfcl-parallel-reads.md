For the weekly ops review I need a one-page overview of how long each of our services waits before giving up.

Please read every service config in `config/` and write `reports/timeouts.md` with:

- a markdown table with the columns `service` and `timeout_ms` (the request timeout under `runtime`, not the connect timeout), one row per service, slowest first;
- as the last line exactly `Total: <number of services> services, slowest: <name of the slowest service>`.

Do not change the configs. `python checks/check_report.py` validates the report. Do not edit anything under `checks/`.
