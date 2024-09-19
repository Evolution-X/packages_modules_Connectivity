/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.testutils

import android.net.DnsResolver
import android.net.InetAddresses
import android.net.Network
import android.os.Handler
import android.os.Looper
import com.android.internal.annotations.GuardedBy
import com.android.net.module.util.DnsPacket
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

// Nonexistent DNS query type to represent "A and/or AAAA queries".
// TODO: deduplicate this with DnsUtils.TYPE_ADDRCONFIG.
private const val TYPE_ADDRCONFIG = -1

class FakeDns(val network: Network, val dnsResolver: DnsResolver) {
    private val HANDLER_TIMEOUT_MS = 1000

    /** Data class to record the Dns entry.  */
    class DnsEntry (val hostname: String, val type: Int, val answerSupplier: AnswerSupplier) {
        // Full match or partial match that target host contains the entry hostname to support
        // random private dns probe hostname.
        fun matches(hostname: String, type: Int): Boolean {
            return hostname.endsWith(this.hostname) && type == this.type
        }
    }

    /**
     * Whether queries on [network] will be answered when private DNS is enabled. Queries that
     * bypass private DNS by using [network.privateDnsBypassingCopy] are always answered.
     */
    var nonBypassPrivateDnsWorking: Boolean = true

    @GuardedBy("answers")
    private val answers = mutableListOf<DnsEntry>()

    interface AnswerSupplier {
        /** Supplies the answer to one DnsResolver query method call.  */
        @Throws(DnsResolver.DnsException::class)
        fun get(): Array<String>?
    }

    private class InstantAnswerSupplier(val answers: Array<String>?) : AnswerSupplier {
        override fun get(): Array<String>? {
            return answers
        }
    }

    /** Clears all entries. */
    fun clearAll() = synchronized(answers) {
        answers.clear()
    }

    /** Returns the answer for a given name and type on the given mock network.  */
    private fun getAnswer(mockNetwork: Network, hostname: String, type: Int):
            CompletableFuture<Array<String>?> {
        if (!checkQueryNetwork(mockNetwork)) {
            return CompletableFuture.completedFuture(null)
        }
        val answerSupplier: AnswerSupplier? = synchronized(answers) {
            answers.firstOrNull({e: DnsEntry -> e.matches(hostname, type)})?.answerSupplier
        }
        if (answerSupplier == null) {
            return CompletableFuture.completedFuture(null)
        }
        if (answerSupplier is InstantAnswerSupplier) {
            // Save latency waiting for a query thread if the answer is hardcoded.
            return CompletableFuture.completedFuture<Array<String>?>(answerSupplier.get())
        }
        val answerFuture = CompletableFuture<Array<String>?>()
        // Don't worry about ThreadLeadMonitor: these threads terminate immediately, so they won't
        // leak, and ThreadLeakMonitor won't monitor them anyway, since they have one-time names
        // such as "Thread-42".
        Thread {
            try {
                answerFuture.complete(answerSupplier.get())
            } catch (e: DnsResolver.DnsException) {
                answerFuture.completeExceptionally(e)
            }
        }.start()
        return answerFuture
    }

    /** Sets the answer for a given name and type.  */
    fun setAnswer(hostname: String, answer: Array<String>?, type: Int) = setAnswer(
            hostname, InstantAnswerSupplier(answer), type)

    /** Sets the answer for a given name and type.  */
    fun setAnswer(
            hostname: String, answerSupplier: AnswerSupplier, type: Int) = synchronized (answers) {
        val ans = DnsEntry(hostname, type, answerSupplier)
        // Replace or remove the existing one.
        when (val index = answers.indexOfFirst { it.matches(hostname, type) }) {
            -1 -> answers.add(ans)
            else -> answers[index] = ans
        }
    }

    private fun checkQueryNetwork(mockNetwork: Network): Boolean {
        // Queries on the wrong network do not work.
        // Queries that bypass private DNS work.
        // Queries that do not bypass private DNS work only if nonBypassPrivateDnsWorking is true.
        return mockNetwork == network.privateDnsBypassingCopy ||
                mockNetwork == network && nonBypassPrivateDnsWorking
    }

    /** Simulates a getAllByName call for the specified name on the specified mock network.  */
    private fun getAllByName(mockNetwork: Network, hostname: String): Array<InetAddress>? {
        val answer = stringsToInetAddresses(queryAllTypes(mockNetwork, hostname)
            .get(HANDLER_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS))
        if (answer == null || answer.size == 0) {
            throw UnknownHostException(hostname)
        }
        return answer.toTypedArray()
    }

    // Regardless of the type, depends on what the responses contained in the network.
    private fun queryAllTypes(
        mockNetwork: Network, hostname: String
    ): CompletableFuture<Array<String>?> {
        val aFuture = getAnswer(mockNetwork, hostname, DnsResolver.TYPE_A)
                .exceptionally { emptyArray() }
        val aaaaFuture = getAnswer(mockNetwork, hostname, DnsResolver.TYPE_AAAA)
                .exceptionally { emptyArray() }
        val combinedFuture = CompletableFuture<Array<String>?>()
        aFuture.thenAcceptBoth(aaaaFuture) { res1: Array<String>?, res2: Array<String>? ->
            var answer: Array<String> = arrayOf()
            if (res1 != null) answer += res1
            if (res2 != null) answer += res2
            combinedFuture.complete(answer)
        }
        return combinedFuture
    }

    /** Starts mocking DNS queries.  */
    fun startMocking() {
        // Queries on mNetwork using getAllByName.
        doAnswer {
            getAllByName(it.mock as Network, it.getArgument(0))
        }.`when`(network).getAllByName(any())

        // Queries on mCleartextDnsNetwork using DnsResolver#query.
        doAnswer {
            mockQuery(it, posNetwork = 0, posHostname = 1, posExecutor = 3, posCallback = 5,
                posType = -1)
        }.`when`(dnsResolver).query(any(), any(), anyInt(), any(), any(), any())

        // Queries on mCleartextDnsNetwork using DnsResolver#query with QueryType.
        doAnswer {
            mockQuery(it, posNetwork = 0, posHostname = 1, posExecutor = 4, posCallback = 6,
                posType = 2)
        }.`when`(dnsResolver).query(any(), any(), anyInt(), anyInt(), any(), any(), any())

        // Queries using rawQuery. Currently, mockQuery only supports TYPE_SVCB.
        doAnswer {
            mockQuery(it, posNetwork = 0, posHostname = 1, posExecutor = 5, posCallback = 7,
                posType = 3)
        }.`when`(dnsResolver).rawQuery(any(), any(), anyInt(), anyInt(), anyInt(), any(), any(),
            any())
    }

    private fun stringsToInetAddresses(addrs: Array<String>?): List<InetAddress>? {
        if (addrs == null) return null
        val out: MutableList<InetAddress> = ArrayList()
        for (addr in addrs) {
            out.add(InetAddresses.parseNumericAddress(addr))
        }
        return out
    }

    // Mocks all the DnsResolver query methods used in this test.
    private fun mockQuery(
        invocation: InvocationOnMock, posNetwork: Int, posHostname: Int,
        posExecutor: Int, posCallback: Int, posType: Int
    ): Answer<*>? {
        val hostname = invocation.getArgument<String>(posHostname)
        val executor = invocation.getArgument<Executor>(posExecutor)
        val network = invocation.getArgument<Network>(posNetwork)
        val qtype = if (posType != -1) invocation.getArgument(posType) else TYPE_ADDRCONFIG
        val answerFuture: CompletableFuture<Array<String>?> = if (posType != -1) getAnswer(
            network,
            hostname,
            invocation.getArgument(posType)
        ) else queryAllTypes(network, hostname)

        // Discriminate between different callback types to avoid unchecked cast warnings when
        // calling the onAnswer methods.
        val inetAddressCallback: DnsResolver.Callback<List<InetAddress>> =
            invocation.getArgument(posCallback)
        val byteArrayCallback: DnsResolver.Callback<ByteArray> =
            invocation.getArgument(posCallback)
        val callback: DnsResolver.Callback<*> = invocation.getArgument(posCallback)

        answerFuture.whenComplete { answer: Array<String>?, exception: Throwable? ->
            // Use getMainLooper() because that's what android.net.DnsResolver currently uses.
            Handler(Looper.getMainLooper()).post {
                executor.execute {
                    if (exception != null) {
                        if (exception !is DnsResolver.DnsException) {
                            throw java.lang.AssertionError(
                                "Test error building DNS response",
                                exception
                            )
                        }
                        callback.onError((exception as DnsResolver.DnsException?)!!)
                        return@execute
                    }
                    if (answer != null && answer.size > 0) {
                        when (qtype) {
                            DnsResolver.TYPE_A, DnsResolver.TYPE_AAAA, TYPE_ADDRCONFIG ->
                                inetAddressCallback.onAnswer(stringsToInetAddresses(answer)!!, 0)
                            DnsPacket.TYPE_SVCB ->
                                byteArrayCallback.onAnswer(
                                    DnsSvcbUtils.makeSvcbResponse(hostname, answer), 0)
                            else -> throw UnsupportedOperationException(
                                "Unsupported qtype $qtype, update this fake"
                            )
                        }
                    }
                }
            }
        }
        // If the future does not complete or has no answer do nothing. The timeout should fire.
        return null
    }
}
