"""Opaque encrypted object storage. No image decoding or prompt processing."""
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

DAY = 24 * 60 * 60
ID = re.compile(r"^[a-f0-9]{64}$")


class Problem(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message


class Store:
    def __init__(self, directory, quota=100 * 1024**3, clock=time.time):
        self.root = Path(directory)
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.clock, self.quota = clock, quota
        self.lock = threading.RLock()
        self.db = sqlite3.connect(self.root / 'index.sqlite', check_same_thread=False)
        self.db.execute('PRAGMA secure_delete=ON')
        self.db.execute('CREATE TABLE IF NOT EXISTS objects(id TEXT PRIMARY KEY, size INTEGER NOT NULL, sha TEXT NOT NULL, created REAL NOT NULL, deleted REAL)')
        self.db.commit()
        # Incomplete uploads are never exposed. After a crash, the caller retries them.
        for part in self.root.glob('*.part'):
            part.unlink()
        known = {row[0] for row in self.db.execute('SELECT id FROM objects')}
        for blob in self.root.glob('*.blob'):
            if blob.stem not in known:
                blob.unlink()

    def config(self):
        return json.loads((self.root / 'server.json').read_text())

    def authorize(self, header):
        expected = self.config()['token_sha256']
        candidate = header.removeprefix('Bearer ') if header.startswith('Bearer ') else ''
        if not candidate or not hmac.compare_digest(hashlib.sha256(candidate.encode()).hexdigest(), expected):
            raise Problem(401, 'Access denied')

    def purge(self):
        with self.lock:
            expired = list(self.db.execute('SELECT id FROM objects WHERE deleted IS NOT NULL AND deleted <= ?', (self.clock() - DAY,)))
            for (identifier,) in expired:
                (self.root / (identifier + '.blob')).unlink(missing_ok=True)
                self.db.execute('DELETE FROM objects WHERE id=?', (identifier,))
            self.db.commit()

    def list(self):
        with self.lock:
            self.purge()
            rows = self.db.execute('SELECT id,size,sha,created,deleted FROM objects ORDER BY created DESC').fetchall()
            return dict(version=1, used=sum(r[1] for r in rows), quota=self.quota, objects=[dict(id=r[0],size=r[1],sha256=r[2],created=r[3],deleted=r[4],expires=r[4]+DAY if r[4] is not None else None) for r in rows])

    def put(self, identifier, stream, size, expected_sha):
        if not ID.fullmatch(identifier) or size < 1 or size > 128 * 1024**2 or not ID.fullmatch(expected_sha):
            raise Problem(400, 'Invalid object')
        with self.lock:
            self.purge()
            existing = self.db.execute('SELECT sha,deleted FROM objects WHERE id=?', (identifier,)).fetchone()
            if existing:
                if existing[0] != expected_sha or existing[1] is not None:
                    raise Problem(409, 'Object already exists or is in trash')
                return False
            used = self.db.execute('SELECT COALESCE(SUM(size),0) FROM objects').fetchone()[0]
            if used + size > self.quota:
                raise Problem(507, 'Storage quota reached')
            part = self.root / (identifier + '.part')
            sha = hashlib.sha256()
            try:
                with part.open('xb') as output:
                    remaining = size
                    while remaining:
                        chunk = stream.read(min(65536, remaining))
                        if not chunk:
                            raise Problem(400, 'Incomplete upload')
                        sha.update(chunk)
                        output.write(chunk)
                        remaining -= len(chunk)
                    output.flush()
                    os.fsync(output.fileno())
                if not hmac.compare_digest(sha.hexdigest(), expected_sha):
                    raise Problem(400, 'Checksum mismatch')
                os.replace(part, self.root / (identifier + '.blob'))
                self.db.execute('INSERT INTO objects VALUES(?,?,?,?,NULL)', (identifier,size,expected_sha,self.clock()))
                self.db.commit()
                return True
            finally:
                part.unlink(missing_ok=True)

    def change(self, identifier, restore=False):
        with self.lock:
            self.purge()
            row = self.db.execute('SELECT deleted FROM objects WHERE id=?', (identifier,)).fetchone()
            if row is None:
                raise Problem(404, 'Object not found')
            # Repeated delete must not reset the 24-hour deadline.
            if restore:
                self.db.execute('UPDATE objects SET deleted=NULL WHERE id=?', (identifier,))
            elif row[0] is None:
                self.db.execute('UPDATE objects SET deleted=? WHERE id=?', (self.clock(),identifier))
            self.db.commit()


def handler(store):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.0'

        def log_message(self, *args):
            pass  # Do not log tokens, paths or encrypted user payloads.

        def reply(self, status, data):
            body = json.dumps(data).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write(body)

        def dispatch(self):
            self.connection.settimeout(60)
            path = urlsplit(self.path).path
            try:
                if path == '/healthz' and self.command == 'GET':
                    self.reply(200, dict(status='ok',version=1))
                    return
                store.authorize(self.headers.get('Authorization',''))
                if path == '/v1/objects' and self.command == 'GET':
                    self.reply(200, store.list())
                    return
                if path == '/v1/recovery':
                    with store.lock:
                        recovery = store.root / 'recovery.enc'
                        if self.command == 'GET':
                            if not recovery.exists():
                                raise Problem(404, 'Vault not initialized')
                            self.reply(200, dict(envelope=recovery.read_text()))
                            return
                        if self.command == 'PUT':
                            if recovery.exists():
                                raise Problem(409, 'Vault already initialized')
                            size = int(self.headers.get('Content-Length','0'))
                            if size < 1 or size > 8192:
                                raise Problem(400, 'Invalid recovery envelope')
                            body = self.rfile.read(size)
                            if len(body) != size:
                                raise Problem(400, 'Incomplete envelope')
                            envelope = json.loads(body)['envelope']
                            if not isinstance(envelope,str) or not 16 <= len(envelope) <= 4096:
                                raise Problem(400, 'Invalid recovery envelope')
                            with recovery.open('x') as output:
                                output.write(envelope)
                                output.flush()
                                os.fsync(output.fileno())
                            self.reply(201, dict(saved=True))
                            return
                match = re.fullmatch(r'/v1/objects/([a-f0-9]{64})(/restore|/header)?', path)
                if match:
                    identifier, suffix = match.groups()
                    restore = suffix == '/restore'
                    if self.command == 'PUT' and suffix is None:
                        created = store.put(identifier,self.rfile,int(self.headers.get('Content-Length','0')),self.headers.get('X-Content-SHA256',''))
                        self.reply(201 if created else 200, dict(saved=True))
                        return
                    if self.command == 'DELETE' and suffix is None or self.command == 'POST' and restore:
                        store.change(identifier,bool(restore))
                        self.reply(200, dict(saved=True))
                        return
                    if self.command == 'GET' and not restore:
                        with store.lock:
                            store.purge()
                            row = store.db.execute('SELECT size,sha FROM objects WHERE id=?', (identifier,)).fetchone()
                            if row is None:
                                raise Problem(404,'Object not found')
                            with (store.root / (identifier+'.blob')).open('rb') as source:
                                length = row[0]
                                if suffix == '/header':
                                    prefix = source.read(4)
                                    if len(prefix) != 4:
                                        raise Problem(400,'Invalid envelope')
                                    length = int.from_bytes(prefix,'big') + 4
                                    if not 20 <= length <= min(row[0],1024*1024):
                                        raise Problem(400,'Invalid envelope')
                                    source.seek(0)
                                self.send_response(200)
                                self.send_header('Content-Type','application/octet-stream')
                                self.send_header('Content-Length',str(length))
                                self.send_header('X-Content-SHA256',row[1])
                                self.send_header('Cache-Control','no-store')
                                self.end_headers()
                                while length > 0:
                                    chunk = source.read(min(65536,length))
                                    if not chunk:
                                        break
                                    self.wfile.write(chunk)
                                    length -= len(chunk)
                        return
                raise Problem(404,'Not found')
            except Problem as error:
                self.reply(error.status, dict(error=error.message))
            except (ValueError, KeyError, json.JSONDecodeError):
                self.reply(400,dict(error='Invalid request'))
            except (OSError,sqlite3.Error):
                # No successful receipt if the disk or database failed.
                self.reply(503,dict(error='Storage unavailable'))

        do_GET = dispatch
        do_PUT = dispatch
        do_POST = dispatch
        do_DELETE = dispatch
    return Handler


def main():
    os.umask(0o077)
    store = Store(os.environ.get('VAULT_DATA','/data'),int(os.environ.get('VAULT_QUOTA',str(100*1024**3))))
    store.config()  # Refuse startup without an explicitly provisioned token.
    def sweep():
        while True:
            try:
                store.purge()
            except (OSError,sqlite3.Error):
                pass  # Retry after temporary storage failures; never silently stop expiry.
            time.sleep(1)
    threading.Thread(target=sweep,daemon=True).start()
    ThreadingHTTPServer(('0.0.0.0',8080),handler(store)).serve_forever()


if __name__ == '__main__':
    main()
