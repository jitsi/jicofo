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
package org.jitsi.jicofo.util

import org.jitsi.jicofo.codec.OfferOptions
import org.jitsi.jicofo.conference.Participant

fun OfferOptions.applyConstraints(participant: Participant) {
    audio = audio && participant.hasAudioSupport()
    video = video && participant.hasVideoSupport()
    sctp = sctp && participant.hasSctpSupport()
    rtx = rtx && participant.hasRtxSupport()
    remb = remb && participant.hasRembSupport()
    tcc = tcc && participant.hasTccSupport()
    opusRed = opusRed && participant.hasOpusRedSupport()
}
