from __future__ import annotations

import hashlib
import http.client
import json
import os
import socket
import ssl
import tempfile
import threading
import unittest
import zipfile
from pathlib import Path

import voice_to_text_helper as helper
from settings import AppSettings


def _tls_connection(port: int) -> http.client.HTTPSConnection:
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    return http.client.HTTPSConnection("127.0.0.1", port, context=context, timeout=5)


class ServiceTests(unittest.TestCase):
    def setUp(self) -> None:
        self._temporary = tempfile.TemporaryDirectory()
        self.original_type = helper.type_unicode_text
        self.original_desktop_check = helper.on_interactive_desktop
        self.original_foreground = helper.ctypes.windll.user32.GetForegroundWindow
        self.typed: list[tuple[str, float]] = []

        helper.type_unicode_text = lambda text, delay_ms=0.0: self._fake_type(text, delay_ms)
        helper.on_interactive_desktop = lambda: True
        try:
            helper.ctypes.windll.user32.GetForegroundWindow = lambda: 1
        except Exception:  # pragma: no cover - depends on the host
            self.skipTest("cannot stub GetForegroundWindow on this machine")

    def tearDown(self) -> None:
        helper.type_unicode_text = self.original_type
        helper.on_interactive_desktop = self.original_desktop_check
        helper.ctypes.windll.user32.GetForegroundWindow = self.original_foreground
        self._temporary.cleanup()

    def _fake_type(self, text: str, delay_ms: float = 0.0) -> int:
        self.typed.append((text, delay_ms))
        return len(text.encode("utf-16-le"))

    def _make_service(self, **overrides) -> helper.VoiceHelperService:
        settings = AppSettings(port=0, **overrides)
        service = helper.VoiceHelperService(
            settings,
            app_dir_path=Path(self._temporary.name),
            release_sources=(),  # no build detection; tests publish the APK explicitly
        )
        service.start()
        self.addCleanup(service.stop)
        return service

    @staticmethod
    def _port_of(service: helper.VoiceHelperService) -> int:
        return service.server.server_address[1]

    def _request(
        self, port: int, method: str, path: str, body: dict | None = None, token: str | None = None
    ) -> tuple[int, dict]:
        connection = _tls_connection(port)
        try:
            headers = {"Content-Type": "application/json"} if body is not None else {}
            if token:
                headers["Authorization"] = f"Bearer {token}"
            connection.request(
                method, path, body=json.dumps(body) if body is not None else None, headers=headers
            )
            response = connection.getresponse()
            raw = response.read()
            payload = json.loads(raw.decode("utf-8")) if raw else {}
            return response.status, payload
        finally:
            connection.close()

    def test_service_starts_and_health_reports_hostname(self) -> None:
        service = self._make_service()
        status, payload = self._request(self._port_of(service), "GET", "/v1/health")
        self.assertEqual(status, 200)
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["hostname"], socket.gethostname())
        self.assertFalse(payload["paired"])

    def test_send_requires_pairing(self) -> None:
        service = self._make_service()
        status, payload = self._request(
            self._port_of(service), "POST", "/v1/send", body={"id": "m1", "text": "hello"}
        )
        self.assertEqual(status, 401)
        self.assertEqual(payload["error"], "not_paired")

    def test_pairing_then_send_types_text(self) -> None:
        service = self._make_service()
        port = self._port_of(service)
        pairing = service.new_pairing()
        status, payload = self._request(port, "POST", "/v1/pair", body={"code": pairing["code"]})
        self.assertEqual(status, 200)
        token = payload["session_token"]
        self.assertTrue(service.paired())

        status, payload = self._request(
            port, "POST", "/v1/send", body={"id": "m1", "text": "hello world"}, token=token
        )
        self.assertEqual(status, 200)
        self.assertFalse(payload["duplicate"])
        self.assertEqual(self.typed, [("hello world", 0)])
        self.assertEqual(service.state.delivered_ids["m1"], "done")

    def test_pairing_code_is_single_use(self) -> None:
        service = self._make_service()
        port = self._port_of(service)
        pairing = service.new_pairing()
        self._request(port, "POST", "/v1/pair", body={"code": pairing["code"]})
        status, _ = self._request(port, "POST", "/v1/pair", body={"code": pairing["code"]})
        self.assertEqual(status, 401)
        fresh = service.new_pairing()
        status, _ = self._request(port, "POST", "/v1/pair", body={"code": fresh["code"]})
        self.assertEqual(status, 200)

    def test_duplicate_send_is_acknowledged_not_retried(self) -> None:
        service = self._make_service()
        port = self._port_of(service)
        pairing = service.new_pairing()
        _, payload = self._request(port, "POST", "/v1/pair", body={"code": pairing["code"]})
        token = payload["session_token"]
        body = {"id": "dup-1", "text": "once"}
        self._request(port, "POST", "/v1/send", body=body, token=token)
        status, payload = self._request(port, "POST", "/v1/send", body=body, token=token)
        self.assertEqual(status, 200)
        self.assertTrue(payload["duplicate"])
        self.assertEqual(len(self.typed), 1)

    def test_invalid_text_is_rejected(self) -> None:
        service = self._make_service()
        port = self._port_of(service)
        pairing = service.new_pairing()
        _, payload = self._request(port, "POST", "/v1/pair", body={"code": pairing["code"]})
        token = payload["session_token"]

        cases = [
            {"id": "big", "text": "x" * (helper.MAX_TEXT + 1)},
            {"id": "blank", "text": "   "},
            {"text": "no id"},
        ]
        for body in cases:
            status, _ = self._request(port, "POST", "/v1/send", body=body, token=token)
            self.assertEqual(status, 400, f"expected 400 for {body!r}")

    def test_public_addresses_are_refused(self) -> None:
        self.assertTrue(helper.client_is_private("192.168.1.50"))
        self.assertTrue(helper.client_is_private("127.0.0.1"))
        self.assertFalse(helper.client_is_private("8.8.8.8"))
        self.assertFalse(helper.client_is_private("not-an-ip"))

    def test_unpair_forgets_token(self) -> None:
        service = self._make_service()
        port = self._port_of(service)
        pairing = service.new_pairing()
        _, payload = self._request(port, "POST", "/v1/pair", body={"code": pairing["code"]})
        token = payload["session_token"]
        service.unpair()
        self.assertFalse(service.paired())
        status, _ = self._request(
            port, "POST", "/v1/send", body={"id": "after-unpair", "text": "hi"}, token=token
        )
        self.assertEqual(status, 401)

    def test_typing_delay_is_forwarded(self) -> None:
        service = self._make_service(typing_delay_ms=25)
        port = self._port_of(service)
        pairing = service.new_pairing()
        _, payload = self._request(port, "POST", "/v1/pair", body={"code": pairing["code"]})
        token = payload["session_token"]
        status, _ = self._request(
            port, "POST", "/v1/send", body={"id": "slow", "text": "slow typing"}, token=token
        )
        self.assertEqual(status, 200)
        self.assertEqual(self.typed, [("slow typing", 25)])

    def test_fingerprint_is_stable_sha256_hex(self) -> None:
        service = self._make_service()
        fingerprint = service.fingerprint()
        self.assertEqual(len(fingerprint), 64)
        int(fingerprint, 16)

    def _request_bytes(self, port: int, path: str, token: str | None = None) -> bytes:
        connection = _tls_connection(port)
        try:
            headers = {"Authorization": f"Bearer {token}"} if token else {}
            connection.request("GET", path, headers=headers)
            response = connection.getresponse()
            self.assertEqual(response.status, 200)
            return response.read()
        finally:
            connection.close()

    def test_app_release_requires_pairing(self) -> None:
        service = self._make_service()
        status, payload = self._request(self._port_of(service), "GET", "/v1/app-release")
        self.assertEqual(status, 401)
        self.assertEqual(payload["error"], "not_paired")

    def test_app_release_reports_hash_and_serves_the_apk(self) -> None:
        service = self._make_service()
        port = self._port_of(service)
        pairing = service.new_pairing()
        _, payload = self._request(port, "POST", "/v1/pair", body={"code": pairing["code"]})
        token = payload["session_token"]

        status, payload = self._request(port, "GET", "/v1/app-release", token=token)
        self.assertEqual(status, 404)
        self.assertEqual(payload["error"], "no_app_release")

        apk_bytes = b"PK\x03\x04fake-apk-payload"
        service.state.app_release_path.write_bytes(apk_bytes)

        status, payload = self._request(port, "GET", "/v1/app-release", token=token)
        self.assertEqual(status, 200)
        self.assertEqual(payload["size"], len(apk_bytes))
        self.assertEqual(payload["sha256"], hashlib.sha256(apk_bytes).hexdigest())

        self.assertEqual(self._request_bytes(port, "/v1/app.apk", token=token), apk_bytes)

    def test_preview_requires_pairing(self) -> None:
        service = self._make_service()
        status, payload = self._request(
            self._port_of(service), "POST", "/v1/preview", body={"text": "hel"}
        )
        self.assertEqual(status, 401)
        self.assertEqual(payload["error"], "not_paired")

    def test_preview_updates_state_without_typing(self) -> None:
        service = self._make_service()
        port = self._port_of(service)
        pairing = service.new_pairing()
        _, payload = self._request(port, "POST", "/v1/pair", body={"code": pairing["code"]})
        token = payload["session_token"]

        status, _ = self._request(
            port, "POST", "/v1/preview", body={"text": "hello wor"}, token=token
        )
        self.assertEqual(status, 200)
        text, age = service.state.preview_snapshot()
        self.assertEqual(text, "hello wor")
        self.assertLess(age, 5)
        self.assertEqual(self.typed, [])

        status, _ = self._request(port, "POST", "/v1/preview", body={"text": ""}, token=token)
        self.assertEqual(status, 200)
        self.assertEqual(service.state.preview_snapshot()[0], "")

    def test_final_send_clears_the_preview(self) -> None:
        service = self._make_service()
        port = self._port_of(service)
        pairing = service.new_pairing()
        _, payload = self._request(port, "POST", "/v1/pair", body={"code": pairing["code"]})
        token = payload["session_token"]
        self._request(port, "POST", "/v1/preview", body={"text": "hello"}, token=token)
        self._request(port, "POST", "/v1/send", body={"id": "p1", "text": "hello"}, token=token)
        self.assertEqual(service.state.preview_snapshot()[0], "")
        self.assertEqual(self.typed, [("hello", 0)])


    @staticmethod
    def _write_apk(path: Path, tag: bytes = b"a") -> bytes:
        """A minimal but structurally valid APK: ZIP header, entries, and a central directory."""
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("AndroidManifest.xml", b"manifest-" + tag)
            archive.writestr("classes.dex", tag * (80 * 1024))
        return path.read_bytes()

    def _make_publishing_service(self, sources) -> helper.VoiceHelperService:
        app_dir = Path(self._temporary.name) / "app"
        service = helper.VoiceHelperService(
            AppSettings(port=0), app_dir_path=app_dir, release_sources=tuple(sources)
        )
        self.addCleanup(service.stop)
        return service

    def test_newest_build_is_published_without_publish_apk(self) -> None:
        build = Path(self._temporary.name) / "build" / "app-debug.apk"
        payload = self._write_apk(build)
        service = self._make_publishing_service([build])

        self.assertTrue(service.publish_newest_build())
        served = service.state.app_release_path.read_bytes()
        self.assertEqual(served, payload)
        self.assertEqual(
            service.state.app_release()["sha256"], hashlib.sha256(payload).hexdigest()
        )

        # Nothing changed: the next poll must stay quiet and not rewrite the file.
        before = service.state.app_release_path.stat().st_mtime_ns
        self.assertFalse(service.publish_newest_build())
        self.assertEqual(service.state.app_release_path.stat().st_mtime_ns, before)

    def test_newer_build_replaces_the_served_apk(self) -> None:
        build = Path(self._temporary.name) / "build" / "app-debug.apk"
        first = self._write_apk(build, b"a")
        service = self._make_publishing_service([build])
        service.publish_newest_build()

        second = self._write_apk(build, b"b")
        self.assertTrue(service.publish_newest_build())
        self.assertNotEqual(first, second)
        self.assertEqual(service.state.app_release_path.read_bytes(), second)

    def test_truncated_build_is_ignored(self) -> None:
        build = Path(self._temporary.name) / "build" / "app-debug.apk"
        build.parent.mkdir(parents=True)
        build.write_bytes(b"PK\x03\x04" + b"x" * 200)
        service = self._make_publishing_service([build])

        self.assertFalse(service.publish_newest_build())
        self.assertIsNone(service.state.app_release())

    def test_build_detection_can_be_disabled(self) -> None:
        build = Path(self._temporary.name) / "build" / "app-debug.apk"
        self._write_apk(build)
        service = self._make_publishing_service([])

        self.assertFalse(service.publish_newest_build())
        self.assertIsNone(service.state.app_release())

    def test_newest_of_several_sources_wins(self) -> None:
        older = Path(self._temporary.name) / "older.apk"
        newer = Path(self._temporary.name) / "newer.apk"
        self._write_apk(older, b"o")
        payload = self._write_apk(newer, b"n")
        os.utime(older, (1_900_000_000, 1_900_000_000))
        os.utime(newer, (2_000_000_000, 2_000_000_000))

        service = self._make_publishing_service([older, newer])
        self.assertTrue(service.publish_newest_build())
        self.assertEqual(service.state.app_release_path.read_bytes(), payload)


if __name__ == "__main__":
    unittest.main()
