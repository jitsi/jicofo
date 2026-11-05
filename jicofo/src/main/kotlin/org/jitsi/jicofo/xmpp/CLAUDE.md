# XMPP

## Visitor node reconnects

- In Smack, `MultiUserChat.isJoined()` returns `myRoomJid != null`. A connection loss does not clear this value, so `isJoined()` stays true after a reconnect. Do not use it to detect a lost MUC.
- Smack can rejoin rooms after a reconnect (`MultiUserChatManager`, flag `autoJoinOnReconnect`). Jicofo does not enable it, so Smack does not rejoin.
- When a visitor node restarts, the XMPP stream to it reconnects without stream resumption. The node lost its MUC state, so jicofo is no longer an occupant of the room and receives no presence from it. Unless jicofo handles this, it keeps sending new visitors to that room.
- `VisitorConnectionMonitor` adds a Smack `ConnectionListener` to each visitor `XmppProvider`. It reports a reset only when `authenticated` fires with `resumed=false`, and it handles the reset on `TaskPools.ioPool`, not on the Smack thread. Use this Smack flag. Do not infer a reset from other state, because that can race.
- `XmppServices` passes the reset to `FocusManager.visitorConnectionReset()`, which calls `JitsiMeetConferenceImpl.visitorConnectionReset()` for each conference. The conference discards the stale room and ends the visitors that were in it. This step is necessary: no presence tells jicofo that these visitors left, so each one would keep a `Participant` and a bridge endpoint.
- `selectVisitorNode()` (`conference/ConferenceUtil.kt`) does not reuse a node whose `XmppProvider.registered` is false, and it prefers registered nodes for a new room. If no node is registered, it falls back to a random node.
