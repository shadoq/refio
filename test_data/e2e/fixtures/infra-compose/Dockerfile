FROM python:3.12-slim

WORKDIR /app
COPY app/ ./app/

# The application listens on 8000 inside the container.
EXPOSE 8000

CMD ["python", "-m", "app.main"]
