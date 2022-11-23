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
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension
import org.jitsi.xmpp.extensions.jingle.ParameterPacketExtension
import org.jitsi.xmpp.extensions.jitsimeet.SSRCInfoPacketExtension
import org.jxmpp.jid.impl.JidCreate

/**
 * The description of a single source (i.e. an SSRC and its properties from "a=ssrc" lines in SDP).
 */
data class Source(
    val ssrc: Long,
    /** Audio or Video **/
    val mediaType: MediaType,
    /** Optional name */
    val name: String? = null,
    /** Optional msid */
    val msid: String? = null,
    /** Optional video type (camera or desktop) */
    val videoType: VideoType? = null
) {
    /** Create a [Source] from an XML extension. */
    constructor(mediaType: MediaType, sourcePacketExtension: SourcePacketExtension) : this(
        sourcePacketExtension.ssrc,
        mediaType,
        sourcePacketExtension.name,
        sourcePacketExtension.getChildExtensionsOfType(ParameterPacketExtension::class.java)
            .filter { it.name == "msid" }.map { it.value }.firstOrNull(),
        if (sourcePacketExtension.videoType == null) null else VideoType.parseString(sourcePacketExtension.videoType)
    )

    /** Serializes this [Source] to XML */
    @JvmOverloads
    fun toPacketExtension(
        /** An optional JID for the owner of this source to encode in the XML extension. */
        owner: String? = null,
        encodeMsid: Boolean = true
    ) = SourcePacketExtension().apply {
        ssrc = this@Source.ssrc
        name = this@Source.name
        if (owner != null) {
            addChildExtension(SSRCInfoPacketExtension().apply { this.owner = JidCreate.from(owner) })
        }

        if (encodeMsid && msid != null) {
            addChildExtension(ParameterPacketExtension("msid", msid))
        }

        this@Source.videoType?.let {
            videoType = it.toString()
        }
    }

    /**
     * A compact JSON representation of this [Source] (optimized for size). This is done ad-hoc instead of using e.g.
     * jackson because we only need serialization support (no parsing) and to keep it separated from the main code.
     * Note that we don't encode the media type.
     */
    val compactJson: String by lazy {
        buildString {
            append("""{"s":$ssrc""")
            name?.let {
                append(""","n":"$it"""")
            }
            msid?.let {
                append(""","m":"$it"""")
            }
            if (videoType == VideoType.Desktop) {
                append(""","v":"d"""")
            }
            append("}")
        }
    }

    /** Expanded JSON format used for debugging. */
    fun toJson() = OrderedJsonObject().apply {
        put("ssrc", ssrc)
        put("media_type", mediaType.toString())
        put("name", name ?: "null")
        put("msid", msid ?: "null")
        put("videoType", videoType.toString())
    }

    companion object {
        /**
         * Generates a source name in deterministic format used in the jitsi-meet client. First part is the endpoint id,
         * followed by "-", then the first letter of the media type and the index of the source (zero based).
         * For example "endpointA-a0" is the first audio source of "endpointA" or "endpointA-v0" for the first video
         * source.
         */
        fun nameForIdAndMediaType(endpointId: String, mediaType: MediaType, idx: Int): String {
            return "$endpointId-${(mediaType.toString()[0])}$idx"
        }
    }
}
