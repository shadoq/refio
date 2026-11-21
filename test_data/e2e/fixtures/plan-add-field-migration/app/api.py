from app import storage
from app.models import User


def user_to_dict(user):
    return {"id": user.id, "name": user.name}


def create_user(conn, payload):
    if not payload.get("name"):
        raise ValueError("name is required")
    user = User(id=payload["id"], name=payload["name"])
    storage.insert_user(conn, user)
    return user_to_dict(user)
