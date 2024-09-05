/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define MAX_POLICIES 16

#define STRUCT_SIZE(name, size) _Static_assert(sizeof(name) == (size), "Incorrect struct size.")

// Retrieve the first (ie. high) 64 bits of an IPv6 address (in network order)
#define v6_hi_be64(v) (*(uint64_t*)&((v).s6_addr32[0]))

// Retrieve the last (ie. low) 64 bits of an IPv6 address (in network order)
#define v6_lo_be64(v) (*(uint64_t*)&((v).s6_addr32[2]))

// This returns a non-zero u64 iff a != b
#define v6_not_equal(a, b) ((v6_hi_be64(a) ^ v6_hi_be64(b)) \
                          | (v6_lo_be64(a) ^ v6_lo_be64(b)))

typedef struct {
    struct in6_addr src_ip;
    struct in6_addr dst_ip;
    uint32_t ifindex;
    __be16 src_port;
    uint16_t dst_port_start;
    uint16_t dst_port_end;
    uint8_t proto;
    int8_t dscp_val;  // -1 none, or 0..63 DSCP value
    bool match_src_ip;
    bool match_dst_ip;
    bool match_src_port;
    bool match_proto;
} DscpPolicy;
STRUCT_SIZE(DscpPolicy, 2 * 16 + 4 + 3 * 2 + 6 * 1);  // 48

typedef struct {
    struct in6_addr src_ip;
    struct in6_addr dst_ip;
    uint32_t ifindex;
    __be16 src_port;
    uint16_t dst_port;
    uint8_t proto;
    int8_t dscp_val;  // -1 none, or 0..63 DSCP value
    uint8_t pad[2];
} RuleEntry;
STRUCT_SIZE(RuleEntry, 2 * 16 + 4 + 2 * 2 + 4 * 1);  // 44
