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
from net_tests_utils.host.python import adb_utils
from net_tests_utils.host.python.assert_utils import UnexpectedBehaviorError


class TestAdbUtils(unittest.TestCase):

  def setUp(self):
    self.mock_ad = MagicMock()  # Mock Android device object
    self.mock_ad.log = MagicMock()
    self.mock_ad.adb.shell.return_value = b""  # Default empty return for shell

  @patch(
      "net_tests_utils.host.python.adb_utils.expect_dumpsys_state_with_retry"
  )
  @patch("net_tests_utils.host.python.adb_utils._set_screen_state")
  def test_set_doze_mode_enable(
      self, mock_set_screen_state, mock_expect_dumpsys_state
  ):
    adb_utils.set_doze_mode(self.mock_ad, True)
    mock_set_screen_state.assert_called_once_with(self.mock_ad, False)

  @patch(
      "net_tests_utils.host.python.adb_utils.expect_dumpsys_state_with_retry"
  )
  def test_set_doze_mode_disable(self, mock_expect_dumpsys_state):
    adb_utils.set_doze_mode(self.mock_ad, False)

  @patch("net_tests_utils.host.python.adb_utils._get_screen_state")
  def test_set_screen_state_success(self, mock_get_screen_state):
    mock_get_screen_state.side_effect = [False, True]  # Simulate toggle
    adb_utils._set_screen_state(self.mock_ad, True)

  @patch("net_tests_utils.host.python.adb_utils._get_screen_state")
  def test_set_screen_state_failure(self, mock_get_screen_state):
    mock_get_screen_state.return_value = False  # State doesn't change
    with self.assertRaises(UnexpectedBehaviorError):
      adb_utils._set_screen_state(self.mock_ad, True)

  @patch("net_tests_utils.host.python.adb_utils.get_value_of_key_from_dumpsys")
  def test_get_screen_state(self, mock_get_value):
    # Test cases for different return values of get_value_of_key_from_dumpsys
    # TODO: Make it parameterized.
    test_cases = [
        ("Awake", True),
        ("Asleep", False),
        ("Dozing", False),
        ("SomeOtherState", False),
    ]

    for state_str, expected_result in test_cases:
      mock_get_value.return_value = state_str
      self.assertEqual(
          adb_utils._get_screen_state(self.mock_ad), expected_result
      )

  def test_get_value_of_key_from_dumpsys(self):
    self.mock_ad.adb.shell.return_value = (
        b"mWakefulness=Awake\nmOtherKey=SomeValue"
    )
    result = adb_utils.get_value_of_key_from_dumpsys(
        self.mock_ad, "power", "mWakefulness"
    )
    self.assertEqual(result, "Awake")

  @patch("net_tests_utils.host.python.adb_utils.get_value_of_key_from_dumpsys")
  def test_expect_dumpsys_state_with_retry_success(self, mock_get_value):
    # Test cases for different combinations of expected_state and get_value return value
    # TODO: Make it parameterized.
    test_cases = [
        (True, ["true"]),  # Expect True, get True
        (False, ["false"]),  # Expect False, get False
        (
            True,
            ["false", "true"],
        ),  # Expect True, get False which is unexpected, then get True
        (
            False,
            ["true", "false"],
        ),  # Expect False, get True which is unexpected, then get False
    ]

    for expected_state, returned_value in test_cases:
      mock_get_value.side_effect = returned_value
      # Verify the method returns and does not throw.
      adb_utils.expect_dumpsys_state_with_retry(
          self.mock_ad, "service", "key", expected_state, 0
      )

  @patch("net_tests_utils.host.python.adb_utils.get_value_of_key_from_dumpsys")
  def test_expect_dumpsys_state_with_retry_failure(self, mock_get_value):
    mock_get_value.return_value = "false"
    with self.assertRaises(UnexpectedBehaviorError):
      adb_utils.expect_dumpsys_state_with_retry(
          self.mock_ad, "service", "key", True, 0
      )

  @patch("net_tests_utils.host.python.adb_utils.get_value_of_key_from_dumpsys")
  def test_expect_dumpsys_state_with_retry_not_found(self, mock_get_value):
    # Simulate the get_value_of_key_from_dumpsys cannot find the give key.
    mock_get_value.return_value = None

    # Expect the function to raise UnexpectedBehaviorError due to the exception
    with self.assertRaises(UnexpectedBehaviorError):
      adb_utils.expect_dumpsys_state_with_retry(
          self.mock_ad, "service", "key", True
      )


if __name__ == "__main__":
  unittest.main()
