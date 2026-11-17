"""Generated SDK for the Orders API (v1). Do not edit by hand - regenerate from the spec."""

import json
import urllib.request

BASE_URL = "https://orders.internal/api/v1"


def _get(path, params=None):
    url = BASE_URL + path
    if params:
        url = url + "?" + "&".join(f"{k}={v}" for k, v in params.items())
    with urllib.request.urlopen(url) as resp:
        return json.loads(resp.read().decode())


def _post(path, body):
    req = urllib.request.Request(
        BASE_URL + path,
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req) as resp:
        return resp.status


def get_user(user_id):
    """GET /users/{id} - returns the user's profile (id, name, email)."""
    user = _get(f"/users/{user_id}")
    return {
        "id": user["id"],
        "name": user["name"],
        "email": user["email"],  # billing notifications go here
    }


def list_items(cursor=None):
    """GET /items - page through items; limit is optional server-side (default 20)."""
    params = {}
    if cursor:
        params["cursor"] = cursor
    page = _get("/items", params or None)
    return page["items"], page["next_cursor"]


def create_order(user_id, items, total):
    """POST /orders - total is the order total as a number, e.g. 49.5."""
    return _post("/orders", {"user_id": user_id, "items": items, "total": total})
