# Audio translation

## Requests come from Prosody

Prosody owns the `audioTranslationRequests` map in the room metadata. `mod_audio_translation_component.lua` (in jitsi-meet) keeps the subscriptions of each receiver. It sends jicofo the union of them: `{ senderId: [lang, ...] }`. When a member leaves, the module removes the subscriptions of that member. A sender stays in the map only while at least one member in the room requests a translation of that sender.

- Do not add a separate record of requesters in jicofo. `ConferenceTranslationManager.setRequests` uses the map as Prosody sends it.
- A non-empty request for a sender means that at least one member uses the translation.

## SSRCs of the synthetic sources

`TranslationSourceManager` keeps the SSRC of a `<sender, language>` for as long as the request stands and the sender has an audio source. A bridge that injects a synthetic source keeps its RTP sequence numbers and timestamps continuous when the translator reconnects, but that state is local to the bridge. So an SSRC must never be injected by a second bridge: receivers would see it go back to numbers that it already used, and drop the packets.

- Per-source mode: the connect is on the bridge of the sender, so the bridge changes when the conference re-invites the sender. With `reinvite-method = RestartJingle` (the default) the conference removes the sources of the sender, and the next `update` allocates new SSRCs. With `ReplaceTransport` the sender keeps its sources, and `ConferenceTranslationManager.senderReinvited` calls `TranslationSourceManager.remint(sender)`.
- Single-bridge mode: the connect moves to another session when its session goes away. The session manager owns the record of this: it counts the moves in `ColibriSessionManager.translatorConnectMoves` and the listener gets `translatorConnectMoved`. `ConferenceTranslationManager.apply` calls `TranslationSourceManager.remint` when the count is not the count it saw last. Do not keep a second record of the session in the translation manager: a move during an `apply` would be lost.
- The source of a voice agent gets a new SSRC when its endpoint is lost or expires and is allocated again (`ConferenceAgentManager.remint`).
- Both managers take their SSRCs from the `SyntheticSsrcMinter` of the conference. It avoids the sources in the conference source map and never gives out an SSRC a second time, so a translation source and the source of an agent can not get the same SSRC.
