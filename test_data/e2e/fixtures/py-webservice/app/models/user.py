from dataclasses import dataclass


@dataclass
class User:
    id: int
    email: str
    age: int


@dataclass
class CreateUserRequest:
    email: str
    age: int
