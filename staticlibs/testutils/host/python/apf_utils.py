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

import re
from mobly.controllers import android_device
from net_tests_utils.host.python import adb_utils


class PatternNotFoundException(Exception):
  """Raised when the given pattern cannot be found."""


def get_apf_counter(
    ad: android_device.AndroidDevice, iface: str, counter_name: str
) -> int:
  counters = get_apf_counters_from_dumpsys(ad, iface)
  return counters.get(counter_name, 0)


def get_apf_counters_from_dumpsys(
    ad: android_device.AndroidDevice, iface_name: str
) -> dict:
  dumpsys = adb_utils.get_dumpsys_for_service(ad, "network_stack")

  # Extract IpClient section of the specified interface.
  # This takes inputs like:
  # IpClient.wlan0
  #   ...
  # IpClient.wlan1
  #   ...
  iface_pattern = re.compile(
      r"^IpClient\." + iface_name + r"\n" + r"((^\s.*\n)+)", re.MULTILINE
  )
  iface_result = iface_pattern.search(dumpsys)
  if iface_result is None:
    raise PatternNotFoundException("Cannot find IpClient for " + iface_name)

  # Extract APF counters section from IpClient section, which looks like:
  #     APF packet counters:
  #       COUNTER_NAME: VALUE
  #       ....
  apf_pattern = re.compile(
      r"APF packet counters:.*\n.(\s+[A-Z_0-9]+: \d+\n)+", re.MULTILINE
  )
  apf_result = apf_pattern.search(iface_result.group(0))
  if apf_result is None:
    raise PatternNotFoundException(
        "Cannot find APF counters in text: " + iface_result.group(0)
    )

  # Extract key-value pairs from APF counters section into a list of tuples,
  # e.g. [('COUNTER1', '1'), ('COUNTER2', '2')].
  counter_pattern = re.compile(r"(?P<name>[A-Z_0-9]+): (?P<value>\d+)")
  counter_result = counter_pattern.findall(apf_result.group(0))
  if counter_result is None:
    raise PatternNotFoundException(
        "Cannot extract APF counters in text: " + apf_result.group(0)
    )

  # Convert into a dict.
  result = {}
  for key, value_str in counter_result:
    result[key] = int(value_str)

  ad.log.debug("Getting apf counters: " + str(result))
  return result
