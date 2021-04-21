/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2021-Present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.jicofo.xmpp

import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.iqrequest.AbstractIqRequestHandler
import org.jivesoftware.smack.iqrequest.IQRequestHandler
import org.jivesoftware.smack.packet.IQ
import java.lang.IllegalArgumentException

/**
 * Implements an IQ handler registered with a specific set of [XMPPConnection]s. Hides the Smack-level API and exposes
 * just [handleRequest], which uses the specific [IQ] subtype ([T]) and provides the connection on which it was received
 * as a parameter.
 */
abstract class AbstractIqHandler<T : IQ>(
    /**
     * The set of connections to which to register.
     */
    connections: Set<XMPPConnection>,
    /**
     * The name of the IQ child element for this handler. It must match the element name returned by [T] instances.
     */
    elementName: String,
    /**
     * The namespace of the IQ child element for this handler. It must match the namespace returned by [T] instances.
     */
    elementNamespace: String,
    iqTypes: Set<IQ.Type> = setOf(IQ.Type.set, IQ.Type.get),
    mode: IQRequestHandler.Mode = IQRequestHandler.Mode.sync
) {
    /**
     * One handler for each (connection, iqType) pair. These are the actual instances registered with the
     * [XMPPConnection]s.
     */
    private val handlers: List<IQRequestHandlerImpl> = connections.flatMap { connection ->
        iqTypes.map { iqType ->
            IQRequestHandlerImpl(connection, elementName, elementNamespace, iqType, mode).also {
                connection.registerIQRequestHandler(it)
            }
        }
    }

    fun shutdown() = handlers.forEach { it.connection.unregisterIQRequestHandler(it) }

    /**
     * Handle a request that was received on one of the [XMPPConnection]s.
     *
     * @return A non-null value to be sent as a response (should be either an IQ of type `result`, or an Error), or
     * `null` if no response is to be sent immediately. If this returns `null`, the code handling the IQ takes the
     * responsibility of (eventually) sending a response. The response, whether returned by the method or sent in
     * another way, should have the correct properties for an XMPP response (`from` and `to` swapped, matching `id`,
     * and type `result` if the response is an IQ).
     */
    abstract fun handleRequest(request: IqRequest<T>): IQ?

    private inner class IQRequestHandlerImpl(
        val connection: XMPPConnection,
        elementName: String,
        elementNamespace: String,
        iqType: IQ.Type,
        mode: IQRequestHandler.Mode
    ) : AbstractIqRequestHandler(elementName, elementNamespace, iqType, mode) {

        override fun handleIQRequest(iq: IQ): IQ? = handleRequest(
            IqRequest(
                iq as? T ?: throw IllegalArgumentException("Unexpected IQ type: ${iq::class}"),
                connection
            )
        )
    }
}

/** An IQ received on a specific [XMPPConnection] */
data class IqRequest<T>(val iq: T, val connection: XMPPConnection)
