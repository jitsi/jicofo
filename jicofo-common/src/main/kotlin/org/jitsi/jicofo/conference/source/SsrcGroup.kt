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
import org.jitsi.xmpp.extensions.jingle.SourceGroupPacketExtension
import org.json.simple.JSONArray
import java.lang.IllegalArgumentException
import kotlin.jvm.Throws

/** The description of an SSRC grouping (i.e. an ssrc-group line in SDP) */
data class SsrcGroup(
    val semantics: SsrcGroupSemantics,
    val ssrcs: List<Long>,
    val mediaType: MediaType = MediaType.VIDEO
) {

    /** Serializes this [SsrcGroup] to XML */
    fun toPacketExtension(): SourceGroupPacketExtension = SourceGroupPacketExtension().apply {
        semantics = this@SsrcGroup.semantics.toString()
        addSources(
            ssrcs.map { SourcePacketExtension().apply { ssrc = it } }.toList()
        )
    }

    /** A concise string more suitable for logging. */
    override fun toString(): String = "${semantics.toString().uppercase()}$ssrcs"

    companion object {
        /**
         * Creates an [SsrcGroup] from an XML extension. The semantics is encoded as a string, so needs to be parsed
         * into a [SsrcGroupSemantics] (which may fail).
         *
         * @throws IllegalArgumentException if the XML extension does not have a valid "semantics" field.
         */
        @Throws(IllegalArgumentException::class)
        fun fromPacketExtension(sgpe: SourceGroupPacketExtension, mediaType: MediaType = MediaType.VIDEO): SsrcGroup {
            val semantics = try {
                SsrcGroupSemantics.fromString(sgpe.semantics)
            } catch (e: NoSuchElementException) {
                throw IllegalArgumentException("Invalid ssrc-group semantics: ${sgpe.semantics}")
            }

            return SsrcGroup(semantics, sgpe.sources.map { it.ssrc }.toList(), mediaType)
        }
    }

    /**
     * A compact JSON representation of this [SsrcGroup] (optimized for size). This is done ad-hoc instead of using e.g.
     * jackson because we only need serialization support (no parsing) and to keep it separated from the main code.
     *
     * The JSON contains a list with the first element encoding the semantics ("s" for Sim, "f" for Fid), and the rest
     * of the elements encoding the SSRCs. For example a simulcast group for SSRCs 1, 2, 3 is encoded as:
     * ["s", 1, 2, 3]
     */
    val compactJson: String by lazy {
        buildString {
            append("[")
            when (semantics) {
                SsrcGroupSemantics.Sim -> append("\"s\"")
                SsrcGroupSemantics.Fid -> append("\"f\"")
            }
            ssrcs.forEach { append(",$it") }
            append("]")
        }
    }

    /** Expanded JSON format used for debugging. */
    fun toJson() = OrderedJsonObject().apply {
        put("semantics", semantics.toString())
        put("media_type", mediaType.toString())
        put("ssrcs", JSONArray().apply { ssrcs.forEach { add(it) } })
    }
}
