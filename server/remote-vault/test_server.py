import hashlib
import io
import os
import json
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.request
import urllib.error
from http.server import ThreadingHTTPServer
from server import Store, Problem, handler, DAY


class StoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(dir=os.environ.get('VAULT_TEST_TMP'))
        self.now=1000
        self.store=Store(self.tmp.name,quota=128,clock=lambda:self.now)
        Path(self.tmp.name,'server.json').write_text(json.dumps(dict(token_sha256=hashlib.sha256(b'test-token').hexdigest())))
        self.identifier='a'*64
        self.data=b'opaque encrypted bytes'
        self.sha=hashlib.sha256(self.data).hexdigest()

    def tearDown(self):
        self.store.db.close()
        self.tmp.cleanup()

    def put(self):
        return self.store.put(self.identifier,io.BytesIO(self.data),len(self.data),self.sha)

    def test_retry_is_idempotent(self):
        self.assertTrue(self.put())
        self.assertFalse(self.put())
        self.assertEqual(len(self.store.list()['objects']),1)

    def test_partial_upload_never_committed(self):
        with self.assertRaises(Problem):
            self.store.put(self.identifier,io.BytesIO(b'short'),50,self.sha)
        self.assertEqual(self.store.list()['objects'],[])
        self.assertFalse(list(Path(self.tmp.name).glob('*.part')))

    def test_checksum_rejects_tampering(self):
        with self.assertRaises(Problem):
            self.store.put(self.identifier,io.BytesIO(self.data),len(self.data),'b'*64)
        self.assertFalse(list(Path(self.tmp.name).glob('*.blob')))

    def test_quota_includes_trash(self):
        self.put()
        self.store.change(self.identifier)
        with self.assertRaises(Problem) as raised:
            self.store.put('b'*64,io.BytesIO(b'x'*120),120,hashlib.sha256(b'x'*120).hexdigest())
        self.assertEqual(raised.exception.status,507)

    def test_trash_expires_at_24_hours(self):
        self.put()
        self.store.change(self.identifier)
        self.now += DAY-1
        self.assertEqual(len(self.store.list()['objects']),1)
        self.store.change(self.identifier)  # repeated delete must not extend retention
        self.now += 1
        self.assertEqual(self.store.list()['objects'],[])
        self.assertFalse(Path(self.tmp.name,self.identifier+'.blob').exists())
        with self.assertRaises(Problem):
            self.store.change(self.identifier,restore=True)

    def test_restore_before_expiry(self):
        self.put()
        self.store.change(self.identifier)
        self.now += DAY-1
        self.store.change(self.identifier,restore=True)
        self.now += DAY
        self.assertIsNone(self.store.list()['objects'][0]['deleted'])

    def test_access_token_and_path_validation(self):
        self.store.authorize('Bearer test-token')
        with self.assertRaises(Problem):
            self.store.authorize('Bearer wrong')
        with self.assertRaises(Problem):
            self.store.put('../escape',io.BytesIO(self.data),len(self.data),self.sha)

    def test_crash_orphans_removed(self):
        Path(self.tmp.name,'b'*64+'.part').write_bytes(b'partial')
        Path(self.tmp.name,'c'*64+'.blob').write_bytes(b'orphan')
        other=Store(self.tmp.name)
        other.db.close()
        self.assertFalse(list(Path(self.tmp.name).glob('*.part')))
        self.assertFalse(list(Path(self.tmp.name).glob('*.blob')))

    def test_header_endpoint_returns_only_encrypted_prefix(self):
        prefix=(32).to_bytes(4,'big')+b'x'*32
        self.data=prefix+b'opaque-image-stream'
        self.sha=hashlib.sha256(self.data).hexdigest()
        self.put()
        server=ThreadingHTTPServer(('127.0.0.1',0),handler(self.store))
        threading.Thread(target=server.serve_forever,daemon=True).start()
        try:
            req=urllib.request.Request(f'http://127.0.0.1:{server.server_port}/v1/objects/{self.identifier}/header',headers={'Authorization':'Bearer test-token'})
            with urllib.request.urlopen(req) as response:
                self.assertEqual(response.read(),prefix)
            self.store.change(self.identifier)
            self.now+=DAY
            with self.assertRaises(urllib.error.HTTPError) as raised:
                urllib.request.urlopen(req)
            self.assertEqual(raised.exception.code,404)
        finally:
            server.shutdown()
            server.server_close()

    def test_http_roundtrip_and_recovery_is_write_once(self):
        server=ThreadingHTTPServer(('127.0.0.1',0),handler(self.store))
        threading.Thread(target=server.serve_forever,daemon=True).start()
        base=f'http://127.0.0.1:{server.server_port}'
        def request(method,path,data=None,authenticated=True):
            headers={'Authorization':'Bearer test-token'} if authenticated else {}
            headers['X-Content-SHA256']=self.sha
            with urllib.request.urlopen(urllib.request.Request(base+path,data=data,headers=headers,method=method)) as response:
                return response.status,response.read()
        try:
            with self.assertRaises(urllib.error.HTTPError) as raised:
                request('GET','/v1/objects',authenticated=False)
            self.assertEqual(raised.exception.code,401)
            self.assertEqual(request('PUT','/v1/objects/'+self.identifier,self.data)[0],201)
            self.assertEqual(request('GET','/v1/objects/'+self.identifier)[1],self.data)
            recovery=json.dumps(dict(envelope='encrypted-envelope-value')).encode()
            self.assertEqual(request('PUT','/v1/recovery',recovery)[0],201)
            with self.assertRaises(urllib.error.HTTPError) as raised:
                request('PUT','/v1/recovery',recovery)
            self.assertEqual(raised.exception.code,409)
            request('DELETE','/v1/objects/'+self.identifier)
            request('POST','/v1/objects/'+self.identifier+'/restore',b'')
        finally:
            server.shutdown()
            server.server_close()


if __name__=='__main__':
    unittest.main()
