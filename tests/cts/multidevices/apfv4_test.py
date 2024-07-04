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

from net_tests_utils.host.python import apf_test_base

# Constants.
COUNTER_DROPPED_ETHERTYPE_NOT_ALLOWED = "DROPPED_ETHERTYPE_NOT_ALLOWED"
ETHER_BROADCAST_ADDR = "FFFFFFFFFFFF"
ETH_P_ETHERCAT = "88A4"


class ApfV4Test(apf_test_base.ApfTestBase):

  def test_apf_drop_ethercat(self):
    # Ethernet header (14 bytes).
    packet = ETHER_BROADCAST_ADDR  # Destination MAC (broadcast)
    packet += self.server_mac_address.replace(":", "")  # Source MAC
    packet += ETH_P_ETHERCAT  # EtherType (EtherCAT)

    # EtherCAT header (2 bytes) + 44 bytes of zero padding.
    packet += "00" * 46
    self.send_packet_and_expect_counter_increased(
        packet, COUNTER_DROPPED_ETHERTYPE_NOT_ALLOWED
    )
