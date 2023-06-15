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

import org.jitsi.jicofo.xmpp.IqProcessingResult.AcceptedWithResponse
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.iqrequest.AbstractIqRequestHandler
import org.jivesoftware.smack.iqrequest.IQRequestHandler
import org.jivesoftware.smack.packet.ErrorIQ
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError
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
    connections: Set<AbstractXMPPConnection>,
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
    abstract fun handleRequest(request: IqRequest<T>): IqProcessingResult

    private inner class IQRequestHandlerImpl(
        val connection: AbstractXMPPConnection,
        elementName: String,
        elementNamespace: String,
        iqType: IQ.Type,
        mode: IQRequestHandler.Mode
    ) : AbstractIqRequestHandler(elementName, elementNamespace, iqType, mode) {

        /**
         * Handle a request. The cast can not be checked because the type is erased, but we perform the cast here, so
         * any exceptions will occur early and not cause further problems.
         */
        @Suppress("UNCHECKED_CAST")
        override fun handleIQRequest(iq: IQ): IQ? {
            val result = handleRequest(
                IqRequest(
                    iq as? T ?: throw IllegalArgumentException("Unexpected IQ type: ${iq::class}"),
                    connection
                )
            )
            return when (result) {
                is AcceptedWithResponse -> result.response
                is IqProcessingResult.AcceptedWithNoResponse -> null
                is IqProcessingResult.RejectedWithError -> result.response
                is IqProcessingResult.NotProcessed ->
                    IQ.createErrorResponse(iq, StanzaError.Condition.feature_not_implemented)
            }
        }
    }
}

/** An IQ received on a specific [XMPPConnection] */
data class IqRequest<T : IQ>(val iq: T, val connection: AbstractXMPPConnection)

sealed class IqProcessingResult {
    /** The IQ was accepted/handled. The given `response` should be sent as a response. */
    class AcceptedWithResponse(val response: IQ) : IqProcessingResult()

    /**
     *  The IQ was accepted/handled, but no response is available (yet). The handler is responsible for eventually
     *  sending a response by other means.
     *  */
    class AcceptedWithNoResponse : IqProcessingResult()

    /** The IQ was handled, but it resulted in an error. The given error `response` should be sent as a response. */
    class RejectedWithError(val response: ErrorIQ) : IqProcessingResult() {
        constructor(
            request: IqRequest<*>,
            condition: StanzaError.Condition
        ) : this(request.iq, condition)
        constructor(
            iq: IQ,
            condition: StanzaError.Condition
        ) : this(IQ.createErrorResponse(iq, StanzaError.getBuilder(condition).build()))
    }

    /** The IQ was not handled. */
    class NotProcessed : IqProcessingResult()
}
