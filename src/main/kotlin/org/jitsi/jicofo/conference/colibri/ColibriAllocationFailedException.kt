package org.jitsi.jicofo.conference.colibri

import org.jitsi.jicofo.bridge.Bridge

sealed class ColibriAllocationFailedException(message: String = "") : Exception(message)

/** Bridge selection failed, i.e. there were no bridges available. */
class BridgeSelectionFailedException : ColibriAllocationFailedException()
class ColibriTimeoutException(val bridge: Bridge) : ColibriAllocationFailedException()
class BridgeInGracefulShutdownException : ColibriAllocationFailedException()
class GenericColibriAllocationFailedException(message: String) : ColibriAllocationFailedException(message)

class ColibriConferenceDisposedException : ColibriAllocationFailedException()

class ColibriConferenceExpiredException(
    val bridge: Bridge,
    val restartConference: Boolean
) : ColibriAllocationFailedException()

class BadColibriRequestException(message: String = "") : ColibriAllocationFailedException(message)
class BridgeFailedException(
    val bridge: Bridge,
    val restartConference: Boolean
) : ColibriAllocationFailedException()

class ColibriParsingException(message: String) : ColibriAllocationFailedException(message)
