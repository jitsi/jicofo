package org.jitsi.jicofo.conference.colibri.v2

internal class ParticipantInfo(
    var session: Colibri2Session? = null,
    var audioMuted: Boolean = false,
    var videoMuted: Boolean = false
)