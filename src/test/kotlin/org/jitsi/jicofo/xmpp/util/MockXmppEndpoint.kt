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
import mock.xmpp.MockXmppConnection
import org.jitsi.xmpp.extensions.rayo.RayoIqProvider
import org.jivesoftware.smack.iqrequest.AbstractIqRequestHandler
import org.jivesoftware.smack.iqrequest.IQRequestHandler
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.XMPPError
import org.jxmpp.jid.Jid

open class MockXmppEndpoint(val jid: Jid) {
    val xmppConnection = MockXmppConnection(jid)
    fun shutdown() = xmppConnection.disconnect()
}

class MockJigasi(
    jid: Jid,
    var response: Response = Response.Success
) : MockXmppEndpoint(jid) {
    init {
        xmppConnection.registerIQRequestHandler(
            object : AbstractIqRequestHandler(
                RayoIqProvider.DialIq.ELEMENT_NAME, RayoIqProvider.NAMESPACE, IQ.Type.set, IQRequestHandler.Mode.sync
            ) {
                override fun handleIQRequest(iq: IQ): IQ? {
                    return when (response) {
                        Response.Success -> IQ.createResultIQ(iq)
                        Response.Failure -> IQ.createErrorResponse(iq, XMPPError.Condition.internal_server_error)
                        Response.Timeout -> null
                    }
                }
            }
        )
    }

    enum class Response { Success, Failure, Timeout }
}
