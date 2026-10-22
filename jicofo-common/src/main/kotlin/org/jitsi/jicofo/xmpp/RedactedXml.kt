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

import org.jitsi.xmpp.util.RedactColibri
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smack.packet.TopLevelStreamElement

/**
 * The element as XML for a log line, with colibri connect header values and conference tokens redacted. Never
 * throws, so that logging a stanza cannot change what the caller does: a placeholder names the stanza instead.
 */
fun TopLevelStreamElement.toRedactedXml(): String =
    redactXml(toXML().toString(), (this as? Stanza)?.let { "${it.elementName} ${it.stanzaId}" } ?: elementName)

/**
 * [xml] with colibri connect header values and conference tokens redacted. Each transform runs only when the XML
 * has what it redacts; the transforms parse the XML, so an element Smack serialized with a character XML does not
 * allow fails, and [label] stands in for it then, never the XML itself.
 */
fun redactXml(xml: String, label: String = "stanza"): String = try {
    var redacted = xml
    if (redacted.contains("http-header")) {
        redacted = RedactColibri.redactHttpHeaderValues(redacted)
    }
    if (redacted.contains("token=")) {
        redacted = RedactColibri.redactToken(redacted)
    }
    redacted
} catch (e: Exception) {
    "[$label could not be redacted: ${e.javaClass.simpleName}]"
}
