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
from net_tests_utils.host.python import adb_utils, apf_test_base, apf_utils, assert_utils, tether_utils
from net_tests_utils.host.python.tether_utils import UpstreamType

COUNTER_DROPPED_ETHERTYPE_NOT_ALLOWED = "DROPPED_ETHERTYPE_NOT_ALLOWED"


class ApfV4Test(apf_test_base.ApfTestBase):

  def test_apf_drop_ethercat(self):
    count_before_test = apf_utils.get_apf_counter(
        self.clientDevice,
        self.client_iface_name,
        COUNTER_DROPPED_ETHERTYPE_NOT_ALLOWED,
    )
    apf_utils.send_broadcast_empty_ethercat_packet(
        self.serverDevice, self.server_iface_name
    )

    assert_utils.expect_with_retry(
        lambda: apf_utils.get_apf_counter(
            self.clientDevice,
            self.client_iface_name,
            COUNTER_DROPPED_ETHERTYPE_NOT_ALLOWED,
        )
        > count_before_test
    )

    # TODO: Verify the packet is not actually received.
