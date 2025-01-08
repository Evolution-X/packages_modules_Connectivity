/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.net.cts

import android.Manifest.permission.MANAGE_TEST_NETWORKS
import android.net.InetAddresses.parseNumericAddress
import android.net.IpPrefix
import android.net.MacAddress
import android.net.RouteInfo
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.testutils.AutoReleaseNetworkCallbackRule
import com.android.testutils.DnsResolverModuleTest
import com.android.testutils.RecorderCallback.CallbackEntry.LinkPropertiesChanged
import com.android.testutils.RouterAdvertisementResponder
import com.android.testutils.TapPacketReaderRule
import com.android.testutils.TestableNetworkAgent
import com.android.testutils.runAsShell
import java.net.Inet6Address
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith

private val TEST_DNSSERVER_MAC = MacAddress.fromString("00:11:22:33:44:55")
private val TAG = DnsResolverTapTest::class.java.simpleName
private const val TEST_TIMEOUT_MS = 10_000L

@DnsResolverModuleTest
@RunWith(AndroidJUnit4::class)
class DnsResolverTapTest {
    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }
    private val handlerThread = HandlerThread(TAG)

    @get:Rule(order = 1)
    val packetReaderRule = TapPacketReaderRule()

    @get:Rule(order = 2)
    val cbRule = AutoReleaseNetworkCallbackRule()

    private val ndResponder by lazy { RouterAdvertisementResponder(packetReaderRule.reader) }
    private val dnsServerAddr by lazy {
        parseNumericAddress("fe80::124%${packetReaderRule.iface.interfaceName}") as Inet6Address
    }
    private lateinit var agent: TestableNetworkAgent

    @Before
    fun setUp() {
        handlerThread.start()
        val interfaceName = packetReaderRule.iface.interfaceName
        val cb = cbRule.requestNetwork(TestableNetworkAgent.makeNetworkRequestForInterface(
            interfaceName))
        agent = runAsShell(MANAGE_TEST_NETWORKS) {
            TestableNetworkAgent.createOnInterface(context, handlerThread.looper,
                interfaceName, TEST_TIMEOUT_MS)
        }
        ndResponder.addNeighborEntry(TEST_DNSSERVER_MAC, dnsServerAddr)
        ndResponder.start()
        agent.lp.apply {
            addDnsServer(dnsServerAddr)
            // A default route is needed for DnsResolver.java to send queries over IPv6
            // (see usage of DnsUtils.haveIpv6).
            addRoute(RouteInfo(IpPrefix("::/0"), null, null))
        }
        agent.sendLinkProperties(agent.lp)
        cb.eventuallyExpect<LinkPropertiesChanged> { it.lp.dnsServers.isNotEmpty() }
    }

    @After
    fun tearDown() {
        ndResponder.stop()
        if (::agent.isInitialized) {
            agent.unregister()
        }
        handlerThread.quitSafely()
        handlerThread.join()
    }
}