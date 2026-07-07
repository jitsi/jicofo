/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2024 - present 8x8, Inc.
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
package org.jitsi.jicofo.bridge.colibri

import org.jitsi.xmpp.extensions.colibri2.Connect
import java.net.URI

/**
 * A description of a single desired colibri2 `<connect>` on a bridge, with its [url] already resolved for that bridge.
 * The identity is [id]; a session keeps its connects keyed by it and signals add/update/remove as deltas. This is the
 * jicofo-side desired state, separate from the wire [Connect] (which also carries the create/expire delta markers).
 */
data class ConnectSpec(
    val id: String,
    val url: URI,
    val type: Connect.Types,
    val audio: Boolean = true,
    /** Source names exported to (sent to) the peer. */
    val exports: List<String> = emptyList(),
    /** Source names requested from (received from) the peer. */
    val requests: List<String> = emptyList(),
    val httpHeaders: Map<String, String> = emptyMap(),
    val ping: Ping? = null
) {
    data class Ping(val interval: Int, val timeout: Int)

    /** Build the wire [Connect] for this spec with the given delta markers. */
    fun toConnect(create: Boolean = false, expire: Boolean = false): Connect = Connect(
        id = id,
        url = url,
        protocol = Connect.Protocols.MEDIAJSON,
        type = type,
        audio = audio,
        create = create,
        expire = expire
    ).apply {
        httpHeaders.forEach { (name, value) -> addHttpHeader(name, value) }
        if (exports.isNotEmpty()) setExports(exports)
        if (requests.isNotEmpty()) setRequests(requests)
        ping?.let { setPing(it.interval, it.timeout) }
    }

    /**
     * Whether [other] (which must have the same [id]) describes the same connect, so no update needs to be signaled.
     * Source-name lists are compared as sets, since their order is not significant.
     */
    fun sameAs(other: ConnectSpec): Boolean = url == other.url &&
        type == other.type &&
        audio == other.audio &&
        httpHeaders == other.httpHeaders &&
        ping == other.ping &&
        exports.toSet() == other.exports.toSet() &&
        requests.toSet() == other.requests.toSet()
}
