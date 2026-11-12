from __future__ import annotations

import http.client
import json
import ssl
import tempfile
import unittest
from pathlib import Path

import speaktype as helper
from settings import AppSettings


def _tls_connection(port: int) -> http.client.HTTPSConnection:
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    return http.client.HTTPSConnection("127.0.0.1", port, context=context, timeout=5)


class TextEndpointTests(unittest.TestCase):
    """The phone recognizes speech itself; the PC only previews and types text."""

    def setUp(self) -> None:
        self._temporary = tempfile.TemporaryDirectory()
        self.typed: list[str] = []
        self.original_type = helper.type_unicode_text

        def fake_type(text: str, delay_ms: float = 0.0) -> int:
            self.typed.append(text)
            return len(text.encode("utf-16-le"))

        helper.type_unicode_text = fake_type

    def tearDown(self) -> None:
        helper.type_unicode_text = self.original_type
        self._temporary.cleanup()

    def _make_service(self) -> helper.SpeakTypeService:
        settings = AppSettings(port=0)
        service = helper.SpeakTypeService(
            settings,
            app_dir_path=Path(self._temporary.name),
            release_sources=(),
        )
        service.start()
        self.addCleanup(service.stop)
        return service

    @staticmethod
    def _port_of(service: helper.SpeakTypeService) -> int:
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

    def _paired_service(self) -> tuple[helper.SpeakTypeService, int, str]:
        service = self._make_service()
        port = self._port_of(service)
        pairing = service.new_pairing()
        status, payload = self._request(port, "POST", "/v1/pair", body={"code": pairing["code"]})
        self.assertEqual(status, 200)
        return service, port, payload["session_token"]

    def test_health_no_longer_reports_a_model(self) -> None:
        service = self._make_service()
        status, payload = self._request(self._port_of(service), "GET", "/v1/health")
        self.assertEqual(status, 200)
        self.assertNotIn("model", payload)

    def test_audio_endpoints_are_gone(self) -> None:
        _, port, token = self._paired_service()
        for path in ("/v1/audio/start", "/v1/audio/chunk", "/v1/audio/end", "/v1/audio/cancel"):
            status, payload = self._request(port, "POST", path, body={"session": "s1"}, token=token)
            self.assertEqual(status, 404)
            self.assertEqual(payload["error"], "not_found")

    def test_preview_applies_roman_output(self) -> None:
        service, port, token = self._paired_service()
        status, _ = self._request(
            port, "POST", "/v1/preview",
            body={"text": "আমি বাংলা বলি", "output": "roman"}, token=token,
        )
        self.assertEqual(status, 200)
        self.assertEqual(service.state.preview_snapshot()[0], "ami bangla boli")
        self.assertEqual(self.typed, [])

    def test_preview_rejects_unknown_output(self) -> None:
        _, port, token = self._paired_service()
        status, payload = self._request(
            port, "POST", "/v1/preview", body={"text": "hi", "output": "klingon"}, token=token
        )
        self.assertEqual(status, 400)
        self.assertEqual(payload["error"], "invalid_text")

    def test_send_applies_roman_output(self) -> None:
        _, port, token = self._paired_service()
        status, payload = self._request(
            port, "POST", "/v1/send",
            body={"id": "m1", "text": "আমি বাংলা বলি", "output": "roman"}, token=token,
        )
        self.assertEqual(status, 200)
        self.assertFalse(payload["duplicate"])
        self.assertEqual(self.typed, ["ami bangla boli"])

    def test_send_english_output_normalizes(self) -> None:
        _, port, token = self._paired_service()
        status, _ = self._request(
            port, "POST", "/v1/send",
            body={"id": "m2", "text": "hello   world", "output": "english"}, token=token,
        )
        self.assertEqual(status, 200)
        self.assertEqual(self.typed, ["Hello world"])

    def test_send_native_keeps_bengali_script(self) -> None:
        _, port, token = self._paired_service()
        status, _ = self._request(
            port, "POST", "/v1/send",
            body={"id": "m3", "text": "আমি বাংলা বলি"}, token=token,
        )
        self.assertEqual(status, 200)
        self.assertEqual(self.typed, ["আমি বাংলা বলি"])

    def test_send_rejects_unknown_output(self) -> None:
        _, port, token = self._paired_service()
        status, payload = self._request(
            port, "POST", "/v1/send",
            body={"id": "m4", "text": "hi", "output": "klingon"}, token=token,
        )
        self.assertEqual(status, 400)
        self.assertEqual(payload["error"], "invalid_text")
        self.assertEqual(self.typed, [])

    def test_zero_insertion_is_failure_and_retryable(self) -> None:
        _, port, token = self._paired_service()
        helper.type_unicode_text = lambda text, delay_ms=0.0: 0
        status, payload = self._request(
            port, "POST", "/v1/send", body={"id": "z1", "text": "hello"}, token=token
        )
        self.assertEqual(status, 500)
        self.assertEqual(payload["error"], "input_failed")
        def retry_type(text: str, delay_ms: float = 0.0) -> int:
            self.typed.append(text)
            return len(text.encode("utf-16-le"))

        helper.type_unicode_text = retry_type
        status, payload = self._request(
            port, "POST", "/v1/send", body={"id": "z1", "text": "hello"}, token=token
        )
        self.assertEqual(status, 200)
        self.assertEqual(self.typed, ["hello"])

    def test_send_composes_decomposed_bangla(self) -> None:
        _, port, token = self._paired_service()
        status, _ = self._request(
            port, "POST", "/v1/send",
            body={"id": "m5", "text": "ড়", "output": "roman"}, token=token,
        )
        self.assertEqual(status, 200)
        self.assertEqual(self.typed, ["r"])


if __name__ == "__main__":
    unittest.main()