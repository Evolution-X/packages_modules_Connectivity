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
from ipaddress import IPv4Address
from socket import inet_aton

ETHER_BROADCAST_MAC_ADDRESS = "FF:FF:FF:FF:FF:FF"
ARP_REQUEST_OP = 1
ARP_REPLY_OP = 2

"""
This variable defines a template for constructing ARP packets in hexadecimal format.
It's used to provide the common fields for ARP packet, and replaced needed fields when constructing
"""
ARP_TEMPLATE = (
    # Ether Header (14 bytes)
    "{dst_mac}" + # DA
    "{src_mac}" + # SA
    "0806" + # ARP
    # ARP Header (28 bytes)
    "0001" + # Hardware type (Ethernet)
    "0800" + # Protocol type (IPv4)
    "06" + # hardware address length
    "04" + # protocol address length
    "{opcode}" + # opcode
    "{sender_mac}" + # sender MAC
    "{sender_ip}" + # sender IP
    "{target_mac}" + # target MAC
    "{target_ip}" # target IP
)

def construct_arp_packet(src_mac, dst_mac, src_ip, dst_ip, op) -> str:
    """Constructs an ARP packet as a hexadecimal string.

    This function creates an ARP packet by filling in the required fields
    in a predefined ARP packet template.

    Args:
    src_mac: The MAC address of the sender. (e.g. "11:22:33:44:55:66")
    dst_mac: The MAC address of the recipient. (e.g. "aa:bb:cc:dd:ee:ff")
    src_ip: The IP address of the sender. (e.g. "1.1.1.1")
    dst_ip: The IP address of the target machine. (e.g. "2.2.2.2")
    op: The op code of the ARP packet, refer to ARP_*_OP

    Returns:
    A string representing the ARP packet in hexadecimal format.
    """
    # Replace the needed fields from packet template
    arp_pkt = ARP_TEMPLATE.format(
            dst_mac=dst_mac.replace(":",""),
            src_mac=src_mac.replace(":",""),
            opcode=str(op).rjust(4, "0"),
            sender_mac=src_mac.replace(":",""),
            sender_ip=inet_aton(src_ip).hex(),
            target_mac=("000000000000" if op == ARP_REQUEST_OP else dst_mac.replace(":", "")),
            target_ip=inet_aton(dst_ip).hex()
    )

    # always convert to upper case hex string
    return arp_pkt.upper()