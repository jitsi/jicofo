/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2018 - present 8x8, Inc.
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

import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension
import org.jxmpp.jid.DomainBareJid
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.stringprep.XmppStringprepException

/**
 * Reads the original jid as encoded in the resource part by mod_client_proxy, returns the original jid if it format
 * is not as expeceted.
 */
fun parseJidFromClientProxyJid(
    /**
     * The JID of the client_proxy component.
     */
    clientProxy: DomainBareJid?,
    /**
     * The JID to parse.
     */
    jid: Jid
): Jid {

    clientProxy ?: return jid

    if (clientProxy == jid.asDomainBareJid()) {
        jid.resourceOrNull?.let { resource ->
            return try {
                JidCreate.from(resource.toString())
            } catch (e: XmppStringprepException) {
                jid
            }
        }
    }
    return jid
}

fun ColibriStatsExtension.getDouble(name: String): Double? = try {
        getValueAsString(name)?.toDouble()
    } catch (e: Exception) {
        null
    }
