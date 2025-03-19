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
from scapy.layers.inet import IP, ICMP, IPOption_Router_Alert
from scapy.layers.inet6 import (
    IPv6,
    IPv6ExtHdrHopByHop,
    ICMPv6EchoRequest,
    ICMPv6EchoReply,
    ICMPv6MLQuery2,
    ICMPv6MLReport2,
    ICMPv6MLDMultAddrRec,
    RouterAlert
)
from scapy.layers.l2 import Ether
from scapy.contrib.igmpv3 import IGMPv3, IGMPv3mq, IGMPv3mr, IGMPv3gr
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

    @apf_utils.at_least_B()
    @apf_utils.apf_ram_at_least(3000)
    def test_ipv6_icmp_echo_request_offload(self):
        eth = Ether(src=self.server_mac_address, dst=self.client_mac_address)
        ip = IPv6(src=self.server_ipv6_addresses[0], dst=self.client_ipv6_addresses[0])
        icmp = ICMPv6EchoRequest(id=1, seq=123)
        echo_request = bytes(eth/ip/icmp/b"hello").hex()

        eth = Ether(src=self.client_mac_address, dst=self.server_mac_address)
        ip = IPv6(src=self.client_ipv6_addresses[0], dst=self.server_ipv6_addresses[0])
        icmp = ICMPv6EchoReply(id=1, seq=123)
        expected_echo_reply = bytes(eth/ip/icmp/b"hello").hex()

        self.send_packet_and_expect_reply_received(
            echo_request, "DROPPED_IPV6_ICMP6_ECHO_REQUEST_REPLIED", expected_echo_reply
        )

    @apf_utils.at_least_B()
    def test_igmpv3_general_query_offload(self):
        ether = Ether(src=self.server_mac_address, dst='01:00:5e:00:00:01')
        ip = IP(
            src=self.server_ipv4_addresses[0],
            dst='224.0.0.1',
            options=[IPOption_Router_Alert()]
        )
        igmp = IGMPv3(type=0x11)/IGMPv3mq()
        igmpv3_general_query = bytes(ether/ip/igmp).hex()

        mcast_addrs = ['239.0.0.1', '239.0.0.2', '239.0.0.3']

        for addr in mcast_addrs:
            adb_utils.adb_shell(
                self.clientDevice,
                f'ip addr add {addr}/32 dev {self.client_iface_name} autojoin'
            )

        ether = Ether(src=self.client_mac_address, dst='01:00:5e:00:00:16')
        ip = IP(
            src=self.client_ipv4_addresses[0],
            dst='224.0.0.22',
            options=[IPOption_Router_Alert()],
            id=0,
            flags="DF"
        )
        igmpv3_hdr = IGMPv3(type=0x22)
        mcast_records = []
        for addr in mcast_addrs:
            mcast_records.append(IGMPv3gr(rtype=2, maddr=addr))

        igmp = IGMPv3mr(records=mcast_records)
        expected_igmpv3_report = bytes(ether/ip/igmpv3_hdr/igmp).hex()
        self.send_packet_and_expect_reply_received(
            igmpv3_general_query, "DROPPED_IGMP_V3_GENERAL_QUERY_REPLIED", expected_igmpv3_report
        )

        for addr in mcast_addrs:
            adb_utils.adb_shell(
                self.clientDevice,
                f'ip addr del {addr}/32 dev {self.client_iface_name}'
            )

    @apf_utils.at_least_B()
    @apf_utils.apf_ram_at_least(3000)
    def test_mldv2_general_query_offload(self):
        ether = Ether(src=self.server_mac_address, dst='33:33:00:00:00:01')
        ip = IPv6(src=self.server_ipv6_addresses[0], dst='ff02::1', hlim=1)
        hopOpts = IPv6ExtHdrHopByHop(options=[RouterAlert(otype=5)])
        mld = ICMPv6MLQuery2()
        mldv2_general_query = bytes(ether/ip/hopOpts/mld).hex()

        ether = Ether(src=self.client_mac_address, dst='33:33:00:00:00:16')
        ip = IPv6(src=self.client_ipv6_addresses[0], dst='ff02::16', hlim=1)

        mcast_addrs = apf_utils.get_exclude_all_host_ipv6_multicast_addresses(
            self.clientDevice, self.client_iface_name
        )

        mld_records = []
        for addr in mcast_addrs:
            mld_records.append(ICMPv6MLDMultAddrRec(dst=addr, rtype=2))
        mld = ICMPv6MLReport2(records=mld_records)
        expected_mldv2_report = bytes(ether/ip/hopOpts/mld).hex()
        self.send_packet_and_expect_reply_received(
            mldv2_general_query, "DROPPED_IPV6_MLD_V2_GENERAL_QUERY_REPLIED", expected_mldv2_report
        )
