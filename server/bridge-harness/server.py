#!/usr/bin/env python3
"""Loopback-only fictional Bridge for Android contract/UI tests. Never runs Forge or provider requests."""
import argparse
import copy
import hashlib
import json
import re
import secrets
import socket
import threading
import time
import uuid
from datetime import datetime, timezone, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit
from schema import validate

ROOT = Path(__file__).resolve().parents[2]
SPEC = json.loads((ROOT / "docs/bridge/openapi.json").read_text())
FULL = json.loads((ROOT / "docs/bridge/capabilities-full.json").read_text())
SID, DID, MID, EID, BID = [f"00000000-0000-4000-8000-{i:012d}" for i in range(1, 6)]
SCOPES = ["status.read", "models.read", "models.manage", "downloads.manage", "civitai.search", "extensions.read", "extensions.manage", "server.manage", "devices.manage"]
READ = {"status", "models.count", "models.catalog", "extensions.list", "devices.me", "operations.read"}
PRIVATE = {"models.catalog", "models.scan", "models.move", "models.delete", "downloads.create", "downloads.cancel", "downloads.retry", "civitai.download"}


def now():
    return datetime.now(timezone.utc).isoformat()


class State:
    def __init__(self, scenario, credential_file):
        self.scenario = scenario
        self.lock = threading.RLock()
        self.token, self.code = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
        self.invitation_id = str(uuid.uuid4())
        self.credential_file = credential_file
        self.requests, self.operations, self.idempotency = [], {}, {}
        self.exchange_key = None
        self.exchange_body = None
        self.drop_next = False
        self.delay = 0
        self.revoked = False
        self.device = {"id": DID, "name": "Synthetic Android phone", "scopes": list(SCOPES), "created_at": now(), "revoked_at": None}
        if scenario == "read-only": self.device["scopes"] = ["status.read", "models.read", "extensions.read"]
        self.extension = {"id": EID, "name": "Example Tools", "version": "1.0.0", "builtin": False, "protected": False,
                          "current_enabled": True, "next_enabled": True, "installed": True, "restart_required": False}
        self.model = {"id": MID, "type": "checkpoint", "name": "Example XL.safetensors", "root_id": "checkpoint-default", "size_bytes": 1024,
                      "metadata": {}, "source": None}

    def caps(self):
        caps = copy.deepcopy(FULL)
        if self.scenario == "read-only":
            for key, value in caps["features"].items():
                if key not in READ:
                    value.update(authorized=False, reason_code="SCOPE_REQUIRED")
        elif self.scenario == "count-only":
            caps["privacy_policy"] = "count_only"
            for key in PRIVATE:
                caps["features"][key].update(enabled=False, reason_code="PRIVACY_POLICY")
        elif self.scenario == "missing-flag":
            caps["required_flags"]["enable_insecure_extension_access"] = False
            for key in caps["features"]:
                if key.startswith("extensions.") and key != "extensions.list":
                    caps["features"][key].update(enabled=False, reason_code="FLAG_REQUIRED")
        elif self.scenario == "civitai-disabled":
            for key in ["civitai.search", "civitai.download"]:
                caps["features"][key].update(enabled=False, reason_code="NOT_CONFIGURED")
        return caps

    def info(self):
        return {"bridge_id": "other" if self.scenario == "wrong-id" else "forgegen-bridge", "bridge_version": "1.0.0",
                "api_version": {"major": 2 if self.scenario == "incompatible" else 1, "minor": 0},
                "server_id": str(uuid.UUID(int=99)) if self.scenario == "server-changed" else SID,
                "authentication": "bearer-device-token", "pairing": "administrator-issued-single-use-invitation",
                "openapi_url": "/forgegen/v1/openapi.json"}

    def save_credentials(self, base):
        invitation = {"format": "forgegen-pairing", "format_version": 1, "server_url": base, "server_id": SID,
                      "invitation_id": self.invitation_id, "code": self.code,
                      "expires_at": (datetime.now(timezone.utc) + timedelta(minutes=10)).isoformat()}
        self.credential_file.write_text(json.dumps({"token": self.token, "server_id": SID, "device": self.device, "invitation": invitation}))
        self.credential_file.chmod(0o600)


def handler(state):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_):
            pass

        def reply(self, status, value, response_schema=None):
            if response_schema and value is not None:
                validate(value, response_schema, SPEC)
            data = json.dumps(value).encode() if value is not None else b""
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Request-ID", str(uuid.uuid4()))
            self.send_header("X-ForgeGen-API-Version", "1.0")
            if status == 204:
                self.send_header("Allow", "GET, POST, PATCH, DELETE, OPTIONS")
            self.end_headers()
            try:
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def error(self, status, code):
            return self.reply(status, {"error": {"code": code, "message": "Synthetic Bridge response.", "retryable": status >= 500}, "request_id": str(uuid.uuid4())})

        def dispatch(self):
            path, query = urlsplit(self.path).path, parse_qs(urlsplit(self.path).query)
            raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            body = json.loads(raw) if raw else None
            method = self.command.lower()
            # Explicitly test-only controls on the loopback harness, never added to the app or real Bridge.
            if path == "/__harness/requests":
                return self.reply(200, {"items": state.requests, "operations": len(state.operations)})
            if path == "/__harness/control":
                if "scenario" in query: state.scenario = query["scenario"][0]
                if "drop" in query: state.drop_next = True
                if "delay" in query: state.delay = float(query["delay"][0])
                if "revoke" in query: state.revoked = True
                return self.reply(200, {})
            state.requests.append({"method": self.command, "path": path, "query": query, "body": body,
                                   "key": self.headers.get("Idempotency-Key"),
                                   "version": self.headers.get("X-ForgeGen-API-Version"),
                                   "bearer": self.headers.get("Authorization") == "Bearer " + state.token})
            if path == "/sdapi/v1/progress":
                return self.reply(200, {"progress": 0, "eta_relative": 0, "state": {"job_count": 0}})
            if path == "/sdapi/v1/txt2img":
                return self.reply(200, {"images": [], "info": "{}"})
            if not path.startswith("/forgegen/v1/"):
                return self.error(404, "ENDPOINT_NOT_FOUND")
            if state.scenario in ("absent", "html"):
                if state.scenario == "html":
                    self.send_response(200); self.send_header("Content-Type", "text/html"); self.send_header("Content-Length", "13"); self.end_headers(); self.wfile.write(b"<html></html>"); return
                return self.error(404, "ENDPOINT_NOT_FOUND")
            if state.scenario == "timeout": time.sleep(2)
            if state.delay: time.sleep(state.delay)
            template, operation = None, None
            for candidate, methods in SPEC["paths"].items():
                if re.fullmatch(re.sub(r"\{[^}]+\}", "[^/]+", candidate), path):
                    template = candidate
                    operation = methods.get(method)
                    break
            if method == "options": return self.reply(204, None)
            if operation is None: return self.error(404 if template is None else 405, "ENDPOINT_NOT_FOUND")
            if self.headers.get("X-ForgeGen-API-Version", "1.0") != "1.0": return self.error(426, "INCOMPATIBLE_PROTOCOL")
            public = path.endswith(("/info", "/openapi.json", "/pairing/exchange"))
            if not public:
                if state.revoked or state.scenario == "revoked": return self.error(401, "TOKEN_REVOKED")
                if self.headers.get("Authorization") != "Bearer " + state.token: return self.error(401, "AUTH_REQUIRED")
            mutating = method in ("post", "patch", "delete")
            key = self.headers.get("Idempotency-Key")
            if mutating and not key: return self.error(400, "INVALID_REQUEST")
            if method in ("get", "delete", "options") and raw: return self.error(422, "INVALID_REQUEST")
            request_schema = operation.get("requestBody", {}).get("content", {}).get("application/json", {}).get("schema")
            if request_schema:
                try: validate(body, request_schema, SPEC)
                except (AssertionError, ValueError, TypeError): return self.error(422, "INVALID_REQUEST")
            elif raw: return self.error(422, "INVALID_REQUEST")
            fingerprint = hashlib.sha256(json.dumps([self.command, self.path, body], sort_keys=True).encode()).hexdigest()
            if mutating and key in state.idempotency:
                previous, status, result = state.idempotency[key]
                if previous != fingerprint: return self.error(409, "IDEMPOTENCY_CONFLICT")
                return self.reply(status, result)
            status = int(next(k for k in operation["responses"] if k.startswith("2")))
            schema = operation["responses"][str(status)].get("content", {}).get("application/json", {}).get("schema")
            short = path.removeprefix("/forgegen/v1/")
            capability = None
            if short == "info":
                value = state.info()
                schema = None if state.scenario in ("wrong-id", "incompatible") else schema
            elif short == "openapi.json": value = SPEC
            elif short == "pairing/exchange":
                if body["invitation_id"] != state.invitation_id or body["code"] != state.code: return self.error(401, "PAIRING_INVALID")
                if state.exchange_key and state.exchange_key != key: return self.error(401, "PAIRING_INVALID")
                state.exchange_key = key
                state.device["name"] = body["device_name"]
                value = {"device": state.device, "token_type": "Bearer", "access_token": state.token}
            elif short == "devices/me": value = dict(state.device)
            elif short == "capabilities": value = state.caps()
            elif short.startswith("devices/") and method == "delete":
                value = dict(state.device, revoked_at=now())
                if short.split("/")[-1] == DID: state.revoked = True
            elif short == "devices": value = [state.device]
            elif short == "models/roots": value = [{"id": "checkpoint-default", "type": "checkpoint"}, {"id": "lora-default", "type": "lora"}]
            elif short == "models/counts": value = [{"root_id": "checkpoint-default", "type": "checkpoint", "count": 24, "recursive": False, "reason_code": None}]
            elif short == "models" and method == "get": capability = "models.catalog"; value = [state.model]
            elif short.startswith("models/") and method == "get": capability = "models.catalog"; value = state.model
            elif short == "extensions" and method == "get":
                value = [dict(state.extension, id=BID, name="ForgeGen Bridge", protected=True), state.extension]
            elif short == "server/status":
                value = {"busy": False, "phase": "idle", "progress": None, "elapsed_seconds": None,
                         "memory": {"vram_used_bytes": 4509715660, "vram_total_bytes": 12884901888}, "recent_errors": [], "backend": "synthetic"}
            elif short.startswith("civitai/") and method == "get":
                capability = "civitai.search"
                version = {"id": 200, "model_id": 100, "name": "v2.0", "base_model": "SDXL", "files": [{"id": 300, "name": "Example XL.safetensors", "type": "Model", "size_kb": 1024.0, "sha256": "a" * 64}]}
                model = {"id": 100, "name": "Landscape Study XL", "type": "Checkpoint", "creator": "Example Creator", "license": {"description": "Synthetic example"},
                         "source_url": "https://civitai.com/models/100", "versions": [version]}
                value = version if short.startswith("civitai/versions/") else model if short != "civitai/models" else [model]
            elif (short == "operations" or short == "downloads") and method == "get": value = list(state.operations.values())
            elif (short.startswith("operations/") or short.startswith("downloads/")) and method == "get":
                identifier = short.split("/")[1]
                if identifier not in state.operations: return self.error(404, "OPERATION_NOT_FOUND")
                value = copy.deepcopy(state.operations[identifier])
                if value["status"] == "queued": state.operations[identifier].update(status="running", phase="working", progress=0.5)
                elif value["status"] == "running": state.operations[identifier].update(status="completed", phase="finished", progress=1.0)
            else:
                if short.startswith("models/"): capability = "models.scan" if short.endswith("scan") else "models.move" if short.endswith("move") else "models.delete"
                elif short == "downloads": capability = "downloads.create"
                elif short == "civitai/downloads": capability = "civitai.download"
                elif short.startswith("downloads/"): capability = "downloads.retry" if short.endswith("retry") else "downloads.cancel"
                elif short.startswith("server/"): capability = "server." + short.split("/")[-1]
                elif short.startswith("extensions"):
                    capability = "extensions.install" if short == "extensions" else "extensions.bulk" if short.endswith("bulk") else "extensions.toggle" if method == "patch" else "extensions.uninstall"
                    if BID in short or BID in (body or {}).get("extension_ids", []): return self.error(403, "BRIDGE_PROTECTED")
                elif short.startswith("operations/"): capability = "operations.cancel"
                feature = state.caps()["features"].get(capability)
                if feature and not all(feature[k] for k in ("supported", "enabled", "authorized")): return self.error(403, feature["reason_code"] or "SCOPE_REQUIRED")
                identifier = short.split("/")[1] if short.startswith(("downloads/", "operations/")) else str(uuid.uuid4())
                if short.startswith(("downloads/", "operations/")):
                    if identifier not in state.operations: return self.error(404, "OPERATION_NOT_FOUND")
                    if method == "delete": state.operations[identifier].update(status="cancelled", phase="cancelled", progress=None)
                    else: state.operations[identifier].update(status="queued", phase="queued", progress=None)
                else:
                    kind = "download.create" if short in ("downloads", "civitai/downloads") else capability or "server.operation"
                    state.operations[identifier] = {"id": identifier, "kind": kind, "status": "queued", "phase": "queued", "progress": None,
                        "bytes_done": None, "bytes_total": None, "created_at": now(), "updated_at": now(), "result": None, "error": None,
                        "restart_required": short.startswith("extensions")}
                    if short == "server/restart": state.operations[identifier]["result"] = {"restart_requested": True, "requires_reconnect": True}
                    if method == "patch": state.extension.update(next_enabled=body["enabled"], restart_required=True)
                value = {"operation_id": identifier, "status_url": "/forgegen/v1/operations/" + identifier}
            feature = state.caps()["features"].get(capability)
            if feature and not all(feature[k] for k in ("supported", "enabled", "authorized")): return self.error(403, feature["reason_code"] or "SCOPE_REQUIRED")
            if isinstance(value, list):
                offset, limit = int(query.get("offset", [0])[0]), int(query.get("limit", [20 if short == "civitai/models" else 50])[0])
                if not 1 <= limit <= 100 or offset < 0 or (short == "civitai/models" and offset % limit): return self.error(400, "INVALID_REQUEST")
                value = {"items": value[offset:offset + limit], "offset": offset, "limit": limit, "total": None if short == "civitai/models" else len(value)}
            if mutating:
                state.idempotency[key] = (fingerprint, status, value)
                if state.drop_next:
                    state.drop_next = False
                    self.connection.shutdown(socket.SHUT_RDWR)
                    self.connection.close()
                    return
            return self.reply(status, value, schema)

        def do_GET(self): self.safe_dispatch()
        def do_POST(self): self.safe_dispatch()
        def do_PATCH(self): self.safe_dispatch()
        def do_DELETE(self): self.safe_dispatch()
        def do_OPTIONS(self): self.safe_dispatch()

        def safe_dispatch(self):
            try:
                with state.lock: self.dispatch()
            except (BrokenPipeError, ConnectionResetError):
                pass
            except Exception:
                self.error(500, "HARNESS_ERROR")
    return Handler


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--scenario", default="full", choices=["full", "read-only", "count-only", "missing-flag", "civitai-disabled", "revoked", "absent", "html", "timeout", "incompatible", "server-changed", "wrong-id"])
    parser.add_argument("--credential-file", type=Path, required=True)
    args = parser.parse_args()
    state = State(args.scenario, args.credential_file)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), handler(state))
    state.save_credentials(f"http://127.0.0.1:{server.server_port}")
    print(json.dumps({"port": server.server_port}), flush=True)
    server.serve_forever()
