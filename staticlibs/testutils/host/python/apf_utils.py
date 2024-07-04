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
from mobly.controllers.android_device_lib.adb import AdbError
from net_tests_utils.host.python import adb_utils, assert_utils


# Constants.
ETHER_BROADCAST = "FFFFFFFFFFFF"
ETH_P_ETHERCAT = "88A4"


class PatternNotFoundException(Exception):
  """Raised when the given pattern cannot be found."""


class UnsupportedOperationException(Exception):
  pass


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


def get_hardware_address(
    ad: android_device.AndroidDevice, iface_name: str
) -> str:
  """Retrieves the hardware (MAC) address for a given network interface.

  Returns:
      The hex representative of the MAC address in uppercase.
      E.g. 12:34:56:78:90:AB

  Raises:
      PatternNotFoundException: If the MAC address is not found in the command
      output.
  """

  # Run the "ip link" command and get its output.
  ip_link_output = adb_utils.adb_shell(ad, f"ip link show {iface_name}")

  # Regular expression to extract the MAC address.
  # Parse hardware address from ip link output like below:
  # 46: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc mq ...
  #    link/ether 72:05:77:82:21:e0 brd ff:ff:ff:ff:ff:ff
  pattern = r"link/ether (([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2})"
  match = re.search(pattern, ip_link_output)

  if match:
    return match.group(1).upper()  # Extract the MAC address string.
  else:
    raise PatternNotFoundException(
        "Cannot get hardware address for " + iface_name
    )


def send_broadcast_empty_ethercat_packet(
    ad: android_device.AndroidDevice, iface_name: str
) -> None:
  """Transmits a broadcast empty EtherCat packet on the specified interface."""

  # Get the interface's MAC address.
  mac_address = get_hardware_address(ad, iface_name)

  # TODO: Build packet by using scapy library.
  # Ethernet header (14 bytes).
  packet = ETHER_BROADCAST  # Destination MAC (broadcast)
  packet += mac_address.replace(":", "")  # Source MAC
  packet += ETH_P_ETHERCAT  # EtherType (EtherCAT)

  # EtherCAT header (2 bytes) + 44 bytes of zero padding.
  packet += "00" * 46

  # Send the packet using a raw socket.
  send_raw_packet_downstream(ad, iface_name, packet)


def send_raw_packet_downstream(
    ad: android_device.AndroidDevice,
    iface_name: str,
    packet_in_hex: str,
) -> None:
  """Sends a raw packet over the specified downstream interface.

  This function constructs and sends a raw packet using the
  `send-raw-packet-downstream`
  command provided by NetworkStack process. It's primarily intended for testing
  purposes.

  Args:
      ad: The AndroidDevice object representing the connected device.
      iface_name: The name of the network interface to use (e.g., "wlan0",
        "eth0").
      packet_in_hex: The raw packet data starting from L2 header encoded in
        hexadecimal string format.

  Raises:
      UnsupportedOperationException: If the NetworkStack doesn't support
        the `send-raw-packet` command.
      UnexpectedBehaviorException: If the command execution produces unexpected
        output other than an empty response or "Unknown command".

  Important Considerations:
      Security: This method only works on tethering downstream interfaces due
        to security restrictions.
      Packet Format: The `packet_in_hex` must be a valid hexadecimal
        representation of a packet starting from L2 header.
  """

  cmd = (
      "cmd network_stack send-raw-packet-downstream"
      f" {iface_name} {packet_in_hex}"
  )

  # Expect no output or Unknown command if NetworkStack is too old. Throw otherwise.
  try:
    output = adb_utils.adb_shell(ad, cmd)
  except AdbError as e:
    output = str(e.stdout)
  if output:
    if "Unknown command" in output:
      raise UnsupportedOperationException(
          "send-raw-packet-downstream command is not supported."
      )
    raise assert_utils.UnexpectedBehaviorError(
        f"Got unexpected output: {output} for command: {cmd}."
    )
