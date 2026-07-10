from app.models.user import User


class UserRepository:
    """In-memory persistence layer. The only component that touches storage."""

    def __init__(self):
        self._rows = {}
        self._next_id = 1

    def insert(self, email: str, age: int) -> User:
        user = User(id=self._next_id, email=email, age=age)
        self._rows[self._next_id] = user
        self._next_id += 1
        return user

    def find_by_id(self, user_id: int) -> User:
        return self._rows[user_id]
