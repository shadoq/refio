from app.models.user import User
from app.repositories.user_repository import UserRepository


class UserService:
    """Business rules for users. Owns validation, delegates persistence."""

    def __init__(self):
        self._repository = UserRepository()

    def create_user(self, email: str, age: int) -> User:
        if not email or "@" not in email:
            raise ValueError("email must be non-blank and well-formed")
        if age < 0:
            raise ValueError("age must not be negative")
        return self._repository.insert(email, age)

    def get_user(self, user_id: int) -> User:
        return self._repository.find_by_id(user_id)
