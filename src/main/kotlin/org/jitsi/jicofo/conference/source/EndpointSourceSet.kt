/*
 * Copyright @ 2021 - present 8x8, Inc.
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
import org.jxmpp.jid.Jid
import java.lang.IllegalArgumentException
import kotlin.jvm.Throws

/** A set of [Source]s and [SsrcGroup]s, usually associated with an endpoint. */
data class EndpointSourceSet(
    val sources: Set<Source> = emptySet(),
    val ssrcGroups: Set<SsrcGroup> = emptySet(),
) {
    constructor(source: Source) : this(setOf(source))
    constructor(ssrcGroup: SsrcGroup) : this(ssrcGroups = setOf(ssrcGroup))

    /**
     * Whether this set of sources is empty.
     * Note: it is not clear whether it makes sense to describe any [SsrcGroup]s without corresponding sources, but
     * for this representation we allow it.
     */
    fun isEmpty() = sources.isEmpty() && ssrcGroups.isEmpty()

    /**
     * Creates a list of Jingle [ContentPacketExtension]s that describe the sources in this [EndpointSourceSet].
     */
    fun toJingle(owner: Jid? = null): List<ContentPacketExtension> = toJingle(mutableMapOf(), owner)

    val audioSsrcs: Set<Long> by lazy { getSsrcs(MediaType.AUDIO) }
    val videoSsrcs: Set<Long> by lazy { getSsrcs(MediaType.VIDEO) }

    private fun getSsrcs(mediaType: MediaType) = sources.filter { it.mediaType == mediaType }.map { it.ssrc }.toSet()

    /**
     * A concise string more suitable for logging. This may be slow for large sets.
     */
    override fun toString() = "[audio=$audioSsrcs, video=$videoSsrcs, groups=$ssrcGroups]"

    companion object {
        /** An [EndpointSourceSet] instance with no sources or source groups */
        val EMPTY = EndpointSourceSet()

        /**
         * Parses a list of Jingle [ContentPacketExtension]s into an [EndpointSourceSet].
         *
         * @throws IllegalArgumentException if the media type of any of the contents is invalid, or the semantics of
         * any of the SSRC groups is invalid.
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class)
        fun fromJingle(
            contents: List<ContentPacketExtension>
        ): EndpointSourceSet {
            val sources = mutableSetOf<Source>()
            val ssrcGroups = mutableSetOf<SsrcGroup>()

            contents.forEach { content ->
                val rtpDesc: RtpDescriptionPacketExtension? =
                    content.getFirstChildOfType(RtpDescriptionPacketExtension::class.java)

                val mediaTypeString = if (rtpDesc != null) rtpDesc.media else content.name
                val mediaType = MediaType.parseString(mediaTypeString)

                // The previous code looked for [SourcePacketExtension] as children of both the "content" and
                // "description" elements, so this is reproduced here. I don't know which one is correct and/or used.
                rtpDesc?.let {
                    rtpDesc.getChildExtensionsOfType(SourcePacketExtension::class.java).forEach { spe ->
                        sources.add(Source(mediaType, spe))
                    }
                    rtpDesc.getChildExtensionsOfType(SourceGroupPacketExtension::class.java).forEach { sgpe ->
                        ssrcGroups.add(SsrcGroup.fromPacketExtension(sgpe, mediaType))
                    }
                }
                content.getChildExtensionsOfType(SourcePacketExtension::class.java).forEach { spe ->
                    sources.add(Source(mediaType, spe))
                }
            }

            return EndpointSourceSet(sources, ssrcGroups)
        }
    }
}

/**
 * Populates a list of Jingle [ContentPacketExtension]s with extensions that describe the sources in this
 * [EndpointSourceSet]. Creates new [ContentPacketExtension] if necessary, returns the [ContentPacketExtension]s
 * as a list.
 */
fun EndpointSourceSet.toJingle(
    contentMap: MutableMap<MediaType, ContentPacketExtension>,
    owner: Jid?
): List<ContentPacketExtension> {

    sources.forEach { source ->
        val content = contentMap.computeIfAbsent(source.mediaType) {
            ContentPacketExtension().apply { name = source.mediaType.toString() }
        }
        val rtpDesc = content.getOrCreateRtpDescription()
        rtpDesc.addChildExtension(source.toPacketExtension(owner))
    }

    ssrcGroups.forEach { ssrcGroup ->
        val content = contentMap.computeIfAbsent(ssrcGroup.mediaType) {
            ContentPacketExtension().apply { name = ssrcGroup.mediaType.toString() }
        }
        val rtpDesc = content.getOrCreateRtpDescription()
        rtpDesc.addChildExtension(ssrcGroup.toPacketExtension())
    }

    return contentMap.values.toList()
}

/**
 * Returns the [RtpDescriptionPacketExtension] child of this [ContentPacketExtension], creating it if it doesn't
 * exist.
 */
private fun ContentPacketExtension.getOrCreateRtpDescription() =
    getChildExtension(RtpDescriptionPacketExtension::class.java) ?: RtpDescriptionPacketExtension().apply {
        media = name
        this@getOrCreateRtpDescription.addChildExtension(this)
    }

operator fun EndpointSourceSet?.plus(other: EndpointSourceSet?): EndpointSourceSet =
    when {
        this == null && other == null -> EndpointSourceSet.EMPTY
        this == null -> other!!
        other == null -> this
        else -> EndpointSourceSet(
            sources + other.sources,
            ssrcGroups + other.ssrcGroups
        )
    }

operator fun EndpointSourceSet.minus(other: EndpointSourceSet): EndpointSourceSet =
    EndpointSourceSet(sources - other.sources, ssrcGroups - other.ssrcGroups)

operator fun EndpointSourceSet.plus(sources: Set<Source>) =
    EndpointSourceSet(this.sources + sources, this.ssrcGroups)

operator fun EndpointSourceSet.plus(source: Source) =
    EndpointSourceSet(this.sources + source, this.ssrcGroups)

operator fun EndpointSourceSet.plus(ssrcGroup: SsrcGroup) =
    EndpointSourceSet(this.sources, this.ssrcGroups + ssrcGroup)
