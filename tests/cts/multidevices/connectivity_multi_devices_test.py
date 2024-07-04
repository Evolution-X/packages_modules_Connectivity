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

from net_tests_utils.host.python import mdns_utils, multi_devices_test_base, tether_utils
from net_tests_utils.host.python import wifip2p_utils
from net_tests_utils.host.python.tether_utils import UpstreamType


class ConnectivityMultiDevicesTest(
    multi_devices_test_base.MultiDevicesTestBase
):

  def test_hotspot_upstream_wifi(self):
    tether_utils.assume_hotspot_test_preconditions(
        self.serverDevice, self.clientDevice, UpstreamType.WIFI
    )
    try:
      # Connectivity of the client verified by asserting the validated capability.
      tether_utils.setup_hotspot_and_client_for_upstream_type(
          self.serverDevice, self.clientDevice, UpstreamType.WIFI
      )
    finally:
      tether_utils.cleanup_tethering_for_upstream_type(
          self.serverDevice, UpstreamType.WIFI
      )

  def test_hotspot_upstream_cellular(self):
    tether_utils.assume_hotspot_test_preconditions(
        self.serverDevice, self.clientDevice, UpstreamType.CELLULAR
    )
    try:
      # Connectivity of the client verified by asserting the validated capability.
      tether_utils.setup_hotspot_and_client_for_upstream_type(
          self.serverDevice, self.clientDevice, UpstreamType.CELLULAR
      )
    finally:
      tether_utils.cleanup_tethering_for_upstream_type(
          self.serverDevice, UpstreamType.CELLULAR
      )

  def test_mdns_via_hotspot(self):
    tether_utils.assume_hotspot_test_preconditions(
        self.serverDevice, self.clientDevice, UpstreamType.NONE
    )
    mdns_utils.assume_mdns_test_preconditions(
        self.clientDevice, self.serverDevice
    )
    try:
      # Connectivity of the client verified by asserting the validated capability.
      tether_utils.setup_hotspot_and_client_for_upstream_type(
          self.serverDevice, self.clientDevice, UpstreamType.NONE
      )
      mdns_utils.register_mdns_service_and_discover_resolve(
          self.clientDevice, self.serverDevice
      )
    finally:
      mdns_utils.cleanup_mdns_service(self.clientDevice, self.serverDevice)
      tether_utils.cleanup_tethering_for_upstream_type(
          self.serverDevice, UpstreamType.NONE
      )

  def test_mdns_via_wifip2p(self):
    wifip2p_utils.assume_wifi_p2p_test_preconditions(
        self.serverDevice, self.clientDevice
    )
    mdns_utils.assume_mdns_test_preconditions(
        self.clientDevice, self.serverDevice
    )
    try:
      wifip2p_utils.setup_wifi_p2p_server_and_client(
          self.serverDevice, self.clientDevice
      )
      mdns_utils.register_mdns_service_and_discover_resolve(
          self.clientDevice, self.serverDevice
      )
    finally:
      mdns_utils.cleanup_mdns_service(self.clientDevice, self.serverDevice)
      wifip2p_utils.cleanup_wifi_p2p(self.serverDevice, self.clientDevice)
