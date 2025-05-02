/*
 * Copyright (C) 2025 The Android Open Source Project
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
 * limitations under the License
 */

package android.net

import android.Manifest.permission.CHANGE_NETWORK_STATE
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_TEST
import android.net.TestNetworkManager.TestInterfaceRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.platform.test.annotations.AppModeFull
import android.provider.DeviceConfig.NAMESPACE_CONNECTIVITY
import androidx.test.platform.app.InstrumentationRegistry
import com.android.net.module.util.dhcp6.Dhcp6Packet
import com.android.net.module.util.dhcp6.Dhcp6SolicitPacket
import com.android.testutils.AutoCloseTestInterfaceRule
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.DeviceConfigRule
import com.android.testutils.EthernetTestInterface
import com.android.testutils.NdResponder
import com.android.testutils.RaPkt
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.runAsShell
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "Dhcp6PdTest"
private const val SHORT_TIMEOUT_MS = 200L
private const val TIMEOUT_MS = 2000L

private const val DHCP6_PFLAG_CONFIG = "ipclient_dhcpv6_pd_preferred_flag_version"

private val REQUEST: NetworkRequest = NetworkRequest.Builder()
        .addTransportType(TRANSPORT_ETHERNET)
        .addTransportType(TRANSPORT_TEST)
        .removeCapability(NET_CAPABILITY_INTERNET)
        .removeCapability(NET_CAPABILITY_TRUSTED)
        .build()
private val ROUTER_MAC = MacAddress.fromString("01:02:03:04:05:06")
private val ROUTER_V6 = InetAddress.getByName("fe80::0102:03ff:fe04:0506") as Inet6Address

@AppModeFull(reason = "Instant apps can't access EthernetManager")
@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class Dhcp6PdTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val cm = context.getSystemService(ConnectivityManager::class.java)!!
    private val handlerThread = HandlerThread("$TAG thread").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val networkCallback = TestableNetworkCallback().also {
        runAsShell(CHANGE_NETWORK_STATE) {
            val request = NetworkRequest.Builder()
                    .addTransportType(TRANSPORT_ETHERNET)
                    .addTransportType(TRANSPORT_TEST)
                    .removeCapability(NET_CAPABILITY_INTERNET)
                    .removeCapability(NET_CAPABILITY_TRUSTED)
                    .build()
            cm.requestNetwork(request, it, handler)
        }
    }

    @get:Rule(order = 1)
    val deviceConfigRule = DeviceConfigRule().apply {
        setConfig(NAMESPACE_CONNECTIVITY, DHCP6_PFLAG_CONFIG, "1")
    }

    @get:Rule(order = 2)
    val testInterfaceRule = AutoCloseTestInterfaceRule(context)

    private val iface: EthernetTestInterface
    init {
        val req = TestInterfaceRequest.Builder().setTap().build()
        val tap = testInterfaceRule.createTestInterface(req)
        iface = EthernetTestInterface(context, handler, tap)
    }
    private val localMac = iface.testIface.macAddress!!
    private val ndResponder = NdResponder(iface.packetReader).apply { start() }

    @After
    fun tearDown() {
        cm.unregisterNetworkCallback(networkCallback)
        // TODO: AutoCloseTestInterfaceRule should destroy associated EthernetTestInterface.
        iface.destroy()
        handlerThread.quitSafely()
        handlerThread.join()
    }

    private fun eventuallyExpectPacket(predicate: (ByteArray) -> Boolean): ByteArray {
        val p = iface.packetReader.poll(TIMEOUT_MS) {
            it != null && predicate(it)
        }
        assertNotNull(p)
        return p
    }

    private fun isDhcp6Packet(p: ByteArray): Boolean {
        val bb = ByteBuffer.wrap(p)
        bb.position(6 + 6)
        val etherType = bb.getShort()
        if ((etherType.toInt() and 0xffff) != 0x86dd) return false

        bb.position(14 + 6)
        val nextHeader = bb.get()
        if (nextHeader.toInt() != 17) return false

        bb.position(14 + 40 + 2)
        val dport = bb.getShort()
        if (dport.toInt() != 547) return false

        return true
    }

    private inline fun <reified T : Dhcp6Packet> expectDhcp6Packet(): T {
        val l2bytes = eventuallyExpectPacket(::isDhcp6Packet)
        val dhcp6bytes = l2bytes.drop(14 + 40 + 8).toByteArray()
        val packet = Dhcp6Packet.decode(dhcp6bytes, dhcp6bytes.size)
        assertIs<T>(packet)
        return packet as T
    }

    @Test
    fun testSolicit_triggeredByPflag() {
        val ra = RaPkt()
            .addPioOption(prefix = "2001:db8::/64", flags = "LAP")
            .addRdnssOption(dns = "2001:4860:4860::8888,2001:4860:4860::8844")
            .addSllaOption("1:2:3:4:5:6")
        ndResponder.addRouterEntry(ROUTER_MAC, ROUTER_V6, ra)
        expectDhcp6Packet<Dhcp6SolicitPacket>()
    }
}
