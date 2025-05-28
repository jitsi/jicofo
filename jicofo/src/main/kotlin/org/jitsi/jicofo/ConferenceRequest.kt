/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2022 - present 8x8, Inc.
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
package org.jitsi.jicofo

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jitsi.xmpp.extensions.jitsimeet.ConferenceIq
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.stringprep.XmppStringprepException
import kotlin.jvm.Throws

/**
 * The initial request to create or join a conference, a generic version of [ConferenceIq].
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
class ConferenceRequest(
    var room: String? = null,
    var ready: Boolean? = null,
    var focusJid: String? = null,
    var sessionId: String? = null,
    var machineUid: String? = null,
    var identity: String? = null,
    var vnode: String? = null,
    val properties: MutableMap<String, String> = mutableMapOf()
) {
    @Throws(XmppStringprepException::class)
    fun toConferenceIq(token: String? = null) = ConferenceIq().apply {
        this@ConferenceRequest.room?.let {
            room = JidCreate.entityBareFrom(it)
        }
        this@ConferenceRequest.ready?.let {
            isReady = it
        }
        this@ConferenceRequest.focusJid?.let {
            focusJid = it
        }
        this@ConferenceRequest.sessionId?.let {
            sessionId = it
        }
        this@ConferenceRequest.machineUid?.let {
            machineUID = it
        }
        this@ConferenceRequest.identity?.let {
            identity = it
        }
        this@ConferenceRequest.vnode?.let {
            vnode = it
        }
        token?.let {
            setToken(it)
        }
        this@ConferenceRequest.properties.forEach { (k, v) -> addProperty(k, v) }
    }

    fun toJson(): String = mapper.writeValueAsString(this)

    companion object {
        fun fromConferenceIq(iq: ConferenceIq) = ConferenceRequest(
            room = iq.room.toString(),
            ready = iq.isReady,
            focusJid = iq.focusJid,
            sessionId = iq.sessionId,
            machineUid = iq.machineUID,
            identity = iq.identity,
            vnode = iq.vnode,
            properties = iq.propertiesMap
        )

        private val mapper = jacksonObjectMapper()

        @JvmStatic
        @Throws(JsonProcessingException::class, JsonMappingException::class)
        fun parseJson(s: String): ConferenceRequest = mapper.readValue(s, ConferenceRequest::class.java)
    }
}
