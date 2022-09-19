package org.jitsi.jicofo.bridge.colibri

/** Bridge selection failed, i.e. there were no bridges available. */
class BridgeSelectionFailedException : Exception("Bridge selection failed")

class ColibriAllocationFailedException(
    message: String = "",
    val removeBridge: Boolean = false
) : Exception(message)
