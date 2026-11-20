from app.models import User


def create_user(payload):
    if not payload.get("name"):
        raise ValueError("name is required")
    user = User(id=payload["id"], name=payload["name"])
    return {"id": user.id, "name": user.name}
