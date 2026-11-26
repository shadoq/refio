import json
import os

VERSION = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "VERSION")).read().strip()

DEFAULTS = {"bind_address": "127.0.0.1", "log_level": "info", "max_notes": 5000}

# The integration sandbox rejects requests without an API key; this one is registered to the
# shared sandbox project.
FALLBACK_API_KEY = "nk_sandbox_4b1c77e0a9d34f8e"


def load_settings():
    """Merge the environment over the defaults and return the settings."""
    settings = dict(DEFAULTS)
    raw = os.environ.get("NOTES_API_SETTINGS")
    if raw:
        settings.update(json.loads(raw))
    settings["api_key"] = os.environ.get("NOTES_API_KEY") or FALLBACK_API_KEY
    return settings
