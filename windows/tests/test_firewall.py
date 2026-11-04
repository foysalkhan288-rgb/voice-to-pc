from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest import mock

import speaktype as helper


class FirewallTests(unittest.TestCase):
    def test_comparable_path_unifies_separators_and_case(self):
        self.assertEqual(helper._comparable_path(r"C:\Apps/SpeakType.EXE"), r"c:\apps\speaktype.exe")

    def test_detects_rule_from_the_named_rule_query(self):
        calls: list[list[str]] = []

        def fake_netsh(args):
            calls.append(args)
            return r"Program:  C:\Apps\SpeakType.exe"

        with mock.patch.object(helper, "_netsh", fake_netsh):
            self.assertTrue(helper.firewall_rule_present(r"C:\Apps\SpeakType.exe"))
        self.assertEqual(len(calls), 1, "the full scan should not run when the named rule matches")

    def test_falls_back_to_scanning_every_inbound_rule(self):
        """A rule created by the Windows prompt has a different name but still mentions the path."""

        def fake_netsh(args):
            if "name=all" in args:
                return r"Program:  C:\Apps\SpeakType.exe"
            return "\r\nNo rules match the specified criteria.\r\n"

        with mock.patch.object(helper, "_netsh", fake_netsh):
            self.assertTrue(helper.firewall_rule_present(r"C:\Apps\SpeakType.exe"))

    def test_missing_rule_reports_false(self):
        with mock.patch.object(helper, "_netsh", lambda args: "nothing here"):
            self.assertFalse(helper.firewall_rule_present(r"C:\Apps\SpeakType.exe"))

    def test_unreadable_firewall_is_not_reported_as_blocked(self):
        def boom(args):
            raise OSError("netsh is unavailable")

        with mock.patch.object(helper, "_netsh", boom):
            self.assertTrue(
                helper.firewall_rule_present(r"C:\Apps\SpeakType.exe"),
                "an uninspectable firewall must not nag the user",
            )

    def test_add_rule_deletes_stale_rule_first_then_adds(self):
        seen: list[list[str]] = []

        def fake_netsh(args):
            seen.append(args)
            return ""

        completed = mock.Mock(returncode=0)
        with mock.patch.object(helper, "_netsh", fake_netsh), mock.patch.object(
            helper.subprocess, "run", return_value=completed
        ) as run:
            self.assertTrue(helper.add_firewall_rule(r"C:\Apps\SpeakType.exe"))

        self.assertEqual(seen[0][0], "delete")
        args = run.call_args[0][0]
        self.assertIn("add", args)
        self.assertIn("dir=in", args)
        self.assertIn("action=allow", args)
        self.assertIn(r"program=C:\Apps\SpeakType.exe", args)
        self.assertIn("profile=any", args)

    def test_add_rule_reports_netsh_failure(self):
        completed = mock.Mock(returncode=1)
        with mock.patch.object(helper, "_netsh", lambda args: ""), mock.patch.object(
            helper.subprocess, "run", return_value=completed
        ):
            self.assertFalse(helper.add_firewall_rule(r"C:\Apps\SpeakType.exe"))

    def test_firewall_program_is_the_running_interpreter(self):
        self.assertEqual(
            helper.firewall_program().lower(),
            str(Path(sys.executable).resolve()).lower(),
        )

    def test_firewall_flag_runs_headless_and_exits(self):
        """The elevated child must not build a GUI or touch the single-instance mutex."""
        with mock.patch.object(helper, "add_firewall_rule", return_value=True) as add, mock.patch.object(
            helper.sys, "argv", ["SpeakType.exe", helper.FIREWALL_FLAG]
        ):
            with self.assertRaises(SystemExit) as raised:
                helper.main()
        self.assertEqual(raised.exception.code, 0)
        add.assert_called_once_with()

    def test_firewall_flag_reports_failure_in_exit_code(self):
        with mock.patch.object(helper, "add_firewall_rule", return_value=False), mock.patch.object(
            helper.sys, "argv", ["SpeakType.exe", helper.FIREWALL_FLAG]
        ):
            with self.assertRaises(SystemExit) as raised:
                helper.main()
        self.assertEqual(raised.exception.code, 1)


if __name__ == "__main__":
    unittest.main()
