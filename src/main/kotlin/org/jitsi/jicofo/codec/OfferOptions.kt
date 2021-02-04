/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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
package org.jitsi.jicofo.codec

import org.jitsi.jicofo.JitsiMeetConfig
import org.jitsi.jicofo.Participant
import java.lang.Integer.min

/**
 * Options for an offer that jicofo generates for a specific participant (or for an Octo link).
 */
data class OfferOptions(
    var ice: Boolean = true,
    var dtls: Boolean = true,
    var audio: Boolean = true,
    var video: Boolean = true,
    var sctp: Boolean = true,
    var stereo: Boolean = true,
    var tcc: Boolean = true,
    var remb: Boolean = false,
    var rtx: Boolean = true,
    var opusRed: Boolean = true,
    var minBitrate: Int? = null,
    var startBitrate: Int? = null,
    var opusMaxAverageBitrate: Int? = null
)

val OctoOptions = OfferOptions(
    ice = false,
    dtls = false,
    sctp = false,
    stereo = false
)

fun OfferOptions.applyConstraints(jitsiMeetConfig: JitsiMeetConfig) {
    stereo = stereo && jitsiMeetConfig.stereoEnabled()
    if (jitsiMeetConfig.minBitrate > 0) {
        minBitrate = min(jitsiMeetConfig.minBitrate, minBitrate ?: Int.MAX_VALUE)
    }
    if (jitsiMeetConfig.startBitrate > 0) {
        startBitrate = min(jitsiMeetConfig.startBitrate, startBitrate ?: Int.MAX_VALUE)
    }
    if (jitsiMeetConfig.opusMaxAverageBitrate > 0) {
        opusMaxAverageBitrate = min(jitsiMeetConfig.opusMaxAverageBitrate, opusMaxAverageBitrate ?: Int.MAX_VALUE)
    }
}

fun OfferOptions.applyConstraints(participant: Participant) {
    ice = ice && participant.hasIceSupport()
    dtls = dtls && participant.hasDtlsSupport()
    audio = audio && participant.hasAudioSupport()
    video = video && participant.hasVideoSupport()
    sctp = sctp && participant.hasSctpSupport()
    rtx = rtx && participant.hasRtxSupport()
    remb = remb && participant.hasRembSupport()
    tcc = tcc && participant.hasTccSupport()
    opusRed = opusRed && participant.hasOpusRedSupport()
}
