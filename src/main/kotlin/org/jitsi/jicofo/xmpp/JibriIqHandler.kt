/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2017-Present 8x8, Inc.
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

import org.jitsi.jicofo.recording.jibri.BaseJibriRecorder
import org.jitsi.jicofo.util.ErrorResponse
import org.jitsi.xmpp.extensions.jibri.JibriIq
import org.jivesoftware.smack.iqrequest.AbstractIqRequestHandler
import org.jivesoftware.smack.iqrequest.IQRequestHandler
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.XMPPError
import java.util.*

/**
 * A Smack [IQRequestHandler] for "jibri" IQs. Terminates all "jibri" IQs received by Smack, but delegates their
 * handling to specific [BaseJibriRecorder] instances.
 */
class JibriIqHandler() :
    AbstractIqRequestHandler(JibriIq.ELEMENT_NAME, JibriIq.NAMESPACE, IQ.Type.set, IQRequestHandler.Mode.async) {

    private val jibris = Collections.synchronizedList(LinkedList<BaseJibriRecorder>())

    fun addJibri(jibri: BaseJibriRecorder) {
        jibris.add(jibri)
    }

    fun removeJibri(jibri: BaseJibriRecorder?) {
        jibris.remove(jibri)
    }

    /**
     * {@inheritDoc}
     * Pass the request to the first [BaseJibriRecorder] that wants it.
     */
    override fun handleIQRequest(iq: IQ): IQ = synchronized(jibris) {
        iq as JibriIq
        return jibris.find { it.accept(iq) }?.handleIQRequest(iq)
            ?: ErrorResponse.create(iq, XMPPError.Condition.item_not_found, null)
    }
}
