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
package org.jitsi.jicofo.conference.source

import org.jitsi.utils.MediaType
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jitsi.xmpp.extensions.jingle.RtpDescriptionPacketExtension
import org.jitsi.xmpp.extensions.jingle.SourceGroupPacketExtension
import org.jitsi.xmpp.extensions.jitsimeet.SSRCInfoPacketExtension
import org.jxmpp.jid.Jid

fun ConferenceSourceMap.numSourcesOfType(mediaType: MediaType) =
    values.flatMap { it.sources }.count { it.mediaType == mediaType }
fun ConferenceSourceMap.numSourceGroupsOfype(mediaType: MediaType) =
    values.flatMap { it.ssrcGroups }.count { it.mediaType == mediaType }
fun EndpointSourceSet.getFirstSourceOfType(mediaType: MediaType) = sources.firstOrNull { it.mediaType == mediaType }

fun SourcePacketExtension.getOwner() = getFirstChildOfType(SSRCInfoPacketExtension::class.java)?.owner

/** Parse a [ConferenceSourceMap] from a list of Jingle contents, trusting the "owner" field encoded in the
 * sources. We only need this for testing, because normally we do not trust the "owner" field and assign the
 * advertised sources to the endpoint that sent the request. */
fun parseConferenceSourceMap(contents: List<ContentPacketExtension>): ConferenceSourceMap {
    val sourceSets = mutableMapOf<Jid?, EndpointSourceSet>()
    fun findOwner(ssrc: Long): Jid? = sourceSets.entries.find { it.value.sources.any { it.ssrc == ssrc } }?.key

    contents.forEach { content ->
        val rtpDesc: RtpDescriptionPacketExtension? =
            content.getFirstChildOfType(RtpDescriptionPacketExtension::class.java)

        val mediaTypeString = if (rtpDesc != null) rtpDesc.media else content.name
        val mediaType = MediaType.parseString(mediaTypeString)

        // The previous code looked for [SourcePacketExtension] as children of both the "content" and
        // "description" elements, so this is reproduced here. I don't know which one is correct and/or used.
        rtpDesc?.let {
            rtpDesc.getChildExtensionsOfType(SourcePacketExtension::class.java).forEach { spe ->
                val owner = spe.getOwner()
                val ownerSourceSet = sourceSets.computeIfAbsent(owner) { EndpointSourceSet.EMPTY }
                sourceSets[owner] = ownerSourceSet + Source(mediaType, spe)
            }
            rtpDesc.getChildExtensionsOfType(SourceGroupPacketExtension::class.java).forEach { sgpe ->
                val ssrcGroup = SsrcGroup.fromPacketExtension(sgpe, mediaType)
                val owner = findOwner(ssrcGroup.ssrcs[0])
                val ownerSourceSet = sourceSets.computeIfAbsent(owner) { EndpointSourceSet.EMPTY }
                sourceSets[owner] = ownerSourceSet + ssrcGroup
            }
        }
        content.getChildExtensionsOfType(SourcePacketExtension::class.java).forEach { spe ->
            val owner = spe.getOwner()
            val ownerSourceSet = sourceSets.computeIfAbsent(owner) { EndpointSourceSet.EMPTY }
            sourceSets[owner] = ownerSourceSet + Source(mediaType, spe)
        }
    }
    return ConferenceSourceMap(sourceSets)
}
