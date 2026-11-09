# jicofo tests

## ConferenceHarness

- Add at least two members, for example `addParticipants(2)`. Jicofo invites participants only when the room has at least `jicofo.conference.min-participants` members (default 2). With one member, jicofo sends no session-initiate, `getRemoteParticipant()` returns `null`, and `addParticipants(1)` fails with a `NullPointerException`. A requested transcriber or voice agent lowers the minimum to 1.

## TaskPools in Kotest specs

- A spec that sets `TaskPools.ioPool` in `beforeAny()` (for example `ConferenceTest`, which sets `inPlaceExecutor`) resets it for each nested `context`, because Kotest runs `beforeAny()` for each test case, and each `context` is a test case. Nothing fails, but events run in a different order. Set `TaskPools.ioPool` again at the start of each nested `context`.
- To queue tasks until the test runs them, use `PendingExecutor` (`jicofo-common/src/test/kotlin/org/jitsi/jicofo/mock/PendingExecutor.kt`). Set `TaskPools.ioPool = pending.executor`, then call `pending.runAll()`.
- A `JingleSession` keeps the value of `TaskPools.ioPool` from the time jicofo creates it. Set the pool before jicofo creates the session.
