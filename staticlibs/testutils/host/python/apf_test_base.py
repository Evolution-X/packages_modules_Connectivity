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

from mobly import asserts
from net_tests_utils.host.python import adb_utils, apf_utils, multi_devices_test_base, tether_utils
from net_tests_utils.host.python.tether_utils import UpstreamType


class ApfTestBase(multi_devices_test_base.MultiDevicesTestBase):

  def setup_class(self):
    super().setup_class()
    tether_utils.assume_hotspot_test_preconditions(
        self.serverDevice, self.clientDevice, UpstreamType.NONE
    )
    asserts.abort_class_if(
        not apf_utils.is_send_raw_packet_downstream_supported(
            self.serverDevice
        ),
        "NetworkStack is too old to support send raw packet, skip test.",
    )

    client = self.clientDevice.connectivity_multi_devices_snippet

    self.server_iface_name, client_network = (
        tether_utils.setup_hotspot_and_client_for_upstream_type(
            self.serverDevice, self.clientDevice, UpstreamType.NONE
        )
    )
    self.client_iface_name = client.getInterfaceNameFromNetworkHandle(
        client_network
    )

    adb_utils.set_doze_mode(self.clientDevice, True)

  def teardown_class(self):
    adb_utils.set_doze_mode(self.clientDevice, False)
    tether_utils.cleanup_tethering_for_upstream_type(
        self.serverDevice, UpstreamType.NONE
    )
