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

"""Main entrypoint for all of unittest."""

import unittest

# Import all unittest classes here, so it can be discovered by unittest module.
# TODO: make the tests can be executed without manually import classes.
from host.python.adb_utils_test import TestAdbUtils
from host.python.apf_utils_test import TestApfUtils
from host.python.assert_utils_test import TestAssertUtils


if __name__ == "__main__":
  unittest.main()
