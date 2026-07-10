from fastapi import APIRouter

from app.models.user import CreateUserRequest, User
from app.services.user_service import UserService

router = APIRouter(prefix="/users", tags=["users"])

_service = UserService()


@router.post("/")
def create_user(payload: CreateUserRequest) -> User:
    # The router only decodes the request and delegates to the service layer.
    return _service.create_user(payload.email, payload.age)


@router.get("/{user_id}")
def get_user(user_id: int) -> User:
    return _service.get_user(user_id)
