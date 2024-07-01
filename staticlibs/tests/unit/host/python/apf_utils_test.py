#  Copyright (C) 2024 The Android Open Source Project
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

import unittest
from unittest.mock import MagicMock, patch
from net_tests_utils.host.python.apf_utils import (
    PatternNotFoundException,
    get_apf_counter,
    get_apf_counters_from_dumpsys,
)


class TestApfUtils(unittest.TestCase):

  def setUp(self):
    self.mock_ad = MagicMock()  # Mock Android device object

  @patch("net_tests_utils.host.python.adb_utils.get_dumpsys_for_service")
  def test_get_apf_counters_from_dumpsys_success(
      self, mock_get_dumpsys: MagicMock
  ) -> None:
    mock_get_dumpsys.return_value = """
IpClient.wlan0
  APF packet counters:
    COUNTER_NAME1: 123
    COUNTER_NAME2: 456
"""
    iface_name = "wlan0"
    counters = get_apf_counters_from_dumpsys(self.mock_ad, iface_name)
    self.assertEqual(counters, {"COUNTER_NAME1": 123, "COUNTER_NAME2": 456})

  @patch("net_tests_utils.host.python.adb_utils.get_dumpsys_for_service")
  def test_get_apf_counters_from_dumpsys_exceptions(
      self, mock_get_dumpsys: MagicMock
  ) -> None:
    test_cases = [
        "",
        "IpClient.wlan0\n",
        "IpClient.wlan0\n APF packet counters:\n",
        """
IpClient.wlan1
  APF packet counters:
    COUNTER_NAME1: 123
    COUNTER_NAME2: 456
""",
    ]

    for dumpsys_output in test_cases:
      mock_get_dumpsys.return_value = dumpsys_output
      with self.assertRaises(PatternNotFoundException):
        get_apf_counters_from_dumpsys(self.mock_ad, "wlan0")

  @patch("net_tests_utils.host.python.apf_utils.get_apf_counters_from_dumpsys")
  def test_get_apf_counter(self, mock_get_counters: MagicMock) -> None:
    iface = "wlan0"
    mock_get_counters.return_value = {
        "COUNTER_NAME1": 123,
        "COUNTER_NAME2": 456,
    }
    self.assertEqual(get_apf_counter(self.mock_ad, iface, "COUNTER_NAME1"), 123)
    # Not found
    self.assertEqual(get_apf_counter(self.mock_ad, iface, "COUNTER_NAME3"), 0)


if __name__ == "__main__":
  unittest.main()
