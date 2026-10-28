from __future__ import annotations

from typing import TYPE_CHECKING

import customtkinter as ctk

from gui.pages.base import BasePage
from gui.pages.dashboard import DashboardPage
from gui.pages.pairing import PairingPage
from gui.pages.security import SecurityPage
from gui.pages.settings_page import SettingsPage

__all__ = ["BasePage", "DashboardPage", "PairingPage", "SettingsPage", "SecurityPage"]
