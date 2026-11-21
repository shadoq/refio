def check_email(value):
    if "@" not in value:
        raise ValueError("Inavlid email")
    return value
