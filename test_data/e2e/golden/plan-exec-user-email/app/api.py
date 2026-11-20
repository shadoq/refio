from app.models import User


def create_user(payload):
    if not payload.get("name"):
        raise ValueError("name is required")
    email = payload.get("email") or ""
    if "@" not in email:
        raise ValueError("email must contain @")
    user = User(id=payload["id"], name=payload["name"], email=email)
    return {"id": user.id, "name": user.name, "email": user.email}
