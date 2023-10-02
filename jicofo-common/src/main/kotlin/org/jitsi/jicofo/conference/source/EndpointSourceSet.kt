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
import org.jitsi.utils.MediaType.AUDIO
import org.jitsi.utils.MediaType.VIDEO
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jitsi.xmpp.extensions.jingle.RtpDescriptionPacketExtension
import org.jitsi.xmpp.extensions.jingle.SourceGroupPacketExtension
import org.json.simple.JSONArray
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
     * Whether there are any audio sources in this set.
     */
    val hasAudio: Boolean by lazy { sources.any { it.mediaType == AUDIO } }

    /**
     * Whether there are any video sources in this set.
     */
    val hasVideo: Boolean by lazy { sources.any { it.mediaType == VIDEO } }

    /**
     * Creates a list of Jingle [ContentPacketExtension]s that describe the sources in this [EndpointSourceSet].
     */
    fun toJingle(owner: String? = null): List<ContentPacketExtension> = toJingle(mutableMapOf(), owner)

    val audioSsrcs: Set<Long> by lazy { getSsrcs(AUDIO) }
    val videoSsrcs: Set<Long> by lazy { getSsrcs(VIDEO) }

    private fun getSsrcs(mediaType: MediaType) = sources.filter { it.mediaType == mediaType }.map { it.ssrc }.toSet()

    /**
     * A concise string more suitable for logging. This may be slow for large sets.
     */
    override fun toString() = "[audio=$audioSsrcs, video=$videoSsrcs, groups=$ssrcGroups]"

    /**
     * Get a new [EndpointSourceSet] by stripping all simulcast-related SSRCs and groups except for the first SSRC in
     * a simulcast group. This assumes that the first SSRC in the simulcast group and its associated RTX SSRC are the only
     * SSRCs that receivers of simulcast streams need to know about, i.e. that jitsi-videobridge uses that SSRC as the
     * target SSRC when rewriting streams.
     */
    val stripSimulcast: EndpointSourceSet by lazy {
        doStripSimulcast()
    }

    /**
     * A compact JSON representation of this [EndpointSourceSet] (optimized for size). This is done ad-hoc instead of
     * using e.g. jackson because the format is substantially different than the natural representation of
     * [EndpointSourceSet], we only need serialization support (no parsing) and to keep it separated from the main code.
     *
     * The JSON is an array of up to four elements, with index 0 encoding the video sources, index 1 encoding the video
     * source groups, index 2 encoding the audio sources, and index 3 encoding the audio source groups. Trailing empty
     * elements may be skipped, e.g. if there are no audio source groups the list will only have 3 elements.
     *
     * Each element is itself a JSON array of the `compactJson` representation of the sources or SSRC groups.
     *
     * For example, an [EndpointSourceSet] with video sources 1, 2 grouped in a Fid group and an audio source 3 may be
     * encoded as:
     * [
     *   // Array of video sources
     *   [
     *     {"s": 1},
     *     {"s": 2}
     *   ],
     *   // Array of video SSRC groups
     *   [
     *     ["f", 1, 2]
     *   ],
     *   // Array of audio sources
     *   [
     *     {"s": 3}
     *   ]
     *   // Empty array of audio SSRC groups not present.
     * ]
     */
    val compactJson: String by lazy {
        buildString {
            append("[[")
            sources.filter { it.mediaType == VIDEO }.forEachIndexed { i, source ->
                if (i > 0) append(",")
                append(source.compactJson)
            }
            append("],[")
            ssrcGroups.filter { it.mediaType == VIDEO }.forEachIndexed { i, ssrcGroup ->
                if (i > 0) append(",")
                append(ssrcGroup.compactJson)
            }
            append("],[")
            sources.filter { it.mediaType == AUDIO }.forEachIndexed { i, source ->
                if (i > 0) append(",")
                append(source.compactJson)
            }
            append("]")
            if (ssrcGroups.any { it.mediaType == AUDIO }) {
                append(",[")
                ssrcGroups.filter { it.mediaType == AUDIO }.forEachIndexed { i, ssrcGroup ->
                    if (i > 0) append(",")
                    append(ssrcGroup.compactJson)
                }
                append("]")
            }
            append("]")
        }
    }

    /** Expanded JSON format used for debugging. */
    fun toJson() = OrderedJsonObject().apply {
        put(
            "sources",
            JSONArray().apply {
                sources.forEach { add(it.toJson()) }
            }
        )
        put(
            "groups",
            JSONArray().apply {
                ssrcGroups.forEach { add(it.toJson()) }
            }
        )
    }

    companion object {
        /** An [EndpointSourceSet] instance with no sources or source groups */
        @JvmField
        val EMPTY = EndpointSourceSet()

        /**
         * Parses a list of Jingle [ContentPacketExtension]s into an [EndpointSourceSet].
         *
         * @throws IllegalArgumentException if the media type of any of the contents is invalid, or the semantics of
         * any of the SSRC groups is invalid.
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class)
        fun fromJingle(contents: List<ContentPacketExtension>): EndpointSourceSet {
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
    owner: String?
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

operator fun EndpointSourceSet?.plus(other: EndpointSourceSet?): EndpointSourceSet = when {
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

operator fun EndpointSourceSet.plus(sources: Set<Source>) = EndpointSourceSet(this.sources + sources, this.ssrcGroups)

operator fun EndpointSourceSet.plus(source: Source) = EndpointSourceSet(this.sources + source, this.ssrcGroups)

operator fun EndpointSourceSet.plus(ssrcGroup: SsrcGroup) = EndpointSourceSet(this.sources, this.ssrcGroups + ssrcGroup)

private fun EndpointSourceSet.doStripSimulcast(): EndpointSourceSet {
    val groupsToRemove = mutableSetOf<SsrcGroup>()
    val ssrcsToRemove = mutableSetOf<Long>()

    ssrcGroups.filter { it.semantics == SsrcGroupSemantics.Sim }.forEach { simGroup ->
        groupsToRemove.add(simGroup)
        simGroup.ssrcs.forEachIndexed { index: Int, ssrc: Long ->
            if (index > 0) ssrcsToRemove.add(ssrc)
        }
    }
    ssrcGroups.filter { it.semantics == SsrcGroupSemantics.Fid }.forEach { fidGroup ->
        if (fidGroup.ssrcs.size != 2) {
            throw IllegalArgumentException("Invalid FID group, has ${fidGroup.ssrcs.size} ssrcs.")
        }
        if (ssrcsToRemove.contains(fidGroup.ssrcs[0])) {
            ssrcsToRemove.add(fidGroup.ssrcs[1])
            groupsToRemove.add(fidGroup)
        }
    }
    return EndpointSourceSet(
        sources.filter { !ssrcsToRemove.contains(it.ssrc) }.toSet(),
        ssrcGroups - groupsToRemove
    )
}
