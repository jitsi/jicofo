/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2020 - present 8x8, Inc
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

enum class Features(val value: String) {
    AUDIO("urn:xmpp:jingle:apps:rtp:audio"),
    VIDEO("urn:xmpp:jingle:apps:rtp:video"),
    /** DTLS/SCTP feature name. */
    SCTP("urn:xmpp:jingle:transports:dtls-sctp:1"),
    /** RTX (RFC4588) support. */
    RTX("urn:ietf:rfc:4588"),
    /** RTCP REMB. */
    REMB("http://jitsi.org/remb"),
    /** Transport-wide congestion control. */
    TCC("http://jitsi.org/tcc"),
    /** Source name signalling. */
    SOURCE_NAMES("http://jitsi.org/source-name"),
    SSRC_REWRITING_V1("http://jitsi.org/ssrc-rewriting-1"),
    RECEIVE_MULTIPLE_STREAMS("http://jitsi.org/receive-multiple-video-streams"),
    /** Jingle sources encoded as JSON. */
    JSON_SOURCES("http://jitsi.org/json-encoded-sources"),
    OPUS_RED("http://jitsi.org/opus-red"),
    AUDIO_MUTE("http://jitsi.org/protocol/audio-mute");

    companion object {
        val defaultFeatures = setOf(AUDIO, VIDEO, SCTP)
        /** Parse a string and silently return null if not recognized. */
        fun parseString(s: String): Features? = values().find { it.value == s }
    }
}
