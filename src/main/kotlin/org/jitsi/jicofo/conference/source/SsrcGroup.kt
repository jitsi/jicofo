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

import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension
import org.jitsi.xmpp.extensions.jingle.SourceGroupPacketExtension
import java.lang.IllegalArgumentException
import kotlin.jvm.Throws

/** The description of an SSRC grouping (i.e. an ssrc-group line in SDP) */
data class SsrcGroup(
    val semantics: SsrcGroupSemantics,
    val ssrcs: List<Long>
) {

    /** Serializes this [SsrcGroup] to XML */
    fun toPacketExtension(): SourceGroupPacketExtension = SourceGroupPacketExtension().apply {
        semantics = this@SsrcGroup.semantics.toString()
        addSources(
            ssrcs.map { SourcePacketExtension().apply { ssrc = it } }.toList()
        )
    }

    companion object {
        /**
         * Creates an [SsrcGroup] from an XML extension. The semantics is encoded as a sting, so needs to be parsed
         * into a [SsrcGroupSemantics] (which may fail).
         *
         * @throws IllegalArgumentException if the XML extension does not have a valid "semantics" field.
         */
        @Throws(IllegalArgumentException::class)
        fun fromPacketExtension(sgpe: SourceGroupPacketExtension): SsrcGroup {
            val semantics = try {
                SsrcGroupSemantics.fromString(sgpe.semantics)
            } catch (e: NoSuchElementException) {
                throw IllegalArgumentException("Invalid ssrc-group semantics: ${sgpe.semantics}")
            }

            return SsrcGroup(semantics, sgpe.sources.map { it.ssrc }.toList())
        }
    }
}
