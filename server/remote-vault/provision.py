"""Provision a private server; never generate phone encryption keys here."""
import hashlib
import json
import os
from pathlib import Path
import secrets
import sys

os.umask(0o077)
root=Path(__file__).resolve().parent
data=root/'data'
data.mkdir(mode=0o700,exist_ok=True)
config=data/'server.json'
tokenfile=root/'bootstrap-token'
if not config.exists():
    token=secrets.token_urlsafe(32)
    tokenfile.write_text(token)
    config.write_text(json.dumps(dict(token_sha256=hashlib.sha256(token.encode()).hexdigest())))
else:
    token=tokenfile.read_text().strip()
if len(sys.argv)>1:
    from urllib.parse import urlsplit
    url=sys.argv[1].rstrip('/')
    parsed=urlsplit(url)
    if parsed.scheme!='https' or not parsed.hostname or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment:
        raise SystemExit('Provide an HTTPS origin, e.g. https://vault.example:8443')
    cert=root/'tls/caddy/pki/authorities/local/root.crt'
    connection=dict(url=url,token=token,certificate=cert.read_text() if cert.exists() else '')
    (root/'connection.json').write_text(json.dumps(connection,indent=2))
    print('Created private connection.json; transfer securely to the phone. It contains access credentials.')
else:
    print('Token provisioned. Start HTTPS, then rerun with its HTTPS origin to export connection.json.')
