package org.jitsi.jicofo.conference.colibri

import org.jxmpp.jid.Jid

sealed class ColibriAllocationFailedException(message: String = "") : Exception(message)

/** Bridge selection failed, i.e. there were no bridges available. */
class BridgeSelectionFailedException : ColibriAllocationFailedException()

class ColibriConferenceDisposedException : ColibriAllocationFailedException()

class ColibriConferenceExpiredException(
    val jid: Jid,
    val restartConference: Boolean
) : ColibriAllocationFailedException()

class BadColibriRequestException : ColibriAllocationFailedException()
class BridgeFailedException(
    val jid: Jid,
    val restartConference: Boolean
) : ColibriAllocationFailedException()

class ColibriParsingException(message: String) : ColibriAllocationFailedException(message)
