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

package android.net

import android.net.ConnectivityManager.NetworkCallback
import android.net.ConnectivityManager.NetworkCallbackMethodsHolder
import android.os.Build
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.any
import org.mockito.Mockito.doCallRealMethod
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails

@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
@RunWith(DevSdkIgnoreRunner::class)
class NetworkCallbackFlagsTest {

    // To avoid developers forgetting to update NETWORK_CB_METHODS when modifying NetworkCallbacks,
    // or using wrong values, calculate it from annotations here and verify that it matches.
    // This avoids the runtime cost of reflection, but still ensures that the list is correct.
    @Test
    fun testNetworkCallbackMethods_calculateFromAnnotations_matchesHardcodedList() {
        val calculatedMethods = getNetworkCallbackMethodsFromAnnotations()
        assertEquals(
            calculatedMethods.toSet(),
            NetworkCallbackMethodsHolder.NETWORK_CB_METHODS.map {
                NetworkCallbackMethodWithEquals(
                    it.mName,
                    it.mParameterTypes.toList(),
                    callbacksCallingThisMethod = it.mCallbacksCallingThisMethod
                )
            }.toSet()
        )
    }

    data class NetworkCallbackMethodWithEquals(
        val name: String,
        val parameterTypes: List<Class<*>>,
        val callbacksCallingThisMethod: Int
    )

    data class NetworkCallbackMethodBuilder(
        val name: String,
        val parameterTypes: List<Class<*>>,
        val isFinal: Boolean,
        val methodId: Int,
        val mayCall: Set<Int>?,
        var callbacksCallingThisMethod: Int
    ) {
        fun build() = NetworkCallbackMethodWithEquals(
            name,
            parameterTypes,
            callbacksCallingThisMethod
        )
    }

    /**
     * Build [NetworkCallbackMethodsHolder.NETWORK_CB_METHODS] from [NetworkCallback] annotations.
     */
    private fun getNetworkCallbackMethodsFromAnnotations(): List<NetworkCallbackMethodWithEquals> {
        val parsedMethods = mutableListOf<NetworkCallbackMethodBuilder>()
        val methods = NetworkCallback::class.java.declaredMethods
        methods.forEach { method ->
            val cb = method.getAnnotation(
                NetworkCallback.FilteredCallback::class.java
            ) ?: return@forEach
            val callbacksCallingThisMethod = if (cb.calledByCallbackId == 0) {
                0
            } else {
                1 shl cb.calledByCallbackId
            }
            parsedMethods.add(
                NetworkCallbackMethodBuilder(
                    method.name,
                    method.parameterTypes.toList(),
                    Modifier.isFinal(method.modifiers),
                    cb.methodId,
                    cb.mayCall.toSet(),
                    callbacksCallingThisMethod
                )
            )
        }

        // Propagate callbacksCallingThisMethod for transitive calls
        do {
            var hadChange = false
            parsedMethods.forEach { caller ->
                parsedMethods.forEach { callee ->
                    if (caller.mayCall?.contains(callee.methodId) == true) {
                        // Callbacks that call the caller also cause calls to the callee. So
                        // callbacksCallingThisMethod for the callee should include
                        // callbacksCallingThisMethod from the caller.
                        val newValue =
                            caller.callbacksCallingThisMethod or callee.callbacksCallingThisMethod
                        hadChange = hadChange || callee.callbacksCallingThisMethod != newValue
                        callee.callbacksCallingThisMethod = newValue
                    }
                }
            }
        } while (hadChange)

        // Final methods may affect the flags for transitive calls, but cannot be overridden, so do
        // not need to be in the list (no overridden method in NetworkCallback will match them).
        return parsedMethods.filter { !it.isFinal }.map { it.build() }
    }

    @Test
    fun testMethodsAreAnnotated() {
        val annotations = NetworkCallback::class.java.declaredMethods.mapNotNull { method ->
            if (!Modifier.isPublic(method.modifiers) && !Modifier.isProtected(method.modifiers)) {
                return@mapNotNull null
            }
            val annotation = method.getAnnotation(NetworkCallback.FilteredCallback::class.java)
            assertNotNull(annotation, "$method is missing the @FilteredCallback annotation")
            return@mapNotNull annotation
        }

        annotations.groupingBy { it.methodId }.eachCount().forEach { (methodId, cnt) ->
            assertEquals(1, cnt, "Method ID $methodId is used more than once in @FilteredCallback")
        }
    }

    @Test
    fun testObviousCalleesAreInAnnotation() {
        NetworkCallback::class.java.declaredMethods.forEach { method ->
            val annotation = method.getAnnotation(NetworkCallback.FilteredCallback::class.java)
                ?: return@forEach
            val missingFlags = getObviousCallees(method).toMutableSet().apply {
                removeAll(annotation.mayCall.toSet())
            }
            val msg = "@FilteredCallback on $method is missing flags " +
                    "$missingFlags in mayCall. There may be other " +
                    "calls that are not detected if they are done conditionally."
            assertEquals(emptySet(), missingFlags, msg)
        }
    }

    /**
     * Invoke the specified NetworkCallback method with mock arguments, return a set of transitively
     * called methods.
     *
     * This provides an idea of which methods are transitively called by the specified method. It's
     * not perfect as some callees could be called or not depending on the exact values of the mock
     * arguments that are passed in (for example, onAvailable calls onNetworkSuspended only if the
     * capabilities lack the NOT_SUSPENDED capability), but it should catch obvious forgotten calls.
     */
    private fun getObviousCallees(method: Method): Set<Int> {
        // Create a mock NetworkCallback that mocks all methods except the one specified by the
        // caller.
        val mockCallback = mock(NetworkCallback::class.java)

        if (!Modifier.isFinal(method.modifiers) ||
            // The mock class will be NetworkCallback (not a subclass) if using mockito-inline,
            // which mocks final methods too
            mockCallback.javaClass == NetworkCallback::class.java) {
            doCallRealMethod().`when`(mockCallback).let { mockObj ->
                val anyArgs = method.parameterTypes.map { any(it) }
                method.invoke(mockObj, *anyArgs.toTypedArray())
            }
        }

        // Invoke the target method with mock parameters
        val mockParameters = method.parameterTypes.map { getMockFor(method, it) }
        method.invoke(mockCallback, *mockParameters.toTypedArray())

        // Aggregate callees
        val mockingDetails = mockingDetails(mockCallback)
        return mockingDetails.invocations.mapNotNull { inv ->
            if (inv.method == method) {
                null
            } else {
                inv.method.getAnnotation(NetworkCallback.FilteredCallback::class.java)?.methodId
            }
        }.toSet()
    }

    private fun getMockFor(method: Method, c: Class<*>): Any {
        if (!c.isPrimitive && !Modifier.isFinal(c.modifiers)) {
            return mock(c)
        }
        return when (c) {
            NetworkCapabilities::class.java -> NetworkCapabilities()
            LinkProperties::class.java -> LinkProperties()
            LocalNetworkInfo::class.java -> LocalNetworkInfo(null)
            Boolean::class.java -> false
            Int::class.java -> 0
            else -> fail("No mock set for parameter type $c used in $method")
        }
    }
}
