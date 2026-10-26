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
package org.jitsi.jicofo.xmpp

import org.jxmpp.jid.EntityBareJid
import java.net.URLDecoder
import java.text.Normalizer
import java.util.Locale

private const val URI_COMPONENT_UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
private val ENCODED_URI_COMPONENT = Regex("(?:[A-Za-z0-9\\-_.!~*'()]|%[0-9A-Fa-f]{2})+")
private val TENANT_LOCALPART = Regex("\\[([^\\]]*)](.*)")

// jitsi-meet removes these characters from a URL path segment (_ROOM_EXCLUDE_PATTERN in
// react/features/base/util/uri.ts) before it decodes and normalizes the name. "!", "(", ")", "*" and "'" are
// unreserved for encodeURIComponent, so a round trip through decode and encode alone does not detect them.
private val REMOVED_BY_JITSI_MEET = Regex("[:?#\\[\\]@!$&'()*+,;=></\"]")

// A tenant comes from a label of the MUC domain, which the XMPP server prepares: nameprep makes it lowercase and
// keeps non-ASCII characters raw. nginx decodes the tenant from the URL path and config.js maps "." to "_", so
// jitsi-meet never joins a tenant with a "%" or a "." in the MUC. Jibri and the Prosody modules use the same rule.
private val TENANT = Regex("(?:[A-Za-z0-9_-]|[^\\x00-\\x7f])+")
private val HOST_NAME = Regex("(?:[A-Za-z0-9._-]|[^\\x00-\\x7f])+")

/**
 * Whether [name] is a room name equal, ignoring case, to what jitsi-meet's getBackendSafeRoomName produces. Components
 * such as Jibri build a URL from the room JID, and jitsi-meet decodes and encodes again the name it finds in the URL
 * path, so a name in any other form makes them join a room other than the one the request was for. For example, the
 * browser cuts "a?b" at the "?" and resolves ".." as a path, jitsi-meet decodes "%61" to "a", and jitsi-meet removes
 * the "*" from "a*". Jibri and the Prosody modules do a similar check.
 */
internal fun isBackendSafeRoomName(name: String): Boolean {
    if (name == "." || name == ".." || !ENCODED_URI_COMPONENT.matches(name)) {
        return false
    }
    if (REMOVED_BY_JITSI_MEET.containsMatchIn(name)) {
        return false
    }
    val decoded = URLDecoder.decode(name, Charsets.UTF_8)
    val normalized = Normalizer.normalize(decoded, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    // XMPP nodeprep makes the localpart lowercase, so a name that differs only in case is the same room.
    return encodeUriComponent(normalized).equals(name, ignoreCase = true)
}

/**
 * Whether the room name and the tenant of [room] are both in the form jitsi-meet uses. The tenant is either in the
 * domain (room@conference.tenant.example.com) or, in the form that mod_muc_domain_mapper uses internally, in the
 * localpart ([tenant]room@conference.example.com).
 */
internal fun isBackendSafeRoomJid(room: EntityBareJid): Boolean {
    val localpart = room.localpart.toString()
    val tenantMatch = TENANT_LOCALPART.matchEntire(localpart)
    val roomName = tenantMatch?.groupValues?.get(2) ?: localpart
    val tenantIsSafe = tenantMatch == null || TENANT.matches(tenantMatch.groupValues[1])
    // A tenant in the domain cannot be told apart from the rest of the domain, so a "." in it is not detected.
    return isBackendSafeRoomName(roomName) && tenantIsSafe && HOST_NAME.matches(room.domain.toString())
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
