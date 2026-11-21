SMTP_HOST = "smtp.example.test"
SMTP_PASSWORD = "changeme-not-a-real-secret"


def send(to, body):
    return (SMTP_HOST, to, body)
