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
from scapy.layers.inet import IP, ICMP
from scapy.layers.l2 import Ether
from net_tests_utils.host.python import apf_test_base, apf_utils, adb_utils, assert_utils, packet_utils

APFV6_VERSION = 6000
ARP_OFFLOAD_REPLY_LEN = 60

class ApfV6Test(apf_test_base.ApfTestBase):
    def setup_class(self):
        super().setup_class()

        # Skip tests for APF version < 6000
        apf_utils.assume_apf_version_support_at_least(
            self.clientDevice, self.client_iface_name, APFV6_VERSION
        )

    def teardown_class(self):
        # force to stop capture on the server device if any test case failed
        try:
            apf_utils.stop_capture_packets(self.serverDevice, self.server_iface_name)
        except assert_utils.UnexpectedBehaviorError:
            pass
        super().teardown_class()

    def test_unicast_arp_request_offload(self):
        arp_request = packet_utils.construct_arp_packet(
            src_mac=self.server_mac_address,
            dst_mac=self.client_mac_address,
            src_ip=self.server_ipv4_addresses[0],
            dst_ip=self.client_ipv4_addresses[0],
            op=packet_utils.ARP_REQUEST_OP
        )

        arp_reply = packet_utils.construct_arp_packet(
            src_mac=self.client_mac_address,
            dst_mac=self.server_mac_address,
            src_ip=self.client_ipv4_addresses[0],
            dst_ip=self.server_ipv4_addresses[0],
            op=packet_utils.ARP_REPLY_OP
        )

        # Add zero padding up to 60 bytes, since APFv6 ARP offload always sent out 60 bytes reply
        arp_reply = arp_reply.ljust(ARP_OFFLOAD_REPLY_LEN * 2, "0")

        self.send_packet_and_expect_reply_received(
            arp_request, "DROPPED_ARP_REQUEST_REPLIED", arp_reply
        )

    def test_broadcast_arp_request_offload(self):
        arp_request = packet_utils.construct_arp_packet(
            src_mac=self.server_mac_address,
            dst_mac=packet_utils.ETHER_BROADCAST_MAC_ADDRESS,
            src_ip=self.server_ipv4_addresses[0],
            dst_ip=self.client_ipv4_addresses[0],
            op=packet_utils.ARP_REQUEST_OP
        )

        arp_reply = packet_utils.construct_arp_packet(
            src_mac=self.client_mac_address,
            dst_mac=self.server_mac_address,
            src_ip=self.client_ipv4_addresses[0],
            dst_ip=self.server_ipv4_addresses[0],
            op=packet_utils.ARP_REPLY_OP
        )

        # Add zero padding up to 60 bytes, since APFv6 ARP offload always sent out 60 bytes reply
        arp_reply = arp_reply.ljust(ARP_OFFLOAD_REPLY_LEN * 2, "0")

        self.send_packet_and_expect_reply_received(
            arp_request, "DROPPED_ARP_REQUEST_REPLIED", arp_reply
        )

    @apf_utils.at_least_B()
    def test_ipv4_icmp_echo_request_offload(self):
        eth = Ether(src=self.server_mac_address, dst=self.client_mac_address)
        ip = IP(src=self.server_ipv4_addresses[0], dst=self.client_ipv4_addresses[0])
        icmp = ICMP(id=1, seq=123)
        echo_request = bytes(eth/ip/icmp/b"hello").hex()

        eth = Ether(src=self.client_mac_address, dst=self.server_mac_address)
        ip = IP(src=self.client_ipv4_addresses[0], dst=self.server_ipv4_addresses[0])
        icmp = ICMP(type=0, id=1, seq=123)
        expected_echo_reply = bytes(eth/ip/icmp/b"hello").hex()
        self.send_packet_and_expect_reply_received(
            echo_request, "DROPPED_IPV4_PING_REQUEST_REPLIED", expected_echo_reply
        )