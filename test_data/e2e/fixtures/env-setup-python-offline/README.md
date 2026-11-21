# Workspace

- `unitconv/` - shared helper library (no installation, used from source).
- `reporter/` - the trip report tool. Start it with `python reporter/start.py` from anywhere.

Setup is offline: nothing is installed with pip. The reporter needs a `config.toml` next to `config.example.toml`, created from it.
Run `python check.py` to verify the whole setup.
