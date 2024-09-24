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
from mobly import base_test
from net_tests_utils.host.python import packet_utils

class TestPacketUtils(base_test.BaseTestClass):
    def test_unicast_arp_request(self):
        # Using scapy to generate unicast arp request packet:
        #   eth = Ether(src="00:01:02:03:04:05", dst="01:02:03:04:05:06")
        #   arp = ARP(op=1, pdst="192.168.1.1", hwsrc="00:01:02:03:04:05", psrc="192.168.1.2")
        #   pkt = eth/arp
        expect_arp_request = """
            01020304050600010203040508060001080006040001000102030405c0a80102000000000000c0a80101
        """.upper().replace(" ", "").replace("\n", "")
        arp_request = packet_utils.construct_arp_packet(
            src_mac="00:01:02:03:04:05",
            dst_mac="01:02:03:04:05:06",
            src_ip="192.168.1.2",
            dst_ip="192.168.1.1",
            op=packet_utils.ARP_REQUEST_OP
        )
        asserts.assert_equal(expect_arp_request, arp_request)

    def test_broadcast_arp_request(self):
        # Using scapy to generate unicast arp request packet:
        #   eth = Ether(src="00:01:02:03:04:05", dst="FF:FF:FF:FF:FF:FF")
        #   arp = ARP(op=1, pdst="192.168.1.1", hwsrc="00:01:02:03:04:05", psrc="192.168.1.2")
        #   pkt = eth/arp
        expect_arp_request = """
            ffffffffffff00010203040508060001080006040001000102030405c0a80102000000000000c0a80101
        """.upper().replace(" ", "").replace("\n", "")
        arp_request = packet_utils.construct_arp_packet(
            src_mac="00:01:02:03:04:05",
            dst_mac=packet_utils.ETHER_BROADCAST_MAC_ADDRESS,
            src_ip="192.168.1.2",
            dst_ip="192.168.1.1",
            op=packet_utils.ARP_REQUEST_OP
        )
        asserts.assert_equal(expect_arp_request, arp_request)

    def test_arp_reply(self):
        # Using scapy to generate unicast arp request packet:
        #   eth = Ether(src="01:02:03:04:05:06", dst="00:01:02:03:04:05")
        #   arp = ARP(op=2, pdst="192.168.1.2", \
        #             hwsrc="01:02:03:04:05:06", \
        #             psrc="192.168.1.1", \
        #             hwdst="00:01:02:03:04:05")
        #   pkt = eth/arp
        expect_arp_reply = """
            00010203040501020304050608060001080006040002010203040506c0a80101000102030405c0a80102
        """.upper().replace(" ", "").replace("\n", "")
        arp_reply = packet_utils.construct_arp_packet(
            src_mac="01:02:03:04:05:06",
            dst_mac="00:01:02:03:04:05",
            src_ip="192.168.1.1",
            dst_ip="192.168.1.2",
            op=packet_utils.ARP_REPLY_OP
        )
        asserts.assert_equal(expect_arp_reply, arp_reply)
