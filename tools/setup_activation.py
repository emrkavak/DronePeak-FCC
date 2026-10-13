#!/usr/bin/env python3
"""Create the permanent Authenticator secret locally; never overwrite an existing key."""
import base64
import secrets
from pathlib import Path
from urllib.parse import urlencode

root = Path(__file__).resolve().parents[1]
key_file = root / "activation.properties"
setup_file = root / "activation-setup.txt"
if key_file.exists() or setup_file.exists():
    raise SystemExit("Existing activation files found; preserve the permanent key. See ACTIVATION.md.")
secret = base64.b32encode(secrets.token_bytes(20)).decode()
uri = "otpauth://totp/DronePeak:DronePeak?" + urlencode({
    "secret": secret, "issuer": "DronePeak", "algorithm": "SHA1", "digits": 6, "period": 30})
for path, content in [(key_file, "secret=" + secret + "\n"),
                      (setup_file, "DronePeak — time-based, 6 digits, 30 seconds\nSetup key: " + secret + "\n" + uri + "\n")]:
    with open(path, "x", opener=lambda name, flags: __import__("os").open(name, flags, 0o600)) as handle:
        handle.write(content)
print("Created activation.properties and activation-setup.txt. Secret not printed. Read ACTIVATION.md.")
