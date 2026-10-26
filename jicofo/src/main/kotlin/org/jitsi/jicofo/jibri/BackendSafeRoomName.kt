/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2026 - present 8x8, Inc.
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
package org.jitsi.jicofo.jibri

import org.jxmpp.jid.EntityBareJid
import java.net.URLDecoder
import java.text.Normalizer
import java.util.Locale

private const val URI_COMPONENT_UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
private val ENCODED_URI_COMPONENT = Regex("(?:[A-Za-z0-9\\-_.!~*'()]|%[0-9A-Fa-f]{2})+")
private val TENANT_LOCALPART = Regex("\\[([^\\]]*)](.*)")

/**
 * Whether [name] is a room name (or tenant) exactly as jitsi-meet's getBackendSafeRoomName produces it. Jibri builds
 * a URL from the room JID, and jitsi-meet decodes and encodes again the name it finds in the URL path, so a name in any
 * other form makes Jibri join a room other than the one the request was authorized for. For example, "a?b" is cut at
 * the "?", and "%61" becomes "a". This is the same check that Jibri does.
 */
internal fun isBackendSafeRoomName(name: String): Boolean {
    if (!ENCODED_URI_COMPONENT.matches(name)) {
        return false
    }
    val decoded = URLDecoder.decode(name, Charsets.UTF_8)
    val normalized = Normalizer.normalize(decoded, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    // Prosody makes the localpart lowercase, so a name that differs only in case is the same room.
    return encodeUriComponent(normalized).equals(name, ignoreCase = true)
}

/**
 * Whether the room name and the tenant of [room] are both in the form jitsi-meet uses. The tenant is either in the
 * domain (room@conference.tenant.example.com) or, in the form that mod_muc_domain_mapper uses internally, in the
 * localpart ([tenant]room@conference.example.com).
 */
internal fun isBackendSafeRoomJid(room: EntityBareJid): Boolean {
    val localpart = room.localpart.toString()
    val roomNames = TENANT_LOCALPART.matchEntire(localpart)?.destructured?.toList() ?: listOf(localpart)
    // A host name (letters, digits, "-" and ".") is always backend-safe, so checking the whole domain checks the tenant.
    return (roomNames + room.domain.toString()).all { isBackendSafeRoomName(it) }
}

private fun encodeUriComponent(str: String): String = buildString {
    for (byte in str.toByteArray(Charsets.UTF_8)) {
        val b = byte.toInt() and 0xff
        if (b < 0x80 && b.toChar() in URI_COMPONENT_UNRESERVED) {
            append(b.toChar())
        } else {
            append("%%%02x".format(b))
        }
    }
}
