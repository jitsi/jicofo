/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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

package org.jitsi.impl.protocol.xmpp

import org.jitsi.xmpp.extensions.jingle.JingleAction
import org.json.simple.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class JingleStats {
    private val stanzasReceivedByAction: MutableMap<JingleAction, AtomicInteger> = ConcurrentHashMap()
    private val stanzasSentByAction: MutableMap<JingleAction, AtomicInteger> = ConcurrentHashMap()

    fun stanzaReceived(action: JingleAction) {
        stanzasReceivedByAction.computeIfAbsent(action) { AtomicInteger() }.incrementAndGet()
    }
    fun stanzaSent(action: JingleAction) {
        stanzasSentByAction.computeIfAbsent(action) { AtomicInteger() }.incrementAndGet()
    }

    fun toJson() = JSONObject().apply {
        this["sent"] = stanzasSentByAction.map { it.key to it.value.get() }.toMap()
        this["received"] = stanzasReceivedByAction.map { it.key to it.value.get() }.toMap()
    }
}
