from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from settings import DEFAULT_PORT, SETTINGS_FILENAME, AppSettings, app_dir


class AppSettingsTests(unittest.TestCase):
    def setUp(self) -> None:
        self._temporary = tempfile.TemporaryDirectory()
        self.directory = Path(self._temporary.name)
        self.path = self.directory / "settings.json"

    def tearDown(self) -> None:
        self._temporary.cleanup()

    def test_defaults_when_file_missing(self) -> None:
        settings = AppSettings.load(self.path)
        self.assertEqual(settings, AppSettings())

    def test_round_trip_preserves_values(self) -> None:
        original = AppSettings(
            port=51234,
            typing_delay_ms=25,
            show_notifications=False,
            start_minimized=True,
            start_with_windows=True,
            theme="light",
        )
        original.save(self.path)
        self.assertEqual(AppSettings.load(self.path), original)

    def test_corrupt_json_returns_defaults(self) -> None:
        self.path.write_text("{not json", encoding="utf-8")
        self.assertEqual(AppSettings.load(self.path), AppSettings())

    def test_non_dict_json_returns_defaults(self) -> None:
        self.path.write_text("[1, 2, 3]", encoding="utf-8")
        self.assertEqual(AppSettings.load(self.path), AppSettings())

    def test_unknown_keys_ignored_and_missing_keys_defaulted(self) -> None:
        self.path.write_text('{"port": 48770, "bogus": 1}', encoding="utf-8")
        settings = AppSettings.load(self.path)
        self.assertEqual(settings.port, 48770)
        self.assertEqual(settings.theme, "dark")
        self.assertTrue(settings.show_notifications)

    def test_out_of_range_values_fall_back_to_defaults(self) -> None:
        self.path.write_text(
            '{"port": 80, "typing_delay_ms": 5000, "theme": "neon"}',
            encoding="utf-8",
        )
        settings = AppSettings.load(self.path)
        self.assertEqual(settings.port, DEFAULT_PORT)
        self.assertEqual(settings.typing_delay_ms, 0)
        self.assertEqual(settings.theme, "dark")

    def test_numeric_strings_are_accepted(self) -> None:
        self.path.write_text('{"port": "51234", "typing_delay_ms": "40"}', encoding="utf-8")
        settings = AppSettings.load(self.path)
        self.assertEqual(settings.port, 51234)
        self.assertEqual(settings.typing_delay_ms, 40)

    def test_boolean_values_coerce(self) -> None:
        self.path.write_text(
            '{"show_notifications": false, "start_minimized": true, "start_with_windows": "yes"}',
            encoding="utf-8",
        )
        settings = AppSettings.load(self.path)
        self.assertFalse(settings.show_notifications)
        self.assertTrue(settings.start_minimized)
        self.assertFalse(settings.start_with_windows)

    def test_save_creates_missing_directory_without_temp_leftovers(self) -> None:
        nested = self.directory / "nested" / "settings.json"
        AppSettings(port=52000).save(nested)
        self.assertTrue(nested.exists())
        leftovers = list(nested.parent.glob("*.tmp"))
        self.assertEqual(leftovers, [])

    def test_settings_path_lives_inside_app_dir(self) -> None:
        self.assertEqual(AppSettings.settings_path().parent, app_dir())
        self.assertEqual(AppSettings.settings_path().name, SETTINGS_FILENAME)

    def test_theme_is_case_insensitive(self) -> None:
        self.path.write_text('{"theme": "LIGHT"}', encoding="utf-8")
        self.assertEqual(AppSettings.load(self.path).theme, "light")


if __name__ == "__main__":
    unittest.main()
