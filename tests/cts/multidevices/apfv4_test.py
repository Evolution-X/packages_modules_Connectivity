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

from net_tests_utils.host.python import adb_utils, apf_utils, assert_utils, mdns_utils, multi_devices_test_base, tether_utils
from net_tests_utils.host.python.tether_utils import UpstreamType

COUNTER_DROPPED_ETHERTYPE_NOT_ALLOWED = "DROPPED_ETHERTYPE_NOT_ALLOWED"


class ApfV4Test(multi_devices_test_base.MultiDevicesTestBase):

  def test_apf_drop_ethercat(self):
    tether_utils.assume_hotspot_test_preconditions(
        self.serverDevice, self.clientDevice, UpstreamType.NONE
    )
    client = self.clientDevice.connectivity_multi_devices_snippet
    try:
      server_iface_name, client_network = (
          tether_utils.setup_hotspot_and_client_for_upstream_type(
              self.serverDevice, self.clientDevice, UpstreamType.NONE
          )
      )
      client_iface_name = client.getInterfaceNameFromNetworkHandle(
          client_network
      )

      adb_utils.set_doze_mode(self.clientDevice, True)

      count_before_test = apf_utils.get_apf_counter(
          self.clientDevice,
          client_iface_name,
          COUNTER_DROPPED_ETHERTYPE_NOT_ALLOWED,
      )
      try:
        apf_utils.send_broadcast_empty_ethercat_packet(
            self.serverDevice, server_iface_name
        )
      except apf_utils.UnsupportedOperationException:
        asserts.skip(
            "NetworkStack is too old to support send raw packet, skip test."
        )

      assert_utils.expect_with_retry(
          lambda: apf_utils.get_apf_counter(
              self.clientDevice,
              client_iface_name,
              COUNTER_DROPPED_ETHERTYPE_NOT_ALLOWED,
          )
          > count_before_test
      )

      # TODO: Verify the packet is not actually received.
    finally:
      adb_utils.set_doze_mode(self.clientDevice, False)
      tether_utils.cleanup_tethering_for_upstream_type(
          self.serverDevice, UpstreamType.NONE
      )
