from fastapi import FastAPI

from app.routers import users

app = FastAPI(title="accounts-service")

app.include_router(users.router)


@app.get("/health")
def health():
    return {"status": "ok"}
