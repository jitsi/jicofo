package org.jitsi.jicofo.conference.colibri

/** Bridge selection failed, i.e. there were no bridges available. */
class BridgeSelectionFailedException : Exception("Bridge selection failed")

class ColibriAllocationFailedException(
    message: String = "",
    val removeBridge: Boolean = false
) : Exception(message)
