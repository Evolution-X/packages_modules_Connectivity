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
from mobly.controllers import android_device


def assume_wifi_p2p_test_preconditions(
    server_device: android_device, client_device: android_device
) -> None:
  server = server_device.connectivity_multi_devices_snippet
  client = client_device.connectivity_multi_devices_snippet

  # Assert pre-conditions
  asserts.skip_if(not server.hasWifiFeature(), "Server requires Wifi feature")
  asserts.skip_if(not client.hasWifiFeature(), "Client requires Wifi feature")
  asserts.skip_if(
      not server.isP2pSupported(), "Server requires Wi-fi P2P feature"
  )
  asserts.skip_if(
      not client.isP2pSupported(), "Client requires Wi-fi P2P feature"
  )


def setup_wifi_p2p_server_and_client(
    server_device: android_device, client_device: android_device
) -> None:
  """Set up the Wi-Fi P2P server and client."""
  # Start Wi-Fi P2P on both server and client.
  server_device.connectivity_multi_devices_snippet.startWifiP2p()
  client_device.connectivity_multi_devices_snippet.startWifiP2p()


def cleanup_wifi_p2p(
    server_device: android_device, client_device: android_device
) -> None:
  # Stop Wi-Fi P2P
  server_device.connectivity_multi_devices_snippet.stopWifiP2p()
  client_device.connectivity_multi_devices_snippet.stopWifiP2p()
