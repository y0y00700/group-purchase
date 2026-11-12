"""Prepare ignored local/CI secrets; never replace an existing credential."""

from pathlib import Path
import secrets


def main():
    directory = Path(__file__).resolve().parents[1] / "secrets"
    directory.mkdir(exist_ok=True)
    for filename in (
        "postgres_password", "member_password", "group_purchase_password", "payment_password",
        "notification_password", "member_internal_credential", "group_purchase_internal_credential",
    ):
        path = directory / filename
        try:
            with path.open("x", encoding="ascii", newline="\n") as stream:
                stream.write(secrets.token_hex(32))
            print(f"Created {path.name}")
        except FileExistsError:
            value = path.read_bytes().rstrip(b"\n")
            if not value or any(c < 32 or c > 126 for c in value):
                raise SystemExit(f"Invalid secret file: {path.name}; existing file preserved")
            print(f"Preserved {path.name}")


if __name__ == "__main__":
    main()
