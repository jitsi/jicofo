package org.jitsi.jicofo.conference.colibri.v2

import org.jitsi.jicofo.conference.source.ConferenceSourceMap

internal class ParticipantInfo(
    val id: String,
    val statsId: String?,
    var sources: ConferenceSourceMap = ConferenceSourceMap(),
    var session: Colibri2Session,
    var audioMuted: Boolean = false,
    var videoMuted: Boolean = false
)
